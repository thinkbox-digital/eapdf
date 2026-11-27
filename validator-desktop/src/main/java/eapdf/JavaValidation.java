package eapdf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.parser.XrefProgress;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.validation.profiles.Profiles;
import org.verapdf.pdfa.validation.profiles.ValidationProfile;
import org.verapdf.pdfa.validation.validators.BaseValidator;
import org.verapdf.processor.reports.enums.JobEndStatus;

/** Independent Java EA-PDF checks, with the pinned veraPDF engine in the same JAR. */
public final class JavaValidation {
  static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  static final String SCOPE = "Automated checks, not independent EA-PDF certification";
  // The engine implements the reviewed catalogue; comparison evidence is recorded separately.
  static final boolean PORT_COMPLETE = true;
  static final Set<PDFAFlavour> ALLOWED = Set.of(PDFAFlavour.PDFA_3_A, PDFAFlavour.PDFA_3_U,
      PDFAFlavour.PDFA_4, PDFAFlavour.PDFA_4_F, PDFAFlavour.PDFA_4_E);
  static final JsonObject RULES = loadRules();
  private static boolean initialised;

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Validation worker requires an input and report path");
    Path input = Path.of(args[0]), output = Path.of(args[1]);
    if (!Boolean.getBoolean("eapdf.validator.worker")) {
      System.exit(ValidationWorker.run(input, output));
      return;
    }
    ValidationRuntime.watchController();
    int code = 2;
    Path storage = ValidationRuntime.temporaryDirectory();
    Thread scratchCleanup = new Thread(() -> {
      try { ValidationRuntime.removeDirectory(storage); }
      catch (IOException error) { System.err.println("Unable to remove validation scratch: " + error.getMessage()); }
    }, "validation-scratch-cleanup");
    Runtime.getRuntime().addShutdownHook(scratchCleanup);
    String previousTemp = System.getProperty("java.io.tmpdir");
    System.setProperty("java.io.tmpdir", storage.toString());
    try {
    String runId = System.getProperty(ValidationSession.RUN_ID_PROPERTY, java.util.UUID.randomUUID().toString());
    try (ValidationSession session = new ValidationSession(input, output, runId, false)) {
      try (Report report = inspect(input, new Children(), 0, true)) {
        ValidationSession.stage("Writing validation report", input);
        report.write(output, session);
        session.completed();
        code = "PASS".equals(text(report.summary(), "status")) ? 0 : 2;
      } catch (OutOfMemoryError | StackOverflowError error) {
        session.memoryFailure(error);
      } catch (Exception error) {
        session.failed("Validation could not finish: " + error);
      }
    }
    } finally {
      System.setProperty("java.io.tmpdir", previousTemp);
      ValidationRuntime.removeDirectory(storage);
      Runtime.getRuntime().removeShutdownHook(scratchCleanup);
    }
    System.exit(code);
  }

  static synchronized void initialise() {
    if (!initialised) {
      Map<String, String> pins = Map.of("core", "1.31.56", "validation-model", "1.31.225", "parser", "1.31.72", "pdf-model", "1.31.14");
      pins.forEach((artifact, expected) -> {
        try (InputStream input = JavaValidation.class.getResourceAsStream("/META-INF/maven/org.verapdf/" + artifact + "/pom.properties")) {
          if (input == null) throw new IOException("Missing bundled version information for " + artifact);
          var properties = new java.util.Properties(); properties.load(input);
          if (!expected.equals(properties.getProperty("version"))) throw new IOException("Unexpected bundled version of " + artifact);
        } catch (IOException error) { throw new IllegalStateException("The bundled veraPDF engine could not be verified.", error); }
      });
      VeraGreenfieldFoundryProvider.initialise(); initialised = true;
    }
  }

  static byte[] resource(String name, String expected) throws Exception {
    byte[] bytes;
    try (InputStream stream = JavaValidation.class.getResourceAsStream("/validator/" + name)) {
      if (stream == null) throw new IOException("Bundled validation resource is missing: " + name);
      bytes = stream.readAllBytes();
    }
    if (!expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
      throw new IOException("Bundled validation resource has changed: " + name);
    }
    return bytes;
  }

  static final String RULEBOOK_SHA256 = "5753d02350f1ff8262090c272bde6e760dfed20c5a5c97031871f8cbeebb1d21";

  static boolean recommendation(String code) {
    JsonObject rule = RULES.has(code) ? RULES.getAsJsonObject(code) : null;
    JsonObject scope = rule == null ? null : rule.getAsJsonObject("validation_scope");
    JsonObject check = scope == null ? null : scope.getAsJsonObject("check");
    return check != null && "SHOULD".equals(text(check, "strength"));
  }

  private static JsonObject loadRules() {
    try {
      return JsonParser.parseString(new String(resource("validator_rule_text.json",
          RULEBOOK_SHA256), StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception error) { throw new ExceptionInInitializerError(error); }
  }

  static Integer ruleNumber(String code) {
    return RULES.has(code) ? RULES.getAsJsonObject(code).get("rule_number").getAsInt() : null;
  }

  static JsonObject validate(Path path) {
    return validate(path, new Children(), 0);
  }

  static final class Children {
    private final long maxDepth = Math.min(32, ValidationRuntime.setting("VALIDATOR_MAX_CHILD_DEPTH", 8));
    private final long maxCount = ValidationRuntime.setting("VALIDATOR_MAX_CHILDREN", 10000);
    private final long maxBytes = byteLimit();
    Path sharedBudget;
    int count;
    long bytes;

    static long byteLimit() { return ValidationRuntime.setting("VALIDATOR_MAX_CHILD_BYTES", ValidationRuntime.scratchLimit()); }

    boolean accepts(long size, int depth) throws IOException {
      if (size < 0 || depth < 0 || depth >= maxDepth) return false;
      if (sharedBudget == null) return charge(size, count, bytes);
      try (var channel = java.nio.channels.FileChannel.open(sharedBudget, StandardOpenOption.READ, StandardOpenOption.WRITE);
           var lock = channel.lock()) {
        var totals = java.nio.ByteBuffer.allocate(Integer.BYTES + Long.BYTES);
        while (totals.hasRemaining()) {
          if (channel.read(totals) < 0) throw new EOFException("Incomplete collection child budget");
        }
        totals.flip();
        int previousCount = totals.getInt();
        long previousBytes = totals.getLong();
        if (!charge(size, previousCount, previousBytes)) return false;
        totals.clear();
        totals.putInt(previousCount + 1).putLong(previousBytes + size).flip();
        channel.position(0);
        while (totals.hasRemaining()) channel.write(totals);
        return true;
      }
    }

    private boolean charge(long size, int totalCount, long totalBytes) {
      if (totalCount >= Math.min(maxCount, Integer.MAX_VALUE) || size > maxBytes - totalBytes) return false;
      count++;
      bytes += size;
      return true;
    }
    final Set<String> active = new LinkedHashSet<>();
    final Map<String, ChildReport> cache = new LinkedHashMap<>(32, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<String, ChildReport> eldest) { return size() > 32; }
    };
  }

  static JsonObject validate(Path path, Children children, int depth) {
    try (Report report = inspect(path, children, depth, false)) { return report.result(); }
  }

  private static Report inspect(Path path, Children children, int depth, boolean streamed) {
    return inspect(path, children, depth, streamed, null);
  }

  record ChildReport(JsonObject report, boolean identified) {}
  private record MemberProbe(boolean isoApplicable, boolean identified) {}

  static ChildReport validateChild(Path path, Children children, int depth, long identificationLimit) throws Exception {
    initialise();
    MemberProbe member = inspectMember(path, identificationLimit);
    try (Report report = inspect(path, children, depth, false, member.isoApplicable())) {
      return new ChildReport(report.result(), member.identified());
    }
  }

  private static MemberProbe inspectMember(Path path, long identificationLimit) throws Exception {
    // Collect the member type during the existing standards applicability probe.
    // Closing the probe before veraPDF opens keeps the two models separate.
    try (PDDocument probe = openForInspection(path)) {
      boolean identified = EaPdfChecks.identifiedEmail(probe, identificationLimit);
      return new MemberProbe(isoApplicable(probe), identified);
    } catch (BoundedPdfStreams.Limit limit) {
      throw limit;
    } catch (IOException ignored) {
      return new MemberProbe(false, false);
    }
  }

  private static Report inspect(Path path, Children children, int depth, boolean streamed, Boolean memberIsoApplicable) {
    initialise();
    Report report = new Report(path.toAbsolutePath().toString(), streamed);
    boolean complete = false;
    try {
    ValidationSession.stage("Checking PDF/A", path);
    boolean checkIso = memberIsoApplicable != null ? memberIsoApplicable : isoApplicable(path);
    UpstreamPass upstreamPass = checkIso ? checkWithSharedTraversal(path) : new UpstreamPass(pdfa(path, null), null);
    JsonObject pdfa = upstreamPass.primary();
    report.pdfa = pdfa;
    String upstream = text(pdfa, "status");
    report.add("PASS".equals(upstream) ? "PASS" : "FAIL".equals(upstream) ? "FAIL" : "WARN",
        "PDFA_FIRST_PASS", text(pdfa, "message"), pdfa);
    if (!Set.of("PASS", "FAIL").contains(upstream)) report.unavailable("VALIDATION_INCOMPLETE", "PDF/A checking did not finish.");
    if (depth == 0) ValidationSession.pdfaFinished(pdfa);
    // Finish the standards checks before retaining the PDFBox object graph.
    // Otherwise large archives keep two complete PDF representations alive together.
    if (checkIso && "PASS".equals(upstream)) {
      report.iso32005 = upstreamPass.iso();
      if (report.iso32005 == null) {
        ValidationSession.stage("Checking ISO 32005", path);
        try { report.iso32005 = iso(path); }
        catch (Exception error) {
          report.iso32005 = new JsonObject(); report.iso32005.addProperty("status", "ERROR");
          report.iso32005.addProperty("message", "ISO 32005 could not finish: " + error);
        }
      }
      if (depth == 0) ValidationSession.isoFinished(report.iso32005);
    }
    ValidationSession.stage("Checking EA-PDF", path);
    try (PDDocument document = openForInspection(path)) {
      report.check("OPEN", true, "PDF opened.", Map.of("pages", document.getNumberOfPages()));
      new EaPdfChecks(document, report, children, depth).run(path);
    } catch (Exception error) {
      if (report.seen.contains("OPEN")) report.unavailable("VALIDATION_ERROR", "EA-PDF inspection could not finish: " + error);
      else report.check("OPEN", false, "Unable to open the PDF.", Map.of("error", error.toString()));
    }
    report.finishCoverage();
    complete = true;
    return report;
    } finally { if (!complete) report.close(); }
  }

  private static PDDocument openForInspection(Path path) throws IOException {
    return IndexedPdfLoader.load(path, org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());
  }

  private static boolean isoApplicable(PDDocument probe) {
    return probe.getVersion() == 2.0f
        && EaPdfChecks.dict(probe.getDocumentCatalog().getCOSObject(), "StructTreeRoot") != null;
  }

  private static boolean isoApplicable(Path path) {
    try (PDDocument probe = openForInspection(path)) {
      return isoApplicable(probe);
    } catch (IOException ignored) {
      return false;
    }
  }

  private static byte[] isoProfile() throws Exception {
    return resource("config/verapdf/ISO-32005.xml", "9e49c6f93e2d62c150f6ac1c842e90f1e5bf6a7a336420db0f94eda384c58624");
  }

  static JsonObject iso(Path path) throws Exception {
    return pdfa(path, isoProfile());
  }

  static JsonObject pdfa(Path path, byte[] profile) {
    return onUpstreamThread(() -> checkUpstream(path, profile, false)).primary();
  }

  private record UpstreamPass(JsonObject primary, JsonObject iso) {}

  private static UpstreamPass checkWithSharedTraversal(Path path) {
    try {
      UpstreamPass pass = onUpstreamThread(() -> checkUpstream(path, null, true));
      if (pass != null) return pass;
    } catch (OutOfMemoryError | StackOverflowError retry) {
      // The attempt's worker has ended and its parser and caches are released.
    }
    ValidationSession.stage("Retrying PDF/A and ISO 32005 separately", path);
    return new UpstreamPass(pdfa(path, null), null);
  }

  private static <T> T onUpstreamThread(Callable<T> check) {
    // The rule engine retains a Rhino context in thread-local storage even
    // after its parser and documented caches are closed. Finish an owned
    // thread before loading the next PDF model so all such references die.
    var task = new java.util.concurrent.FutureTask<>(check);
    Thread worker = new Thread(task, "eapdf-upstream-check");
    worker.setDaemon(true);
    worker.start();
    boolean interrupted = false;
    try {
      while (worker.isAlive()) {
        try { worker.join(); }
        catch (InterruptedException cancelled) { interrupted = true; worker.interrupt(); }
      }
      if (interrupted) throw new java.util.concurrent.CancellationException("Upstream validation was interrupted.");
      try { return task.get(); }
      catch (java.util.concurrent.ExecutionException failed) {
        if (failed.getCause() instanceof Error error) throw error;
        if (failed.getCause() instanceof RuntimeException error) throw error;
        throw new IllegalStateException("Upstream validation failed", failed.getCause());
      } catch (InterruptedException cancelled) {
        interrupted = true;
        throw new java.util.concurrent.CancellationException("Upstream validation was interrupted.");
      }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  static String failureReason(Throwable error) {
    var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    var text = new StringBuilder();
    for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (!text.isEmpty()) text.append("; caused by ");
      text.append(cause);
    }
    return text.toString();
  }

  private static PDFAParser openUpstreamParser(Path path, PDFAFlavour flavour, String profile)
      throws org.verapdf.core.ModelParsingException, org.verapdf.core.EncryptedPdfException {
    var progress = ValidationSession.count("Indexing " + profile, path, "cross-reference stream entries", -1);
    long[] totals = new long[3];
    try (var tracking = new XrefProgress((entries, encoded, decoded) -> {
      totals[0] += entries;
      totals[1] += encoded;
      totals[2] += decoded;
      progress.advance(totals[0], totals[1], totals[2], null);
    })) {
      PDFAParser parser = Foundries.defaultInstance().createParser(path.toFile(), flavour);
      progress.finish(totals[0], totals[1], totals[2], null);
      return parser;
    }
  }

  private static UpstreamPass checkUpstream(Path path, byte[] profile, boolean tryShared) {
    JsonObject out = upstreamResult(path);
    boolean shared = false;
    try {
      initialise();
      var foundry = Foundries.defaultInstance();
      String profileName = profile == null ? "PDF/A" : "ISO 32005";
      try (var parser = openUpstreamParser(path, profile == null ? PDFAFlavour.NO_FLAVOUR : PDFAFlavour.PDFUA_2, profileName)) {
        shared = tryShared && parser.getFlavour() == PDFAFlavour.PDFA_4_F;
        if (shared) return sharedPass(path, parser, out);
        try (var validator = profile == null ? foundry.createValidator(parser.getFlavour(), 100, false, true, false)
                 : foundry.createValidator(Profiles.profileFromXml(new ByteArrayInputStream(profile)), 100, false, true, false)) {
          var streams = DecodedObjectStreamCache.install(parser);
          BoundedGlyphCache.install();
          ValidationSession.stage("Checking " + profileName, path);
          try (var progress = ValidationSession.monitor(validator)) {
            recordUpstreamResult(out, validator.validate(parser), profile != null, streams);
          }
        }
      }
    } catch (org.verapdf.core.ModelParsingException invalid) {
      if (shared) return null;
      out.addProperty("status", "FAIL"); out.addProperty("ran", true);
      out.add("compliant", com.google.gson.JsonNull.INSTANCE);
      out.addProperty("profile_name", ""); out.addProperty("job_end_status", ""); out.addProperty("failed_rules", 0);
      out.addProperty("message", "veraPDF could not parse the input PDF: " + invalid.getMessage());
    } catch (Exception error) {
      if (shared) return null;
      out.addProperty("message", "PDF/A checks could not finish: " + failureReason(error));
    } finally {
      clearUpstreamState();
    }
    return new UpstreamPass(out, null);
  }

  private static final class SharedValidator extends BaseValidator {
    SharedValidator(ValidationProfile pdfa, ValidationProfile iso) {
      super(List.of(pdfa, iso), 100, false, true, false);
    }
  }

  private static UpstreamPass sharedPass(Path path, PDFAParser parser, JsonObject pdfa) throws Exception {
    var pdfaProfile = Profiles.getVeraProfileDirectory().getValidationProfileByFlavour(parser.getFlavour());
    var isoProfile = Profiles.profileFromXml(new ByteArrayInputStream(isoProfile()));
    var streams = DecodedObjectStreamCache.install(parser);
    BoundedGlyphCache.install();
    try (var validator = new SharedValidator(pdfaProfile, isoProfile)) {
      ValidationSession.stage("Checking PDF/A and ISO 32005", path);
      List<ValidationResult> results;
      try (var progress = ValidationSession.monitor(validator)) {
        results = validator.validateAll(parser);
      }
      // Separate passes remain authoritative for failures and partial results.
      if (results.size() != 2 || !completedPass(results.get(0), PDFAFlavour.PDFA_4_F)
          || !completedPass(results.get(1), PDFAFlavour.PDFUA_2)) return null;
      JsonObject iso = upstreamResult(path);
      recordUpstreamResult(pdfa, results.get(0), false, streams);
      recordUpstreamResult(iso, results.get(1), true, streams);
      return new UpstreamPass(pdfa, iso);
    }
  }

  private static boolean completedPass(ValidationResult result, PDFAFlavour flavour) {
    return result.getPDFAFlavour() == flavour && result.getJobEndStatus() == JobEndStatus.NORMAL
        && result.isCompliant() && result.getTotalAssertions() > 0;
  }

  private static void clearUpstreamState() {
    // Closing the parser releases its file. These containers retain thread-local model data.
    org.verapdf.pd.structure.StructureElementAccessObject.clearParentTreeCache();
    org.verapdf.gf.model.impl.containers.StaticContainers.clearAllContainers();
    org.verapdf.containers.StaticCoreContainers.clearAllContainers();
    org.verapdf.tools.StaticResources.clear();
    org.verapdf.xmp.containers.StaticXmpCoreContainers.clearAllContainers();
  }

  private static JsonObject upstreamResult(Path path) {
    JsonObject out = new JsonObject();
    out.addProperty("file", path.toString());
    out.addProperty("tool", "Bundled veraPDF Java library");
    out.addProperty("status", "ERROR");
    out.addProperty("ran", false);
    out.addProperty("compliant", false);
    out.addProperty("profile_allowed", false);
    return out;
  }

  private static void recordUpstreamResult(JsonObject out, ValidationResult result, boolean iso,
                                           DecodedObjectStreamCache streams) {
    boolean allowed = iso || ALLOWED.contains(result.getPDFAFlavour());
    boolean completed = result.getJobEndStatus() == JobEndStatus.NORMAL;
    out.addProperty("ran", true);
    out.addProperty("compliant", result.isCompliant());
    out.addProperty("profile_allowed", allowed);
    out.addProperty("profile_name", result.getProfileDetails().getName());
    out.addProperty("flavour", result.getPDFAFlavour().getId());
    out.addProperty("status", !completed ? "ERROR" : result.isCompliant() && allowed ? "PASS" : "FAIL");
    out.addProperty("message", !completed ? "veraPDF did not finish." : !allowed ? "The declared PDF/A flavour is not allowed for EA-PDF."
        : result.isCompliant() ? "PDF/A checks passed." : "PDF/A checks failed.");
    out.addProperty("job_end_status", result.getJobEndStatus().getValue());
    out.addProperty("rule_total", result.getValidationProfile().getRules().size());
    out.addProperty("failed_rules", result.getFailedChecks().size());
    out.addProperty("checks", result.getTotalAssertions());
    out.addProperty("object_stream_cache_evictions", streams.evictions);
    out.addProperty("object_stream_cache_peak_decoded_bytes", streams.peakBytes());
    JsonArray failures = new JsonArray();
    for (var assertion : result.getTestAssertions()) {
      if (assertion.getStatus() == org.verapdf.pdfa.results.TestAssertion.Status.PASSED) continue;
      JsonObject finding = new JsonObject();
      finding.addProperty("specification", assertion.getRuleId().getSpecification().toString());
      finding.addProperty("clause", assertion.getRuleId().getClause());
      finding.addProperty("test", assertion.getRuleId().getTestNumber());
      finding.addProperty("message", assertion.getMessage());
      String context = assertion.getLocationContext();
      if ((context == null || context.isEmpty()) && assertion.getLocation() != null) {
        var location = assertion.getLocation();
        context = location.getContext();
      }
      finding.addProperty("context", context);
      finding.addProperty("error", assertion.getErrorMessage());
      failures.add(finding);
    }
    out.add("findings", failures);
  }

  static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? "" : value.getAsString();
  }

  static final class Report implements AutoCloseable {
    final String path;
    final JsonArray items = new JsonArray();
    final List<AutoCloseable> resources = new ArrayList<>();
    final Set<String> seen = new LinkedHashSet<>();
    final Set<String> implemented = new LinkedHashSet<>();
    JsonObject pdfa;
    JsonObject iso32005;
    String profile = "";
    private final Path spool;
    private java.io.BufferedWriter spoolWriter;
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private boolean incomplete, openFailed;
    private final Set<String> failureCodes = new LinkedHashSet<>();
    private final JsonArray unavailableExamples = new JsonArray();
    Report(String path) { this(path, false); }
    Report(String path, boolean streamed) {
      this.path = path;
      for (String level : List.of("PASS", "WARN", "FAIL", "N/A")) counts.put(level, 0L);
      try {
        spool = streamed ? Files.createTempFile("eapdf-findings-", ".jsonl") : null;
        if (spool != null) spoolWriter = Files.newBufferedWriter(spool);
      } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    void add(String level, String code, String message, Object details) {
      JsonObject item = new JsonObject();
      item.addProperty("level", level); item.addProperty("code", code); item.addProperty("message", message);
      if (ruleNumber(code) != null) item.addProperty("rule_number", ruleNumber(code));
      item.add("details", JSON.toJsonTree(details));
      if (spoolWriter == null) items.add(item);
      else try { spoolWriter.write(item.toString()); spoolWriter.newLine(); }
      catch (IOException error) { throw new java.io.UncheckedIOException(error); }
      seen.add(code); counts.merge(level, 1L, Long::sum);
      if ("FAIL".equals(level)) failureCodes.add(code);
      if ("VALIDATION_INCOMPLETE".equals(code) && "WARN".equals(level) && unavailableExamples.size() < 100) unavailableExamples.add(item);
      incomplete |= "VALIDATION_INCOMPLETE".equals(code) && "WARN".equals(level);
      openFailed |= "OPEN".equals(code) && "FAIL".equals(level);
    }
    void check(String code, boolean condition, String message, Object details) {
      add(condition ? "PASS" : "FAIL", code, message, details);
    }
    void recommend(String code, boolean condition, String message, Object details) {
      add(condition ? "PASS" : "WARN", code, message, details);
    }
    void na(String code, String reason, Object details) { add("N/A", code, reason, details); }
    void supported(String... codes) { implemented.addAll(List.of(codes)); }
    void unavailable(String code, String message) {
      unavailable(code, message, Map.of());
    }
    void unavailable(String code, String message, Map<String, ?> evidence) {
      Map<String, Object> details = new LinkedHashMap<>(evidence);
      details.put("assessment", "unavailable");
      add("WARN", code, message, details);
      if (!recommendation(code) && !"VALIDATION_INCOMPLETE".equals(code)) add("WARN", "VALIDATION_INCOMPLETE", message, Map.of("rule", code));
    }
    void finishCoverage() {
      if (openFailed) return;
      List<String> missing = new ArrayList<>();
      for (String code : RULES.keySet()) {
        if (seen.contains(code) || "VALIDATION_INCOMPLETE".equals(code)) continue;
        if (!recommendation(code)) missing.add(code);
        add("WARN", code, "Inspection ended before this check or its applicability could be established.", Map.of("assessment", "unavailable"));
      }
      if (!missing.isEmpty()) add("WARN", "VALIDATION_INCOMPLETE", "Inspection ended before every rule had a result or an applicability decision.", Map.of("rules", missing));
      if (!PORT_COMPLETE && !seen.contains("VALIDATION_INCOMPLETE")) add("WARN", "VALIDATION_INCOMPLETE", "The Java port still needs complete comparison against the existing validator.", Map.of("java_port_complete", false));
      if (!seen.contains("VALIDATION_INCOMPLETE")) na("VALIDATION_INCOMPLETE", "No unavailable checks were recorded.", null);
    }
    JsonObject summary() {
      JsonObject summary = new JsonObject();
      String pdfaStatus = text(pdfa, "status");
      String status = counts.get("FAIL") > 0 || "FAIL".equals(pdfaStatus) ? "FAIL"
          : incomplete || !PORT_COMPLETE || !"PASS".equals(pdfaStatus) ? "INCOMPLETE" : "PASS";
      summary.addProperty("file", path); summary.addProperty("status", status);
      summary.addProperty("verification_spec", profile.isEmpty() ? "" : "1" + profile);
      summary.addProperty("validation_scope", SCOPE);
      summary.addProperty("validation_target", "eapdf");
      summary.addProperty("rulebook_sha256", RULEBOOK_SHA256);
      summary.addProperty("pdfa_status", pdfaStatus);
      summary.addProperty("rule_total", RULES.size());
      summary.addProperty("java_port_complete", PORT_COMPLETE);
      counts.forEach((key, value) -> summary.addProperty("N/A".equals(key) ? "na" : key.toLowerCase(java.util.Locale.ROOT), value));
      return summary;
    }
    JsonObject result() {
      if (spool != null) throw new IllegalStateException("Use streaming report output");
      JsonObject result = new JsonObject();
      result.add("summary", summary()); result.add("pdfa", pdfa); result.add("items", items);
      return result;
    }
    void write(Path output) throws IOException { write(output, null); }
    void write(Path output, ValidationSession session) throws IOException {
      var outputs = ValidationOutputs.of(output);
      output = outputs.report();
      JsonObject reportSummary = session == null ? summary() : session.completedSummary(summary());
      if (spoolWriter != null) spoolWriter.flush();
      Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), ".validation-", ".json");
      try {
        try (var writer = JSON.newJsonWriter(Files.newBufferedWriter(temporary))) {
          writer.beginObject(); writer.name("summary"); JSON.toJson(reportSummary, writer);
          writer.name("pdfa"); JSON.toJson(pdfa, writer);
          writer.name("items"); writer.beginArray();
          if (spool == null) for (var item : items) JSON.toJson(item, writer);
          else try (var reader = Files.newBufferedReader(spool)) {
            for (String line; (line = reader.readLine()) != null;) JSON.toJson(JsonParser.parseString(line), writer);
          }
          writer.endArray(); writer.endObject();
        }
        if (session == null) ValidationSession.replace(temporary, output);
        else session.replaceOutput(temporary, output);
        JsonObject brief = new JsonObject(); brief.add("summary", reportSummary); brief.add("pdfa", pdfa);
        brief.addProperty("report", output.toString());
        brief.add("failure_codes", JSON.toJsonTree(failureCodes)); brief.add("unavailable_examples", unavailableExamples);
        if (session == null) ValidationSession.writeAtomic(outputs.summary(), brief);
        else session.write(outputs.summary(), brief);
      } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() {
      for (AutoCloseable resource : resources) try { resource.close(); }
      catch (Exception error) { System.err.println("Unable to remove validation source scratch: " + error.getMessage()); }
      resources.clear();
      try { if (spoolWriter != null) spoolWriter.close(); }
      catch (IOException error) { throw new java.io.UncheckedIOException(error); }
      finally {
        if (spool != null) {
          try { Files.deleteIfExists(spool); }
          catch (IOException error) {
            System.err.println("Unable to remove validation report spool " + spool + ": " + error);
          }
        }
      }
    }
  }
}
