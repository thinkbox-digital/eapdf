package eapdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;

final class PdfCollectionSupport {
  private PdfCollectionSupport() {}

  static String buildFolderCollectionRenderHtml(List<PdfMailWriter.CollectionMember> members, String collectionProfile,
                                                PdfMailWriter.CollectionRunSummary runSummary,
                                                List<PdfMailWriter.FindingAidRow> findingAidRows) {
    StringBuilder s = new StringBuilder();
    s.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>");
    s.append("<style>");
    s.append("@page { size: letter; margin: 0.65in; }");
    s.append("html, body, body * { font-family: ArchiveFont !important; }");
    s.append("body { font-size: 11pt; color: #222; line-height: 1.35; }");
    s.append("h1 { font-size: 20pt; margin: 0 0 8px; }");
    s.append("p { margin: 0 0 10px; }");
    s.append(".lede { color: #5f5544; margin-bottom: 16px; }");
    s.append(".member-list { margin-top: 14px; }");
    s.append(".member { border: 1px solid #d7c7ae; border-radius: 10px; padding: 10px 12px; margin-bottom: 10px; background: #fcfaf6; }");
    s.append(".member-name { font-weight: bold; font-size: 12pt; color: #1f3f5b; }");
    s.append(".member-meta { color: #675b4f; font-size: 10pt; margin-top: 3px; }");
    s.append(".member-file { color: #3e4f63; font-family: monospace; font-size: 10pt; margin-top: 4px; }");
    s.append(".member-count { margin-top: 2px; }");
    s.append(".message-delimiter { margin-top: 24px; page-break-inside: avoid; width: 100%; }");
    s.append(".message-delimiter-label { font-size: 8.5pt; color: #7f7462; letter-spacing: 0.08em; text-transform: uppercase; margin-bottom: 6px; }");
    s.append(".branding-box { border: 1px solid #cdbd9f; overflow: hidden; position: relative; width: 100%; box-sizing: border-box; }");
    s.append(".branding-image { display: block; width: 100%; height: auto; }");
    s.append(".branding-overlay { position: absolute; left: 24px; top: 14px; max-width: 72%; }");
    s.append(".branding-overlay-inner { background: #ffffff; border: 1px solid #cdbd9f; padding: 7px 10px; }");
    s.append(".branding-title { font-size: 10pt; font-weight: bold; color: #1f3f5b; }");
    s.append(".branding-subtitle { font-size: 8.5pt; color: #5f5544; margin-top: 2px; }");
    s.append("</style></head><body>");
    s.append("<h1>Email Archive</h1>");
    s.append("<p class=\"lede\">EA-PDF 1").append(Common.escapeHtml(collectionProfile)).append(" container. Embedded child EA-PDF files are grouped by top-level folder.</p>");
    if (runSummary != null) {
      s.append("<p><strong>Harvested email(s):</strong> ").append(Common.escapeHtml(runSummary.harvestedEmails)).append("</p>");
      s.append("<p><strong>Run stats:</strong> ").append(runSummary.messageCount).append(" messages across ")
          .append(runSummary.folderCount).append(" folders</p>");
      s.append("<p><strong>Generated:</strong> ").append(Common.escapeHtml(runSummary.generatedAt)).append("</p>");
    }
    s.append("<div class=\"member-list\">");
    for (PdfMailWriter.CollectionMember member : members) {
      s.append("<div class=\"member\">");
      s.append("<div class=\"member-name\">").append(Common.escapeHtml(member.groupName)).append("</div>");
      s.append("<div class=\"member-meta\">Child profile: EA-PDF 1").append(Common.escapeHtml(member.childProfile))
          .append(" &bull; Messages: ").append(member.records.size()).append("</div>");
      s.append("<div class=\"member-file\">Embedded file: ").append(Common.escapeHtml(member.filename)).append("</div>");
      s.append("</div>");
    }
    s.append("</div>");
    s.append("<p>Folders are sorted alphabetically. Each collection folder contains embedded child EA-PDF message files in chronological order.</p>");
    PdfFindingAidSupport.appendFindingAidTable(s, findingAidRows, true);
    PdfMailWriter.appendBrandingBlock(s, "1" + collectionProfile);
    s.append("</body></html>");
    return Common.stripInvalidXml(s.toString());
  }

  static String buildMessageCollectionRenderHtml(List<PdfMailWriter.CollectionMember> members, String collectionProfile, String folderName) {
    StringBuilder s = new StringBuilder();
    s.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>");
    s.append("<style>");
    s.append("@page { size: letter; margin: 0.65in; }");
    s.append("html, body, body * { font-family: ArchiveFont !important; }");
    s.append("body { font-size: 11pt; color: #222; line-height: 1.35; }");
    s.append("h1 { font-size: 20pt; margin: 0 0 8px; }");
    s.append("p { margin: 0 0 10px; }");
    s.append(".lede { color: #5f5544; margin-bottom: 16px; }");
    s.append(".member-list { margin-top: 14px; }");
    s.append(".member { border: 1px solid #d7c7ae; border-radius: 10px; padding: 10px 12px; margin-bottom: 10px; background: #fcfaf6; }");
    s.append(".member-name { font-weight: bold; font-size: 12pt; color: #1f3f5b; }");
    s.append(".member-meta { color: #675b4f; font-size: 10pt; margin-top: 3px; }");
    s.append(".member-file { color: #3e4f63; font-family: monospace; font-size: 10pt; margin-top: 4px; }");
    s.append(".branding-box { border: 1px solid #cdbd9f; overflow: hidden; position: relative; width: 100%; box-sizing: border-box; }");
    s.append(".branding-image { display: block; width: 100%; height: auto; }");
    s.append(".branding-overlay { position: absolute; left: 24px; top: 14px; max-width: 72%; }");
    s.append(".branding-overlay-inner { background: #ffffff; border: 1px solid #cdbd9f; padding: 7px 10px; }");
    s.append(".branding-title { font-size: 10pt; font-weight: bold; color: #1f3f5b; }");
    s.append(".branding-subtitle { font-size: 8.5pt; color: #5f5544; margin-top: 2px; }");
    s.append("</style></head><body>");
    s.append("<h1>").append(Common.escapeHtml(folderName)).append("</h1>");
    s.append("<p class=\"lede\">EA-PDF 1").append(Common.escapeHtml(collectionProfile)).append(" folder collection. Embedded child EA-PDF files are listed in chronological order.</p>");
    s.append("<div class=\"member-list\">");
    for (PdfMailWriter.CollectionMember member : members) {
      s.append("<div class=\"member\">");
      s.append("<div class=\"member-name\">").append(Common.escapeHtml(member.subject)).append("</div>");
      s.append("<div class=\"member-meta\">Child profile: EA-PDF 1").append(Common.escapeHtml(member.childProfile)).append("</div>");
      s.append("<div class=\"member-file\">Embedded file: ").append(Common.escapeHtml(member.filename)).append("</div>");
      s.append("</div>");
    }
    s.append("</div>");
    PdfMailWriter.appendBrandingBlock(s, "1" + collectionProfile);
    s.append("</body></html>");
    return Common.stripInvalidXml(s.toString());
  }

  static List<String> collectionGroupNames(EmailRecord record) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    String raw = Common.nz(record == null ? "" : record.folderPath);
    List<String> memberships = record != null && !record.folderMemberships.isEmpty()
        ? record.folderMemberships : List.of(raw.split(","));
    if (!memberships.isEmpty()) {
      for (String part : memberships) {
        String label = part == null ? "" : part.trim();
        if (label.isBlank()) continue;
        if (shouldIgnoreCollectionLabel(label)) continue;
        out.add(Common.stripInvalidXml(label));
      }
    }
    if (out.isEmpty()) out.add(Common.safeCollectionLabel("Messages", "Messages"));
    return new ArrayList<>(out);
  }

  static List<EmailRecord> sortRecordsChronologically(List<EmailRecord> records) {
    List<EmailRecord> out = new ArrayList<>(records == null ? List.of() : records);
    out.sort((a, b) -> {
      int cmp = Common.messageInstant(a).compareTo(Common.messageInstant(b));
      if (cmp != 0) return cmp;
      cmp = Common.nz(a == null ? "" : a.subject).compareToIgnoreCase(Common.nz(b == null ? "" : b.subject));
      if (cmp != 0) return cmp;
      return Common.nz(a == null ? "" : a.messageId).compareToIgnoreCase(Common.nz(b == null ? "" : b.messageId));
    });
    return out;
  }

  static List<EmailRecord> sortRecordsReverseChronologically(List<EmailRecord> records) {
    List<EmailRecord> out = sortRecordsChronologically(records);
    Collections.reverse(out);
    return out;
  }

  static List<PdfMailWriter.FolderSection> buildFolderSections(List<EmailRecord> records) {
    Map<String, List<EmailRecord>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    long memberships = 0;
    for (EmailRecord record : records) {
      Set<String> assigned = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      for (String groupName : collectionGroupNames(record)) {
        if (!assigned.add(groupName)) continue;
        ProcessingLimits.checkCount(++memberships);
        groups.computeIfAbsent(groupName, k -> new ArrayList<>()).add(record);
      }
    }
    List<PdfMailWriter.FolderSection> sections = new ArrayList<>();
    for (Map.Entry<String, List<EmailRecord>> entry : groups.entrySet()) {
      List<EmailRecord> groupRecords = sortRecordsChronologically(entry.getValue());
      PdfMailWriter.FolderSection section = new PdfMailWriter.FolderSection(entry.getKey(), groupRecords);
      int totalSize = 0;
      for (EmailRecord record : groupRecords) totalSize += Math.max(0, PdfMailWriter.messageMetadataSize(record));
      section.totalSizeBytes = totalSize;
      sections.add(section);
    }
    return sections;
  }

  static List<PdfMailWriter.CollectionFolder> buildCollectionFolders(List<PdfMailWriter.FolderSection> sections) {
    List<PdfMailWriter.CollectionFolder> folders = new ArrayList<>();
    Set<String> displayNames = new LinkedHashSet<>();
    int folderId = 1;
    for (PdfMailWriter.FolderSection section : sections) {
      String base = Common.safeCollectionLabel(section.folderName, "Messages");
      String display = base;
      int suffix = 2;
      while (!displayNames.add(Common.filenameCaseKey(display))) display = base + " (" + suffix++ + ")";
      folders.add(new PdfMailWriter.CollectionFolder(display, folderId++, section.records.size()));
    }
    return folders;
  }

  static List<PdfMailWriter.CollectionMember> buildFolderCollectionMembers(List<PdfMailWriter.FolderSection> sections, List<PdfMailWriter.CollectionFolder> folders, String fontPath, String iccPath, boolean debug, Path debugDir, String collectionProfile, PayloadStore memberStore) throws Exception {
    List<PdfMailWriter.CollectionMember> members = new ArrayList<>();
    int globalIndex = 0;
    for (int folderIndex = 0; folderIndex < folders.size(); folderIndex++) {
      PdfMailWriter.CollectionFolder folder = folders.get(folderIndex);
      List<EmailRecord> groupRecords = sections.get(folderIndex).records;
      int folderOrder = 0;
      for (EmailRecord record : sortRecordsReverseChronologically(groupRecords)) {
        folderOrder++;
        globalIndex++;
        ProcessingLimits.checkCount(globalIndex);
        String subject = collectionMemberSubject(record, folderOrder);
        String childProfile = childProfileForCollection(collectionProfile, 1);
        String filename = collectionMemberFilename(subject, childProfile, folderOrder);
        record.embedSourceMessage = !"ci".equals(collectionProfile);
        Path childPath = memberStore == null ? null : memberStore.createPdf();
        PdfMailWriter.SingleMessageBuildResult built = PdfMailWriter.buildSingleMessagePdf(Path.of(filename), record, fontPath, iccPath, debug, debugDir.resolve("collection_child_" + globalIndex), 1, childProfile, childPath);
        byte[] pdfBytes = built.pdfBytes;
        int pdfSize = childPath == null ? pdfBytes.length : Math.toIntExact(Files.size(childPath));
        folder.totalSizeBytes += pdfSize;
        members.add(new PdfMailWriter.CollectionMember(folder.name, subject, childProfile, List.of(record), folder.id, folderOrder, filename, pdfBytes,
            Math.max(record.attachmentCount, 0), Common.nz(record.date)));
        members.get(members.size() - 1).premisBytes = built.premisBytes;
        members.get(members.size() - 1).pdfPath = childPath;
        members.get(members.size() - 1).storedPdfSize = pdfSize;
        members.get(members.size() - 1).stage(memberStore);
      }
    }
    return members;
  }

  static void addTopLevelCollectionDictionary(PDDocument doc, List<PdfMailWriter.CollectionFolder> folders) throws IOException {
    COSDictionary collection = new COSDictionary();
    collection.setName(COSName.TYPE, "Collection");
    collection.setName(COSName.getPDFName("View"), "T");
    collection.setItem(COSName.getPDFName("Sort"), collectionSortDictionary("adobe:Order", "FileName"));
    collection.setName(COSName.getPDFName("Reorder"), "adobe:Order");

    COSDictionary schema = new COSDictionary();
    schema.setItem(COSName.getPDFName("FileName"), collectionSchemaField("Folder Name", "F"));
    schema.setItem(COSName.getPDFName("Messages"), collectionSchemaField("# of Messages", "N"));
    schema.setItem(COSName.getPDFName("adobe:Order"), collectionSchemaField("Order", "N"));
    schema.setItem(COSName.getPDFName("To"), collectionSchemaField("To", "S"));
    schema.setItem(COSName.getPDFName("From"), collectionSchemaField("From", "S"));
    schema.setItem(COSName.getPDFName("Sent"), collectionSchemaField("Sent", "D"));
    schema.setItem(COSName.getPDFName("Subject"), collectionSchemaField("Subject", "S"));
    schema.setItem(COSName.getPDFName("Message-ID"), collectionSchemaField("Message-ID", "S"));
    schema.setItem(COSName.getPDFName("Cc"), collectionSchemaField("Cc", "S"));
    schema.setItem(COSName.getPDFName("Bcc"), collectionSchemaField("Bcc", "S"));
    schema.setItem(COSName.getPDFName("Size"), collectionSchemaField("Size", "Size"));
    schema.setItem(COSName.getPDFName("Attachments"), collectionSchemaField("Attachments", "N"));
    collection.setItem(COSName.getPDFName("Schema"), schema);
    COSBase folderTree = buildCollectionFolderTree(folders);
    if (folderTree != null) collection.setItem(COSName.getPDFName("Folders"), folderTree);
    doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Collection"), collection);
  }

  static void addMessageCollectionDictionary(PDDocument doc) {
    COSDictionary collection = new COSDictionary();
    collection.setName(COSName.TYPE, "Collection");
    collection.setName(COSName.getPDFName("View"), "T");
    collection.setItem(COSName.getPDFName("Sort"), collectionSortDictionary("adobe:Order", "Subject"));

    COSDictionary schema = new COSDictionary();
    schema.setItem(COSName.getPDFName("adobe:Order"), collectionSchemaField("Order", "N"));
    schema.setItem(COSName.getPDFName("To"), collectionSchemaField("To", "S"));
    schema.setItem(COSName.getPDFName("From"), collectionSchemaField("From", "S"));
    schema.setItem(COSName.getPDFName("Sent"), collectionSchemaField("Sent", "D"));
    schema.setItem(COSName.getPDFName("Subject"), collectionSchemaField("Subject", "S"));
    schema.setItem(COSName.getPDFName("Folder"), collectionSchemaField("Folder", "S"));
    schema.setItem(COSName.getPDFName("Message-ID"), collectionSchemaField("Message-ID", "S"));
    schema.setItem(COSName.getPDFName("Cc"), collectionSchemaField("Cc", "S"));
    schema.setItem(COSName.getPDFName("Bcc"), collectionSchemaField("Bcc", "S"));
    schema.setItem(COSName.getPDFName("Attachments"), collectionSchemaField("Attachments", "N"));
    schema.setItem(COSName.getPDFName("Size"), collectionSchemaField("Size", "Size"));
    collection.setItem(COSName.getPDFName("Schema"), schema);
    doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Collection"), collection);
  }

  static EmailRecord buildCollectionFrontRecord(List<EmailRecord> records, String collectionProfile, List<PdfMailWriter.CollectionMember> members) {
    EmailRecord r = new EmailRecord();
    r.subject = "Email Collection";
    r.folderPath = "Collection";
    r.contentSetDescription = "Collection front matter";
    r.mailGuid = Common.safeFilename("collection-" + collectionProfile + "-" + members.size());
    r.messageId = "";
    r.archiveGlobalId = records.isEmpty() ? "" : Common.nz(records.get(0).archiveGlobalId);
    r.archiveEmails = new ArrayList<>();
    for (EmailRecord item : records) {
      for (String email : item.archiveEmails) {
        if (!r.archiveEmails.contains(email)) r.archiveEmails.add(email);
      }
    }
    return r;
  }

  static void addCollectionDPartStructure(PDDocument doc, List<EmailRecord> records, String collectionProfile) throws Exception {
    if (doc.getNumberOfPages() == 0) return;
    new PdfDocumentParts(doc).addFrontMatter(0, doc.getNumberOfPages() - 1, "Collection front matter");
  }

  static void addCollectionLogicalStructure(PDDocument doc) throws IOException {
    if (doc.getNumberOfPages() == 0) return;
    COSDictionary tree = new COSDictionary();
    tree.setName(COSName.TYPE, "StructTreeRoot");
    COSDictionary roles = new COSDictionary();
    roles.setName(COSName.getPDFName("Mail_ContentSet"), "Art");
    tree.setItem(COSName.ROLE_MAP, roles);
    COSDictionary root = PdfMailWriter.structElem("Document", tree, doc.getPage(0));
    COSDictionary content = PdfMailWriter.structElem("Mail_ContentSet", PdfDocumentParts.indirect(doc, root), doc.getPage(0));
    PdfMailWriter.addStringUserProperty(content, "Mail_ContentSetType", "FrontMatter");
    COSDictionary renderedTree = (COSDictionary) doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
    SemanticStructure.attach(doc, tree, content, renderedTree, 0, doc.getNumberOfPages() - 1);
    root.setItem(COSName.K, PdfDocumentParts.indirect(doc, content));
    tree.setItem(COSName.K, PdfDocumentParts.indirect(doc, root));
    PdfMailWriter.addLogicalStructureNamespaces(doc, tree);
    if (PdfMailWriter.pdfUaFeaturesEnabled()) SemanticStructure.addDestinations(doc, tree);
    doc.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, PdfDocumentParts.indirect(doc, tree));
  }

  static void embedCollectionFiles(PDDocument doc, List<EmailRecord> records, List<PdfMailWriter.CollectionMember> members, boolean debug, boolean topLevelFolders) throws Exception {
    PDDocumentCatalog cat = doc.getDocumentCatalog();
    COSDictionary catd = cat.getCOSObject();

    COSArray afArray = (COSArray)catd.getDictionaryObject(COSName.getPDFName("AF"));
    if (afArray == null) {
      afArray = new COSArray();
      catd.setItem(COSName.getPDFName("AF"), afArray);
    }

    PDDocumentNameDictionary names = cat.getNames();
    if (names == null) {
      names = new PDDocumentNameDictionary(cat);
      cat.setNames(names);
    }

    PDEmbeddedFilesNameTreeNode efs = names.getEmbeddedFiles();
    Map<String, PDComplexFileSpecification> nameMap = efs != null && efs.getNames() != null ? new LinkedHashMap<>(efs.getNames()) : new LinkedHashMap<>();
    if (efs == null) efs = new PDEmbeddedFilesNameTreeNode();

    for (PdfMailWriter.CollectionMember member : members) {
      AssociatedFile af = new AssociatedFile();
      af.description = "Contained EA-PDF message";
      String nameTreeKey = topLevelFolders && member.folderId > 0 ? ("<" + member.folderId + ">" + member.filename) : member.filename;
      COSDictionary collectionItem = topLevelFolders ? topLevelCollectionItemDictionary(member) : messageCollectionItemDictionary(member);
      af.bytes = member.pdfBytes;
      af.dataPath = member.pdfPath;
      af.storedSize = member.pdfSize();
      String checksum = member.pdfPath == null ? Common.md5Hex(member.pdfBytes) : Common.digestHex(member.pdfPath, "MD5");
      addEmbeddedPlan(doc, afArray, nameMap, nameTreeKey, member.filename, "application/pdf", "Data", checksum, af,
          af.description, collectionItem);
      if (debug) System.out.println("[debug] collection member embedded: " + member.filename + " messages=" + member.records.size());
    }

    for (PdfMailWriter.ArchiveEmbeddedFilePlan plan : PdfMailWriter.buildArchiveEmbeddedFilePlans(records)) {
      if (!"Source".equals(plan.relationship) && !plan.file.sourceSetMember) continue;
      addEmbeddedPlan(doc, afArray, nameMap, plan.filename, plan.filename, plan.mime, plan.relationship,
          plan.checksumMd5, plan.file, plan.file.description, null);
    }

    efs.setNames(nameMap);
    names.setEmbeddedFiles(efs);
    cat.setNames(names);
  }

  static void repairSavedTopLevelCollection(Path pdfPath, List<PdfMailWriter.CollectionFolder> folders, List<PdfMailWriter.CollectionMember> members, boolean debug) throws Exception {
    Common.writePdfAtomically(pdfPath, temporary -> {
      try (PDDocument doc = Loader.loadPDF(pdfPath.toFile(), org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
        addTopLevelCollectionDictionary(doc, folders);
        rewriteTopLevelCollectionItems(doc, members);
        doc.save(temporary.toFile());
      }
    });
  }

  static void writeFolderMessageCollection(Path out, List<EmailRecord> records, String fontPath, String iccPath, boolean debug, Path debugDir,
                                           String collectionProfile, String folderName) throws Exception {
    if (records == null || records.isEmpty()) throw new IllegalArgumentException("records must not be empty");
    List<PdfMailWriter.CollectionMember> members = buildMessageCollectionMembers(records, fontPath, iccPath, debug, debugDir, collectionProfile);
    if (members.isEmpty()) throw new IllegalArgumentException("collection requires at least one embedded EA-PDF member");

    EmailRecord debugRecord = new EmailRecord();
    String html = buildMessageCollectionRenderHtml(members, collectionProfile, folderName);
    byte[] rendered = PdfMailWriter.renderHtmlPdf(html, PdfMailWriter.ROOT_URI(), fontPath, debugRecord, debug);

    try (PDDocument doc = Loader.loadPDF(rendered)) {
      doc.setVersion(2.0f);
      doc.getDocument().setVersion(2.0f);
      if (PdfMailWriter.CONFIG.branding.enabled) PdfMailWriter.drawBrandingFooter(doc, fontPath, "1" + collectionProfile, 0);
      PdfMailWriter.clearImageInterpolation(doc);
      PdfMailWriter.setDocumentInfo(doc, records.get(0));
      PdfMailWriter.configureArchiveCatalog(doc, collectionProfile);
      addMessageCollectionDictionary(doc);
      PdfMailWriter.addOutlines(doc, buildCollectionFrontRecord(records, collectionProfile, members));
      addCollectionDPartStructure(doc, records, collectionProfile);
      PdfMailWriter.drawPageContext(doc, fontPath);
      addCollectionLogicalStructure(doc);
      embedCollectionFiles(doc, records, members, debug, false);
      PdfMailWriter.setPdfA3Metadata(doc, iccPath, records, collectionProfile);
      addMessageCollectionDictionary(doc);
      Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
      Common.writePdfAtomically(out, temporary -> doc.save(temporary.toFile()));
    }
  }

  static List<PdfMailWriter.CollectionMember> buildMessageCollectionMembers(List<EmailRecord> records, String fontPath, String iccPath, boolean debug, Path debugDir, String collectionProfile) throws Exception {
    List<PdfMailWriter.CollectionMember> members = new ArrayList<>();
    List<EmailRecord> ordered = sortRecordsChronologically(records);
    int parallelism = PdfMailWriter.renderParallelism(ordered.size());
    if (parallelism <= 1) {
      int index = 0;
      for (EmailRecord record : ordered) {
        index++;
        String folderName = collectionFolderDisplay(record);
        String subject = collectionMemberSubject(record, index);
        String childProfile = childProfileForCollection(collectionProfile, 1);
        String filename = collectionMemberFilename(subject, childProfile, index);
        record.embedSourceMessage = !"ci".equals(collectionProfile);
        PdfMailWriter.SingleMessageBuildResult built = PdfMailWriter.buildSingleMessagePdf(Path.of(filename), record, fontPath, iccPath, debug, debugDir.resolve("collection_child_" + index), 1, childProfile);
        byte[] pdfBytes = built.pdfBytes;
        int attachmentCount = Math.max(record.attachmentCount, 0);
        String latestSent = Common.nz(record.date);
        members.add(new PdfMailWriter.CollectionMember(folderName, subject, childProfile, List.of(record), 0, index, filename, pdfBytes, attachmentCount, latestSent));
        members.get(members.size() - 1).premisBytes = built.premisBytes;
      }
    } else {
      ExecutorService pool = Common.newWorkers(parallelism);
      try {
        List<Future<PdfMailWriter.CollectionMember>> futures = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
          final int index = i + 1;
          final EmailRecord record = ordered.get(i);
          futures.add(pool.submit(() -> {
            String folderName = collectionFolderDisplay(record);
            String subject = collectionMemberSubject(record, index);
            String childProfile = childProfileForCollection(collectionProfile, 1);
            String filename = collectionMemberFilename(subject, childProfile, index);
            record.embedSourceMessage = !"ci".equals(collectionProfile);
            PdfMailWriter.SingleMessageBuildResult built = PdfMailWriter.buildSingleMessagePdf(Path.of(filename), record, fontPath, iccPath, debug, debugDir.resolve("collection_child_" + index), 1, childProfile);
            byte[] pdfBytes = built.pdfBytes;
            int attachmentCount = Math.max(record.attachmentCount, 0);
            String latestSent = Common.nz(record.date);
            PdfMailWriter.CollectionMember member = new PdfMailWriter.CollectionMember(folderName, subject, childProfile, List.of(record), 0, index, filename, pdfBytes, attachmentCount, latestSent);
            member.premisBytes = built.premisBytes;
            return member;
          }));
        }
        for (Future<PdfMailWriter.CollectionMember> future : futures) members.add(Common.awaitWorker(future));
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception ex) throw ex;
        throw new RuntimeException(cause);
      } finally {
        Common.stopWorkers(pool);
      }
      members.sort(Comparator.comparingInt(member -> member.orderIndex));
    }
    return members;
  }

  static String childProfileForCollection(String collectionProfile, int count) {
    boolean isolated = "ci".equals(collectionProfile);
    if (count <= 1) return isolated ? "si" : "s";
    return isolated ? "mi" : "m";
  }

  static String collectionMemberFilename(String groupName, String childProfile, int index) {
    String rawStem = Common.safeFileStem(Common.nz(groupName).isBlank() ? ("folder_" + index) : groupName);
    String stem = rawStem.replaceAll("\\.{2,}", "_").replaceAll("\\s+", " ").trim();
    if (stem.isBlank()) stem = "message";
    return String.format("%s_%06d_1%s.pdf", stem, index, childProfile);
  }

  static String collectionFolderDisplay(EmailRecord record) {
    List<String> names = collectionGroupNames(record);
    if (names.isEmpty()) return "Messages";
    return String.join(", ", names);
  }

  static String collectionMemberSubject(EmailRecord record, int index) {
    String subject = Common.nz(record == null ? "" : record.subject).trim();
    return subject.isBlank() ? ("message " + index) : subject;
  }

  private static boolean shouldIgnoreCollectionLabel(String label) {
    String upper = Common.nz(label).trim().toUpperCase(Locale.ROOT);
    if (upper.isBlank()) return true;
    if (upper.equals("UNREAD")
        || upper.equals("IMPORTANT")
        || upper.equals("STARRED")
        || upper.equals("ALL_MAIL")
        || upper.equals("CHAT")
        || upper.equals("CATEGORY_FORUMS")
        || upper.equals("CATEGORY_UPDATES")
        || upper.equals("CATEGORY_PROMOTIONS")
        || upper.equals("CATEGORY_SOCIAL")
        || upper.equals("CATEGORY_PERSONAL")) return true;
    return upper.startsWith("CATEGORY_");
  }

  private static COSDictionary collectionSortDictionary(String... fieldNames) {
    COSDictionary sort = new COSDictionary();
    COSArray fields = new COSArray();
    for (String fieldName : fieldNames) {
      if (!Common.nz(fieldName).isBlank()) fields.add(COSName.getPDFName(fieldName));
    }
    sort.setItem(COSName.S, fields);
    return sort;
  }

  private static COSBase buildCollectionFolderTree(List<PdfMailWriter.CollectionFolder> folders) throws IOException {
    if (folders == null || folders.isEmpty()) return null;
    COSDictionary root = new COSDictionary();
    root.setName(COSName.TYPE, "Folder");
    root.setInt(COSName.getPDFName("ID"), 0);
    root.setString(COSName.NAME, Common.safeCollectionLabel("Collection", "Collection"));
    root.setString(COSName.getPDFName("ModDate"), pdfDateNow());
    COSArray free = new COSArray();
    long nextId = Math.max(0, folders.stream().mapToLong(folder -> folder.id).max().orElse(0)) + 1;
    if (nextId <= Integer.MAX_VALUE) {
      free.add(COSInteger.get(nextId));
      free.add(COSInteger.get(Integer.MAX_VALUE));
    }
    root.setItem(COSName.getPDFName("Free"), free);
    COSObject rootObj = new COSObject(root);
    COSObject first = null;
    COSObject prev = null;
    COSDictionary prevNode = null;
    int orderIndex = 0;
    for (PdfMailWriter.CollectionFolder folder : folders) {
      orderIndex++;
      COSDictionary node = new COSDictionary();
      node.setName(COSName.TYPE, "Folder");
      node.setInt(COSName.getPDFName("ID"), folder.id);
      node.setString(COSName.NAME, folder.name);
      node.setItem(COSName.PARENT, rootObj);
      String now = pdfDateNow();
      node.setString(COSName.getPDFName("CreationDate"), now);
      node.setString(COSName.getPDFName("ModDate"), now);
      node.setLong(COSName.getPDFName("Messages"), folder.messageCount);
      node.setLong(COSName.getPDFName("Size"), folder.totalSizeBytes);
      COSDictionary ci = new COSDictionary();
      ci.setFloat(COSName.getPDFName("adobe:Order"), orderIndex);
      ci.setInt(COSName.getPDFName("Messages"), folder.messageCount);
      ci.setLong(COSName.getPDFName("Size"), folder.totalSizeBytes);
      node.setItem(COSName.getPDFName("CI"), ci);
      COSObject nodeObj = new COSObject(node);
      if (prev != null && prevNode != null) prevNode.setItem(COSName.NEXT, nodeObj);
      else first = nodeObj;
      prev = nodeObj;
      prevNode = node;
    }
    if (first != null) root.setItem(COSName.getPDFName("Child"), first);
    return rootObj;
  }

  private static String pdfDateNow() {
    Calendar now = Calendar.getInstance();
    int offsetMinutes = now.getTimeZone().getOffset(now.getTimeInMillis()) / (60 * 1000);
    char sign = offsetMinutes >= 0 ? '+' : '-';
    int abs = Math.abs(offsetMinutes);
    int hours = abs / 60;
    int minutes = abs % 60;
    return String.format(Locale.ROOT,
        "D:%04d%02d%02d%02d%02d%02d%c%02d'%02d'",
        now.get(Calendar.YEAR),
        now.get(Calendar.MONTH) + 1,
        now.get(Calendar.DAY_OF_MONTH),
        now.get(Calendar.HOUR_OF_DAY),
        now.get(Calendar.MINUTE),
        now.get(Calendar.SECOND),
        sign,
        hours,
        minutes);
  }

  private static COSDictionary collectionSchemaField(String displayName, String subtype) {
    COSDictionary field = new COSDictionary();
    field.setString(COSName.N, displayName);
    field.setName(COSName.getPDFName("Subtype"), subtype);
    field.setBoolean(COSName.getPDFName("E"), false);
    return field;
  }

  private static void addEmbeddedPlan(PDDocument doc, COSArray afArray, Map<String, PDComplexFileSpecification> nameMap,
                                      String nameTreeKey, String filename, String mime, String relationship, String checksumMd5, AssociatedFile file,
                                      String description, COSDictionary collectionItem) throws Exception {
    if (!file.hasData() || file.size() == 0) return;
    var prefix = java.util.regex.Pattern.compile("^<\\d+>").matcher(nameTreeKey);
    String folderPrefix = !nameTreeKey.equals(filename) && prefix.find() ? prefix.group() : "";
    String key = PdfMailWriter.uniqueAttachmentKey(nameMap,
        folderPrefix + Common.collectionFilename(nameTreeKey.substring(folderPrefix.length())));
    PDComplexFileSpecification fs = new PDComplexFileSpecification();
    String storedName = key.substring(folderPrefix.length());
    fs.setFile(storedName);
    fs.setFileUnicode(storedName);

    PDEmbeddedFile ef;
    try (var data = file.openData()) {
      ef = new PDEmbeddedFile(doc, data);
    }
    ef.setSubtype(mime);
    ef.setSize(file.size());
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    ef.setCreationDate(now);
    ef.setModDate(now);
    PdfMailWriter.setEmbeddedChecksum(ef, checksumMd5);

    COSDictionary efd = ef.getCOSObject();
    efd.setName(COSName.SUBTYPE, mime);
    efd.setInt(COSName.SIZE, file.size());
    COSDictionary params = (COSDictionary)efd.getDictionaryObject(COSName.PARAMS);
    if (params == null) {
      params = new COSDictionary();
      efd.setItem(COSName.PARAMS, params);
    }
    params.setInt(COSName.SIZE, file.size());

    fs.setEmbeddedFile(ef);
    fs.setEmbeddedFileUnicode(ef);

    COSDictionary fsd = fs.getCOSObject();
    fsd.setName(COSName.getPDFName("AFRelationship"), relationship);
    if (!Common.nz(description).isBlank()) fsd.setString(COSName.DESC, description);
    if (collectionItem != null) fsd.setItem(COSName.getPDFName("CI"), collectionItem);

    nameMap.put(key, fs);
    afArray.add(fs.getCOSObject());
  }

  static COSDictionary topLevelCollectionItemDictionary(PdfMailWriter.CollectionMember member) {
    COSDictionary ci = messageCollectionItemDictionary(member);
    ci.removeItem(COSName.getPDFName("Sent"));
    if (!member.records.isEmpty()) {
      EmailRecord record = member.records.get(0);
      Calendar sent = org.apache.pdfbox.util.DateConverter.toCalendar(Common.nz(record.sentXmp));
      if (sent == null) sent = org.apache.pdfbox.util.DateConverter.toCalendar(Common.nz(record.rawDateHeader));
      if (sent == null) sent = org.apache.pdfbox.util.DateConverter.toCalendar(Common.nz(record.date));
      if (sent != null) ci.setDate(COSName.getPDFName("Sent"), sent);
      ci.setInt(COSName.getPDFName("Size"), PdfMailWriter.messageMetadataSize(record));
    }
    return ci;
  }

  static void rewriteTopLevelCollectionItems(PDDocument doc, List<PdfMailWriter.CollectionMember> members) throws Exception {
    PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
    if (names == null) return;
    PDEmbeddedFilesNameTreeNode efs = names.getEmbeddedFiles();
    if (efs == null || efs.getNames() == null || efs.getNames().isEmpty()) return;
    Map<String, PDComplexFileSpecification> nameMap = new LinkedHashMap<>(efs.getNames());
    Map<String, PdfMailWriter.CollectionMember> memberByKey = new LinkedHashMap<>();
    for (PdfMailWriter.CollectionMember member : members) {
      String key = member.folderId > 0 ? ("<" + member.folderId + ">" + member.filename) : member.filename;
      memberByKey.put(key, member);
    }
    for (Map.Entry<String, PDComplexFileSpecification> entry : nameMap.entrySet()) {
      PdfMailWriter.CollectionMember member = memberByKey.get(entry.getKey());
      if (member == null) continue;
      entry.getValue().getCOSObject().setItem(COSName.getPDFName("CI"), topLevelCollectionItemDictionary(member));
    }
    efs.setNames(nameMap);
    names.setEmbeddedFiles(efs);
    doc.getDocumentCatalog().setNames(names);
  }

  static COSDictionary messageCollectionItemDictionary(PdfMailWriter.CollectionMember member) {
    COSDictionary ci = new COSDictionary();
    ci.setInt(COSName.getPDFName("adobe:Order"), member.orderIndex);
    ci.setString(COSName.getPDFName("Subject"), member.subject);
    ci.setString(COSName.getPDFName("Folder"), member.groupName);
    if (!Common.nz(member.sentDisplay).isBlank()) ci.setString(COSName.getPDFName("Sent"), member.sentDisplay);
    ci.setInt(COSName.getPDFName("Attachments"), member.attachmentCount);
    ci.setInt(COSName.getPDFName("Size"), member.pdfSize());
    if (!member.records.isEmpty()) {
      EmailRecord first = member.records.get(0);
      if (!Common.nz(first.from).isBlank()) ci.setString(COSName.getPDFName("From"), first.from);
      if (!Common.nz(first.to).isBlank()) ci.setString(COSName.getPDFName("To"), first.to);
      if (!Common.nz(first.cc).isBlank()) ci.setString(COSName.getPDFName("Cc"), first.cc);
      if (!Common.nz(first.bcc).isBlank()) ci.setString(COSName.getPDFName("Bcc"), first.bcc);
      if (!Common.nz(first.messageId).isBlank()) ci.setString(COSName.getPDFName("Message-ID"), first.messageId);
    }
    return ci;
  }
}
