package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MboxPreservationTests {
  static final String ENVELOPE = "From sender@example.org Fri Oct 16 12:34:56 2026\n";

  static List<byte[]> parse(String mailbox, String format) throws Exception {
    List<byte[]> messages = new ArrayList<>();
    MboxParser.read(new ByteArrayInputStream(mailbox.getBytes(StandardCharsets.ISO_8859_1)), format, messages::add);
    return messages;
  }

  @Test
  void bodyFromLinesRepeatedQuotingAndTrailingNewlinesArePreserved() throws Exception {
    for (String newline : List.of("\n", "\r\n", "\r")) {
      String message = "Subject: First\n\nFrom ordinary body text\n>From once\n>>From twice\n\n\n";
      String second = "Subject: Second\n\nNo final newline";
      List<byte[]> messages = parse((ENVELOPE + message + ENVELOPE + second).replace("\n", newline), "mboxrd");
      Assertions.assertEquals(2, messages.size());
      Assertions.assertArrayEquals(message.replace(">>From twice", ">From twice").replace(">From once", "From once")
          .replace("\n", newline).getBytes(StandardCharsets.ISO_8859_1), messages.get(0));
      Assertions.assertArrayEquals(second.replace("\n", newline).getBytes(StandardCharsets.ISO_8859_1), messages.get(1));
    }
  }

  @Test
  void lengthDelimitedBodyCanContainAnEntireEnvelopeAndHeader() throws Exception {
    String body = "Body\n" + ENVELOPE + "Subject: This is body text\n\n>From literal\n\n";
    String first = "Subject: First\nContent-Length: " + body.getBytes(StandardCharsets.ISO_8859_1).length + "\n\n" + body;
    List<byte[]> messages = parse(ENVELOPE + first + "\n" + ENVELOPE + "Subject: Second\nContent-Length: 4\n\nLast", "auto");
    Assertions.assertEquals(2, messages.size());
    Assertions.assertArrayEquals(first.getBytes(StandardCharsets.ISO_8859_1), messages.get(0));
    Assertions.assertTrue(new String(messages.get(1), StandardCharsets.ISO_8859_1).endsWith("Last"));
  }

  @Test
  void mailboxVariantsApplyOnlyTheirOwnQuotingRule() throws Exception {
    String message = "Subject: Quote\n\n>From one\n>>From two\n";
    Assertions.assertTrue(new String(parse(ENVELOPE + message, "mboxo").get(0), StandardCharsets.ISO_8859_1).endsWith("From one\n>>From two\n"));
    int length = message.substring(message.indexOf("\n\n") + 2).getBytes(StandardCharsets.ISO_8859_1).length;
    String withLength = message.replace("Subject: Quote\n", "Subject: Quote\nContent-Length: " + length + "\n");
    Assertions.assertTrue(new String(parse(ENVELOPE + withLength, "mboxcl").get(0), StandardCharsets.ISO_8859_1).endsWith("From one\n>>From two\n"));
    Assertions.assertArrayEquals(withLength.getBytes(StandardCharsets.ISO_8859_1), parse(ENVELOPE + withLength, "mboxcl2").get(0));
  }

  @Test
  void malformedLengthsAndUnsupportedFormatsFailClearly() {
    for (String value : List.of("-1", "invalid", "1000", "1")) {
      Assertions.assertThrows(Common.UserFacingException.class,
          () -> parse(ENVELOPE + "Subject: Broken\nContent-Length: " + value + "\n\nBody", "auto"));
    }
    Assertions.assertThrows(Common.UserFacingException.class, () -> parse(ENVELOPE, "unknown"));
  }
}
