package eapdf;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Exercise the GUI and validation worker from the release candidate. */
public final class ReleaseValidatorGuiSmoke {
  private ReleaseValidatorGuiSmoke() {}

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected candidate JAR, profile 1s PDF, and report path");
    require(!GraphicsEnvironment.isHeadless(), "A graphical desktop is required to test the validator");
    Path candidate = Path.of(args[0]).toRealPath();
    Path input = Path.of(args[1]).toRealPath();
    Path report = Path.of(args[2]).toAbsolutePath();
    require(!Files.exists(report), "Report path must be new: " + report);

    try (var archive = new JarFile(candidate.toFile())) {
      require(ValidatorGui.class.getName().equals(archive.getManifest().getMainAttributes()
          .getValue(Attributes.Name.MAIN_CLASS)), "Unexpected validator Main-Class");
    }
    Path source = Path.of(ValidatorGui.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    require(Files.isSameFile(candidate, source), "Validator GUI was not loaded from the candidate JAR");
    // Use only the packaged classpath when the GUI starts its validation subprocess.
    System.setProperty("java.class.path", candidate.toString());
    ValidatorGui.main(new String[0]);

    JFrame frame = waitFor(() -> {
      for (Window window : Window.getWindows()) {
        if (window instanceof JFrame validator && validator.isShowing()
            && "EA-PDF Validator".equals(validator.getTitle())) return validator;
      }
      return null;
    }, 20, "The packaged validator window did not open");
    try {
      JLabel fileLabel = onEdt(() -> find(frame, JLabel.class, label -> "PDF:".equals(label.getText())));
      JLabel status = onEdt(() -> find(frame, JLabel.class, label -> label.getText() != null
          && label.getText().startsWith("Ready. Choose a PDF")));
      JButton validate = onEdt(() -> find(frame, JButton.class, button -> "Validate".equals(button.getText())));
      JButton save = onEdt(() -> find(frame, JButton.class, button -> "Save Report…".equals(button.getText())));
      require(fileLabel != null && fileLabel.getLabelFor() instanceof JTextField
          && status != null && validate != null && save != null, "Validator controls are missing");

      onEdt(() -> {
        ((JTextField) fileLabel.getLabelFor()).setText(input.toString());
        validate.doClick();
        return null;
      });
      String outcome = waitFor(() -> validate.isEnabled() ? status.getText() : null,
          120, "Validator did not finish checking " + input);
      require(outcome.startsWith("Result: PASS") && outcome.contains("PDF/A: PASS"), outcome);
      onEdt(() -> {
        require(save.isEnabled(), "The validator did not enable report saving");
        saveReport(frame, save, report);
        return null;
      });
      require(Files.isRegularFile(report), "Validator did not save the report: " + report);
      var saved = ValidatorGui.Result.parse(Files.readString(report));
      require("PASS".equals(saved.status()) && "PASS".equals(saved.pdfaStatus()) && "1s".equals(saved.profile()),
          "Saved report did not confirm EA-PDF 1s and PDF/A success");
      System.out.println("Packaged validator GUI validated EA-PDF 1s and saved " + report);
    } finally {
      onEdt(() -> {
        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        return null;
      });
    }
  }

  private static void saveReport(JFrame frame, JButton save, Path report) {
    Timer picker = new Timer(50, event -> {
      for (Window window : frame.getOwnedWindows()) {
        if (!(window instanceof JDialog) || !window.isShowing()) continue;
        JFileChooser chooser = find(window, JFileChooser.class, Component::isShowing);
        if (chooser != null) {
          ((Timer) event.getSource()).stop();
          chooser.setSelectedFile(report.toFile());
          chooser.approveSelection();
          return;
        }
      }
    });
    picker.start();
    try { save.doClick(); }
    finally { picker.stop(); }
  }

  private static <T extends Component> T find(Component component, Class<T> type, Predicate<T> matches) {
    if (type.isInstance(component)) {
      T candidate = type.cast(component);
      if (matches.test(candidate)) return candidate;
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        T found = find(child, type, matches);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static <T> T waitFor(Callable<T> probe, int seconds, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      T result = onEdt(probe);
      if (result != null) return result;
      Thread.sleep(100);
    }
    throw new IllegalStateException(message);
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    var task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(30, TimeUnit.SECONDS);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
