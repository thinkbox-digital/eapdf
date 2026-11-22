package eapdf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** CLI controller: vendor parsers execute in a disposable, bounded JVM. */
final class ValidationWorker {
    static int run(Path input, Path report) throws Exception {
        long lockAllowance = TimeUnit.SECONDS.toNanos(ValidationRuntime.timeoutSeconds(0));
        try (var reports = new ValidationRuntime.ReportSet(input, report, lockAllowance)) {
            return runClaimed(input, reports);
        }
    }

    private static int runClaimed(Path input, ValidationRuntime.ReportSet reports) throws Exception {
        String runId = UUID.randomUUID().toString();
        // Publish the new outcome before checking whether the input is still available.
        try (var session = new ValidationSession(input, reports.report, runId, false)) {
            if (!Files.isRegularFile(input) || !Files.isReadable(input)) {
                session.failed("Validation input is not a readable file.");
                System.err.println("Validation input is not a readable file: " + input);
                return 1;
            }
            session.failed("Validation worker is starting.");
        }
        long allowance = TimeUnit.SECONDS.toNanos(ValidationRuntime.timeoutSeconds(Files.size(input)));
        try (var job = new WorkerJob()) {
            Path directory = job.directory;
            // Only the lock-owning controller writes the caller's report files, even if a previous
            // controller was killed before its worker noticed its parent had exited.
            Path staged = directory.resolve("report.json");
            long heap = Math.min(Runtime.getRuntime().maxMemory() / (1024 * 1024), ValidationRuntime.workerHeapMiB());
            var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + heap + "m", "-XX:ActiveProcessorCount=4", "-XX:MaxDirectMemorySize=128m",
                "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=128m", "-Deapdf.validator.worker=true",
                "-D" + ValidationSession.RUN_ID_PROPERTY + "=" + runId,
                "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, ValidationRuntime.controllerArgument()));
            System.getProperties().stringPropertyNames().stream().filter(key -> key.startsWith("VALIDATOR_"))
                .sorted().forEach(key -> command.add("-D" + key + "=" + System.getProperty(key)));
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), JavaValidation.class.getName(), input.toString(), staged.toString()));
            Process process = job.start(new ProcessBuilder(ValidationRuntime.boundedCommand(command, heap)).inheritIO());
            long started = System.nanoTime();
            long lastCheck = 0;
            String reason = null;
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                job.observe();
                if (System.nanoTime() - started >= allowance) { reason = "Validation exceeded its time limit."; break; }
                if (System.nanoTime() - lastCheck > TimeUnit.SECONDS.toNanos(1)) {
                    lastCheck = System.nanoTime();
                    if (ValidationRuntime.scratchExceeded(directory, staged)) { reason = "Validation exceeded its scratch/report budget."; break; }
                    reports.mirror(staged, false);
                }
            }
            boolean reaped = job.stop();
            if (!reaped) reason = reason == null ? "Validation processes could not be reaped."
                : reason + " Some validation processes could not be reaped.";
            int code = process.isAlive() ? 2 : process.exitValue();
            if (reason == null && ValidationRuntime.scratchExceeded(directory, staged))
                reason = "Validation exceeded its scratch/report budget.";
            if (reason == null && !Files.isRegularFile(staged)) reason = "Validation worker produced no report.";
            if (reason == null && (code == 0 || code == 2)) reports.mirror(staged, true);
            if (reason != null || (code != 0 && code != 2)) {
                Path checkpoint = reaped ? staged.resolveSibling(staged.getFileName() + ".summary.json") : null;
                try (var session = new ValidationSession(input, reports.report, runId, true, checkpoint)) {
                    session.failed(reason == null ? "Validation worker stopped before completion (exit " + code + ")." : reason);
                }
                return 2;
            }
            return code;
        }
    }

    private static final class WorkerJob implements AutoCloseable {
        final Path directory;
        private final Set<ProcessHandle> descendants = new LinkedHashSet<>();
        private final Thread shutdown = new Thread(() -> {
            try { close(); }
            catch (IOException error) { System.err.println("Unable to remove validation scratch: " + error.getMessage()); }
        }, "validation-controller-cleanup");
        private Process process;
        private boolean closed, stopAttempted, reaped;

        WorkerJob() throws IOException {
            directory = ValidationRuntime.controllerDirectory();
            try { Runtime.getRuntime().addShutdownHook(shutdown); }
            catch (RuntimeException error) {
                try { ValidationRuntime.removeDirectory(directory); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }

        synchronized Process start(ProcessBuilder builder) throws IOException {
            if (closed) throw new IOException("Validation was cancelled.");
            process = builder.start();
            return process;
        }

        synchronized void observe() { if (process != null) process.descendants().forEach(descendants::add); }

        synchronized boolean stop() {
            if (stopAttempted) return reaped;
            stopAttempted = true;
            if (process == null) return reaped = true;
            boolean interrupted = Thread.interrupted();
            try {
                observe();
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
                if (process.isAlive()) process.destroy();
                try { if (waitForExit(2)) return reaped = true; }
                catch (InterruptedException error) { interrupted = true; }
                observe();
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                try { reaped = waitForExit(5); }
                catch (InterruptedException error) { interrupted = true; }
                return reaped;
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }

        private boolean waitForExit(int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (true) {
                observe();
                if (!process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) return true;
                if (System.nanoTime() >= deadline) return false;
                TimeUnit.MILLISECONDS.sleep(50);
            }
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
            if (stop()) ValidationRuntime.removeDirectory(directory);
            else System.err.println("Unable to reap all validation processes. Retained scratch: " + directory);
        }
    }
}
