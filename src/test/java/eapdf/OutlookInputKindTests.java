package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OutlookInputKindTests {
  @TempDir Path directory;

  @Test
  void mixedFolderSkipsContactAndStickyNoteRatherThanCreatingExtraEmails() throws Exception {
    Path email = directory.resolve("email.msg");
    MsgBodySelectionTests.writeMsg(email, 1, false);
    Path contact = directory.resolve("contact.msg");
    Path note = directory.resolve("note.msg");
    Files.copy(Path.of("src/test/resources/eapdf/outlook-non-email/contact-unicode.msg"), contact);
    Files.copy(Path.of("src/test/resources/eapdf/outlook-non-email/note.msg"), note);
    byte[] originalContact = Files.readAllBytes(contact);
    assertEquals("application/x-outlook-item", EmailToPDFA.detectMsgInputKind(contact));
    assertEquals("application/x-outlook-item", EmailToPDFA.detectMsgInputKind(note));
    var capture = new ByteArrayOutputStream();
    PrintStream previous = System.out;
    try {
      System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
      assertEquals(List.of(email.toString()), EmailToPDFA.collectInputs(directory.toString()));
    } finally {
      System.setOut(previous);
    }
    assertTrue(capture.toString(StandardCharsets.UTF_8).contains("skipped input: " + contact));
    assertTrue(capture.toString(StandardCharsets.UTF_8).contains("skipped input: " + note));
    assertThrows(Common.UserFacingException.class, () -> EmailToPDFA.readAny(contact.toString(), "", List.of(), false));
    assertArrayEquals(originalContact, Files.readAllBytes(contact));
  }

  @Test
  void ordinaryMeetingAndReportMessageClassesRemainAccepted() throws Exception {
    for (String type : List.of("IPM.Note", "IPM.Note.Tutanota", "IPM.Schedule.Meeting.Request", "REPORT.IPM.Note.NDR", "IPM.TaskRequest")) {
      Path source = directory.resolve(type + ".msg");
      MsgBodySelectionTests.writeMsg(source, 1, false);
      try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(source)))) {
        byte[] itemClass = MsgBodySelectionTests.unicode(type);
        storage.getRoot().createDocument("__substg1.0_001A001F", new ByteArrayInputStream(itemClass));
        byte[] original;
        try (var input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) { original = input.readAllBytes(); }
        var properties = ByteBuffer.allocate(original.length + 16).order(ByteOrder.LITTLE_ENDIAN);
        properties.put(original).putInt(0x001a001f).putInt(6).putInt(itemClass.length).putInt(0);
        storage.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties.array()));
        try (var output = new ByteArrayOutputStream()) {
          storage.writeFilesystem(output);
          Files.write(source, output.toByteArray());
        }
      }
      assertEquals("application/vnd.ms-outlook", EmailToPDFA.detectMsgInputKind(source));
      assertEquals(1, EmailToPDFA.readAny(source.toString(), "", List.of(), false).size());
    }
  }
}
