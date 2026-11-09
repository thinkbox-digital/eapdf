package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Finite allowances for large local archives and folders; explicit settings win. */
final class PstJobBudget {
  private final Map<String, Long> defaults = new LinkedHashMap<>();

  static PstJobBudget forInput(String entry, String[] arguments, Path temporary) throws Exception {
    var budget = new PstJobBudget();
    long space = Files.getFileStore(temporary).getUsableSpace() / 5 * 4;
    if (space < 1) throw Common.fail("No available conversion working storage");
    budget.defaults.put("eapdf.maxTempBytes", space);
    budget.defaults.put("eapdf.maxOutputBytes", space / 2);
    budget.defaults.put("eapdf.maxTempFiles", Long.MAX_VALUE);
    if (entry.equals("eapdf.GmailDownloadToPdf")) {
      int limit = (Integer) GmailDownloadToPdf.parseArgs(arguments).getOrDefault("limit", 0);
      if (limit > 0 && limit <= 200 && System.getProperty("eapdf.workerHeapBytes") == null
          && System.getenv("EAPDF_WORKER_HEAP_BYTES") == null)
        budget.defaults.put("eapdf.workerHeapBytes", ConversionMemory.defaultHeap(512L * 1024 * 1024));
      return budget;
    }
    if (!entry.equals("eapdf.EmailToPDFA")) return budget;
    String input = (String) EmailToPDFA.parseArgs(arguments).get("in");
    if (input == null) return budget;
    Path path = Path.of(input);
    long bytes = inputBytes(path);
    if (bytes < 128L * 1024 * 1024) {
      if (System.getProperty("eapdf.workerHeapBytes") == null && System.getenv("EAPDF_WORKER_HEAP_BYTES") == null)
        budget.defaults.put("eapdf.workerHeapBytes", ConversionMemory.defaultHeap(512L * 1024 * 1024));
      return budget;
    }
    long messages = 250000; // Input bytes cannot safely predict how many short emails it contains.
    long heap = ConversionMemory.defaultHeap(512L * 1024 * 1024);
    budget.defaults.put("eapdf.workerHeapBytes", heap);
    budget.defaults.put("eapdf.maxMetadataBytes", heap / 8);
    // Scratch contains expanded streams, rendered pages and staged outputs together.
    // PST size is not an upper bound on this expansion. Reserve disk headroom;
    // the supervisor still enforces the aggregate allowance and explicit overrides.
    budget.defaults.put("eapdf.maxTempBytes", space);
    budget.defaults.put("eapdf.maxOutputBytes", space / 2);
    budget.defaults.put("eapdf.jobTimeoutSeconds", Math.min(86400, Math.max(600, messages * 2)));
    System.out.println("Large email input processing: " + bytes + " input bytes; memory and work allowances sized to this input.");
    return budget;
  }

  static long inputBytes(Path path) throws java.io.IOException {
    long bytes = 0;
    if (Files.isRegularFile(path)) bytes = Files.size(path);
    else if (Files.isDirectory(path)) {
      try (var files = Files.walk(path)) {
        for (var file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
          String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
          if (name.endsWith(".eml") || name.endsWith(".msg") || name.endsWith(".pst") || name.endsWith(".mbox"))
            bytes = Math.addExact(bytes, Files.size(file));
        }
      }
    }
    return bytes;
  }

  void useWorkerHeap(long heap) {
    defaults.put("eapdf.workerHeapBytes", heap);
    defaults.put("eapdf.maxMetadataBytes", heap / 8);
    defaults.put("eapdf.workerAddressBytes", Math.addExact(Math.addExact(heap, 4L * 1024 * 1024 * 1024),
        setting("eapdf.maxTempBytes", "EAPDF_MAX_TEMP_BYTES", 0)));
  }

  long setting(String property, String environment, long fallback) {
    return ProcessingLimits.setting(property, environment, defaults.getOrDefault(property, fallback));
  }

  void addWorkerSettings(java.util.List<String> command) {
    for (var entry : defaults.entrySet()) {
      String environment = "EAPDF_" + entry.getKey().substring(6).replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
      if (System.getProperty(entry.getKey()) == null && System.getenv(environment) == null)
        command.add("-D" + entry.getKey() + "=" + entry.getValue());
    }
  }
}
