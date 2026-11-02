package eapdf;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class MboxParser {
  static final Pattern ENVELOPE = Pattern.compile("From \\S+\\s+(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+"
      + "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{1,2}\\s+"
      + "\\d{1,2}:\\d{2}(?::\\d{2})?\\s+(?:\\S+\\s+)?\\d{4}(?:\\s.*)?");

  interface MessageConsumer {
    void accept(byte[] message) throws Exception;
  }

  record Entry(int ordinal, long envelopeOffset, long messageOffset, long rawSize, String rawSha256,
               long exportedSize, String exportedSha256, long paddingSize, String framing, String quoting) {}
  record ReadResult(String requestedFormat, long containerSize, String containerSha256, List<Entry> entries) {}

  static ReadResult read(InputStream source, String format, MessageConsumer consumer) throws Exception {
    if (!Set.of("auto", "mboxo", "mboxrd", "mboxcl", "mboxcl2").contains(format)) throw Common.fail("unsupported mailbox format: " + format);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    LineInput input = new LineInput(new DigestInputStream(source, digest));
    List<Entry> entries = new ArrayList<>();
    byte[] envelope = input.line();
    long envelopeOffset = input.lastLineOffset;
    int count = 0;
    while (envelope != null) {
      ProcessingLimits.checkCount(++count);
      if (!isEnvelope(envelope)) throw Common.fail("invalid mailbox separator: " + text(envelope));
      long messageOffset = Math.addExact(envelopeOffset, envelope.length);
      long nextEnvelopeOffset = 0;
      ByteArrayOutputStream message = new ByteArrayOutputStream();
      byte[] line;
      boolean bodyStarted = false;
      while ((line = input.line()) != null) {
        if (isEnvelope(line)) throw Common.fail("mailbox message has no header/body separator before entry at byte " + input.lastLineOffset);
        ProcessingLimits.checkMessage((long)message.size() + line.length, "mailbox headers");
        message.write(line);
        if (text(line).isEmpty()) {
          bodyStarted = true;
          break;
        }
      }
      if (!bodyStarted) throw Common.fail("mailbox message has no header/body separator");
      String headers = message.toString(StandardCharsets.ISO_8859_1);
      String declared = MboxReader.headerValue(headers, 0, headers.length(), "Content-Length");
      boolean lengthDelimited = format.equals("mboxcl") || format.equals("mboxcl2") || (format.equals("auto") && !declared.isBlank());
      if (lengthDelimited) {
        long remaining;
        try {
          remaining = Long.parseLong(declared);
          if (remaining < 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
          throw Common.fail("invalid or missing mailbox Content-Length: " + declared);
        }
        byte[] buffer = new byte[8192];
        ProcessingLimits.checkMessage((long)message.size() + remaining, "mailbox Content-Length");
        while (remaining > 0) {
          int read = input.readBody(buffer, (int)Math.min(remaining, buffer.length));
          if (read < 0) throw Common.fail("truncated mailbox Content-Length");
          message.write(buffer, 0, read);
          remaining -= read;
        }
        envelope = input.line();
        while (envelope != null && text(envelope).isEmpty()) envelope = input.line();
        if (envelope != null && !isEnvelope(envelope)) throw Common.fail("mailbox Content-Length does not end at a message boundary");
        nextEnvelopeOffset = input.lastLineOffset;
      } else {
        envelope = null;
        while ((line = input.line()) != null) {
          if (isEnvelope(line)) {
            long candidateOffset = input.lastLineOffset;
            byte[] next = input.line();
            if (next == null || !text(next).matches("[!-9;-~]+:.*")) {
              throw Common.fail("Ambiguous MBOX separator at byte " + candidateOffset
                  + ": next entry has no recognizable first header; mailbox inventory was not accepted");
            }
            input.pending = next;
            input.pendingOffset = input.lastLineOffset;
            envelope = line;
            nextEnvelopeOffset = candidateOffset;
            break;
          }
          ProcessingLimits.checkMessage((long)message.size() + line.length, "mailbox message");
          message.write(line);
        }
      }
      String quoting = lengthDelimited ? (format.equals("mboxcl") ? "mboxo" : "mboxcl2")
          : (format.equals("mboxo") ? "mboxo" : "mboxrd");
      byte[] raw = message.toByteArray();
      byte[] exported = unquote(message.toString(StandardCharsets.ISO_8859_1), quoting);
      long rawEnd = Math.addExact(messageOffset, raw.length);
      long end = envelope == null ? input.position : nextEnvelopeOffset;
      if (end < rawEnd) throw Common.fail("Mailbox entry byte ranges overlap");
      if ((long)(entries.size() + 1) * 512 > ProcessingLimits.metadataBytes())
        throw Common.fail("Mailbox entry inventory exceeds the metadata limit");
      entries.add(new Entry(count, envelopeOffset, messageOffset, raw.length, Common.sha256Hex(raw),
          exported.length, Common.sha256Hex(exported), end - rawEnd, lengthDelimited ? "Content-Length" : "separator", quoting));
      consumer.accept(exported);
      envelopeOffset = nextEnvelopeOffset;
    }
    return new ReadResult(format, input.position, HexFormat.of().formatHex(digest.digest()), List.copyOf(entries));
  }

  static byte[] unquote(String message, String format) {
    if (format.equals("mboxcl2")) return message.getBytes(StandardCharsets.ISO_8859_1);
    StringBuilder result = new StringBuilder(message.length());
    boolean headers = true;
    int position = 0;
    while (position < message.length()) {
      int end = MboxReader.lineEnd(message, position);
      int next = MboxReader.nextLineStart(message, end);
      String line = message.substring(position, end);
      if (headers && line.isEmpty()) headers = false;
      else if (!headers && (format.equals("mboxo") ? line.startsWith(">From ") : line.matches(">+From .*"))) line = line.substring(1);
      result.append(line).append(message, end, next);
      position = next;
    }
    return result.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  static boolean isEnvelope(byte[] line) {
    return ENVELOPE.matcher(text(line)).matches();
  }

  static String text(byte[] line) {
    return new String(line, StandardCharsets.ISO_8859_1).replaceFirst("[\\r\\n]+$", "");
  }

  static final class LineInput {
    final PushbackInputStream source;
    byte[] pending;
    long pendingOffset;
    long position;
    long lastLineOffset;
    final long maxBytes = ProcessingLimits.messageBytes();

    LineInput(InputStream source) {
      this.source = new PushbackInputStream(new BufferedInputStream(source), 1);
    }

    byte[] line() throws IOException {
      if (pending != null) {
        byte[] line = pending;
        pending = null;
        lastLineOffset = pendingOffset;
        return line;
      }
      lastLineOffset = position;
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      int value;
      while ((value = source.read()) >= 0) {
        position++;
        line.write(value);
        if (line.size() > maxBytes) ProcessingLimits.checkMessage(line.size(), "mailbox line");
        if (value == '\n') break;
        if (value == '\r') {
          int next = source.read();
          if (next == '\n') { line.write(next); position++; }
          else if (next >= 0) source.unread(next);
          break;
        }
      }
      return line.size() == 0 ? null : line.toByteArray();
    }

    int readBody(byte[] buffer, int length) throws IOException {
      int count = source.read(buffer, 0, length);
      if (count > 0) position = Math.addExact(position, count);
      return count;
    }
  }
}
