package com.pff;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PstNodeBudgetTests {
  @Test
  void compressedNodeRespectsConfiguredDecodedSize() throws Exception {
    byte[] text = "Project notes and correspondence.\n".repeat(60).getBytes(StandardCharsets.UTF_8);
    var compressed = new ByteArrayOutputStream();
    try (var output = new DeflaterOutputStream(compressed)) {
      output.write(text);
    }
    String property = "eapdf.maxMessageBytes";
    String previous = System.getProperty(property);
    try {
      System.setProperty(property, String.valueOf(text.length));
      try (var input = new PSTNodeInputStream(null, compressed.toByteArray(), false)) {
        assertArrayEquals(text, input.readAllBytes());
      }
      System.setProperty(property, String.valueOf(text.length - 1));
      assertThrows(PSTNodeInputStream.ProcessingLimitExceeded.class,
          () -> new PSTNodeInputStream(null, compressed.toByteArray(), false));
    } finally {
      if (previous == null) System.clearProperty(property);
      else System.setProperty(property, previous);
    }
  }
}
