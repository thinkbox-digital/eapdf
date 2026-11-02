package eapdf;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.plaf.FontUIResource;

/** Desktop interface for checking one EA-PDF with the bundled Java validation engine. */
public final class ValidatorGui {
  static final float GUI_FONT_SIZE = 16f;

  private static void configureFonts() {
    for (Object key : new ArrayList<>(UIManager.getDefaults().keySet())) {
      Object value = UIManager.get(key);
      if (value instanceof Font font)
        UIManager.put(key, new FontUIResource(font.deriveFont(GUI_FONT_SIZE)));
    }
  }
  public static void main(String[] args) {
    if (java.awt.GraphicsEnvironment.isHeadless()) {
      System.err.println("EA-PDF Validator needs a graphical desktop.");
      System.exit(1);
    }
    SwingUtilities.invokeLater(() -> {
      try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
      catch (Exception ignored) { }
      new ValidatorGui(new JavaValidatorEngine()).show();
    });
  }
  interface Engine {
    Result validate(Path input) throws Exception;
    void cancel();
    default void progress(java.util.function.Consumer<String> listener) { }
  }

  record Finding(String level, String code, String message, String details) {
    Integer ruleNumber() { return JavaValidation.ruleNumber(code); }
  }

  record FindingGroup(String level, String code, String message, List<Finding> occurrences) {
    FindingGroup { occurrences = java.util.Collections.unmodifiableList(occurrences); }
    Integer ruleNumber() { return JavaValidation.ruleNumber(code); }
    int count() { return occurrences.size(); }
    String detailText() {
      StringBuilder text = new StringBuilder(ruleNumber() == null ? "" : "Rule " + ruleNumber() + " — ")
          .append(code).append(" — ").append(level).append("\n\n")
          .append(switch (level) {
            case "PASS" -> "Observed result: this reported check was satisfied. Other occurrences of this rule may have separate results.";
            case "FAIL" -> "Observed result: this reported check found a violation.";
            case "WARN" -> "Observed result: a recommendation was not met, or the check could not be completed; the finding and evidence below state which.";
            default -> "Observed result: this reported check was not applicable; the reason is given below.";
          }).append("\n\nFinding / checked condition:\n").append(message)
          .append("\n\nOccurrences: ").append(count());
      var details = new LinkedHashMap<String, Integer>();
      int displayed = 0, characters = 0;
      for (Finding occurrence : occurrences) {
        if (displayed >= 1000 || characters > 1_000_000) break;
        displayed++;
        if (!occurrence.details().isEmpty()) {
          details.merge(occurrence.details(), 1, Integer::sum);
          characters += occurrence.details().length();
        }
      }
      if (displayed < occurrences.size()) text.append("\nShowing details for the first ").append(displayed)
          .append(" occurrences. Save the report for every occurrence.");
      int index = 0;
      for (var entry : details.entrySet()) {
        text.append("\n\nDetails ").append(++index);
        if (entry.getValue() > 1) text.append(" (").append(entry.getValue()).append(" occurrences)");
        text.append(":\n").append(entry.getKey());
      }
      return text.toString();
    }
  }

  static List<FindingGroup> groupFindings(List<Finding> findings) {
    if (findings instanceof ReportFindings stored) return stored.groups();
    record Key(String level, String code, String message) {}
    var grouped = new LinkedHashMap<Key, List<Finding>>();
    for (Finding finding : findings)
      grouped.computeIfAbsent(new Key(finding.level(), finding.code(), finding.message()), key -> new ArrayList<>()).add(finding);
    List<FindingGroup> groups = new ArrayList<>();
    grouped.forEach((key, values) -> groups.add(new FindingGroup(key.level(), key.code(), key.message(), values)));
    return sortGroups(groups);
  }

  static List<FindingGroup> sortGroups(List<FindingGroup> groups) {
    groups.sort(Comparator.comparingInt((FindingGroup group) -> "PDFA_FIRST_PASS".equals(group.code()) ? -2
        : "ISO32005_UPSTREAM".equals(group.code()) ? -1 : group.ruleNumber() == null ? Integer.MAX_VALUE : group.ruleNumber()));
    return List.copyOf(groups);
  }

  static boolean matchesRule(String code, String keyword) {
    String rule = code.toLowerCase(Locale.ROOT);
    for (String word : keyword.strip().toLowerCase(Locale.ROOT).split("\\s+"))
      if (!rule.contains(word)) return false;
    return true;
  }

  record Result(String status, String pdfaStatus, String profile, String scope,
                List<Finding> findings, String json) {
    Result {
      if (!(findings instanceof ReportFindings)) findings = List.copyOf(findings);
    }

    static Result parse(String json) throws IOException {
      try {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject summary = root.getAsJsonObject("summary");
        JsonObject pdfa = root.getAsJsonObject("pdfa");
        List<Finding> findings = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("items")) findings.add(finding(value.getAsJsonObject()));
        return checked(summary, pdfa, findings, json);
      } catch (RuntimeException error) {
        throw new IOException("The validator returned an unreadable report: " + error.getMessage(), error);
      }
    }

    static Result parse(Path report) throws IOException {
      ReportFindings findings = new ReportFindings(report);
      try (var reader = new com.google.gson.stream.JsonReader(Files.newBufferedReader(report))) {
        reader.setStrictness(com.google.gson.Strictness.STRICT);
        JsonObject summary = null, pdfa = null;
        boolean items = false;
        reader.beginObject();
        while (reader.hasNext()) switch (reader.nextName()) {
          case "summary" -> { if (summary != null) throw new IOException("Duplicate report summary"); summary = JsonParser.parseReader(reader).getAsJsonObject(); }
          case "pdfa" -> { if (pdfa != null) throw new IOException("Duplicate PDF/A result"); pdfa = JsonParser.parseReader(reader).getAsJsonObject(); }
          case "items" -> {
            if (items) throw new IOException("Duplicate findings"); items = true;
            reader.beginArray();
            while (reader.hasNext()) findings.append(JsonParser.parseReader(reader).getAsJsonObject());
            reader.endArray();
          }
          default -> reader.skipValue();
        }
        reader.endObject();
        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("Trailing report data");
        return checked(summary, pdfa, findings, "");
      } catch (IOException | RuntimeException error) {
        findings.close();
        throw new IOException("The validator returned an unreadable report: " + error.getMessage(), error);
      }
    }

    static Finding finding(JsonObject item) {
      String level = required(item, "level");
      if (!Set.of("PASS", "FAIL", "WARN", "N/A").contains(level)) throw new IllegalArgumentException("Unknown finding level: " + level);
      JsonElement details = item.get("details");
      return new Finding(level, required(item, "code"), required(item, "message"),
          details == null || details.isJsonNull() ? "" : JavaValidation.JSON.toJson(details));
    }

    private static Result checked(JsonObject summary, JsonObject pdfa, List<Finding> findings, String json) {
        String status = required(summary, "status");
        if (!Set.of("PASS", "FAIL", "INCOMPLETE").contains(status)) throw new IllegalArgumentException("Unknown validation status: " + status);
        String pdfaStatus = required(pdfa, "status");
        if (!Set.of("PASS", "FAIL", "ERROR", "SKIP", "NOT_RUN").contains(pdfaStatus)) throw new IllegalArgumentException("Unknown PDF/A status: " + pdfaStatus);
        if (findings.isEmpty()) throw new IllegalArgumentException("The report has no findings");
        // A broken or truncated backend report must never appear as a successful check.
        if ("PASS".equals(status) && (!"PASS".equals(pdfaStatus)
            || !pdfa.get("ran").getAsBoolean() || !pdfa.get("compliant").getAsBoolean()
            || !pdfa.get("profile_allowed").getAsBoolean()
            || (findings instanceof ReportFindings stored ? stored.contradictsPass() : findings.stream().anyMatch(f -> "FAIL".equals(f.level())
                || ("WARN".equals(f.level()) && "VALIDATION_INCOMPLETE".equals(f.code())))))) {
          throw new IllegalArgumentException("The PASS result contradicts the report");
        }
        return new Result(status, pdfaStatus, optional(summary, "verification_spec"),
            required(summary, "validation_scope"), findings, json);
    }

    void save(Path destination) throws IOException {
      if (findings instanceof ReportFindings stored) stored.save(destination);
      else Files.writeString(destination, json);
    }
    void close() {
      if (findings instanceof ReportFindings stored) try { stored.close(); }
      catch (IOException error) { System.err.println("Unable to remove report scratch: " + error.getMessage()); }
    }

    private static String required(JsonObject object, String key) {
      JsonElement value = object.get(key);
      if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException("Missing report field: " + key);
      }
      return value.getAsString();
    }

    private static String optional(JsonObject object, String key) {
      return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
  }

  private final Engine engine;
  private final JFrame frame = new JFrame("EA-PDF Validator");
  private final JTextField file = new JTextField();
  private final JButton browse = new JButton("Choose PDF…");
  private final JButton validate = new JButton("Validate");
  private final JButton stop = new JButton("Stop");
  private final JButton save = new JButton("Save report…");
  private final JLabel status = new JLabel("Choose one PDF to validate.");
  private final JProgressBar progress = new JProgressBar();
  private final JTextField ruleFilter = new JTextField();
  private final JLabel visibleCount = new JLabel();
  private final DefaultTableModel rows = new DefaultTableModel(new String[]{"Result", "Rule #", "Rule", "Finding"}, 0) {
    @Override public boolean isCellEditable(int row, int column) { return false; }
    @Override public Class<?> getColumnClass(int column) { return column == 1 ? Integer.class : String.class; }
  };
  private final JTable table = new JTable(rows);
  private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(rows);
  private final JTextArea detail = new JTextArea();
  private final JTextArea specification = new JTextArea();
  private Result result;
  private List<FindingGroup> groups = List.of();
  private boolean running;
  private volatile boolean stopRequested;
  private boolean closeRequested;
  private Path checkedInput;

  ValidatorGui(Engine engine) {
    this.engine = engine;
  }

  void show() {
    configureFonts();
    frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    frame.addWindowListener(new WindowAdapter() {
      @Override public void windowClosing(WindowEvent event) {
        if (running) {
          closeRequested = true;
          requestStop();
        } else { clear(); frame.dispose(); }
      }
    });
    JPanel content = new JPanel(new BorderLayout(8, 8));
    content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    JPanel input = new JPanel(new BorderLayout(8, 8));
    input.add(new JLabel("PDF:"), BorderLayout.WEST);
    input.add(file, BorderLayout.CENTER);
    input.add(browse, BorderLayout.EAST);
    JPanel actions = new JPanel();
    actions.add(validate);
    actions.add(stop);
    actions.add(save);
    JPanel header = new JPanel(new BorderLayout());
    header.add(input, BorderLayout.NORTH);
    header.add(actions, BorderLayout.CENTER);
    header.add(new JLabel("EA-PDF, PDF/A and applicable ISO 32005 checks run locally."), BorderLayout.SOUTH);
    content.add(header, BorderLayout.NORTH);
    table.setRowSorter(sorter);
    DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
    renderer.putClientProperty("html.disable", Boolean.TRUE);
    table.setDefaultRenderer(Object.class, renderer);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getColumnModel().getColumn(0).setMaxWidth(110);
    table.getColumnModel().getColumn(1).setMaxWidth(75);
    table.getColumnModel().getColumn(2).setPreferredWidth(260);
    table.getColumnModel().getColumn(3).setPreferredWidth(560);
    detail.setEditable(false);
    detail.setLineWrap(true);
    detail.setWrapStyleWord(true);
    specification.setEditable(false);
    specification.setLineWrap(true);
    specification.setWrapStyleWord(true);
    JScrollPane findingPane = new JScrollPane(detail);
    findingPane.setBorder(BorderFactory.createTitledBorder("Finding"));
    JScrollPane specificationPane = new JScrollPane(specification);
    specificationPane.setBorder(BorderFactory.createTitledBorder("Specification"));
    JSplitPane information = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, findingPane, specificationPane);
    information.setResizeWeight(0.4);
    JPanel findings = new JPanel(new BorderLayout(8, 8));
    JPanel filter = new JPanel(new BorderLayout(8, 8));
    filter.add(new JLabel("Keyword in Rule:"), BorderLayout.WEST);
    filter.add(ruleFilter, BorderLayout.CENTER);
    filter.add(visibleCount, BorderLayout.EAST);
    findings.add(filter, BorderLayout.NORTH);
    findings.add(new JScrollPane(table), BorderLayout.CENTER);
    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, findings, information);
    split.setResizeWeight(0.55);
    content.add(split, BorderLayout.CENTER);
    JPanel footer = new JPanel(new BorderLayout(8, 8));
    footer.add(status, BorderLayout.CENTER);
    footer.add(progress, BorderLayout.SOUTH);
    content.add(footer, BorderLayout.SOUTH);
    browse.addActionListener(event -> choose());
    validate.addActionListener(event -> start());
    file.addActionListener(event -> start());
    file.getDocument().addDocumentListener(new DocumentListener() {
      @Override public void insertUpdate(DocumentEvent event) { changed(); }
      @Override public void removeUpdate(DocumentEvent event) { changed(); }
      @Override public void changedUpdate(DocumentEvent event) { changed(); }
      private void changed() {
        if (!running) {
          clear();
          status.setText("Choose one PDF to validate.");
        }
      }
    });
    stop.addActionListener(event -> requestStop());
    save.addActionListener(event -> save());
    table.getSelectionModel().addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting()) selectedFinding();
    });
    ruleFilter.getDocument().addDocumentListener(new DocumentListener() {
      @Override public void insertUpdate(DocumentEvent event) { filterRules(); }
      @Override public void removeUpdate(DocumentEvent event) { filterRules(); }
      @Override public void changedUpdate(DocumentEvent event) { filterRules(); }
    });
    var escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
    var clearFilter = new AbstractAction() {
      @Override public void actionPerformed(ActionEvent event) {
        ruleFilter.setText("");
        ruleFilter.requestFocusInWindow();
      }
    };
    frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escape, "clear-rule-filter");
    frame.getRootPane().getActionMap().put("clear-rule-filter", clearFilter);
    ruleFilter.getInputMap(JComponent.WHEN_FOCUSED).put(escape, "clear-rule-filter");
    ruleFilter.getActionMap().put("clear-rule-filter", clearFilter);
    table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(escape, "clear-rule-filter");
    table.getActionMap().put("clear-rule-filter", clearFilter);
    stop.setEnabled(false);
    save.setEnabled(false);
    frame.setContentPane(content);
    SwingUtilities.updateComponentTreeUI(frame);
    table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 8);
    frame.setSize(new Dimension(2000, 1100));
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
    split.setDividerLocation(0.55);
    information.setDividerLocation(0.4);
    filterRules();
  }

  private void selectedFinding() {
    if (result == null || table.getSelectedRow() < 0) {
      detail.setText(""); specification.setText(""); return;
    }
    FindingGroup finding = groups.get(table.convertRowIndexToModel(table.getSelectedRow()));
    detail.setText(finding.detailText());
    detail.setCaretPosition(0);
    specification.setText(SpecificationReferences.text(finding.code()));
    specification.setCaretPosition(0);
  }

  private void filterRules() {
    sorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
      @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
        return matchesRule(entry.getStringValue(2), ruleFilter.getText());
      }
    });
    visibleCount.setText(table.getRowCount() + " / " + rows.getRowCount() + " groups"
        + (result == null ? "" : "; " + result.findings().size() + " findings"));
    selectedFinding();
  }

  private void choose() {
    JFileChooser chooser = new JFileChooser();
    chooser.setMultiSelectionEnabled(false);
    chooser.setFileFilter(new FileNameExtensionFilter("PDF files", "pdf"));
    if (!file.getText().isBlank()) {
      try { chooser.setSelectedFile(Path.of(file.getText().strip()).toFile()); }
      catch (RuntimeException ignored) { /* The picker still works after invalid manual input. */ }
    }
    if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
      file.setText(chooser.getSelectedFile().getAbsolutePath());
      clear();
      status.setText("Ready to validate.");
    }
  }

  private void clear() {
    if (result != null) result.close();
    result = null;
    groups = List.of();
    checkedInput = null;
    rows.setRowCount(0);
    filterRules();
    detail.setText("");
    specification.setText("");
    save.setEnabled(false);
  }

  private void start() {
    if (running) return;
    clear();
    Path input;
    try {
      if (file.getText().isBlank()) throw new IOException("Choose a PDF first.");
      input = Path.of(file.getText().strip()).toAbsolutePath().normalize();
      if (!Files.isRegularFile(input) || !Files.isReadable(input)) throw new IOException("The PDF cannot be read.");
    } catch (Exception error) {
      status.setText("Validation did not start.");
      detail.setText(error.getMessage());
      return;
    }
    running = true;
    stopRequested = false;
    setRunning(true);
    status.setText("Validating " + input.getFileName() + "…");
    engine.progress(message -> SwingUtilities.invokeLater(() -> { if (running && !stopRequested) status.setText(message); }));
    new SwingWorker<Result, Void>() {
      @Override protected Result doInBackground() throws Exception { return engine.validate(input); }
      @Override protected void done() {
        try {
          Result checked = get();
          {
            result = checked;
            checkedInput = input;
            groups = groupFindings(result.findings());
            for (FindingGroup finding : groups) rows.addRow(new Object[]{finding.level(), finding.ruleNumber(), finding.code(), finding.message()});
            filterRules();
            status.setText("Result: " + result.status() + "    PDF/A: " + result.pdfaStatus()
                + (result.profile().isBlank() ? "" : "    EA-PDF: " + result.profile()));
            detail.setText(result.scope());
            save.setEnabled(true);
          }
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          failed(error);
        } catch (ExecutionException error) {
          if (stopRequested && error.getCause() instanceof CancellationException) status.setText("Stopped. Validation was not completed.");
          else failed(error.getCause());
        } catch (CancellationException error) {
          status.setText("Stopped. Validation was not completed.");
        } finally {
          running = false;
          setRunning(false);
          if (closeRequested) { clear(); frame.dispose(); }
        }
      }
    }.execute();
  }

  private void failed(Throwable error) {
    status.setText("Validation could not finish.");
    detail.setText(error.getMessage() == null ? error.toString() : error.getMessage());
  }

  private void setRunning(boolean value) {
    file.setEnabled(!value);
    browse.setEnabled(!value);
    validate.setEnabled(!value);
    stop.setEnabled(value);
    progress.setIndeterminate(value);
  }

  private void requestStop() {
    stopRequested = true;
    stop.setEnabled(false);
    status.setText("Stopping…");
    engine.cancel();
  }

  private void save() {
    if (result == null) return;
    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new FileNameExtensionFilter("JSON reports", "json"));
    chooser.setSelectedFile(new java.io.File("validation-report.json"));
    if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path destination = chooser.getSelectedFile().toPath();
    if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
      destination = destination.resolveSibling(destination.getFileName() + ".json");
    }
    try {
      if (Files.exists(destination) && Files.isSameFile(destination, checkedInput)) {
        throw new IOException("The input PDF cannot be replaced by a report.");
      }
    } catch (IOException error) {
      JOptionPane.showMessageDialog(frame, error.getMessage(), "Could not save report", JOptionPane.ERROR_MESSAGE);
      return;
    }
    if (Files.exists(destination) && JOptionPane.showConfirmDialog(frame, "Replace the existing report?",
        "Save report", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
    try { result.save(destination); }
    catch (IOException error) { JOptionPane.showMessageDialog(frame, error.getMessage(), "Could not save report", JOptionPane.ERROR_MESSAGE); }
  }
}
