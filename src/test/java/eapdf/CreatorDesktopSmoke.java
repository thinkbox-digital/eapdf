package eapdf;

import com.google.gson.JsonParser;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Runs the same Swing checks against class files or a packaged Creator GUI JAR. */
public final class CreatorDesktopSmoke {
  private CreatorDesktopSmoke() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) {
      throw new IllegalArgumentException("Expected input EML, scratch directory, and optional evidence directory");
    }
    run(Path.of(args[0]), Path.of(args[1]), args.length == 3 ? Path.of(args[2]) : null);
  }

  static void run(Path source, Path scratch, Path evidence) throws Exception {
    require(!GraphicsEnvironment.isHeadless(), "Creator smoke requires a graphical display");
    Path inputPath = source.toAbsolutePath();
    require(Files.isRegularFile(inputPath), "Missing Creator smoke input: " + inputPath);
    Path work = Files.createDirectories(scratch.toAbsolutePath());

    List<Frame> existing = onEdt(() -> List.of(Frame.getFrames()));
    EapdfGui.main(new String[0]);
    JFrame frame = onEdt(() -> Arrays.stream(Frame.getFrames())
        .filter(candidate -> candidate instanceof JFrame && candidate.isShowing() && !existing.contains(candidate))
        .map(JFrame.class::cast)
        .filter(candidate -> findOrNull(candidate, JButton.class, b -> b.getText().equals("Convert Local Input")) != null)
        .findFirst().orElseThrow(() -> new AssertionError("Creator main did not show its window")));
    try {
      Container local = onEdt(() -> {
        JTabbedPane tabs = find(frame, JTabbedPane.class, t -> t.indexOfTab("Local Email") >= 0);
        return (Container) tabs.getComponentAt(tabs.indexOfTab("Local Email"));
      });
      JButton run = onEdt(() -> button(local, "Convert Local Input"));
      JButton stop = onEdt(() -> button(frame, "Stop"));
      JButton openOutput = onEdt(() -> button(frame, "Open output folder"));
      JLabel status = onEdt(() -> find(frame, JLabel.class, label -> label.getText().equals("Ready")));
      JTextField input = onEdt(() -> pathField(local, "Input file or folder"));
      JTextField output = onEdt(() -> pathField(local, "Output directory (required)"));

      SwingUtilities.invokeAndWait(() -> {
        find(local, JCheckBox.class, box -> box.getText().startsWith("Accessibility")).setSelected(true);
        find(local, JCheckBox.class, box -> box.getText().equals("Sign output")).setSelected(false);
      });
      require(errorDialog(frame, run::doClick).contains("Choose a local email"), "Creator accepted empty input");
      SwingUtilities.invokeAndWait(() -> input.setText(work.resolve("missing.eml").toString()));
      require(errorDialog(frame, run::doClick).contains("cannot be read"), "Creator accepted missing input");
      Path destination = Files.createDirectory(work.resolve("created"));
      choose(frame, onEdt(() -> find(input.getParent(), JButton.class, b -> true)), inputPath);
      choose(frame, onEdt(() -> find(output.getParent(), JButton.class, b -> true)), destination);
      require(onEdt(() -> inputPath.toString().equals(input.getText()) && destination.toString().equals(output.getText())),
          "Creator did not keep the selected paths");
      SwingUtilities.invokeAndWait(run::doClick);
      awaitCompletion(run);
      SwingUtilities.invokeAndWait(() -> {
        require(status.getText().equals("Finished"), "Conversion status: " + status.getText());
        require(!stop.isEnabled() && openOutput.isEnabled(), "Creator controls did not finish conversion");
        find(frame, JTextArea.class, area -> area.getText().contains("Validate the PDFs"));
      });
      List<Path> pdfs = publishedPdfs(destination);
      require(pdfs.size() == 1, "Creator must write exactly one PDF");
      Path progress;
      try (var evidenceDirectories = Files.newDirectoryStream(destination, ".eapdf-evidence-*")) {
        var directories = evidenceDirectories.iterator();
        require(directories.hasNext(), "Creator did not publish job evidence");
        progress = directories.next().resolve("progress.json");
        require(!directories.hasNext(), "Creator published more than one job evidence directory");
      }
      require(JsonParser.parseString(Files.readString(progress)).getAsJsonObject().get("state").getAsString()
          .equals("COMPLETED"),
          "Creator progress did not record completion");
      if (evidence != null) {
        recordEvidence(frame, inputPath, pdfs.getFirst(), progress, evidence);
      }

      Path stopped = Files.createDirectory(work.resolve("stopped"));
      SwingUtilities.invokeAndWait(() -> {
        output.setText(stopped.toString());
        run.doClick();
        require(stop.isEnabled(), "Stop was disabled during conversion");
        stop.doClick();
      });
      awaitCompletion(run);
      SwingUtilities.invokeAndWait(() -> {
        require(status.getText().equals("Stopped"), "Cancellation status: " + status.getText());
        require(!stop.isEnabled(), "Stop was still enabled after cancellation");
      });
      require(publishedPdfs(stopped).isEmpty(), "Creator published a PDF after cancellation");
      System.out.println("Creator GUI smoke passed");
    } finally {
      SwingUtilities.invokeAndWait(frame::dispose);
    }
  }

  private static List<Path> publishedPdfs(Path directory) throws Exception {
    try (var paths = Files.list(directory)) {
      return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".pdf")).toList();
    }
  }

  private static void recordEvidence(JFrame frame, Path source, Path pdf, Path progress, Path evidence) throws Exception {
    Files.createDirectories(evidence);
    Files.copy(pdf, evidence.resolve("eml_gui_1s.pdf"), StandardCopyOption.REPLACE_EXISTING);
    Files.copy(progress, evidence.resolve("progress.json"), StandardCopyOption.REPLACE_EXISTING);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
    Files.writeString(evidence.resolve("source.sha256"), HexFormat.of().formatHex(digest));
    BufferedImage image = onEdt(() -> {
      BufferedImage screenshot = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
      var graphics = screenshot.createGraphics();
      try {
        frame.printAll(graphics);
      } finally {
        graphics.dispose();
      }
      return screenshot;
    });
    ImageIO.write(image, "png", evidence.resolve("creator-completed.png").toFile());
  }

  private static JButton button(Container root, String text) {
    return find(root, JButton.class, button -> button.getText().equals(text));
  }

  private static JTextField pathField(Container root, String name) {
    JLabel label = find(root, JLabel.class, candidate -> name.equals(candidate.getText()));
    return find((Container) label.getLabelFor(), JTextField.class, field -> true);
  }

  private static void choose(JFrame frame, JButton browse, Path selection) throws Exception {
    interactWithDialog(frame, browse::doClick, JFileChooser.class, chooser -> {
      chooser.setSelectedFile(selection.toFile());
      chooser.approveSelection();
    });
  }

  private static String errorDialog(JFrame frame, Runnable action) throws Exception {
    var message = new AtomicReference<String>();
    interactWithDialog(frame, action, JOptionPane.class, pane -> {
      message.set(String.valueOf(pane.getMessage()));
      pane.setValue(JOptionPane.OK_OPTION);
    });
    return message.get();
  }

  private static <T extends Component> void interactWithDialog(JFrame frame, Runnable action, Class<T> type,
      Consumer<T> interact) throws Exception {
    var handled = new AtomicReference<>(false);
    SwingUtilities.invokeAndWait(() -> {
      Timer timer = new Timer(100, event -> {
        for (Window window : frame.getOwnedWindows()) {
          if (window instanceof JDialog dialog && dialog.isShowing()) {
            T component = findOrNull(dialog, type, candidate -> true);
            if (component != null) {
              ((Timer) event.getSource()).stop();
              handled.set(true);
              interact.accept(component);
              return;
            }
          }
        }
      });
      Timer deadline = new Timer(10_000, event -> {
        for (Window window : frame.getOwnedWindows()) {
          window.dispose();
        }
      });
      deadline.setRepeats(false);
      timer.start();
      deadline.start();
      try {
        action.run();
      } finally {
        timer.stop();
        deadline.stop();
      }
    });
    require(handled.get(), "Creator did not show " + type.getSimpleName());
  }

  private static <T extends Component> T find(Container root, Class<T> type, Predicate<T> predicate) {
    T component = findOrNull(root, type, predicate);
    require(component != null, "Creator window is missing " + type.getSimpleName());
    return component;
  }

  private static <T extends Component> T findOrNull(Container root, Class<T> type, Predicate<T> predicate) {
    for (Component component : root.getComponents()) {
      if (type.isInstance(component) && predicate.test(type.cast(component))) {
        return type.cast(component);
      }
      if (component instanceof Container child) {
        T found = findOrNull(child, type, predicate);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static <T> T onEdt(Supplier<T> action) throws Exception {
    var result = new AtomicReference<T>();
    SwingUtilities.invokeAndWait(() -> result.set(action.get()));
    return result.get();
  }

  private static void awaitCompletion(JButton run) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (System.nanoTime() < deadline) {
      if (onEdt(run::isEnabled)) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Creator desktop workflow timed out");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
