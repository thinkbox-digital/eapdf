package eapdf;

import static eapdf.EaPdfChecks.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jakarta.mail.internet.InternetAddress;
import org.w3c.dom.Element;

/** FOAF mailbox syntax and RFC 6068 decoding, kept separate from source ownership. */
final class MailboxChecks {
  record Agents(boolean present, boolean valid, List<String> mailboxes, int declaredCount) {}
  record Key(String local, String domain) {}

  static Agents read(Element email, String property) {
    Element field = fieldElement(email, property);
    if (field == null) return new Agents(false, true, List.of(), 0);
    var containers = children(valueElement(field));
    if (containers.size() != 1 || !is(containers.getFirst(), RDF, "Seq")) return new Agents(true, false, List.of(), 0);
    List<String> mailboxes = new ArrayList<>();
    boolean valid = true; int declaredCount = 0;
    for (Element item : children(containers.getFirst())) {
      if (!is(item, RDF, "li")) { valid = false; continue; }
      var fields = children(item);
      Element agent = fields.size() == 1 && (is(fields.getFirst(), RDF, "Description") || is(fields.getFirst(), FOAF, "Agent")) ? fields.getFirst() : item;
      String mailbox = agent.getAttributeNS(FOAF, "mbox");
      int supplied = mailbox.isEmpty() ? 0 : 1;
      for (Element child : children(agent)) if (is(child, FOAF, "mbox")) {
        supplied++;
        if (!children(child).isEmpty()) valid = false;
        mailbox = child.hasAttributeNS(RDF, "resource") ? child.getAttributeNS(RDF, "resource") : child.getTextContent();
      }
      if (supplied == 1 && !mailbox.isBlank()) declaredCount++;
      try {
        String decoded = decode(mailbox.strip());
        if (supplied != 1 || !valid(decoded)) valid = false;
        else mailboxes.add(decoded);
      } catch (IllegalArgumentException error) { valid = false; }
    }
    return new Agents(true, valid, List.copyOf(mailboxes), declaredCount);
  }

  static String decode(String value) {
    if (!value.regionMatches(true, 0, "mailto:", 0, 7)) return value;
    String address = value.substring(7);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int index = 0; index < address.length();) {
      if (address.charAt(index) == '%') {
        if (index + 2 >= address.length()) throw new IllegalArgumentException("Invalid percent escape");
        int high = Character.digit(address.charAt(index + 1), 16), low = Character.digit(address.charAt(index + 2), 16);
        if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid percent escape");
        bytes.write(high * 16 + low); index += 3;
      } else {
        int point = address.codePointAt(index);
        bytes.writeBytes(new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8));
        index += Character.charCount(point);
      }
    }
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    } catch (java.nio.charset.CharacterCodingException error) { throw new IllegalArgumentException("Invalid mailbox URI UTF-8", error); }
  }

  static boolean valid(String value) {
    if (value.codePoints().anyMatch(c -> c < 32 || c == 127)) return false;
    int at = value.lastIndexOf('@');
    if (at <= 0 || at == value.length() - 1) return false;
    String ascii = value.replaceAll("[^\\x00-\\x7F]", "x");
    String domain = ascii.substring(ascii.lastIndexOf('@') + 1);
    if (!domain.matches("[A-Za-z0-9.-]+|\\[[^\\[\\]\\r\\n]+\\]")) return false;
    try { new InternetAddress(ascii, true).validate(); return true; }
    catch (Exception error) { return false; }
  }

  static Key key(String value) {
    int at = value.lastIndexOf('@');
    if (at <= 0) return null;
    String local = value.substring(0, at), domain = value.substring(at + 1).toLowerCase(Locale.ROOT);
    if (local.startsWith("\"") && local.endsWith("\"")) local = local.substring(1, local.length() - 1).replaceAll("\\\\(.)", "$1");
    return new Key(local, domain);
  }
}
