package eapdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A separate JVM keeps validation memory and cancellation independent of the window. */
final class JavaValidatorEngine implements ValidatorGui.Engine {
  private static final class Run {
    final AtomicBoolean stopped = new AtomicBoolean();
    volatile Process process;
    void check() { if (stopped.get()) throw new CancellationException("Validation stopped."); }
  }
  private final AtomicReference<Run> current = new AtomicReference<>(new Run());
  private volatile java.util.function.Consumer<String> progress = ignored -> {};
  @Override public void progress(java.util.function.Consumer<String> listener) { progress = listener; }

  @Override public ValidatorGui.Result validate(Path input) throws Exception {
    Run run = current.get();
    Path directory = null;
    try {
      run.check();
      directory = ValidationRuntime.temporaryDirectory();
      Path output = directory.resolve("report.json");
      Path diagnostic = directory.resolve("diagnostics.txt");
      String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
      String classpath = System.getProperty("java.class.path");
      long heap = Math.min(Runtime.getRuntime().maxMemory() / (1024 * 1024), ValidationRuntime.workerHeapMiB());
      ProcessBuilder builder = new ProcessBuilder(ValidationRuntime.boundedCommand(java.util.List.of(javaExecutable, "-Xmx" + heap + "m",
          "-XX:MaxDirectMemorySize=128m", "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=128m", "-Deapdf.validator.worker=true",
          "-XX:ActiveProcessorCount=4", "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, "-cp", classpath,
          JavaValidation.class.getName(), input.toRealPath().toString(), output.toString()), heap));
      builder.redirectOutput(diagnostic.toFile()).redirectErrorStream(true);
      run.check();
      run.process = builder.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ValidationRuntime.timeoutSeconds(Files.size(input)));
      long lastProgress = 0;
      boolean timedOut = false;
      boolean overBudget = false;
      while (!run.process.waitFor(200, TimeUnit.MILLISECONDS)) {
        if (run.stopped.get() || System.nanoTime() > deadline || overBudget) {
          timedOut = !run.stopped.get();
          run.process.destroy();
          if (!run.process.waitFor(5, TimeUnit.SECONDS)) { run.process.destroyForcibly(); run.process.waitFor(); }
          break;
        }
        if (System.nanoTime() - lastProgress >= TimeUnit.SECONDS.toNanos(1)) {
          overBudget = ValidationRuntime.scratchExceeded(directory, output);
          Path state = output.resolveSibling(output.getFileName() + ".progress.json");
          if (Files.isRegularFile(state)) try {
            var value = com.google.gson.JsonParser.parseString(Files.readString(state)).getAsJsonObject();
            progress.accept(JavaValidation.text(value, "stage") + " — " + JavaValidation.text(value, "detail"));
          } catch (IOException | RuntimeException ignored) { }
          lastProgress = System.nanoTime();
        }
      }
      overBudget |= ValidationRuntime.scratchExceeded(directory, output);
      String diagnosticText;
      try (var stream = Files.newInputStream(diagnostic)) {
        diagnosticText = new String(stream.readNBytes(16 * 1024), java.nio.charset.StandardCharsets.UTF_8);
      }
      if (!diagnosticText.isBlank()) System.err.println(diagnosticText);
      if (!Files.isRegularFile(output) || timedOut || overBudget || run.stopped.get()) {
        try (var session = new ValidationSession(input, output, true)) {
          session.failed(run.stopped.get() ? "Validation was stopped before the worker could write its report."
              : overBudget ? "Validation exceeded its scratch/report budget." : timedOut ? "Validation exceeded its time limit." : "Validation worker ended before it could write its report. " + diagnosticText);
        }
      }
      var result = ValidatorGui.Result.parse(output);
      if (run.process.exitValue() != ("PASS".equals(result.status()) ? 0 : 2)
          && !"INCOMPLETE".equals(result.status()) && !result.findings().stream().anyMatch(f -> "VALIDATION_INCOMPLETE".equals(f.code()) && "WARN".equals(f.level()))) {
        result.close();
        throw new IOException("Validation ended unexpectedly.\n" + diagnosticText);
      }
      return result;
    } finally {
      try {
        Process process = run.process;
        if (process != null && process.isAlive()) {
          process.destroy();
          if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
          }
        }
      } finally {
        try {
          ValidationRuntime.removeDirectory(directory);
        } finally { current.compareAndSet(run, new Run()); }
      }
    }
  }

  @Override public void cancel() {
    Run run = current.get();
    run.stopped.set(true);
    Process process = run.process;
    if (process != null) process.destroy();
  }
}
