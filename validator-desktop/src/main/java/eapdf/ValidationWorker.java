package eapdf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** CLI controller: vendor parsers execute in a disposable, bounded JVM. */
final class ValidationWorker {
    static int run(Path input, Path report) throws Exception {
        if (!Files.isRegularFile(input)) throw new IOException("Validation input is not a readable file");
        // Reset stale results before starting the parser process.
        try (var session = new ValidationSession(input, report)) { session.failed("Validation worker is starting."); }
        Path directory = ValidationRuntime.temporaryDirectory();
        Process process = null;
        Thread cleanup = null;
        String reason = null;
        try {
            long heap = Math.min(Runtime.getRuntime().maxMemory() / (1024 * 1024), ValidationRuntime.workerHeapMiB());
            var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + heap + "m", "-XX:ActiveProcessorCount=4", "-XX:MaxDirectMemorySize=128m",
                "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=128m", "-Deapdf.validator.worker=true",
                "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory));
            System.getProperties().stringPropertyNames().stream().filter(key -> key.startsWith("VALIDATOR_"))
                .sorted().forEach(key -> command.add("-D" + key + "=" + System.getProperty(key)));
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), JavaValidation.class.getName(), input.toString(), report.toString()));
            process = new ProcessBuilder(ValidationRuntime.boundedCommand(command, heap)).inheritIO().start();
            Process running = process;
            cleanup = new Thread(() -> {
                stop(running);
                try { ValidationRuntime.removeDirectory(directory); } catch (IOException ignored) { }
            }, "validation-controller-cleanup");
            Runtime.getRuntime().addShutdownHook(cleanup);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ValidationRuntime.timeoutSeconds(Files.size(input)));
            long lastCheck = 0;
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > deadline) { reason = "Validation exceeded its time limit."; break; }
                if (System.nanoTime() - lastCheck > TimeUnit.SECONDS.toNanos(1)) {
                    lastCheck = System.nanoTime();
                    if (ValidationRuntime.scratchExceeded(directory, report)) { reason = "Validation exceeded its scratch/report budget."; break; }
                }
            }
            if (reason != null) stop(process);
            int code = process.waitFor();
            if (reason == null && ValidationRuntime.scratchExceeded(directory, report))
                reason = "Validation exceeded its scratch/report budget.";
            if (reason != null || (code != 0 && code != 2)) {
                try (var session = new ValidationSession(input, report)) {
                    session.failed(reason == null ? "Validation worker stopped before completion (exit " + code + ")." : reason);
                }
                return 2;
            }
            return code;
        } finally {
            if (process != null) stop(process);
            if (cleanup != null) try { Runtime.getRuntime().removeShutdownHook(cleanup); } catch (IllegalStateException ignored) { }
            ValidationRuntime.removeDirectory(directory);
        }
    }
    private static void stop(Process process) {
        var children = process.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        if (process.isAlive()) {
            process.destroy();
            try { if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); } }
            catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
}
