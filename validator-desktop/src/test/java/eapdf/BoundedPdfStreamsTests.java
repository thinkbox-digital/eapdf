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

    @Test void diskStreamingDoesNotRequireTheAttachmentToFitTheHeapAllowance() throws Exception {
        var command = java.util.List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-XX:ActiveProcessorCount=1", "-Djava.io.tmpdir=" + directory,
            "-cp", System.getProperty("java.class.path"), StreamingProbe.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output); assertTrue(output.contains("STREAMED 20971520"), output);
        } finally { process.destroyForcibly(); }
    }
    public static class StreamingProbe {
        public static void main(String[] args) throws Exception {
            try (var stream = new COSStream()) {
                byte[] chunk = new byte[1024];
                try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) {
                    for (int i = 0; i < 20480; i++) output.write(chunk);
                }
                var file = new EaPdfChecks.Embedded("", new COSDictionary(), stream, null, "large.bin", "application/octet-stream", "", "Data", 20971520);
                try (var input = file.open()) {
                    long size = input.transferTo(OutputStream.nullOutputStream());
                    if (size != 20971520) throw new AssertionError(size);
                    System.out.println("STREAMED " + size);
                }
                try { file.bytes(); throw new AssertionError("In-memory path must retain its heap allowance"); }
                catch (UncheckedIOException expected) {
                    if (!(expected.getCause() instanceof BoundedPdfStreams.Limit)) throw expected;
                }
            }
        }
    }

    @Test void dangerousPredictorRowsAndUnsupportedCodecsAreNotInvoked() throws Exception {
        try (var stream = new COSStream()) {
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            var parameters = new COSDictionary(); parameters.setInt(COSName.COLUMNS, Integer.MAX_VALUE);
            stream.setItem(COSName.DECODE_PARMS, parameters);
            assertThrows(BoundedPdfStreams.Limit.class, () -> BoundedPdfStreams.bytes(stream, 1024));
            assertThrows(BoundedPdfStreams.Limit.class, () -> BoundedPdfStreams.open(stream, BoundedPdfStreams.streamLimit()));
            stream.removeItem(COSName.DECODE_PARMS); stream.setItem(COSName.FILTER, COSName.getPDFName("JBIG2Decode"));
            assertThrows(BoundedPdfStreams.Limit.class, () -> BoundedPdfStreams.bytes(stream, 1024));
        }
    }
}
