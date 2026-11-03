package eapdf;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BoundedPdfStreamsTests {
    @TempDir Path directory;

    @Test void compressedExpansionStopsBeforeTheOutputLimitAndRemovesScratch() throws Exception {
        String previous = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", directory.toString());
        try (var stream = new COSStream(); var budget = BoundedPdfStreams.scope(true)) {
            byte[] original = new byte[1024 * 1024]; Arrays.fill(original, (byte)65);
            try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) { output.write(original); }
            stream.setInt(COSName.getPDFName("DL"), 1); // Declared size is not a trusted bound.
            assertThrows(BoundedPdfStreams.Limit.class, () -> BoundedPdfStreams.bytes(stream, 4096));
            try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
            assertArrayEquals(original, BoundedPdfStreams.bytes(stream, original.length));
            try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
        } finally { System.setProperty("java.io.tmpdir", previous); }
    }

    @Test void completedDecodesDoNotConsumeFutureStreamAllowances() throws Exception {
        String previous = System.getProperty("VALIDATOR_MAX_DECODED_BYTES");
        System.setProperty("VALIDATOR_MAX_DECODED_BYTES", "12");
        try (var stream = new COSStream()) {
            try (var output = stream.createOutputStream()) { output.write(new byte[8]); }
            try (var budget = BoundedPdfStreams.scope(true)) {
                assertEquals(8, BoundedPdfStreams.bytes(stream, 8).length);
                assertEquals(8, BoundedPdfStreams.bytes(stream, 8).length);
            }
            try (var budget = BoundedPdfStreams.scope(true)) { assertEquals(8, BoundedPdfStreams.bytes(stream, 8).length); }
        } finally {
            if (previous == null) System.clearProperty("VALIDATOR_MAX_DECODED_BYTES"); else System.setProperty("VALIDATOR_MAX_DECODED_BYTES", previous);
        }
    }

    @Test void dangerousPredictorRowsAndUnsupportedCodecsAreNotInvoked() throws Exception {
        try (var stream = new COSStream()) {
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            var parameters = new COSDictionary(); parameters.setInt(COSName.COLUMNS, Integer.MAX_VALUE);
            stream.setItem(COSName.DECODE_PARMS, parameters);
            assertThrows(BoundedPdfStreams.Limit.class, () -> BoundedPdfStreams.bytes(stream, 1024));
            stream.removeItem(COSName.DECODE_PARMS); stream.setItem(COSName.FILTER, COSName.getPDFName("JBIG2Decode"));
            assertThrows(BoundedPdfStreams.Limit.class, () -> BoundedPdfStreams.bytes(stream, 1024));
        }
    }
}
