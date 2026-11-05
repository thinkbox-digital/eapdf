package eapdf;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Four persistent isolated parsers; the coordinator alone reads the parent PDF. */
final class CollectionValidationPool implements AutoCloseable {
    static final int WORKERS = 4, WINDOW = 8, RECYCLE_AFTER = 128;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
    private final BlockingQueue<Worker> available = new ArrayBlockingQueue<>(WORKERS);
    private final List<Worker> workers = new ArrayList<>();
    private final Path directory;
    private final Thread shutdown;
    private volatile boolean closed;

    record Result(JsonObject report, int descendants, long bytes) { }

    static boolean enabled() {
        if (Boolean.getBoolean("eapdf.validator.childWorker")) return false;
        long count = ValidationRuntime.setting("VALIDATOR_CHILD_PROCESSES", WORKERS);
        if (count != 1 && count != WORKERS) throw new IllegalArgumentException("VALIDATOR_CHILD_PROCESSES must be 1 or 4");
        return count == WORKERS;
    }

    CollectionValidationPool() throws IOException {
        directory = Files.createTempDirectory("eapdf-collection-workers-");
        for (int i = 0; i < WORKERS; i++) {
            Worker worker = new Worker(i); workers.add(worker); available.add(worker);
        }
        shutdown = new Thread(this::stop, "collection-worker-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    Future<Result> submit(Path input, Set<String> ancestors, int depth) {
        Set<String> lineage = Set.copyOf(ancestors);
        return executor.submit(() -> {
            Worker worker = available.take();
            try { return worker.validate(input, lineage, depth); }
            finally { available.offer(worker); }
        });
    }

    private synchronized void stop() {
        closed = true;
        for (Runnable waiting : executor.shutdownNow()) if (waiting instanceof Future<?> future) future.cancel(true);
        for (Worker worker : workers) worker.stop();
    }

    List<Long> processIds() {
        return workers.stream().map(worker -> worker.process).filter(Objects::nonNull).map(Process::pid).toList();
    }

    @Override public void close() throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            stop();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new IOException("Collection worker tasks did not stop");
        } catch (InterruptedException error) { interrupted = true; stop(); throw new IOException("Collection validation cancelled", error); }
        finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
            try { ValidationRuntime.removeDirectory(directory); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private final class Worker {
        final int number;
        volatile Process process;
        BufferedWriter requests;
        BufferedReader replies;
        Path scratch;
        int used;
        Worker(int number) { this.number = number; }

        private synchronized void start() throws IOException {
            if (closed) throw new IOException("Collection validation cancelled");
            if (process != null && process.isAlive() && used < RECYCLE_AFTER) return;
            stop();
            if (scratch != null) ValidationRuntime.removeDirectory(scratch);
            scratch = Files.createTempDirectory(directory, "worker-" + number + "-");
            long defaultHeap = Math.max(128, Math.min(1024, Runtime.getRuntime().maxMemory() / (4 * 1024 * 1024)));
            long heap = ValidationRuntime.setting("VALIDATOR_CHILD_HEAP_MIB", defaultHeap);
            var command = new ArrayList<String>();
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
                command.addAll(List.of("/usr/bin/prlimit", "--as=" + Math.multiplyExact(heap + 4096, 1024L * 1024), "--core=0"));
            }
            command.addAll(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + heap + "m", "-XX:ActiveProcessorCount=1", "-XX:MaxDirectMemorySize=128m",
                "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=128m", "-XX:-CreateCoredumpOnCrash",
                "-Deapdf.validator.childWorker=true", "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + scratch));
            System.getProperties().stringPropertyNames().stream().filter(key -> key.startsWith("VALIDATOR_"))
                .sorted().forEach(key -> command.add("-D" + key + "=" + System.getProperty(key)));
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), CollectionValidationPool.class.getName(),
                Long.toString(ProcessHandle.current().pid())));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            requests = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            replies = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            used = 0;
            System.out.println("[validation] Collection worker " + (number + 1) + " started: pid=" + process.pid() + ", heap=" + heap + " MiB");
        }

        Result validate(Path input, Set<String> ancestors, int depth) throws Exception {
            start();
            Path output = scratch.resolve("result.json");
            String id = UUID.randomUUID().toString();
            JsonObject task = new JsonObject();
            task.addProperty("id", id); task.addProperty("input", input.toAbsolutePath().toString());
            task.addProperty("output", output.toString()); task.addProperty("depth", depth);
            task.add("ancestors", JavaValidation.JSON.toJsonTree(ancestors));
            try {
                requests.write(task.toString()); requests.newLine(); requests.flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ValidationRuntime.timeoutSeconds(Files.size(input)));
                while (!replies.ready()) {
                    if (Thread.currentThread().isInterrupted() || closed) throw new InterruptedException("Collection validation cancelled");
                    if (!process.isAlive()) throw new IOException("Collection worker exited before completing a child (exit " + process.exitValue() + ")");
                    if (System.nanoTime() > deadline) throw new IOException("Collection child validation exceeded its time allowance");
                    Thread.sleep(10);
                }
                String response = replies.readLine();
                if (response == null) throw new IOException("Collection worker closed its response channel");
                JsonObject ack = JsonParser.parseString(response).getAsJsonObject();
                if (!id.equals(JavaValidation.text(ack, "id"))) throw new IOException("Unexpected collection worker response");
                if (!ack.get("ok").getAsBoolean()) throw new IOException(JavaValidation.text(ack, "error"));
                JsonObject result;
                try (var reader = Files.newBufferedReader(output)) { result = JsonParser.parseReader(reader).getAsJsonObject(); }
                if (!result.has("summary") || !result.has("items") || !result.has("pdfa")
                    || !Set.of("PASS", "FAIL", "INCOMPLETE").contains(JavaValidation.text(result.getAsJsonObject("summary"), "status")))
                    throw new IOException("Incomplete collection worker report");
                used++;
                return new Result(result, ack.get("count").getAsInt(), ack.get("bytes").getAsLong());
            } catch (Exception error) {
                stop(); throw error;
            } finally { Files.deleteIfExists(output); }
        }

        synchronized void stop() {
            Process running = process;
            if (running == null) return;
            var descendants = running.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            running.destroy();
            try { if (!running.waitFor(2, TimeUnit.SECONDS)) { running.destroyForcibly(); running.waitFor(); } }
            catch (InterruptedException error) { running.destroyForcibly(); Thread.currentThread().interrupt(); }
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
    }

    /** Internal pipe protocol, not a public CLI option. */
    public static void main(String[] args) throws Exception {
        long parent = Long.parseLong(args[0]);
        PrintStream protocol = System.out;
        System.setOut(System.err); // Preserve diagnostics without corrupting the reply channel.
        Thread watchdog = new Thread(() -> {
            try {
                while (ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(1000);
                Runtime.getRuntime().halt(2);
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "collection-parent-watch");
        watchdog.setDaemon(true); watchdog.start();
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                JsonObject task = JsonParser.parseString(line).getAsJsonObject(), ack = new JsonObject();
                ack.addProperty("id", JavaValidation.text(task, "id"));
                try {
                    var children = new JavaValidation.Children();
                    for (var value : task.getAsJsonArray("ancestors")) children.active.add(value.getAsString());
                    JsonObject report = JavaValidation.validate(Path.of(JavaValidation.text(task, "input")), children, task.get("depth").getAsInt());
                    Path output = Path.of(JavaValidation.text(task, "output"));
                    Path temporary = Files.createTempFile(output.getParent(), ".child-result-", ".json");
                    try {
                        // This is an in-memory report transfer: retain explicit null details.
                        try (var writer = Files.newBufferedWriter(temporary)) {
                            new com.google.gson.GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(report, writer);
                        }
                        ValidationSession.replace(temporary, output);
                    } finally { Files.deleteIfExists(temporary); }
                    ack.addProperty("ok", true); ack.addProperty("count", children.count); ack.addProperty("bytes", children.bytes);
                } catch (Exception error) { ack.addProperty("ok", false); ack.addProperty("error", error.toString()); }
                protocol.println(ack); protocol.flush();
            }
        }
    }
}
