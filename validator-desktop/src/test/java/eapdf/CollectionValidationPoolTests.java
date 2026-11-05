package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CollectionValidationPoolTests {
    private static Path collection(String name) {
        Path result = Path.of("..", "examples", name).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(result), "Tracked real-input example is required: " + result);
        return result;
    }
    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new java.security.DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static JsonElement normalized(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, child) -> {
                JsonElement normalized = normalized(child);
                if (key.equals("items") && normalized.isJsonArray()) {
                    var sorted = new JsonArray(); normalized.getAsJsonArray().asList().stream()
                        .sorted(Comparator.comparing(JsonElement::toString)).forEach(sorted::add); normalized = sorted;
                }
                result.add(key, normalized);
            }); return result;
        }
        if (value.isJsonArray()) { var result = new JsonArray(); value.getAsJsonArray().forEach(item -> result.add(normalized(item))); return result; }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
            return new JsonPrimitive(value.getAsString().replaceAll("[^\\s\"']*eapdf-validator-child-[0-9]+\\.pdf", "CHILD.pdf"));
        return value;
    }
    @ParameterizedTest @ValueSource(strings={"mbox/mbox_enron-sample_1c.pdf", "mbox/mbox_enron-sample_1ci.pdf", "pst/pst_quality-attached-message_1c.pdf"})
    void realCollectionsRetainEverySerialFinding(String name) throws Exception {
        Path input = collection(name); String before = hash(input);
        String old = System.getProperty("VALIDATOR_CHILD_PROCESSES");
        try {
            System.setProperty("VALIDATOR_CHILD_PROCESSES", "1"); long start = System.nanoTime();
            JsonObject serial = JavaValidation.validate(input); long serialMillis = (System.nanoTime()-start)/1_000_000;
            System.setProperty("VALIDATOR_CHILD_PROCESSES", "4"); start = System.nanoTime();
            JsonObject parallel = JavaValidation.validate(input); long parallelMillis = (System.nanoTime()-start)/1_000_000;
            if (!normalized(serial).equals(normalized(parallel))) {
                Path evidence = Path.of(System.getProperty("java.io.tmpdir"), "parallel-validation-difference-" + input.getFileName()); Files.createDirectories(evidence);
                Files.writeString(evidence.resolve("serial.json"), normalized(serial).toString());
                Files.writeString(evidence.resolve("parallel.json"), normalized(parallel).toString());
                fail("Serial/parallel findings differ; evidence: " + evidence);
            }
            assertEquals(before, hash(input));
            System.out.println("[parallel-regression] " + name + " serial_ms=" + serialMillis + " four_process_ms=" + parallelMillis + " sha256=" + before);
        } finally { if (old == null) System.clearProperty("VALIDATOR_CHILD_PROCESSES"); else System.setProperty("VALIDATOR_CHILD_PROCESSES", old); }
    }
    @Test void fourProcessesRunAndNestedCollectionsDoNotSpawnMore() throws Exception {
        Path input = collection("mbox/mbox_enron-sample_1ci.pdf");
        List<Long> pids;
        try (var pool = new CollectionValidationPool()) {
            var futures = new ArrayList<Future<CollectionValidationPool.Result>>();
            for (int i=0;i<4;i++) futures.add(pool.submit(input, Set.of(hash(input)), 1));
            for (var future : futures) {
                var result = future.get(120, TimeUnit.SECONDS);
                assertTrue(result.descendants() > 0);
                assertTrue(Set.of("PASS", "FAIL", "INCOMPLETE").contains(JavaValidation.text(result.report().getAsJsonObject("summary"), "status")));
            }
            pids = pool.processIds(); assertEquals(4, new HashSet<>(pids).size());
            for (long pid : pids) assertEquals(0, ProcessHandle.of(pid).orElseThrow().descendants().count(), "Nested collections stay in their assigned process");
        }
        for (long pid : pids) assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void crashedWorkerCannotProduceAPassingResult() throws Exception {
        Path input = collection("pst/pst_quality-attached-message_1c.pdf");
        try (var pool = new CollectionValidationPool()) {
            var job = pool.submit(input, Set.of(hash(input)), 1);
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (pool.processIds().isEmpty() && System.nanoTime()<deadline) Thread.sleep(10);
            assertFalse(pool.processIds().isEmpty());
            ProcessHandle.of(pool.processIds().getFirst()).orElseThrow().destroyForcibly();
            assertThrows(ExecutionException.class, () -> job.get(20, TimeUnit.SECONDS));
        }
    }
    @Test void closeStopsActiveAndQueuedJobs() throws Exception {
        Path input = collection("pst/pst_quality-attached-message_1c.pdf");
        var pool = new CollectionValidationPool(); var jobs = new ArrayList<Future<CollectionValidationPool.Result>>();
        List<Long> pids;
        try {
            for (int i=0;i<8;i++) jobs.add(pool.submit(input, Set.of(hash(input)), 1));
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (pool.processIds().size()<4 && System.nanoTime()<deadline) Thread.sleep(10);
            pids = pool.processIds(); assertEquals(4,pids.size());
        } finally { pool.close(); }
        for (var job : jobs) assertTrue(job.isDone(), "Cancellation must not leave pending futures");
        for (long pid : pids) assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
