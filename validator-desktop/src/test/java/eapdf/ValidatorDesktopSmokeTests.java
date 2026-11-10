package eapdf;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Run on a real graphical desktop with -Deapdf.validator.uiTest=true. */
@EnabledIfSystemProperty(named = "eapdf.validator.uiTest", matches = "true")
class ValidatorDesktopSmokeTests {
  @Test void windowValidatesARealFileAndStopsASecondRunWithoutLeavingWorkFiles() throws Exception {
    assertFalse(GraphicsEnvironment.isHeadless(), "A graphical desktop is required for this test");
    Set<Path> before = workspaces();
    AtomicReference<ValidatorGui> reference = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> {
      ValidatorGui gui = new ValidatorGui(new JavaValidatorEngine()); reference.set(gui); gui.show();
    });
    ValidatorGui gui = reference.get();
    JFrame frame = field(gui, "frame", JFrame.class);
    JTextField file = field(gui, "file", JTextField.class);
    JButton validate = field(gui, "validate", JButton.class);
    JButton stop = field(gui, "stop", JButton.class);
    JLabel status = field(gui, "status", JLabel.class);
    JTable table = field(gui, "table", JTable.class);
    javax.swing.JTextPane specification = field(gui, "specification", javax.swing.JTextPane.class);
    JTextArea detail = field(gui, "detail", JTextArea.class);
    JTextField ruleFilter = field(gui, "ruleFilter", JTextField.class);
    try {
      SwingUtilities.invokeAndWait(() -> {
        var picker = new javax.swing.Timer(100, event -> {
          for (var window : java.awt.Window.getWindows()) {
            var chooser = findChooser(window);
            if (chooser != null && chooser.isShowing()) {
              ((javax.swing.Timer) event.getSource()).stop();
              chooser.setSelectedFile(Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_archive_1c.pdf").toAbsolutePath().normalize().toFile());
              chooser.approveSelection(); return;
            }
          }
        });
        picker.start();
        try { fieldUnchecked(gui, "browse", JButton.class).doClick(); }
        finally { picker.stop(); }
      });
      await(() -> status.getText().startsWith("Result:"));
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(status.getText().startsWith("Result: PASS"));
        assertTrue(status.getText().contains("PDF/A: PASS"));
        assertTrue(table.getRowCount() >= JavaValidation.RULES.size());
        assertEquals(4, table.getColumnCount());
        assertTrue(frame.getWidth() <= java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().width);
        assertTrue(fieldUnchecked(gui, "outcome", JLabel.class).isVisible());
        assertEquals("Validation complete", fieldUnchecked(gui, "outcome", JLabel.class).getText());
        for (var component : java.util.List.of(file, validate, stop, status, table,
            table.getTableHeader(), specification, detail, ruleFilter))
          assertEquals(ValidatorGui.GUI_FONT_SIZE, component.getFont().getSize2D(), component.getClass().getName());
        assertTrue(table.getRowHeight() > table.getFontMetrics(table.getFont()).getHeight());
        int embeddedRows = 0, embedded = -1;
        for (int row = 0; row < table.getRowCount(); row++) if ("EMBEDDED_STREAM".equals(table.getValueAt(row, 2))) { embeddedRows++; embedded = row; }
        assertEquals(1, embeddedRows);
        table.setRowSelectionInterval(embedded, embedded);
        var result = fieldUnchecked(gui, "result", ValidatorGui.Result.class);
        long occurrences = result.findings().stream().filter(f -> "EMBEDDED_STREAM".equals(f.code())).count();
        assertTrue(occurrences > 1);
        assertTrue(detail.getText().contains("Occurrences: " + occurrences));
        assertTrue(detail.getText().contains("Details 1:"));
        assertTrue(validate.isEnabled()); assertFalse(stop.isEnabled());
        table.getRowSorter().toggleSortOrder(1);
        int selected = -1;
        for (int row = 0; row < table.getRowCount(); row++) {
          if ("CATALOG_PIECEINFO".equals(table.getValueAt(row, 2))) { selected = row; break; }
        }
        assertTrue(selected >= 0);
        table.setRowSelectionInterval(selected, selected);
        assertTrue(specification.getText().contains("Section 9.1; printed page(s) 62; PDF page(s) 69"));
        assertTrue(specification.getText().contains("SHALL NOT contain a PieceInfo"));
        assertEquals(JavaValidation.ruleNumber("CATALOG_PIECEINFO"), table.getValueAt(selected, 1));
        assertTrue(detail.getText().startsWith("Rule " + JavaValidation.ruleNumber("CATALOG_PIECEINFO")));
        assertTrue(detail.isShowing()); assertTrue(specification.isShowing());
        int total = table.getRowCount();
        ruleFilter.setText("CaTaLoG pieceinfo");
        assertTrue(table.getRowCount() > 0 && table.getRowCount() < total);
        assertEquals("CATALOG_PIECEINFO", table.getValueAt(0, 2));
        table.setRowSelectionInterval(0, 0);
        assertTrue(detail.getText().contains("CATALOG_PIECEINFO"));
        ruleFilter.setText("[no such rule]");
        assertEquals(0, table.getRowCount()); assertEquals("", detail.getText()); assertEquals("", specification.getText());
        ruleFilter.setText("");
        assertEquals(total, table.getRowCount());
        ruleFilter.setText("catalog pieceinfo");
        var escape = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0);
        Object action = ruleFilter.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(escape);
        assertNotNull(action);
        ruleFilter.getActionMap().get(action).actionPerformed(new java.awt.event.ActionEvent(ruleFilter, 0, "Escape"));
        assertEquals("", ruleFilter.getText());
        assertEquals(total, table.getRowCount());
        assertNotNull(frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape));
        assertNotNull(table.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(escape));
        ruleFilter.setText("eapdf");
        for (int row = 0; row < table.getRowCount(); row++) if ("EAPDF_REV".equals(table.getValueAt(row, 2))) {
          assertTrue(table.getValueAt(row, 3).toString().contains("XMP integer 2024"));
          table.setRowSelectionInterval(row, row);
        }
        ruleFilter.setText("ASSET_NUMBERMESSAGES_MATCH_RENDERED");
        assertEquals(1, table.getRowCount()); table.setRowSelectionInterval(0, 0);
        assertTrue(specification.getText().contains("This rule checks:"));
        assertTrue(specification.getText().contains("particular source-asset occurrence"));
        assertTrue(specification.getText().contains("Linked source context"));
        assertTrue(detail.getText().contains("rendered_messages"));
        assertTrue(detail.getText().contains("declared"));
      });
      SwingUtilities.invokeAndWait(() -> {
        ruleFilter.setText("");
        for (int row = 0; row < table.getRowCount(); row++) if ("ASSET_NUMBERMESSAGES_MATCH_RENDERED".equals(table.getValueAt(row, 2))) {
          table.setRowSelectionInterval(row, row); table.scrollRectToVisible(table.getCellRect(row, 0, true)); break;
        }
        assertTrue(fieldUnchecked(gui, "browse", JButton.class).getLocationOnScreen().y >= file.getLocationOnScreen().y - 4);
        assertNotNull(validate.getIcon());
      });
      Path screenshot = Path.of(System.getProperty("java.io.tmpdir"), "eapdf-validator-native", "window.png");
      Files.createDirectories(screenshot.getParent());
      BufferedImage picture = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
      SwingUtilities.invokeAndWait(() -> {
        var graphics = picture.createGraphics();
        try { frame.printAll(graphics); } finally { graphics.dispose(); }
      });
      ImageIO.write(picture, "png", screenshot.toFile());
      SwingUtilities.invokeAndWait(() -> {
        ruleFilter.setText("");
        file.setText(Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_archive_1c.pdf").toAbsolutePath().normalize().toString()); validate.doClick();
      });
      Object engine = field(gui, "engine", Object.class);
      @SuppressWarnings("unchecked") AtomicReference<Object> run = field(engine, "current", AtomicReference.class);
      await(() -> {
        try { Process process = field(run.get(), "process", Process.class); return process != null && process.isAlive(); }
        catch (Exception error) { throw new IllegalStateException(error); }
      });
      SwingUtilities.invokeAndWait(stop::doClick);
      await(() -> status.getText().startsWith("Result: INCOMPLETE"));
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(table.getRowCount() > 0); assertTrue(validate.isEnabled());
        var stopped = fieldUnchecked(gui, "result", ValidatorGui.Result.class);
        assertEquals("INCOMPLETE", stopped.status());
        assertTrue(stopped.findings().stream().anyMatch(f -> "VALIDATION_INCOMPLETE".equals(f.code())));
        frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING));
      });
      assertEquals(before, workspaces(), "The validation worker must remove its own temporary workspace");
    } finally { SwingUtilities.invokeAndWait(frame::dispose); }
  }

  private static javax.swing.JFileChooser findChooser(java.awt.Container parent) {
    if (parent instanceof javax.swing.JFileChooser chooser) return chooser;
    for (var child : parent.getComponents()) if (child instanceof java.awt.Container container) {
      var found = findChooser(container); if (found != null) return found;
    }
    return null;
  }

  private static <T> T field(Object object, String name, Class<T> type) throws Exception {
    var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(object));
  }
  private static <T> T fieldUnchecked(Object object, String name, Class<T> type) {
    try { return field(object, name, type); }
    catch (Exception error) { throw new AssertionError(error); }
  }
  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
    while (System.nanoTime() < deadline) {
      AtomicReference<Boolean> ready = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) return;
      Thread.sleep(100);
    }
    fail("The desktop validation action did not finish");
  }
  private static Set<Path> workspaces() throws Exception {
    try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return paths.filter(path -> path.getFileName().toString().startsWith("eapdf-validator-")
          && !Set.of("eapdf-validator-native", "eapdf-validator-build", "eapdf-validator-desktop-build").contains(path.getFileName().toString())).collect(Collectors.toSet());
    }
  }
}
