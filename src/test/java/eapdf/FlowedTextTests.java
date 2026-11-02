package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FlowedTextTests {
  static byte[] message(String type, String body, String transfer) {
    return ("From: sender@example.org\r\nTo: recipient@example.org\r\n"
        + "Subject: Flowed paragraph\r\nDate: Thu, 22 Oct 2026 12:00:00 +0000\r\n"
        + "Message-ID: <flowed@example.org>\r\nMIME-Version: 1.0\r\nContent-Type: " + type + "\r\n"
        + (transfer == null ? "" : "Content-Transfer-Encoding: " + transfer + "\r\n")
        + "\r\n" + body).getBytes(StandardCharsets.UTF_8);
  }

  static EmailRecord parse(String type, String body, String transfer) throws Exception {
    byte[] raw = message(type, body, transfer);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "flowed.eml", "flowed.eml", "", List.of(), false);
    Assertions.assertArrayEquals(raw, record.sourceBytes);
    return record;
  }

  static EmailRecord flowed(String body, String delSp) throws Exception {
    return parse("text/plain; charset=UTF-8; format=flowed" + (delSp == null ? "" : "; delsp=" + delSp), body, null);
  }

  @Test
  void flowedBreaksFormOneLogicalParagraph() throws Exception {
    EmailRecord record = flowed("First flowed \r\nparagraph.\r\nFixed line\r\n", null);
    Assertions.assertEquals("First flowed paragraph.\nFixed line\n", record.textBody);
    Assertions.assertTrue(record.bodyMime.contains("format=flowed"));
  }

  @Test
  void delSpYesRemovesTheSoftBreakMarkerInsideTextWithoutAsciiSpaces() throws Exception {
    Assertions.assertEquals("国際会議\n", flowed("国際 \r\n会議\r\n", "yes").textBody);
  }

  @Test
  void quotingFlowsOnlyWithinOneQuoteDepth() throws Exception {
    Assertions.assertEquals(">quoted paragraph\n>>deeper reply\n",
        flowed("> quoted \r\n> paragraph\r\n>> deeper \r\n>> reply\r\n", null).textBody);
  }

  @Test
  void spaceStuffingIsRemovedExactlyOnce() throws Exception {
    Assertions.assertEquals("From line\n>literal\n indented\n",
        flowed(" From line\r\n >literal\r\n  indented\r\n", null).textBody);
  }

  @Test
  void signatureSeparatorDoesNotFlowIntoTheSignature() throws Exception {
    Assertions.assertEquals("Body\n-- \nSignature\n", flowed("Body\r\n-- \r\nSignature\r\n", "yes").textBody);
  }

  @Test
  void fixedAndUnknownFormatsRetainPhysicalWhitespace() throws Exception {
    for (String format : new String[]{null, "fixed", "unknown"}) {
      String type = "text/plain; charset=UTF-8" + (format == null ? "" : "; format=" + format) + "; delsp=yes";
      Assertions.assertEquals("one \ntwo\n From line\n", parse(type, "one \r\ntwo\r\n From line\r\n", null).textBody);
    }
  }

  @Test
  void unrecognizedDelSpKeepsTheWordSeparator() throws Exception {
    Assertions.assertEquals("one two\n", flowed("one \r\ntwo\r\n", "unknown").textBody);
  }

  @Test
  void quotedPrintableDecodingPrecedesFlowing() throws Exception {
    Assertions.assertEquals("one two\n", parse("text/plain; charset=UTF-8; format=flowed",
        "one=20\r\ntwo\r\n", "quoted-printable").textBody);
  }

  @Test
  void htmlIgnoresPlainTextFlowingParameters() throws Exception {
    String html = "<pre>inter \r\nnational</pre>";
    Assertions.assertEquals(html, parse("text/html; charset=UTF-8; format=flowed; delsp=yes", html, null).htmlBody);
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    byte[] raw = message("text/plain; charset=UTF-8; format=flowed; delsp=yes",
        "Flowed  \r\nparagraph.\r\n\r\n> Quoted  \r\n> reply.\r\n\r\n-- \r\nSignature\r\n", null);
    Files.write(directory.resolve("flowed.eml"), raw);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "flowed.eml", "flowed.eml", "", List.of(), false);
    Files.writeString(directory.resolve("body.txt"), record.textBody);
    System.out.println(record.textBody.replace("\n", "\\n"));
    if (args.length > 1) Thread.sleep(45000);
  }
}
