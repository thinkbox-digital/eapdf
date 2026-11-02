package eapdf;

import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import com.pff.PSTObject;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PstItemSelectionTests {
  static final Path SOURCE = Path.of("src/test/resources/eapdf/email/attached-message.pst");

  static PSTMessage firstMessage(PSTFolder folder) throws Exception {
    for (int i = 0; i < folder.getContentCount(); i++) {
      var item = folder.getNextChild();
      if (item instanceof PSTMessage message && !message.getMessageClass().isBlank()) return message;
    }
    if (folder.hasSubfolders()) {
      for (var child : folder.getSubFolders()) {
        PSTMessage message = firstMessage(child);
        if (message != null) return message;
      }
    }
    return null;
  }

  static final class NativeItem implements AutoCloseable {
    final PSTFile file = new PSTFile(SOURCE.toString());
    final PSTMessage message = firstMessage(file.getRootFolder());
    final Object item;
    final Field data;
    final Field type;
    final Field external;
    final Object oldData;
    final int oldType;
    final boolean oldExternal;

    NativeItem() throws Exception {
      var field = PSTObject.class.getDeclaredField("items");
      field.setAccessible(true);
      item = ((Map<?, ?>) field.get(message)).get(0x001a);
      data = item.getClass().getField("data"); data.setAccessible(true);
      type = item.getClass().getField("entryValueType"); type.setAccessible(true);
      external = item.getClass().getField("isExternalValueReference"); external.setAccessible(true);
      oldData = data.get(item);
      oldType = type.getInt(item);
      oldExternal = external.getBoolean(item);
    }

    void classify(String value) throws Exception {
      data.set(item, value.getBytes(StandardCharsets.UTF_16LE));
      type.setInt(item, 0x1f);
      external.setBoolean(item, false);
    }

    void restore() throws Exception {
      data.set(item, oldData);
      type.setInt(item, oldType);
      external.setBoolean(item, oldExternal);
    }

    public void close() throws Exception {
      try { restore(); } finally { file.close(); }
    }
  }

  @Test
  void contactIsExplicitlySkippedAndNormalEmailIsStillCollected() throws Exception {
    byte[] original = Files.readAllBytes(SOURCE);
    try (var nativeItem = new NativeItem()) {
      var records = new ArrayList<EmailRecord>();
      int[] counts = new int[2];
      nativeItem.classify("IPM.Contact");
      PstReader.collectPstMessage(nativeItem.message, "Inbox", SOURCE.toString(), "", List.of(), records, false, counts);
      assertTrue(records.isEmpty());
      assertArrayEquals(new int[]{1, 1}, counts);
      nativeItem.restore();
      PstReader.collectPstMessage(nativeItem.message, "Inbox", SOURCE.toString(), "", List.of(), records, false, counts);
      assertEquals(1, records.size());
      assertEquals("pst", records.getFirst().sourceType);
      assertArrayEquals(new int[]{2, 1}, counts);
    }
    assertArrayEquals(original, Files.readAllBytes(SOURCE));
  }

  @Test
  void nonEmailObjectCannotBeReconstructedAsAnAttachedEmail() throws Exception {
    try (var nativeItem = new NativeItem()) {
      nativeItem.classify("IPM.Contact");
      var error = assertThrows(Common.UserFacingException.class,
          () -> PstReader.readMessage(nativeItem.message, "Inbox", SOURCE.toString(), "", List.of(), false, 1));
      assertTrue(error.getMessage().contains("not an email (IPM.Contact)"));
    }
  }
}
