package eapdf;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.MemoryDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class GmailDownloadToPdf {
  static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
  static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);

  public static void main(String[] args) throws Exception {
    if (ConversionSupervisor.run(GmailDownloadToPdf.class, args)) return;
    try (Common.JobReport job = Common.beginJob()) {
      Map<String,Object> opt = parseArgs(args);
      EapdfConfig.get().requireCreator();
      PdfSigning.preflight();
      String user = (String)opt.getOrDefault("user", "me");
      String fontPath = Common.bundledFontPath();
      String iccPath = Common.bundledIccPath();
      String credentialsPath = Common.resolveCredentialsPath((String)opt.get("credentials"));
      String tokenDir = (String)opt.getOrDefault("token-dir", Paths.get(System.getProperty("user.home"), ".gmail_token_store").toString());

      if (credentialsPath.isBlank()) throw Common.fail("missing --credentials /path/to/credentials.json");

      String folderName = (String)opt.getOrDefault("folder", "ALL_MAIL");
      String outPdfDir = (String)opt.getOrDefault("out-pdf", "out");
      String debugDir = (String)opt.getOrDefault("debug-dir", Paths.get(outPdfDir, "_debug").toString());
      boolean debug = Boolean.TRUE.equals(opt.get("debug"));
      String archiveProfile = (String)opt.get("archive-profile");
      String archiveOutput = (String)opt.get("archive-output");
      if (archiveProfile != null && archiveOutput != null && !archiveOutput.isBlank()) {
        Path destination = Path.of(archiveOutput);
        OutputTransaction.protectInput(destination, Path.of(credentialsPath));
        OutputTransaction.protectInput(destination, Path.of(tokenDir, "StoredCredential"));
        OutputTransaction.protectConfiguredInputs(destination, fontPath, iccPath);
        OutputTransaction.allowReplacement(destination);
      }
      boolean unreadOnly = Boolean.TRUE.equals(opt.get("unread-only"));
      int limit = (Integer)opt.getOrDefault("limit", 0);

      Files.createDirectories(Paths.get(outPdfDir));
      if (debug) debugDir = ConversionProgress.startDebugDirectory(Paths.get(debugDir)).toString();

      System.out.println("connecting: Gmail API");
      Gmail service = buildService(credentialsPath, tokenDir, user);

      String query = buildMessageQuery(unreadOnly);
      Map<String,String> labelNames = loadLabelNames(service, user);
      List<String> labelIds = resolveLabelIds(folderName, labelNames);
      List<com.google.api.services.gmail.model.Message> messages = listMessages(service, user, labelIds, query, limit);

      String resumePath = System.getProperty("eapdf.gmailResumeDir", System.getenv("EAPDF_GMAIL_RESUME_DIR"));
      Path resumeRoot = resumePath == null || resumePath.isBlank() ? null : Paths.get(resumePath);
      String account = resumeRoot == null ? user : service.users().getProfile(user).execute().getEmailAddress();
      if (resumeRoot != null && Common.nz(account).isBlank()) throw Common.fail("Cannot identify the Gmail account for resume storage");
      String scope = resumeRoot == null ? "" : resumeScope(account, opt, outPdfDir, archiveOutput, fontPath, iccPath);
      try (GmailResumeStore resume = new GmailResumeStore(resumeRoot, scope, messages.stream().map(message -> message.getId()).toList())) {
      messages = resume.selectedIds().stream().map(id -> new com.google.api.services.gmail.model.Message().setId(id)).toList();

      int total = messages.size();
      ConversionProgress.total(total);
      System.out.println("folder: " + displayFolderSelection(folderName));
      System.out.println("messages matched: " + total);
      System.out.println("messages selected: " + total);
      if (messages.isEmpty()) throw Common.fail("no Gmail messages matched the selection");
      for (var message : messages) Common.inputState("gmail://" + message.getId(), "accepted");

      Path debugPath = Paths.get(debugDir);
      List<EmailRecord> records = convertMessages(service, user, messages, labelNames, resume, opt,
          (path, record, sequence) -> PdfMailWriter.writeEaPdfA3(path, record, fontPath, iccPath,
              debug, debugPath, sequence));

      if (archiveProfile != null) {
        if (!"m".equals(archiveProfile) && !"mi".equals(archiveProfile)
            && !"c".equals(archiveProfile) && !"ci".equals(archiveProfile)) {
          throw Common.fail("unsupported --archive-profile (expected m, mi, c, or ci): " + archiveProfile);
        }
        boolean embedSource = "m".equals(archiveProfile) || "c".equals(archiveProfile);
        for (EmailRecord r : records) r.embedSourceMessage = embedSource;

        Path out;
        if (archiveOutput != null && !archiveOutput.isBlank()) {
          out = Paths.get(archiveOutput);
        } else {
          out = Common.availableArchivePath(Paths.get(outPdfDir).resolve("gmail_" + Common.safeFileStem(defaultArchiveStem(folderName)) + "_1" + archiveProfile + ".pdf"));
        }
        Path archiveDebugDir = Paths.get(debugDir, "gmail_archive_1" + archiveProfile);
        if (debug) Files.createDirectories(archiveDebugDir);
        if ("c".equals(archiveProfile) || "ci".equals(archiveProfile)) {
          PdfMailWriter.writeEaPdfCollection(out, records, fontPath, iccPath, debug, archiveDebugDir, archiveProfile);
        } else {
          PdfMailWriter.writeEaPdfArchive(out, records, fontPath, iccPath, debug, archiveDebugDir, archiveProfile);
        }
        System.out.println("wrote archive: " + out);
        for (EmailRecord record : records) Common.inputState(record.sourcePath, "completed");
        System.out.println("done. converted: " + records.size());
        return;
      }

      System.out.println("done. converted: " + messages.size());
      }
    } catch (Common.UserFacingException e) {
      Common.exitWithError(e.getMessage());
    }
  }

  @FunctionalInterface
  interface MessageWriter {
    void write(Path path, EmailRecord record, int sequence) throws Exception;
  }

  private record PendingMessage(String id, String sourcePath, Path output, long memoryBytes, Future<?> conversion) {}

  static int conversionWorkers(int messages) {
    Common.JobReport job = Common.ACTIVE_JOB.get();
    long memoryWorkers = Math.max(1, Runtime.getRuntime().maxMemory() / (128L * 1024 * 1024));
    return (int) Math.max(1, Math.min(memoryWorkers, Math.min(messages,
        Math.min(Runtime.getRuntime().availableProcessors(), job == null ? 4 : job.workerLimit))));
  }

  /** Keep Gmail access and checkpoint writes on the caller, overlapping bounded PDF workers. */
  static List<EmailRecord> convertMessages(Gmail service, String user,
      List<com.google.api.services.gmail.model.Message> messages, Map<String, String> labelNames, GmailResumeStore resume,
      Map<String, Object> opt, MessageWriter writer) throws Exception {
    String outPdfDir = (String) opt.getOrDefault("out-pdf", "out");
    String archiveProfile = (String) opt.get("archive-profile");
    String globalId = (String) opt.get("global-id");
    List<String> archiveEmails = castStringList(opt.get("email"));
    boolean debug = Boolean.TRUE.equals(opt.get("debug"));
    boolean omitSource = Boolean.TRUE.equals(opt.get("omit-source"));
    String folderName = (String) opt.getOrDefault("folder", "ALL_MAIL");
    List<EmailRecord> records = new ArrayList<>();
    int workers = conversionWorkers(messages.size());
    long reserved = 0, memoryBudget = Runtime.getRuntime().maxMemory();
    ExecutorService pool = archiveProfile == null ? Common.newWorkers(workers) : null;
    Deque<PendingMessage> pending = new ArrayDeque<>();
    if (pool != null) System.out.println("Gmail conversion workers: " + workers);
    try {
      for (int i = 0; i < messages.size(); i++) {
        if (pending.size() >= workers) {
          PendingMessage completed = pending.removeFirst();
          completeMessage(completed, resume);
          reserved -= completed.memoryBytes();
        }
        ProcessingLimits.checkCancelled();
        com.google.api.services.gmail.model.Message meta = messages.get(i);
        String sourcePath = "gmail://" + Common.nz(meta.getId());
        Common.inputState(sourcePath, "started");
        if (archiveProfile == null && resume.completed(meta.getId(), Paths.get(outPdfDir))) {
          Common.inputState(sourcePath, "completed");
          System.out.println("resumed a completed Gmail message");
          ConversionProgress.completed();
          continue;
        }
        com.google.api.services.gmail.model.Message full = downloadMessage(service, user, meta.getId(), resume);
        ProcessingLimits.checkMessage((long)full.getRaw().length() * 3 / 4, sourcePath);
        byte[] raw = Base64.getUrlDecoder().decode(full.getRaw());
        Common.JobReport job = Common.ACTIVE_JOB.get();
        if (job != null) job.largestSourceMessageBytes = Math.max(job.largestSourceMessageBytes, raw.length);
        // Reserve by actual source size, rather than the maximum permitted message
        // size (which otherwise consumes the entire heap allowance for one task).
        long memory = Math.max(128L * 1024 * 1024, raw.length * 16L);
        while (!pending.isEmpty() && memory > memoryBudget - reserved) {
          PendingMessage completed = pending.removeFirst();
          completeMessage(completed, resume);
          reserved -= completed.memoryBytes();
        }

        MimeMessage mm = new MimeMessage(EmlReader.mimeSession(raw), new ByteArrayInputStream(raw));
        int seq = i + 1;
        String subject = EmlReader.decodedSubject(mm);
        String stem = messageFileStem(subject, meta.getId());
        String sourceFilename = stem + ".eml";

        EmailRecord r = EmlReader.readRfc822Bytes(raw, sourcePath, sourceFilename, globalId, archiveEmails, debug);
        r.sourceType = "gmail";
        for (AssociatedFile file : r.associatedFiles) {
          if ("Source".equals(file.afRelationship)) file.description = Common.sourceDescription(r);
        }
        r.embedSourceMessage = !omitSource;
        if (r.folderPath == null || r.folderPath.isBlank()) {
          r.folderPath = describeMessageLabels(full.getLabelIds(), labelNames, folderName);
        }
        r.folderMemberships.addAll(messageLabelNames(full.getLabelIds(), labelNames, folderName));
        if (archiveProfile != null) {
          records.add(Common.stageRecord(r));
        } else {
          Path pdfPath = Paths.get(outPdfDir).resolve(stem + ".pdf");
          Future<?> conversion = pool.submit(() -> { writer.write(pdfPath, r, seq); return null; });
          pending.addLast(new PendingMessage(meta.getId(), sourcePath, pdfPath, memory, conversion));
          reserved += memory;
        }
      }

      while (!pending.isEmpty()) completeMessage(pending.removeFirst(), resume);
      return records;
    } finally {
      if (pool != null) Common.stopWorkers(pool);
    }
  }

  private static void completeMessage(PendingMessage pending, GmailResumeStore resume) throws Exception {
    EmailToPDFA.awaitConversion(pending.conversion());
    resume.rememberCompleted(pending.id(), List.of(pending.output()));
    System.out.println("wrote pdf: " + pending.output());
    Common.inputState(pending.sourcePath(), "completed");
  }

  static String resumeScope(String account, Map<String, Object> options, String outputDirectory, String archiveOutput,
                            String fontPath, String iccPath) throws Exception {
    LinkedHashSet<String> fonts = new LinkedHashSet<>();
    fonts.add(Common.digestHex(Paths.get(fontPath), "SHA-256"));
    for (Path fallback : FontSupport.fallbacks(fontPath)) fonts.add(Common.digestHex(fallback, "SHA-256"));
    return GmailResumeStore.JSON.toJson(Arrays.asList(account, options,
        Paths.get(outputDirectory).toAbsolutePath().normalize().toString(),
        Paths.get(archiveOutput == null ? outputDirectory : archiveOutput).toAbsolutePath().normalize().toString(),
        fonts, Common.digestHex(Paths.get(iccPath), "SHA-256"), Common.projectVersion(), PdfMailWriter.CONFIG));
  }

  static com.google.api.services.gmail.model.Message downloadMessage(Gmail service, String user, String messageId, GmailResumeStore resume) throws Exception {
    var cached = resume.downloaded(messageId);
    if (cached != null) return cached;
    var message = downloadMessage(service, user, messageId);
    resume.rememberDownload(messageId, message);
    return message;
  }

  static com.google.api.services.gmail.model.Message downloadMessage(Gmail service, String user, String messageId) throws IOException {
    for (int attempt = 0; ; attempt++) {
      try {
        return service.users().messages().get(user, messageId).setFormat("raw").execute();
      } catch (IOException exception) {
        if (attempt >= 2 || !retryableDownload(exception)) throw exception;
        String cause = exception instanceof HttpResponseException response
            ? "HTTP " + response.getStatusCode() : exception.getClass().getSimpleName();
        System.err.println("warning: retrying Gmail download after " + cause);
        // Network retry backoff, not polling for work; interruption cancels it.
        try {
          //noinspection BusyWait
          Thread.sleep(250L << attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          InterruptedIOException cancelled = new InterruptedIOException("Gmail download interrupted");
          cancelled.initCause(interrupted);
          throw cancelled;
        }
      }
    }
  }

  static boolean retryableDownload(IOException exception) {
    if (exception instanceof HttpResponseException response) {
      int status = response.getStatusCode();
      return status == 408 || status == 429 || status >= 500 && status < 600;
    }
    return !(exception instanceof InterruptedIOException);
  }

  static Gmail buildService(String credentialsPath, String tokenDir, String user) throws Exception {
    NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    Credential credential = authorize(httpTransport, credentialsPath, tokenDir, user);
    Gmail service = new Gmail.Builder(httpTransport, JSON_FACTORY, credential).setApplicationName("GmailDownloadToPdf").build();
    if (!"me".equalsIgnoreCase(user)) {
      try { service.users().getProfile(user).execute(); }
      catch (HttpResponseException error) {
        if (error.getStatusCode() != 403 && error.getStatusCode() != 404) throw error;
        throw Common.fail("Google did not authorize the requested Gmail mailbox. Check the account and permissions, then authorize again if needed.");
      }
    }
    return service;
  }

  static Credential authorize(NetHttpTransport httpTransport, String credentialsPath, String tokenDir, String user) throws Exception {
    try (InputStream in = Files.newInputStream(Paths.get(credentialsPath))) {
      GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in, StandardCharsets.UTF_8));
      var builder = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
          .setAccessType("offline");
      String configured = System.getenv(GmailTokenStore.PASSWORD_ENV);
      if (configured == null || configured.isBlank()) builder.setDataStoreFactory(new MemoryDataStoreFactory());
      else {
        char[] password = configured.toCharArray();
        try {
          builder.setCredentialDataStore(GmailTokenStore.open(Path.of(tokenDir), clientSecrets.getDetails().getClientId(), user, password));
        } finally { Arrays.fill(password, '\0'); }
      }
      var receiver = new GmailOAuthReceiver();
      return new AuthorizationCodeInstalledApp(builder.build(), receiver) {
        @Override protected void onAuthorization(AuthorizationCodeRequestUrl url) throws IOException {
          super.onAuthorization(url.setState(receiver.state));
        }
      }.authorize(GmailTokenStore.KEY);
    }
  }

  static List<com.google.api.services.gmail.model.Message> listMessages(Gmail service, String user, List<String> labelIds, String query, int limit) throws IOException {
    List<com.google.api.services.gmail.model.Message> out = new ArrayList<>();
    String pageToken = null;
    long pageSize = limit > 0 ? Math.min(limit, 500) : 500;

    do {
      Gmail.Users.Messages.List req = service.users().messages().list(user).setMaxResults(pageSize);
      if (labelIds != null && !labelIds.isEmpty()) req.setLabelIds(labelIds);
      if (query != null && !query.isBlank()) req.setQ(query);
      if (pageToken != null) req.setPageToken(pageToken);

      ListMessagesResponse resp = req.execute();
      List<com.google.api.services.gmail.model.Message> batch = resp.getMessages();
      if (batch != null) {
        for (com.google.api.services.gmail.model.Message m : batch) {
          out.add(m);
          ProcessingLimits.checkCount(out.size());
          if (limit > 0 && out.size() >= limit) return out;
        }
      }
      pageToken = resp.getNextPageToken();
    } while (pageToken != null);

    return out;
  }

  static String buildMessageQuery(boolean unreadOnly) {
    return unreadOnly ? "is:unread" : null;
  }

  static Map<String,String> loadLabelNames(Gmail service, String user) throws IOException {
    Map<String,String> out = new LinkedHashMap<>();
    var resp = service.users().labels().list(user).execute();
    if (resp.getLabels() != null) {
      for (var label : resp.getLabels()) {
        String id = label.getId();
        String name = label.getName();
        if (id != null && !id.isBlank()) out.put(id, name == null || name.isBlank() ? id : name);
      }
    }
    return out;
  }

  static String describeMessageLabels(List<String> labelIds, Map<String,String> labelNames, String fallback) {
    return String.join(", ", messageLabelNames(labelIds, labelNames, fallback));
  }

  static List<String> messageLabelNames(List<String> labelIds, Map<String,String> labelNames, String fallback) {
    if (labelIds == null || labelIds.isEmpty()) return List.of(displayFolderSelection(fallback));
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String id : labelIds) {
      String name = labelNames.getOrDefault(id, id);
      if (name == null || name.isBlank()) continue;
      out.add(name);
    }
    if (out.isEmpty()) return List.of(displayFolderSelection(fallback));
    return new ArrayList<>(out);
  }

  static String displayFolderSelection(String folderName) {
    String t = folderName == null ? "" : folderName.trim();
    if (t.isBlank()) return "ALL_MAIL";
    if (t.equalsIgnoreCase("ALL") || t.equalsIgnoreCase("ALL_MAIL") || t.equals("*")) return "ALL_MAIL";
    return t;
  }

  static String defaultArchiveStem(String folderName) {
    return displayFolderSelection(folderName);
  }

  static List<String> parseLabelIds(String folderName) {
    if (folderName == null || folderName.isBlank()) return Collections.emptyList();
    String[] parts = folderName.split(",");
    List<String> out = new ArrayList<>();
    for (String p : parts) {
      String s = p == null ? "" : p.trim();
      if (s.isBlank()) continue;
      if (s.equalsIgnoreCase("ALL") || s.equalsIgnoreCase("ALL_MAIL") || s.equals("*")) return Collections.emptyList();
      out.add(s);
    }
    return out.isEmpty() ? Collections.emptyList() : out;
  }

  static List<String> resolveLabelIds(String selection, Map<String, String> labelNames) {
    if ("ALL_MAIL".equals(displayFolderSelection(selection))) return List.of();
    if (labelNames.containsKey(selection)) return List.of(selection);
    List<String> wholeNameMatches = labelNames.entrySet().stream()
        .filter(entry -> entry.getValue().equals(selection)).map(Map.Entry::getKey).toList();
    if (wholeNameMatches.size() == 1) return wholeNameMatches;
    if (wholeNameMatches.size() > 1) throw Common.fail("ambiguous Gmail label: " + selection);
    List<String> result = new ArrayList<>();
    for (String token : parseLabelIds(selection)) {
      if (labelNames.containsKey(token)) {
        result.add(token);
        continue;
      }
      List<String> matches = labelNames.entrySet().stream()
          .filter(entry -> entry.getValue().equals(token)).map(Map.Entry::getKey).toList();
      if (matches.isEmpty()) throw Common.fail("unknown Gmail label: " + token);
      if (matches.size() > 1) throw Common.fail("ambiguous Gmail label: " + token);
      result.add(matches.get(0));
    }
    return result;
  }

  static String messageFileStem(String subject, String gmailMessageId) throws Exception {
    String identifier = Common.nz(gmailMessageId);
    if (identifier.isBlank()) throw Common.fail("Gmail message ID is missing");
    if (!identifier.matches("[a-z0-9_-]{1,64}")) {
      identifier = "id-" + Common.sha256Hex(identifier.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    // Query order can change between downloads; the message ID stays fixed.
    return Common.safeFileStem(Common.safeFilename(subject)) + "_gmail-" + identifier;
  }

  static Map<String,Object> parseArgs(String[] args) {
    Common.validateCliArguments(args, Set.of("--user", "--credentials", "--token-dir", "--folder", "--out-pdf", "--debug-dir", "--global-id", "-g", "--email", "-e", "--limit", "--archive-profile", "--archive-output"),
        Set.of("--omit-source", "--unread-only", "--debug"), false);
    Map<String,Object> m = new LinkedHashMap<>();
    List<String> emails = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (a.equals("--user") && i + 1 < args.length) { m.put("user", args[++i]); continue; }
      if (a.equals("--credentials") && i + 1 < args.length) { m.put("credentials", args[++i]); continue; }
      if (a.equals("--token-dir") && i + 1 < args.length) { m.put("token-dir", args[++i]); continue; }
      if (a.equals("--folder") && i + 1 < args.length) { m.put("folder", args[++i]); continue; }
      if (a.equals("--out-pdf") && i + 1 < args.length) { m.put("out-pdf", args[++i]); continue; }
      if (a.equals("--debug-dir") && i + 1 < args.length) { m.put("debug-dir", args[++i]); continue; }
      if ((a.equals("--global-id") || a.equals("-g")) && i + 1 < args.length) { m.put("global-id", args[++i]); continue; }
      if ((a.equals("--email") || a.equals("-e")) && i + 1 < args.length) { emails.add(args[++i]); continue; }
      if (a.equals("--limit") && i + 1 < args.length) { m.put("limit", Common.numericOption(a, args[++i], 0)); continue; }
      if (a.equals("--omit-source")) { m.put("omit-source", true); continue; }
      if (a.equals("--archive-profile") && i + 1 < args.length) { m.put("archive-profile", args[++i]); continue; }
      if (a.equals("--archive-output") && i + 1 < args.length) { m.put("archive-output", args[++i]); continue; }
      if (a.equals("--unread-only")) { m.put("unread-only", true); continue; }
      if (a.equals("--debug")) { m.put("debug", true); continue; }
    }
    m.put("email", emails);
    Common.validateArchiveOptions(m);
    return m;
  }

  static List<String> castStringList(Object o) {
    if (o == null) return new ArrayList<>();
    if (o instanceof List<?> l) {
      List<String> out = new ArrayList<>();
      for (Object x : l) out.add(x == null ? "" : x.toString());
      return out;
    }
    return new ArrayList<>();
  }
}
