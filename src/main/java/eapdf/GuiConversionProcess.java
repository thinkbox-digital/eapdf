package eapdf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** One conversion's process, cancellation, durable output and private run resources. */
final class GuiConversionProcess implements AutoCloseable {
  private final List<String> command, logHeader;
  private final Path workingDirectory, tempConfigPath, runLog;
  private final char[] signingPassword;
  private volatile Process activeProcess;
  private volatile boolean stopRequested;

  GuiConversionProcess(List<String> command, Path workingDirectory, Path tempConfigPath,
                       char[] signingPassword, Path runLog, List<String> logHeader) {
    this.command = List.copyOf(command);
    this.workingDirectory = workingDirectory;
    this.tempConfigPath = tempConfigPath;
    this.signingPassword = signingPassword;
    this.runLog = runLog;
    this.logHeader = List.copyOf(logHeader);
  }

  boolean stopRequested() { return stopRequested; }

  int execute(GuiLog.BatchConsumer display) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workingDirectory.toFile());
    builder.redirectErrorStream(true);
    builder.environment().put("EAPDF_CONFIG", tempConfigPath.toString());
    Process process;
    try {
      if (signingPassword != null) builder.environment().put(PdfSigning.PASSWORD_ENV, new String(signingPassword));
      process = builder.start();
    } finally {
      builder.environment().remove(PdfSigning.PASSWORD_ENV);
      if (signingPassword != null) Arrays.fill(signingPassword, '\0');
    }
    activeProcess = process;
    if (stopRequested) ConversionSupervisor.requestStop(process);
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
         var saved=Files.newBufferedWriter(runLog,StandardCharsets.UTF_8)) {
      for(String line:logHeader){saved.write(line);saved.newLine();}
      GuiLog.copy(reader,saved,display);
      int exitCode=process.waitFor();saved.write("Exit code: "+exitCode);saved.newLine();return exitCode;
    } catch (Exception error) {
      if (!stopRequested) {
        ConversionSupervisor.requestStop(process);
        throw error;
      }
    }
    return process.waitFor();
  }

  void requestStop() {
    stopRequested = true;
    Process process = activeProcess;
    if (process == null) return;
    Thread stop = new Thread(() -> ConversionSupervisor.requestStop(process), "eapdf-gui-stop");
    stop.setDaemon(true);
    stop.start();
  }

  @Override public void close() {
    if (signingPassword != null) Arrays.fill(signingPassword, '\0');
    try { Files.deleteIfExists(tempConfigPath); } catch (Exception ignored) {}
    activeProcess = null;
  }
}
