package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class MonthlyFilenameArchiveToPdf {
  static final DateTimeFormatter FILENAME_DATE = DateTimeFormatter.BASIC_ISO_DATE;
  static final String ARCHIVE_PROFILE = "m";
  static final String DEFAULT_INPUT_DIR = "/media/lepore/Work/temp/out/";

  public static void main(String[] args) throws Exception {
    if (ConversionSupervisor.run(MonthlyFilenameArchiveToPdf.class, args)) return;
    try (Common.JobReport job = Common.beginJob()) {
      Map<String, Object> opt = parseArgs(args);
      Path inputDir = Paths.get((String) opt.getOrDefault("in", DEFAULT_INPUT_DIR)).toAbsolutePath().normalize();
      if (!Files.isDirectory(inputDir)) throw Common.fail("input directory not found: " + inputDir);

      Path outDir = Paths.get((String) opt.getOrDefault("out", inputDir.resolve("_monthly_1m").toString())).toAbsolutePath().normalize();
      Path debugDir = Paths.get((String) opt.getOrDefault("debug-dir", outDir.resolve("_debug").toString())).toAbsolutePath().normalize();
      String fontPath = Common.resolveFontPath((String) opt.get("font"));
      String iccPath = Common.resolveIccPath((String) opt.get("icc"));
      String globalId = (String) opt.get("global-id");
      List<String> archiveEmails = EmailToPDFA.castStringList(opt.get("email"));
      boolean debug = Boolean.TRUE.equals(opt.get("debug"));

      Files.createDirectories(outDir);
      if (debug) Files.createDirectories(debugDir);

      MonthlyInputs monthly = collectMonthlyInputs(inputDir);
      if (monthly.groupedInputs.isEmpty()) {
        throw Common.fail("no .eml files with YYYYMMDD filename prefix found under: " + inputDir);
      }

      System.out.println("input directory: " + inputDir);
      System.out.println("output directory: " + outDir);
      System.out.println("months matched: " + monthly.groupedInputs.size());
      System.out.println("files matched: " + monthly.matchedFiles);
      if (monthly.skippedFiles > 0) {
        System.out.println("files skipped (missing or invalid YYYYMMDD prefix): " + monthly.skippedFiles);
      }

      int monthCount = 0;
      int singleMessageCount = 0;
      for (Map.Entry<String, List<String>> entry : monthly.groupedInputs.entrySet()) {
        String month = entry.getKey();
        List<String> inputs = entry.getValue();
        List<EmailRecord> records = flatten(EmailToPDFA.readManyForArchive(inputs, globalId, archiveEmails, debug));
        if (records.isEmpty()) continue;
        for (EmailRecord record : records) record.embedSourceMessage = true;
        records = PdfMailWriter.sortRecordsChronologically(records);

        boolean singleMessage = records.size() == 1;
        String profile = singleMessage ? "s" : ARCHIVE_PROFILE;
        Path out = Common.availableArchivePath(outDir.resolve(month + "_1" + profile + ".pdf"));
        Path monthDebugDir = debugDir.resolve(month + "_1" + profile);
        if (debug) Files.createDirectories(monthDebugDir);

        System.out.println("month: " + month + " inputs: " + inputs.size() + " messages: " + records.size());
        if (singleMessage) {
          PdfMailWriter.writeEaPdfA3(out, records.get(0), fontPath, iccPath, debug, monthDebugDir, 1);
          System.out.println("wrote single-message PDF: " + out);
          singleMessageCount++;
        } else {
          PdfMailWriter.writeEaPdfArchive(out, records, fontPath, iccPath, debug, monthDebugDir, ARCHIVE_PROFILE);
          System.out.println("wrote archive: " + out);
          monthCount++;
        }
        for (String input : inputs) Common.inputState(input, "completed");
      }

      System.out.println("done. monthly archives written: " + monthCount);
      System.out.println("single-message monthly PDFs written: " + singleMessageCount);
    } catch (Common.UserFacingException e) {
      Common.exitWithError(e.getMessage());
    }
  }

  static Map<String, Object> parseArgs(String[] args) {
    Common.validateCliArguments(args, java.util.Set.of("--out", "--font", "--icc", "--global-id", "-g", "--email", "-e", "--debug-dir"),
        java.util.Set.of("--debug"), true);
    Map<String, Object> m = new LinkedHashMap<>();
    List<String> emails = new ArrayList<>();
    List<String> rest = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (a.equals("--out") && i + 1 < args.length) { m.put("out", args[++i]); continue; }
      if (a.equals("--font") && i + 1 < args.length) { m.put("font", args[++i]); continue; }
      if (a.equals("--icc") && i + 1 < args.length) { m.put("icc", args[++i]); continue; }
      if ((a.equals("--global-id") || a.equals("-g")) && i + 1 < args.length) { m.put("global-id", args[++i]); continue; }
      if ((a.equals("--email") || a.equals("-e")) && i + 1 < args.length) { emails.add(args[++i]); continue; }
      if (a.equals("--debug")) { m.put("debug", true); continue; }
      if (a.equals("--debug-dir") && i + 1 < args.length) { m.put("debug-dir", args[++i]); continue; }
      rest.add(a);
    }
    if (!rest.isEmpty()) m.put("in", rest.get(0));
    m.put("email", emails);
    return m;
  }

  static MonthlyInputs collectMonthlyInputs(Path inputDir) throws Exception {
    List<String> candidates = EmailToPDFA.collectInputs(inputDir.toString());
    Map<String, List<String>> grouped = new TreeMap<>();
    int matched = 0;
    int skipped = 0;
    for (String candidate : candidates) {
      Path path = Paths.get(candidate);
      String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
      if (!fileName.toLowerCase(Locale.ROOT).endsWith(".eml")) {
        Common.inputState(candidate, "skipped");
        System.out.println("skipped input: " + candidate + " (monthly conversion requires .eml)");
        continue;
      }
      String month = monthFromFilename(fileName);
      if (month == null) {
        skipped++;
        Common.inputState(candidate, "skipped");
        System.out.println("skipped input: " + candidate + " (missing or invalid YYYYMMDD prefix)");
        continue;
      }
      grouped.computeIfAbsent(month, key -> new ArrayList<>()).add(candidate);
      matched++;
    }
    return new MonthlyInputs(grouped, matched, skipped);
  }

  static String monthFromFilename(String fileName) {
    if (fileName == null || fileName.length() < 8) return null;
    String prefix = fileName.substring(0, 8);
    try {
      LocalDate date = LocalDate.parse(prefix, FILENAME_DATE);
      return String.format("%04d-%02d", date.getYear(), date.getMonthValue());
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  static List<EmailRecord> flatten(List<List<EmailRecord>> batches) {
    List<EmailRecord> out = new ArrayList<>();
    if (batches == null) return out;
    for (List<EmailRecord> batch : batches) {
      if (batch == null || batch.isEmpty()) continue;
      out.addAll(batch);
    }
    return out;
  }

  static final class MonthlyInputs {
    final Map<String, List<String>> groupedInputs;
    final int matchedFiles;
    final int skippedFiles;

    MonthlyInputs(Map<String, List<String>> groupedInputs, int matchedFiles, int skippedFiles) {
      this.groupedInputs = groupedInputs;
      this.matchedFiles = matchedFiles;
      this.skippedFiles = skippedFiles;
    }
  }
}
