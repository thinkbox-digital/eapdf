package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GuiConversionProcessTests {
  @TempDir Path temporary;

  private List<String> command(boolean wait) {
    return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp",
        System.getProperty("java.class.path"), Probe.class.getName(), Boolean.toString(wait));
  }

  @Test void mergedOutputIsSavedAndDeliveredOnTheEdtWithPrivateRunSettings() throws Exception {
    Path config = Files.writeString(temporary.resolve("settings.json"), "{}"), log = temporary.resolve("run.log");
    char[] password = "test-only".toCharArray();
    var displayed = new ArrayList<String>();
    try (var run = new GuiConversionProcess(command(false), temporary, config, password, log, List.of("header"))) {
      assertEquals(0, run.execute(lines -> SwingUtilities.invokeAndWait(() -> {
        assertTrue(SwingUtilities.isEventDispatchThread());
        displayed.addAll(lines);
      })));
      assertArrayEquals(new char[password.length], password);
      assertTrue(Files.exists(config));
      assertFalse(run.stopRequested());
    }
    assertFalse(Files.exists(config));
    assertTrue(displayed.contains("ready π"));
    assertTrue(displayed.contains("merged stderr"));
    assertTrue(displayed.contains("configuration available: true"));
    assertTrue(displayed.contains("password available: true"));
    String saved = Files.readString(log);
    for (String line : displayed) assertTrue(saved.contains(line));
    assertTrue(saved.startsWith("header" + System.lineSeparator()));
    assertTrue(saved.endsWith("Exit code: 0" + System.lineSeparator()));
  }

  @Test void startFailureStillWipesPasswordAndDeletesPrivateConfiguration() throws Exception {
    Path config = Files.writeString(temporary.resolve("settings.json"), "{}");
    char[] password = "test-only".toCharArray();
    try (var run = new GuiConversionProcess(List.of(temporary.resolve("missing-program").toString()),
        temporary, config, password, temporary.resolve("run.log"), List.of())) {
      assertThrows(java.io.IOException.class, () -> run.execute(lines -> fail("No process started")));
      assertArrayEquals(new char[password.length], password);
    }
    assertFalse(Files.exists(config));
  }

  @Test void cancellationBeforeStartupAndDuringOutputStopsTheProcess() {
    assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
      for (boolean before : new boolean[]{true, false}) {
        Path config = Files.writeString(temporary.resolve("settings-" + before + ".json"), "{}");
        try (var run = new GuiConversionProcess(command(true), temporary, config, null,
            temporary.resolve("run-" + before + ".log"), List.of())) {
          if (before) run.requestStop();
          int exit = run.execute(lines -> {
            if (!before && lines.contains("ready π")) run.requestStop();
          });
          assertTrue(run.stopRequested());
          assertNotEquals(0, exit);
        }
        assertFalse(Files.exists(config));
      }
    });
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      System.out.println("configuration available: " + Files.exists(Path.of(System.getenv("EAPDF_CONFIG"))));
      System.out.println("password available: " + (System.getenv(PdfSigning.PASSWORD_ENV) != null));
      System.err.println("merged stderr");
      System.out.println("ready π");
      if (Boolean.parseBoolean(args[0])) Thread.sleep(60_000);
    }
  }
}
