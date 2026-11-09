package eapdf;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
class ValidationWorkFileTests {
    @TempDir Path temporary;
    private String probe(long bytes, String limit, boolean descendant) throws Exception {
        String previous = System.getProperty("VALIDATOR_MAX_WORK_FILE_BYTES");
        try {
            if (limit == null) System.clearProperty("VALIDATOR_MAX_WORK_FILE_BYTES");
            else System.setProperty("VALIDATOR_MAX_WORK_FILE_BYTES", limit);
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), temporary.resolve("report.json").toString(), Long.toString(bytes), Boolean.toString(descendant));
            Process process = new ProcessBuilder(ValidationRuntime.boundedCommand(command, 128)).redirectErrorStream(true).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output.strip();
        } finally {
            if (previous == null) System.clearProperty("VALIDATOR_MAX_WORK_FILE_BYTES");
            else System.setProperty("VALIDATOR_MAX_WORK_FILE_BYTES", previous);
        }
    }
    @Test void reportsLargerThan64MiBAreAllowedByDefault() throws Exception {
        assertEquals("WROTE 75497472", probe(72L * 1024 * 1024, null, false));
    }
    @Test void explicitLowerLimitStillStopsWriting() throws Exception {
        assertEquals("LIMIT", probe(2L * 1024 * 1024, "1048576", false));
        assertTrue(Files.size(temporary.resolve("report.json")) <= 1048576);
    }
    @Test void descendantsInheritTheLimit() throws Exception {
        assertEquals("LIMIT", probe(2L * 1024 * 1024, "1048576", true));
    }
    @Test void aggregateScratchLimitStillRejectsOversizedWork() throws Exception {
        Path scratch = Files.createDirectory(temporary.resolve("scratch")), report = temporary.resolve("report.json");
        try (var file = new RandomAccessFile(scratch.resolve("decoded.bin").toFile(), "rw")) {
            file.setLength(513L * 1024 * 1024);
        }
        String previous = System.getProperty("VALIDATOR_MAX_SCRATCH_BYTES");
        try {
            System.setProperty("VALIDATOR_MAX_SCRATCH_BYTES", "536870912");
            assertTrue(ValidationRuntime.scratchExceeded(scratch, report));
        } finally {
            if (previous == null) System.clearProperty("VALIDATOR_MAX_SCRATCH_BYTES");
            else System.setProperty("VALIDATOR_MAX_SCRATCH_BYTES", previous);
        }
    }
    @Test void diskMappingsDoNotConsumeTheNativeHeapHeadroom() throws Exception {
        String oldScratch = System.getProperty("VALIDATOR_MAX_SCRATCH_BYTES"), oldFile = System.getProperty("VALIDATOR_MAX_WORK_FILE_BYTES");
        try {
            System.setProperty("VALIDATOR_MAX_SCRATCH_BYTES", "8589934592");
            System.setProperty("VALIDATOR_MAX_WORK_FILE_BYTES", "8589934592");
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"),
                MappingProbe.class.getName(), temporary.resolve("mapped.bin").toString());
            Process process = new ProcessBuilder(ValidationRuntime.boundedCommand(command, 128)).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output); assertTrue(output.contains("MAPPED 6"), output);
            } finally { process.destroyForcibly(); }
        } finally {
            if (oldScratch == null) System.clearProperty("VALIDATOR_MAX_SCRATCH_BYTES"); else System.setProperty("VALIDATOR_MAX_SCRATCH_BYTES", oldScratch);
            if (oldFile == null) System.clearProperty("VALIDATOR_MAX_WORK_FILE_BYTES"); else System.setProperty("VALIDATOR_MAX_WORK_FILE_BYTES", oldFile);
        }
    }
    public static class MappingProbe {
        public static void main(String[] args) throws Exception {
            // Sparse mappings reserve addresses without allocating a 6 GiB heap or disk payload.
            try (var file = java.nio.channels.FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                var mappings = new ArrayList<java.nio.MappedByteBuffer>();
                for (int i = 0; i < 6; i++) mappings.add(file.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, i * 1073741824L, 1073741824L));
                System.out.println("MAPPED " + mappings.size());
                for (var mapping : mappings) org.apache.pdfbox.io.IOUtils.unmap(mapping);
            }
        }
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (Boolean.parseBoolean(args[2])) {
                int code = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx64m", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"),
                    Probe.class.getName(), args[0], args[1], "false").inheritIO().start().waitFor();
                System.exit(code);
            }
            try (var file = new RandomAccessFile(args[0], "rw")) {
                file.seek(Long.parseLong(args[1]) - 1); file.write(0);
                System.out.println("WROTE " + file.length());
            } catch (java.io.IOException limit) { System.out.println("LIMIT"); }
        }
    }
}
