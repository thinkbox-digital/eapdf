package eapdf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

public class EapdfGui {
  private static final java.util.regex.Pattern JAVA_FAILURE = java.util.regex.Pattern.compile(
      "^(?:Exception in thread \"[^\"]+\" |Caused by: )([\\w.$]+)(?::\\s*(.*))?$");
  private final JFrame frame = new JFrame("ThinkBox EMailToPDF GUI");
  private final JTextArea logArea = new JTextArea();
  private final JTextArea summaryArea = new JTextArea();
  private final JProgressBar progressBar = new JProgressBar();
  private final JTabbedPane messageTabs = new JTabbedPane();
  private final JLabel statusLabel = new JLabel("Ready");
  private final JButton stopButton = new JButton("Stop");
  private final JButton openOutputButton = new JButton("Open output folder");
  private final JLabel outputLabel = new JLabel(" ");
  private Path lastOutputDirectory;
  private final List<JButton> runButtons = new ArrayList<>();
  private volatile Process activeProcess;
  private volatile boolean stopRequested;
  private boolean running;
  private boolean closeRequested;
  private String lastRunError;
  private boolean incompleteSource;
  private final Path launchDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception ignored) {
      }
      new EapdfGui().show();
    });
  }

  private void show() {
    frame.setTitle("ThinkBox EMailToPDF GUI " + Common.projectVersion());
    frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    frame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent event) {
        if (running) {
          closeRequested = true;
          stopActiveProcess();
        } else {
          frame.dispose();
          System.exit(0);
        }
      }
    });
    frame.setLayout(new BorderLayout(12, 12));
    frame.getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

    JPanel top = new JPanel(new BorderLayout(12, 12));
    top.add(buildHeader(), BorderLayout.NORTH);
    top.add(buildTabs(), BorderLayout.CENTER);

    frame.add(top, BorderLayout.NORTH);
    frame.add(buildLogPanel(), BorderLayout.CENTER);

    frame.setMinimumSize(new Dimension(1200, 955));
    addMissingTooltips(frame.getContentPane(), "Conversion options");
    frame.pack();
    var screen = frame.getGraphicsConfiguration().getBounds();
    var screenInsets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
    int availableWidth = screen.width - screenInsets.left - screenInsets.right;
    int availableHeight = screen.height - screenInsets.top - screenInsets.bottom;
    frame.setMinimumSize(new Dimension(Math.min(1200, availableWidth), Math.min(955, availableHeight)));
    frame.setSize(Math.min((int)Math.round(frame.getWidth() * 1.2), availableWidth),
        Math.min((int)Math.round(frame.getHeight() * 1.2), availableHeight));
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
  }

  private JPanel buildHeader() {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

    JLabel title = new JLabel("EA-PDF Desktop Converter");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
    JLabel subtitle = new JLabel("Authentic email archives—with original content, structured metadata, and provenance preserved for the future.");
    subtitle.setFont(subtitle.getFont().deriveFont(14f));

    panel.add(title);
    panel.add(Box.createVerticalStrut(4));
    panel.add(subtitle);
    return panel;
  }

  private JTabbedPane buildTabs() {
    JTabbedPane tabs = new JTabbedPane() {
      @Override public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        Component selected = getSelectedComponent();
        if (selected != null) {
          int tallest = 0;
          for (int i = 0; i < getTabCount(); i++) tallest = Math.max(tallest, getComponentAt(i).getPreferredSize().height);
          size.height += selected.getPreferredSize().height - tallest;
        }
        return size;
      }
    };
    tabs.addChangeListener(event -> { tabs.revalidate(); if (tabs.getParent() != null) tabs.getParent().revalidate(); });
    tabs.addTab("Local Email", topAligned(buildLocalPanel()));
    tabs.addTab("Gmail", topAligned(buildGmailPanel()));
    return tabs;
  }

  private JPanel topAligned(JPanel content) {
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(content, BorderLayout.NORTH);
    return panel;
  }

  private JPanel buildLogPanel() {
    JPanel panel = new JPanel(new BorderLayout(8, 8));
    panel.setBorder(BorderFactory.createTitledBorder("Conversion"));

    logArea.setEditable(false);
    logArea.setLineWrap(true);
    logArea.setWrapStyleWord(true);
    summaryArea.setEditable(false);
    summaryArea.setLineWrap(true);
    summaryArea.setWrapStyleWord(true);

    JPanel controls = new JPanel(new BorderLayout(8, 0));
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    stopButton.setToolTipText("Stop the active conversion; unfinished output is not published.");
    stopButton.setEnabled(false);
    stopButton.addActionListener(e -> stopActiveProcess());
    actions.add(stopButton);
    openOutputButton.setToolTipText("Open the most recent conversion output directory.");
    openOutputButton.setEnabled(false);
    openOutputButton.addActionListener(e -> openOutputFolder());
    actions.add(openOutputButton);
    progressBar.setPreferredSize(new Dimension(120, 18));
    actions.add(progressBar);
    controls.add(actions, BorderLayout.WEST);
    controls.add(statusLabel, BorderLayout.CENTER);

    JScrollPane logScroll = new JScrollPane(logArea);
    logScroll.setPreferredSize(new Dimension(960, 250));

    panel.add(controls, BorderLayout.NORTH);
    messageTabs.addTab("Summary", new JScrollPane(summaryArea));
    messageTabs.addTab("Details", logScroll);
    panel.add(messageTabs, BorderLayout.CENTER);
    panel.add(outputLabel, BorderLayout.SOUTH);
    return panel;
  }

  private JPanel buildLocalPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
    GridBagConstraints gc = defaultConstraints();

    JTextField inputField = new JTextField("", 42);
    inputField.setToolTipText("Choose an EML, MSG, PST or MBOX file, or a folder.");
    JTextField outDirField = new JTextField("", 42);
    JComboBox<Choice> profileBox = new JComboBox<>(new Choice[] {
        new Choice("One PDF per email (s, default)", "", false, false),
        new Choice("One PDF per email, omit originals (si)", "", false, true),
        new Choice("One archive PDF (2+ emails) (m)", "m", true, false),
        new Choice("One archive PDF (2+ emails), omit originals (mi)", "mi", true, false),
        new Choice("Folder collection (c)", "c", true, false),
        new Choice("Folder collection, omit originals (ci)", "ci", true, false)
    });
    RunConfigControls configControls = buildRunConfigControls();

    addPathRow(panel, gc, 0, "Input file or folder", inputField, e -> choosePath(inputField, JFileChooser.FILES_AND_DIRECTORIES, false));
    addPathRow(panel, gc, 1, "Output directory (required)", outDirField, e -> choosePath(outDirField, JFileChooser.DIRECTORIES_ONLY, false, inputField));
    addRow(panel, gc, 2, "Profile", buildProfilePicker(profileBox, true));
    addRow(panel, gc, 3, "Features", buildRunConfigPanel(configControls));

    JButton runButton = new JButton("Convert Local Input");
    runButton.setToolTipText("Convert the selected local email source using the profile and feature options above.");
    runButtons.add(runButton);
    profileBox.setSelectedIndex(0);

    runButton.addActionListener(e -> {
      String input = inputField.getText().trim();
      String outDir = outDirField.getText().trim();
      if (input.isBlank()) {
        showError("Choose a local email file or folder.");
        return;
      }
      try {
        if (!Files.isReadable(Paths.get(input))) {
          showError("That input cannot be read. Choose an existing email file or folder.");
          return;
        }
      } catch (java.nio.file.InvalidPathException ex) {
        showError("That input path is not valid. Choose an email file or folder.");
        return;
      }
      if (!requireOutputDirectory(outDirField)) return;
      List<String> args = new ArrayList<>();
      args.add("--out");
      args.add(outDir);
      args.add("--jobs");
      args.add("4");

      Choice choice = (Choice) profileBox.getSelectedItem();
      if (choice != null && choice.archiveMode) {
        args.add("--archive-profile");
        args.add(choice.code);
      } else if (choice != null && choice.omitSource) {
        args.add("--omit-source");
      }
      args.add(input);
      runJavaMain("Local Email", "eapdf.EmailToPDFA", args, configControls.snapshot());
    });

    addRunRow(panel, gc, 4, runButton);
    return panel;
  }

  private JPanel buildProfilePicker(JComboBox<Choice> choices, boolean localInput) {
    choices.setToolTipText("Choose single-message PDFs, a combined archive, or a collection. Isolated profiles omit original source assets.");
    JPanel picker = new JPanel(new BorderLayout(0, 4));
    JTextArea description = new JTextArea(3, 52);
    description.setName("profileDescription");
    description.setEditable(false);
    description.setFocusable(false);
    description.setOpaque(false);
    description.setLineWrap(true);
    description.setWrapStyleWord(true);
    description.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN));
    Runnable update = () -> {
      Choice choice = (Choice) choices.getSelectedItem();
      if (choice == null) return;
      String kept = localInput ? "Keeps source emails or PST message exports, existing folders, and attachment contents."
          : "Keeps original emails and attachment contents.";
      String omitted = "Original emails and attachment contents are omitted.";
      String text;
      if (choice.code.equals("c") || choice.code.equals("ci")) {
        text = "Stores separate email PDFs inside one collection. "
            + (choice.code.equals("ci") ? omitted : kept);
      } else if (choice.archiveMode) {
        text = "Combines at least two emails into one readable document. "
            + (choice.code.equals("mi") ? omitted : kept);
      } else if (choice.omitSource) {
        text = "Creates one PDF per email. " + omitted;
      } else {
        text = "Creates one PDF per email. " + kept;
      }
      description.setText(text);
      description.setCaretPosition(0);
    };
    choices.addActionListener(event -> update.run());
    update.run();
    picker.add(choices, BorderLayout.NORTH);
    picker.add(description, BorderLayout.CENTER);
    return picker;
  }

  private JPanel buildGmailPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
    GridBagConstraints gc = defaultConstraints();

    JTextField userField = new JTextField("", 24);
    JTextField credentialsField = new JTextField("", 42);
    JTextField tokenDirField = new JTextField("", 42);
    JTextField folderField = new JTextField("", 24);
    JTextField outDirField = new JTextField("", 42);
    JComboBox<Choice> profileBox = new JComboBox<>(new Choice[] {
        new Choice("One PDF per email (s, default)", "", false, false),
        new Choice("One PDF per email, omit originals (si)", "", false, true),
        new Choice("One archive PDF (2+ emails) (m)", "m", true, false),
        new Choice("One archive PDF (2+ emails), omit originals (mi)", "mi", true, false),
        new Choice("Folder collection (c)", "c", true, false),
        new Choice("Folder collection, omit originals (ci)", "ci", true, false)
    });
    JCheckBox unreadOnlyBox = new JCheckBox("Unread only");
    JSpinner limitSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 1));
    RunConfigControls configControls = buildRunConfigControls();
    userField.setToolTipText("Gmail account email address, or leave blank to use the account you authorize in the browser.");
    credentialsField.setToolTipText("Select the Google OAuth client credentials JSON file for this application.");
    tokenDirField.setToolTipText("Folder used to store and reuse Gmail authorization tokens. Leave blank to use .gmail_token_store in your home directory.");
    folderField.setToolTipText("Gmail folder or label to download. Leave blank for all mail.");
    outDirField.setToolTipText("Required destination folder for the downloaded emails and generated EA-PDF files.");
    unreadOnlyBox.setToolTipText("Download only messages marked unread in Gmail; their read status is not changed.");
    limitSpinner.setToolTipText("Maximum number of messages to download. Set 0 to download all matching messages.");
    ((JSpinner.DefaultEditor) limitSpinner.getEditor()).getTextField().setToolTipText(limitSpinner.getToolTipText());

    addRow(panel, gc, 0, "Email address", userField);
    addPathRow(panel, gc, 1, "Credentials JSON", credentialsField, e -> choosePath(credentialsField, JFileChooser.FILES_ONLY, false));
    addPathRow(panel, gc, 2, "Token directory", tokenDirField, e -> choosePath(tokenDirField, JFileChooser.DIRECTORIES_ONLY, false, credentialsField));
    addRow(panel, gc, 3, "Folder / label", folderField);
    addPathRow(panel, gc, 4, "Output directory (required)", outDirField, e -> choosePath(outDirField, JFileChooser.DIRECTORIES_ONLY, false, credentialsField, tokenDirField));
    addRow(panel, gc, 5, "Profile", buildProfilePicker(profileBox, false));
    addRow(panel, gc, 6, "Limit (0 = all)", limitSpinner);
    addRow(panel, gc, 7, "Unread filter", unreadOnlyBox);
    addRow(panel, gc, 8, "Features", buildRunConfigPanel(configControls));

    JButton runButton = new JButton("Download + Convert Gmail");
    runButton.setToolTipText("Download the selected Gmail messages and create EA-PDF output using these options.");
    runButtons.add(runButton);
    profileBox.setSelectedIndex(0);

    runButton.addActionListener(e -> {
      String credentials = credentialsField.getText().trim();
      if (credentials.isBlank()) {
        showError("Choose a Gmail credentials JSON file.");
        return;
      }
      if (!requireOutputDirectory(outDirField)) return;
      List<String> args = new ArrayList<>();
      putFlag(args, "--user", userField.getText().trim(), "me");
      putFlag(args, "--credentials", credentials, null);
      putFlag(args, "--token-dir", tokenDirField.getText().trim(), null);
      putFlag(args, "--folder", folderField.getText().trim(), null);
      putFlag(args, "--out-pdf", outDirField.getText().trim(), null);
      int limit = ((Number) limitSpinner.getValue()).intValue();
      if (limit > 0) {
        args.add("--limit");
        args.add(Integer.toString(limit));
      }
      if (unreadOnlyBox.isSelected()) args.add("--unread-only");

      Choice choice = (Choice) profileBox.getSelectedItem();
      if (choice != null && choice.archiveMode) {
        args.add("--archive-profile");
        args.add(choice.code);
      } else if (choice != null && choice.omitSource) {
        args.add("--omit-source");
      }
      runJavaMain("Gmail", "eapdf.GmailDownloadToPdf", args, configControls.snapshot());
    });

    addRunRow(panel, gc, 9, runButton);
    return panel;
  }

  static int showSigningPasswordDialog(Component parent, JPasswordField password) {
    String title = "Signing certificate password";
    JLabel label = new JLabel("Signing certificate password:");
    label.setLabelFor(password);
    password.getAccessibleContext().setAccessibleName("Signing certificate password");
    password.setToolTipText("Enter the password that unlocks your signing certificate; it is not saved in the configuration.");
    label.setLabelFor(password);
    label.setToolTipText(password.getToolTipText());
    JPanel content = new JPanel(new BorderLayout(0, 8));
    content.add(label, BorderLayout.NORTH);
    content.add(password, BorderLayout.CENTER);
    var pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION) {
      @Override public void selectInitialValue() {
        password.requestFocusInWindow();
      }
    };
    var dialog = pane.createDialog(parent, title);
    addMissingTooltips(dialog.getContentPane(), title);
    int titleWidth = password.getFontMetrics(password.getFont()).stringWidth(title);
    dialog.setMinimumSize(new Dimension(Math.max(440, titleWidth + 160), dialog.getHeight()));
    dialog.setLocationRelativeTo(parent);
    dialog.addWindowFocusListener(new WindowAdapter() {
      @Override public void windowGainedFocus(WindowEvent event) {
        SwingUtilities.invokeLater(password::requestFocusInWindow);
      }
    });
    try {
      dialog.setVisible(true);
      return pane.getValue() instanceof Integer choice ? choice : JOptionPane.CLOSED_OPTION;
    } finally {
      dialog.dispose();
    }
  }

  private boolean requireOutputDirectory(JTextField field) {
    if (!field.getText().isBlank()) return true;
    showError("Choose an output directory before starting conversion.");
    field.requestFocusInWindow();
    return false;
  }

  private void runJavaMain(String label, String mainClass, List<String> args, RunConfigOverrides configOverrides) {
    if (running) {
      showError("A conversion is already running. Stop it first or wait for it to finish.");
      return;
    }
    Path tempConfigPath;
    Path outputDirectory;
    try {
      String outputFlag = mainClass.equals("eapdf.GmailDownloadToPdf") ? "--out-pdf" : "--out";
      int outputIndex = args.indexOf(outputFlag);
      if (outputIndex < 0 || outputIndex + 1 >= args.size() || args.get(outputIndex + 1).isBlank()) {
        showError("Choose an output directory before starting conversion.");
        return;
      }
      outputDirectory = launchDirectory.resolve(args.get(outputIndex + 1)).normalize();
      if (Files.exists(outputDirectory) && !Files.isDirectory(outputDirectory)) {
        showError("The output path is a file. Choose a folder for the PDFs.");
        return;
      }
      tempConfigPath = writeGuiConfig(configOverrides);
    } catch (Exception ex) {
      showError("Failed to prepare run settings: " + Objects.toString(ex.getMessage(), "unknown error"));
      return;
    }
    final char[] signingPassword;
    if (configOverrides.signing.enabled) {
      JPasswordField password = new JPasswordField(24);
      int choice = showSigningPasswordDialog(frame, password);
      signingPassword = password.getPassword();
      password.setText("");
      if (choice != JOptionPane.OK_OPTION) {
        java.util.Arrays.fill(signingPassword, '\0');
        try { Files.deleteIfExists(tempConfigPath); } catch (Exception ignored) {}
        return;
      }
    } else signingPassword = null;
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    // The GUI decodes the merged process log as UTF-8, including supervisor output.
    command.add("-Dstdout.encoding=UTF-8");
    command.add("-Dstderr.encoding=UTF-8");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(mainClass);
    command.addAll(args);

    final Path runLog;
    try {
      Path logs=Paths.get(System.getProperty("user.home"),".eapdf","logs");Files.createDirectories(logs);
      runLog=Files.createTempFile(logs,"conversion-",".log");
    } catch(Exception error) {
      if(signingPassword!=null)java.util.Arrays.fill(signingPassword,'\0');
      try{Files.deleteIfExists(tempConfigPath);}catch(Exception ignored){}
      showError("Could not create the conversion log: "+error.getMessage());return;
    }
    List<String> logHeader=List.of("", "== "+label+" ==", "$ "+shellCommand(command),
        "GUI feature overrides: "+describeConfigOverrides(configOverrides),"Full conversion log: "+runLog);
    logArea.setToolTipText("Recent output only. Complete conversion log: "+runLog);
    appendLogLines(logHeader);
    lastOutputDirectory = null;
    stopRequested = false;
    lastRunError = null;
    incompleteSource = false;
    outputLabel.setText(" ");
    summaryArea.setText("Converting " + label + "..." + System.lineSeparator());
    messageTabs.setSelectedIndex(0);
    setRunning(true, "Running " + label + "...");

    SwingWorker<Integer, Void> worker = new SwingWorker<>() {
      @Override
      protected Integer doInBackground() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(System.getProperty("user.dir")));
        builder.redirectErrorStream(true);
        builder.environment().put("EAPDF_CONFIG", tempConfigPath.toString());
        Process process;
        try {
          if (signingPassword != null) builder.environment().put(PdfSigning.PASSWORD_ENV, new String(signingPassword));
          process = builder.start();
        } finally {
          builder.environment().remove(PdfSigning.PASSWORD_ENV);
          if (signingPassword != null) java.util.Arrays.fill(signingPassword, '\0');
        }
        activeProcess = process;
        if (stopRequested) ConversionSupervisor.requestStop(process);
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var saved=Files.newBufferedWriter(runLog,StandardCharsets.UTF_8)) {
          for(String line:logHeader){saved.write(line);saved.newLine();}
          GuiLog.copy(reader,saved,lines->SwingUtilities.invokeAndWait(()->appendLogLines(lines)));
          int exitCode=process.waitFor();saved.write("Exit code: "+exitCode);saved.newLine();return exitCode;
        } catch (Exception error) {
          if (!stopRequested) {
            ConversionSupervisor.requestStop(process);
            throw error;
          }
        }
        return process.waitFor();
      }

      @Override
      protected void done() {
        try {
          int exitCode = get();
          appendLog("Exit code: " + exitCode);
          appendSummary("Full conversion log: "+runLog);
          boolean stopped = stopRequested && exitCode != 0;
          if (exitCode == 0 && Files.isDirectory(outputDirectory)) {
            lastOutputDirectory = outputDirectory;
            outputLabel.setText("Saved to: " + outputDirectory);
            outputLabel.setToolTipText(outputDirectory.toString());
          }
          setRunning(false, stopped ? "Stopped" : exitCode == 0 ? incompleteSource ? "Created with source exceptions" : "Finished" : "Failed");
          appendSummary(stopped ? "Stopped." : exitCode == 0 ? incompleteSource
              ? "Created with source exceptions. Review the warnings and validation report before accepting these files."
              : "Finished. Validate the PDFs before accepting them."
              : lastRunError == null ? "The conversion did not finish. See Details for the error."
              : "Could not finish: " + lastRunError);
          if (exitCode != 0 && !stopped) {
            showError(lastRunError == null ? "The conversion did not finish. See Details for the error." : lastRunError);
          }
        } catch (Exception ex) {
          appendLog("Error: " + ex.getMessage());
          appendSummary("Full conversion log: "+runLog);
          setRunning(false, stopRequested ? "Stopped" : "Failed");
          appendSummary(stopRequested ? "Stopped." : "The conversion did not finish. See Details for the error.");
          if (!stopRequested) showError(Objects.toString(ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage(), "Conversion failed."));
        } finally {
          if (signingPassword != null) java.util.Arrays.fill(signingPassword, '\0');
          try { Files.deleteIfExists(tempConfigPath); } catch (Exception ignored) {}
          activeProcess = null;
          if (closeRequested) {
            frame.dispose();
            System.exit(0);
          }
        }
      }
    };
    worker.execute();
  }

  private void stopActiveProcess() {
    if (!running || stopRequested) return;
    stopRequested = true;
    Process process = activeProcess;
    appendLog("Stopping active process...");
    statusLabel.setText("Stopping...");
    stopButton.setEnabled(false);
    if (process == null) return;
    Thread stop = new Thread(() -> ConversionSupervisor.requestStop(process), "eapdf-gui-stop");
    stop.setDaemon(true);
    stop.start();
  }

  private void setRunning(boolean running, String status) {
    this.running = running;
    statusLabel.setText(status);
    stopButton.setEnabled(running);
    progressBar.setIndeterminate(running);
    progressBar.setValue(running ? 0 : lastOutputDirectory == null ? 0 : 100);
    openOutputButton.setEnabled(!running && lastOutputDirectory != null);
    for (JButton button : runButtons) button.setEnabled(!running);
  }

  private void openOutputFolder() {
    if (lastOutputDirectory == null) return;
    try {
      if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        throw new java.io.IOException("Folder opening is unavailable on this desktop.");
      }
      Desktop.getDesktop().open(lastOutputDirectory.toFile());
    } catch (Exception ex) {
      showError("Could not open the folder. Your files are in: " + lastOutputDirectory);
    }
  }

  private void appendLog(String line) {appendLogLines(List.of(line));}

  private void appendLogLines(List<String> lines) {
    var detail=new StringBuilder();var summary=new StringBuilder();
    for(String line:lines){
      GuiLog.tail(detail,line+System.lineSeparator(),GuiLog.DISPLAY_CHARACTERS);
      if (line.contains("INCOMPLETE SOURCE:")) incompleteSource = true;
      if (line.startsWith("[progress] ") && running && !stopRequested)
        statusLabel.setText(line.substring("[progress] ".length()));
      String error = conversionError(line),message=null;
      if (line.startsWith("wrote: ") || line.startsWith("wrote archive: ") || line.startsWith("wrote pdf: ")) {
        try {message="Converted: " + Paths.get(line.substring(line.indexOf(':') + 1).trim()).getFileName();}
        catch (java.nio.file.InvalidPathException ex) {message=line;}
      } else if (error != null && (!line.startsWith("Caused by: ") || lastRunError == null)) {
        lastRunError = error;if (!stopRequested) message="Error: " + lastRunError;
      } else if (line.startsWith("warning: ")) message=line;
      if(message!=null)GuiLog.tail(summary,message+System.lineSeparator(),GuiLog.SUMMARY_CHARACTERS);
    }
    GuiLog.append(logArea,detail.toString(),GuiLog.DISPLAY_CHARACTERS);
    if(!summary.isEmpty())GuiLog.append(summaryArea,summary.toString(),GuiLog.SUMMARY_CHARACTERS);
  }

  static String conversionError(String line) {
    if (line.regionMatches(true, 0, "error:", 0, 6)) return line.substring(6).trim();
    var failure = JAVA_FAILURE.matcher(line);
    if (!failure.matches()) return null;
    String message = failure.group(2);
    return message == null || message.isBlank() ? "The conversion failed." : message.trim();
  }

  private void appendSummary(String line) {
    GuiLog.append(summaryArea,line+System.lineSeparator(),GuiLog.SUMMARY_CHARACTERS);
  }

  private JPanel inlinePanel(Component... components) {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    for (Component component : components) panel.add(component);
    return panel;
  }

  static RunConfigControls buildRunConfigControls() {
    EapdfConfig cfg = EapdfConfig.normalize(EapdfConfig.get());
    JCheckBox searchIndex = new JCheckBox("Embed searchable index", cfg.searchIndex.enabled);
    searchIndex.setToolTipText("Embed a compact message-filter index and a separate full-text index for faster offline filtering and searches. Full-text generation uses built-in PDFBox extraction; unsupported outputs retain ordinary search.");
    JCheckBox pdfUa = new JCheckBox("Accessibility (PDF/UA)", cfg.accessibility != null && cfg.accessibility.addPdfUaFeatures);

    pdfUa.setToolTipText("Enable accessibility features. This does not establish PDF/UA conformance.");

    return new RunConfigControls(pdfUa, searchIndex, cfg.signing);
  }

  private JPanel buildRunConfigPanel(RunConfigControls controls) {
    JButton creator = new JButton("Creator identity...");
    creator.setToolTipText("Identify the person or organization responsible for creating the archive.");
    creator.addActionListener(event -> {
      String value = JOptionPane.showInputDialog(frame,
          "Person or organization responsible for creating these EA-PDF files:", EapdfConfig.get().creator);
      if (value != null) EapdfConfig.get().creator = value.strip();
    });
    JButton certificate = new JButton("Signing settings...");
    certificate.setToolTipText("Choose a signing certificate and optional key alias and signature reason.");
    certificate.addActionListener(event -> {
      JTextField path = new JTextField(controls.signing.pkcs12Path, 36);
      JTextField alias = new JTextField(controls.signing.alias, 24);
      JTextField reason = new JTextField(controls.signing.reason, 24);
      JPanel settings = new JPanel(new GridBagLayout());
      GridBagConstraints gc = new GridBagConstraints();
      gc.insets = new Insets(5, 5, 5, 5);
      addPathRow(settings, gc, 0, "Certificate (.p12 or .pfx)", path,
          e -> choosePath(path, JFileChooser.FILES_ONLY, false));
      addRow(settings, gc, 1, "Key alias (optional)", alias);
      addRow(settings, gc, 2, "Reason (optional)", reason);
      addRow(settings, gc, 3, "", new JLabel("The password is requested when conversion starts."));
      if (JOptionPane.showConfirmDialog(frame, settings, "Digital signature",
          JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
        controls.signing.pkcs12Path = path.getText().trim();
        controls.signing.alias = alias.getText().trim();
        controls.signing.reason = reason.getText().trim();
      }
    });
    return inlinePanel(controls.pdfUa, controls.searchIndex,
        controls.signOutput, certificate, creator);
  }

  static Path writeGuiConfig(RunConfigOverrides overrides) throws Exception {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    EapdfConfig cfg = gson.fromJson(gson.toJson(EapdfConfig.get()), EapdfConfig.class);
    cfg = EapdfConfig.normalize(cfg == null ? new EapdfConfig() : cfg);
    cfg.requireCreator();
    cfg.searchIndex.enabled = overrides.searchIndexEnabled;
    cfg.searchIndex.fullTextEnabled = overrides.searchIndexEnabled;
    cfg.accessibility.addPdfUaFeatures = overrides.pdfUaEnabled;
    cfg.signing = overrides.signing;
    Path tempConfig = Files.createTempFile("eapdf-gui-config-", ".json");
    Files.writeString(tempConfig, gson.toJson(cfg), StandardCharsets.UTF_8);
    tempConfig.toFile().deleteOnExit();
    return tempConfig;
  }

  private String describeConfigOverrides(RunConfigOverrides overrides) {
    return "searchIndex=" + overrides.searchIndexEnabled
        + ", pdfUa=" + overrides.pdfUaEnabled
        + ", signing=" + overrides.signing.enabled;
  }

  private void addPathRow(JPanel panel, GridBagConstraints gc, int row, String label, JTextField field,
                          java.awt.event.ActionListener browseAction) {
    JButton browse = new JButton("Browse...");
    browse.setToolTipText("Choose " + label.toLowerCase(java.util.Locale.ROOT) + ".");
    browse.addActionListener(browseAction);
    JPanel pathPanel = new JPanel(new BorderLayout(8, 0));
    pathPanel.add(field, BorderLayout.CENTER);
    pathPanel.add(browse, BorderLayout.EAST);
    addRow(panel, gc, row, label, pathPanel);
  }

  private void addRunRow(JPanel panel, GridBagConstraints gc, int row, JButton button) {
    gc.gridy = row;
    gc.gridx = 0;
    gc.gridwidth = 2;
    gc.weightx = 1.0;
    gc.fill = GridBagConstraints.NONE;
    gc.anchor = GridBagConstraints.WEST;
    panel.add(button, gc);
  }

  private void addNoteRow(JPanel panel, GridBagConstraints gc, int row, String note) {
    JLabel label = new JLabel("<html><span style='color:#555555;'>" + note + "</span></html>");
    GridBagConstraints noteGc = (GridBagConstraints) gc.clone();
    noteGc.gridy = row;
    noteGc.gridx = 1;
    noteGc.weightx = 1.0;
    noteGc.fill = GridBagConstraints.HORIZONTAL;
    noteGc.anchor = GridBagConstraints.NORTHWEST;
    noteGc.insets = new Insets(0, 6, 6, 6);
    panel.add(label, noteGc);
  }

  private void addRow(JPanel panel, GridBagConstraints gc, int row, String label, Component component) {
    addMissingTooltips(component, label);
    GridBagConstraints left = (GridBagConstraints) gc.clone();
    left.gridy = row;
    left.gridx = 0;
    left.weightx = 0;
    left.fill = GridBagConstraints.NONE;
    left.anchor = GridBagConstraints.NORTHWEST;
    JLabel fieldLabel = new JLabel(label);
    fieldLabel.setLabelFor(component);
    fieldLabel.setToolTipText(tooltipFor(component, label));
    panel.add(fieldLabel, left);

    GridBagConstraints right = (GridBagConstraints) gc.clone();
    right.gridy = row;
    right.gridx = 1;
    right.weightx = 1.0;
    right.fill = GridBagConstraints.HORIZONTAL;
    right.anchor = GridBagConstraints.NORTHWEST;
    panel.add(component, right);
  }

  static String tooltipFor(Component component, String fallback) {
    if (component instanceof javax.swing.JComponent field && field.getToolTipText() != null)
      return field.getToolTipText();
    if (component instanceof java.awt.Container container)
      for (Component child : container.getComponents()) {
        String help = tooltipFor(child, null);
        if (help != null) return help;
      }
    return fallback;
  }

  static void addMissingTooltips(Component component, String context) {
    if (component instanceof javax.swing.JComponent field && field.getToolTipText() == null) {
      if (field instanceof javax.swing.AbstractButton button)
        field.setToolTipText(button.getText() == null || button.getText().isBlank() ? context : button.getText());
      else if ((field instanceof javax.swing.text.JTextComponent text && text.isEditable()) || field instanceof JComboBox<?> || field instanceof JSpinner)
        field.setToolTipText(context);
    }
    if (component instanceof java.awt.Container container)
      for (Component child : container.getComponents()) addMissingTooltips(child, context);
  }

  private GridBagConstraints defaultConstraints() {
    GridBagConstraints gc = new GridBagConstraints();
    gc.insets = new Insets(6, 6, 6, 6);
    gc.anchor = GridBagConstraints.NORTHWEST;
    return gc;
  }

  private void choosePath(JTextField field, int selectionMode, boolean saveDialog, JTextField... anchorFields) {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(selectionMode);
    if (selectionMode == JFileChooser.FILES_AND_DIRECTORIES) {
      chooser.setFileFilter(new FileNameExtensionFilter("Email files (EML, MSG, PST, MBOX)", "eml", "msg", "pst", "mbox"));
    }
    Path start = chooseStartPath(field, anchorFields);
    chooser.setCurrentDirectory(start.toFile());

    String current = field.getText().trim();
    if (!current.isBlank()) {
      chooser.setSelectedFile(new File(current));
    } else if (saveDialog && selectionMode == JFileChooser.FILES_ONLY) {
      chooser.setSelectedFile(start.resolve("output.pdf").toFile());
    }
    int result = saveDialog ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame);
    if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
      field.setText(chooser.getSelectedFile().getPath());
    }
  }

  private Path chooseStartPath(JTextField field, JTextField... anchorFields) {
    Path current = pathFromField(field);
    if (current != null) return current;
    if (anchorFields != null) {
      for (JTextField anchor : anchorFields) {
        Path anchorPath = pathFromField(anchor);
        if (anchorPath != null) return anchorPath;
      }
    }
    return launchDirectory;
  }

  private Path pathFromField(JTextField field) {
    if (field == null) return null;
    String value = field.getText().trim();
    if (value.isBlank()) return null;
    Path path = Paths.get(value).toAbsolutePath().normalize();
    if (Files.isDirectory(path)) return path;
    Path parent = path.getParent();
    if (parent != null && Files.exists(parent)) return parent;
    return null;
  }

  private void putFlag(List<String> args, String flag, String value, String defaultValue) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) return;
    if (defaultValue != null && normalized.equals(defaultValue)) return;
    args.add(flag);
    args.add(normalized);
  }

  private void showError(String message) {
    JOptionPane.showMessageDialog(frame, message, "EA-PDF GUI", JOptionPane.ERROR_MESSAGE);
  }

  private String javaExecutable() {
    Path javaBin = Paths.get(System.getProperty("java.home"), "bin",
        System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
    return Files.exists(javaBin) ? javaBin.toString() : "java";
  }

  private String shellCommand(List<String> command) {
    List<String> quoted = new ArrayList<>();
    for (String part : command) {
      if (part == null) continue;
      if (part.isBlank() || part.matches(".*\\s+.*")) quoted.add("\"" + part.replace("\"", "\\\"") + "\"");
      else quoted.add(part);
    }
    return String.join(" ", quoted);
  }

  private static final class Choice {
    final String label;
    final String code;
    final boolean archiveMode;
    final boolean omitSource;

    Choice(String label, String code, boolean archiveMode, boolean omitSource) {
      this.label = label;
      this.code = code;
      this.archiveMode = archiveMode;
      this.omitSource = omitSource;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  static final class RunConfigControls {
    final JCheckBox pdfUa;
    final JCheckBox searchIndex;
    final JCheckBox signOutput;
    final EapdfConfig.SigningConfig signing;

    RunConfigControls(JCheckBox pdfUa, JCheckBox searchIndex, EapdfConfig.SigningConfig defaults) {
      this.pdfUa = pdfUa;
      this.searchIndex = searchIndex;
      this.signing = new Gson().fromJson(new Gson().toJson(defaults), EapdfConfig.SigningConfig.class);
      this.signOutput = new JCheckBox("Sign output", defaults.enabled);
      this.signOutput.setToolTipText("Sign each outer EA-PDF with your certificate. Embedded files stay unchanged.");
    }

    RunConfigOverrides snapshot() {
      EapdfConfig.SigningConfig copy = new Gson().fromJson(new Gson().toJson(signing), EapdfConfig.SigningConfig.class);
      copy.enabled = signOutput.isSelected();
      return new RunConfigOverrides(
          pdfUa.isSelected(), searchIndex.isSelected(), copy);
    }
  }

  static final class RunConfigOverrides {
    final boolean pdfUaEnabled;
    final boolean searchIndexEnabled;
    final EapdfConfig.SigningConfig signing;

    RunConfigOverrides(boolean pdfUaEnabled, boolean searchIndexEnabled,
        EapdfConfig.SigningConfig signing) {
      this.pdfUaEnabled = pdfUaEnabled;
      this.searchIndexEnabled = searchIndexEnabled;
      this.signing = signing;
    }
  }
}
