package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class PdfFindingAidSupport {
  private PdfFindingAidSupport() {}

  private static String findingAidSubject(EmailRecord record) {
    return record.sourceProblems.isEmpty() ? Common.nz(record.subject)
        : "INCOMPLETE: " + String.join("; ", record.sourceProblems) + " — " + Common.nz(record.subject);
  }

  static List<PdfMailWriter.FindingAidRow> buildFindingAidRows(List<EmailRecord> records) {
    List<PdfMailWriter.FindingAidRow> rows = new ArrayList<>();
    int ordinal = 0;
    for (EmailRecord record : records) {
      if (record == null) continue;
      ordinal++;
      try {
        Common.finalizeRecordMetadata(record);
      } catch (Exception e) {
        throw new RuntimeException("failed to prepare finding aid row", e);
      }
      List<String> groups = PdfCollectionSupport.collectionGroupNames(record);
      rows.add(new PdfMailWriter.FindingAidRow(
          ordinal,
          groups.isEmpty() ? "Messages" : groups.get(0),
          findingAidSentDisplay(Common.nz(record.date)),
          Common.displayAddressHeader(record.from, record.fromAgents),
          Common.displayAddressHeader(record.to, record.toAgents),
          findingAidSubject(record),
          Common.nz(record.messageId),
          Math.max(record.attachmentCount, 0)));
    }
    return rows;
  }

  static List<PdfMailWriter.FindingAidRow> buildFindingAidRowsFromMembers(List<PdfMailWriter.CollectionMember> members) {
    List<PdfMailWriter.FindingAidRow> rows = new ArrayList<>();
    int ordinal = 0;
    for (PdfMailWriter.CollectionMember member : members) {
      if (member == null || member.records.isEmpty()) continue;
      ordinal++;
      EmailRecord record = member.records.get(0);
      rows.add(new PdfMailWriter.FindingAidRow(
          ordinal,
          Common.nz(member.groupName),
          findingAidSentDisplay(Common.nz(member.sentDisplay)),
          Common.displayAddressHeader(record.from, record.fromAgents),
          Common.displayAddressHeader(record.to, record.toAgents),
          member.records.stream().anyMatch(r -> !r.sourceProblems.isEmpty())
              ? "INCOMPLETE: " + member.records.stream().flatMap(r -> r.sourceProblems.stream()).distinct()
                  .collect(java.util.stream.Collectors.joining("; ")) + " — " + Common.nz(member.subject)
              : Common.nz(member.subject),
          Common.nz(record.messageId),
          Math.max(member.attachmentCount, 0)));
    }
    return rows;
  }

  static PdfMailWriter.FindingAidDocument buildCollectionFrontMatter(String profile,
                                                                   PdfMailWriter.CollectionRunSummary summary,
                                                                   String fontPath, boolean incomplete) throws Exception {
    PDDocument document = new PDDocument(org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());
    try {
      PDPage page = new PDPage(new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth()));
      document.addPage(page);
      PDType0Font font = PdfMailWriter.loadUiFont(document, fontPath);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        PdfMailWriter.drawText(content, font, 20f, 46.8f, 565f, "Email Collection");
        PdfMailWriter.drawText(content, font, 11f, 46.8f, 535f, "EA-PDF 1" + profile + " collection");
        PdfMailWriter.drawText(content, font, 11f, 46.8f, 515f, summary.countsText());
        PdfMailWriter.drawText(content, font, 11f, 46.8f, 495f, "Open the contained email PDFs from the Messages pane.");
        if (incomplete) PdfMailWriter.drawText(content, font, 11f, 46.8f, 475f,
            "INCOMPLETE SOURCE — see conversion warnings in the contained email PDFs.");
      }
      ProcessingLimits.accountPages(document.getNumberOfPages());
      return new PdfMailWriter.FindingAidDocument(document, List.of(), null);
    } catch (Exception error) {
      document.close();
      throw error;
    }
  }

  static PdfMailWriter.FindingAidDocument buildFindingAidPdf(List<PdfMailWriter.FindingAidRow> rows, String profile,
                                                             PdfMailWriter.CollectionRunSummary runSummary,
                                                             String fontPath, boolean collectionMode,
                                                             String excelFilename) throws Exception {
    return buildFindingAidPdf(rows, profile, runSummary, fontPath, collectionMode, excelFilename, false);
  }

  static PdfMailWriter.FindingAidDocument buildFindingAidPdf(List<PdfMailWriter.FindingAidRow> rows, String profile,
                                                             PdfMailWriter.CollectionRunSummary runSummary,
                                                             String fontPath, boolean collectionMode,
                                                             String excelFilename, boolean reserveBranding) throws Exception {
    PDDocument doc = new PDDocument(org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());
    PDType0Font font = PdfMailWriter.loadUiFont(doc, fontPath);
    List<PdfMailWriter.FindingAidPlacement> placements = new ArrayList<>();
    PDRectangle excelLinkRect = null;

    final PDRectangle pageRect = new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth());
    final float pageWidth = pageRect.getWidth();
    final float pageHeight = pageRect.getHeight();
    final float margin = 46.8f;
    final float contentWidth = pageWidth - (margin * 2f);
    final float titleSize = 20f;
    final float textSize = 9f;
    final float headerSize = 8.5f;
    final float lineGap = 14f;
    final float tableHeaderHeight = 16f;
    final float minRowHeight = 18f;
    final float tableTopGap = 14f;
    final float bottomLimit = margin + 20f;
    // A subject can use a full row on a continuation page instead of an arbitrary four lines.
    final int subjectMaxLines = Math.max(1, (int) ((pageHeight - margin - 6f - tableTopGap
        - tableHeaderHeight - bottomLimit - 8f) / (textSize + 2f)));
    final boolean includeFolderColumn = findingAidShowsFolderColumn(profile, collectionMode);
    final float[] widths = includeFolderColumn
        ? new float[] {
            contentWidth * 0.06f,
            contentWidth * 0.12f,
            contentWidth * 0.22f,
            contentWidth * 0.18f,
            contentWidth * 0.14f,
            contentWidth * 0.28f
        }
        : new float[] {
            contentWidth * 0.06f,
            contentWidth * 0.25f,
            contentWidth * 0.22f,
            contentWidth * 0.15f,
            contentWidth * 0.32f
        };

    int index = 0;
    int currentPageNumber = 0;
    PDPageContentStream cs = null;

    try {
      while (index < rows.size() || currentPageNumber == 0) {
        if (cs != null) cs.close();
        PDPage page = new PDPage(pageRect);
        doc.addPage(page);
        currentPageNumber++;
        cs = new PDPageContentStream(doc, page);
        float y = pageHeight - margin;

        if (currentPageNumber == 1) {
          // A 20-point heading extends above its baseline; keep it below the banner.
          if (PdfMailWriter.CONFIG.branding.enabled) y -= 14f;
          y = drawFindingAidHeader(cs, font, margin, y, titleSize, textSize, lineGap, profile, runSummary, collectionMode);
        } else {
          y -= 6f;
        }

        y -= tableTopGap;
        SemanticStructure.beginGroup(cs, "Table");
        drawFindingAidTableHeader(cs, font, margin, y, widths, tableHeaderHeight, headerSize, includeFolderColumn);
        y -= tableHeaderHeight;

        while (index < rows.size()) {
          PdfMailWriter.FindingAidRow row = rows.get(index);
          float rowHeight = findingAidRowHeight(font, textSize, widths, row, minRowHeight, includeFolderColumn, subjectMaxLines);
          if (y - rowHeight < bottomLimit) break;
          PdfMailWriter.FindingAidPlacement placement = drawFindingAidRow(cs, font, margin, y, widths, rowHeight, textSize, row, includeFolderColumn, subjectMaxLines);
          placements.add(new PdfMailWriter.FindingAidPlacement(row.ordinal, currentPageNumber, placement.numberRect, placement.subjectRect));
          y -= rowHeight;
          index++;
        }
        SemanticStructure.endGroup(cs);
      }
    } finally {
      if (cs != null) cs.close();
    }

    ProcessingLimits.accountPages(doc.getNumberOfPages());
    return new PdfMailWriter.FindingAidDocument(doc, placements, excelLinkRect);
  }

  static String buildFindingAidRenderHtml(List<PdfMailWriter.FindingAidRow> rows, String profile,
                                          PdfMailWriter.CollectionRunSummary runSummary, boolean collectionMode) {
    boolean includeFolderColumn = findingAidShowsFolderColumn(profile, collectionMode);
    StringBuilder s = new StringBuilder();
    s.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>");
    s.append("<style>");
    s.append("@page { size: letter; margin: 0.65in; }");
    s.append("html, body, body * { font-family: ArchiveFont !important; }");
    s.append("body { font-size: 10pt; color: #222; line-height: 1.35; }");
    s.append("h1 { font-size: 20pt; margin: 0 0 8px; }");
    s.append("p { margin: 0 0 8px; }");
    s.append("table { width: 100%; border-collapse: collapse; margin-top: 12px; table-layout: fixed; }");
    s.append("th, td { border: 1px solid #d7c7ae; padding: 4px 6px; vertical-align: top; text-align: left; word-wrap: break-word; }");
    s.append("th { background: #efe6d7; color: #3f342a; }");
    if (includeFolderColumn) {
      s.append(".num { width: 6%; } .from { width: 22%; } .to { width: 18%; } .date { width: 14%; } .folder { width: 12%; } .subject { width: 28%; }");
    } else {
      s.append(".num { width: 6%; } .from { width: 25%; } .to { width: 22%; } .date { width: 15%; } .subject { width: 32%; }");
    }
    s.append("</style></head><body>");
    s.append("<h1>Finding Aid</h1>");
    s.append("<p>This index summarizes structured fields for messages in this EA-PDF 1")
        .append(Common.escapeHtml(profile))
        .append(collectionMode ? " collection." : " archive.")
        .append("</p>");
    s.append("<p><strong>Harvested email(s):</strong> ").append(Common.escapeHtml(runSummary.harvestedEmails)).append("</p>");
    s.append("<p><strong>Run stats:</strong> ").append(runSummary.countsText()).append("</p>");
    s.append("<p><strong>Generated:</strong> ").append(Common.escapeHtml(runSummary.generatedAt)).append("</p>");
    appendFindingAidTable(s, rows, includeFolderColumn);
    s.append("</body></html>");
    return Common.stripInvalidXml(s.toString());
  }

  static void appendFindingAidTable(StringBuilder s, List<PdfMailWriter.FindingAidRow> rows, boolean includeFolderColumn) {
    s.append("<table><thead><tr>");
    s.append("<th class=\"num\">#</th>");
    if (includeFolderColumn) s.append("<th class=\"folder\">Folder</th>");
    s.append("<th class=\"from\">From</th>");
    s.append("<th class=\"to\">To</th>");
    s.append("<th class=\"date\">Date</th>");
    s.append("<th class=\"subject\">Subject</th>");
    s.append("</tr></thead><tbody>");
    for (PdfMailWriter.FindingAidRow row : rows) {
      s.append("<tr>");
      s.append("<td>").append(row.ordinal).append("</td>");
      if (includeFolderColumn) s.append("<td>").append(Common.escapeHtml(row.folder)).append("</td>");
      s.append("<td>").append(Common.escapeHtml(row.from)).append("</td>");
      s.append("<td>").append(Common.escapeHtml(row.to)).append("</td>");
      s.append("<td>").append(Common.escapeHtml(row.sent)).append("</td>");
      s.append("<td>").append(Common.escapeHtml(row.subject)).append("</td>");
      s.append("</tr>");
    }
    s.append("</tbody></table>");
  }

  static void writeFindingAidSpreadsheet(Path pdfOut, List<PdfMailWriter.FindingAidRow> rows, String profile) throws Exception {
    boolean collection = List.of("c", "ci").contains(profile);
    if (!(PdfMailWriter.CONFIG.findingAid.excelEnabled || collection && PdfMailWriter.CONFIG.findingAid.embedExcel)) return;
    if (pdfOut == null || rows == null) return;
    Path xlsxOut = findingAidSpreadsheetPath(pdfOut);
    Files.createDirectories(xlsxOut.getParent() == null ? Paths.get(".") : xlsxOut.getParent());
    OutputTransaction.write(xlsxOut, temporary -> {
      try (var output = Files.newOutputStream(temporary)) { writeFindingAidSpreadsheet(rows, profile, output); }
    });
  }

  static Path findingAidSpreadsheetPath(Path pdfOut) {
    String stem = pdfOut.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
    return pdfOut.resolveSibling(stem + "_index.xlsx");
  }

  static String findingAidPdfFilename(Path pdfOut) {
    return Common.collectionFilename(pdfOut.getFileName().toString().replaceFirst("(?i)\\.pdf$", "") + "_finding_aid.pdf");
  }

  static byte[] buildFindingAidSidecar(Path pdfOut, List<PdfMailWriter.FindingAidRow> rows, String profile,
                                      PdfMailWriter.CollectionRunSummary summary, String fontPath, String iccPath) throws Exception {
    // Collection auxiliaries are separate files: neither the collection nor this
    // ordinary index PDF contains a spreadsheet or a synthetic email wrapper.
    var findingAid = buildFindingAidPdf(rows, profile, summary, fontPath, true,
        findingAidSpreadsheetPath(pdfOut).getFileName().toString());
    try (PDDocument document = findingAid.document) {
      document.setVersion(2.0f);
      document.getDocument().setVersion(2.0f);
      var catalog = document.getDocumentCatalog();
      document.getDocument().getTrailer().removeItem(COSName.INFO);
      String now = Common.xml(Common.xmpDateNowUtc());
      String product = Common.xml(Common.productLabel());
      String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
          + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
          + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
          + " xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\""
          + " xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">"
          + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Finding Aid</rdf:li></rdf:Alt></dc:title>"
          + "<dc:format>application/pdf</dc:format><pdfaid:part>4</pdfaid:part><pdfaid:rev>2020</pdfaid:rev>"
          + "<pdf:Producer>" + product + "</pdf:Producer><xmp:CreatorTool>" + product + "</xmp:CreatorTool>"
          + "<xmp:CreateDate>" + now + "</xmp:CreateDate><xmp:ModifyDate>" + now + "</xmp:ModifyDate>"
          + "<xmp:MetadataDate>" + now + "</xmp:MetadataDate></rdf:Description>"
          + PdfMailWriter.pdfUaIdentification() + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
      PdfMailWriter.setCompressedMetadata(document, xmp);
      if (PdfMailWriter.CONFIG.branding.enabled) PdfMailWriter.drawBrandingBanner(document, fontPath, "1" + profile);
      try (var icc = Files.newInputStream(Path.of(iccPath))) {
        var intent = new org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent(document, icc);
        intent.setInfo("sRGB IEC61966-2.1");
        intent.setOutputCondition("sRGB IEC61966-2.1");
        intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
        intent.setRegistryName("http://www.color.org");
        catalog.addOutputIntent(intent);
      }
      PdfMailWriter.normalizePageContentStreams(document);
      if (PdfMailWriter.pdfUaFeaturesEnabled()) {
        var preferences = new org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences(new COSDictionary());
        preferences.setDisplayDocTitle(true);
        catalog.setViewerPreferences(preferences);
        PdfMailWriter.applyCatalogAccessibility(catalog, preferences);
        COSDictionary tree = new COSDictionary();
        tree.setName(COSName.TYPE, "StructTreeRoot");
        tree.setItem(COSName.ROLE_MAP, new COSDictionary());
        COSDictionary structure = PdfMailWriter.structElem("Document", tree, document.getPage(0));
        SemanticStructure.attach(document, tree, structure, null, 0, document.getNumberOfPages() - 1);
        tree.setItem(COSName.K, PdfDocumentParts.indirect(document, structure));
        PdfMailWriter.addLogicalStructureNamespaces(document, tree);
        catalog.getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, PdfDocumentParts.indirect(document, tree));
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      PdfFontMetadata.save(document, output);
      ProcessingLimits.checkOutput(output.size());
      return output.toByteArray();
    }
  }

  static String premisFilename(Path pdfOut) {
    if (pdfOut == null || pdfOut.getFileName() == null) return "premis.xml";
    String stem = pdfOut.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
    return stem + "_premis.xml";
  }

  static Path premisSidecarPath(Path pdfOut) {
    if (pdfOut == null) return Paths.get(premisFilename(null));
    return pdfOut.resolveSibling(premisFilename(pdfOut));
  }

  static void writePremisSidecar(Path pdfOut, byte[] premisBytes) throws Exception {
    if (pdfOut == null || premisBytes == null || premisBytes.length == 0) return;
    Path sidecar = premisSidecarPath(pdfOut);
    Files.createDirectories(sidecar.getParent() == null ? Paths.get(".") : sidecar.getParent());
    OutputTransaction.write(sidecar, temporary -> Files.write(temporary, premisBytes));
  }

  static PDComplexFileSpecification embedSpreadsheetFile(PDDocument doc, Path pdfOut, List<PdfMailWriter.FindingAidRow> rows,
                                                         String profile, boolean addFirst) throws Exception {
    if (rows == null) return null;
    Path staged = Files.createTempFile("eapdf-index-", ".xlsx");
    try {
      try (var output = Files.newOutputStream(staged)) { writeFindingAidSpreadsheet(rows, profile, output); }
      var digest = java.security.MessageDigest.getInstance("MD5");
      try (var input = new java.security.DigestInputStream(Files.newInputStream(staged), digest)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
      try (var input = Files.newInputStream(staged)) {
        return embedStandaloneCatalogStream(doc, findingAidSpreadsheetPath(pdfOut).getFileName().toString(), input,
            Math.toIntExact(Files.size(staged)), java.util.HexFormat.of().formatHex(digest.digest()), "Finding aid spreadsheet index",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Data", addFirst);
      }
    } finally { Files.deleteIfExists(staged); }
  }

  static PDComplexFileSpecification embedStandaloneCatalogBytes(PDDocument doc, String filename, byte[] bytes, String description,
                                                                String mime, String relationship, boolean addFirst) throws Exception {
    if (bytes == null || bytes.length == 0 || Common.nz(filename).isBlank()) return null;
    return embedStandaloneCatalogStream(doc, filename, new ByteArrayInputStream(bytes), bytes.length,
        Common.md5Hex(bytes), description, mime, relationship, addFirst);
  }

  private static PDComplexFileSpecification embedStandaloneCatalogStream(PDDocument doc, String filename, java.io.InputStream input,
      int size, String checksum, String description, String mime, String relationship, boolean addFirst) throws Exception {
    PDDocumentCatalog cat = doc.getDocumentCatalog();
    COSDictionary catd = cat.getCOSObject();
    COSArray afArray = (COSArray) catd.getDictionaryObject(COSName.getPDFName("AF"));
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
    Map<String, PDComplexFileSpecification> nameMap =
        efs != null && efs.getNames() != null ? new LinkedHashMap<>(efs.getNames()) : new LinkedHashMap<>();
    if (efs == null) efs = new PDEmbeddedFilesNameTreeNode();

    boolean collection = catd.containsKey(COSName.getPDFName("Collection"));
    String key = PdfMailWriter.uniqueAttachmentKey(nameMap, collection ? Common.collectionFilename(filename) : filename);
    PDComplexFileSpecification fs = new PDComplexFileSpecification();
    fs.setFile(collection ? key : Common.portablePdfFilename(filename));
    fs.setFileUnicode(collection ? key : Common.portablePdfFilename(filename));

    PDEmbeddedFile ef = new PDEmbeddedFile(doc, input, COSName.FLATE_DECODE);
    ef.setSubtype(mime);
    ef.setSize(size);
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    ef.setCreationDate(now);
    ef.setModDate(now);
    PdfMailWriter.setEmbeddedChecksum(ef, checksum);
    fs.setEmbeddedFile(ef);
    fs.setEmbeddedFileUnicode(ef);

    COSDictionary fsd = fs.getCOSObject();
    fsd.setName(COSName.getPDFName("AFRelationship"), relationship);
    fsd.setString(COSName.DESC, description);

    if (addFirst) {
      Map<String, PDComplexFileSpecification> reordered = new LinkedHashMap<>();
      reordered.put(key, fs);
      reordered.putAll(nameMap);
      nameMap = reordered;
      COSArray newAf = new COSArray();
      newAf.add(fs.getCOSObject());
      for (int i = 0; i < afArray.size(); i++) newAf.add(afArray.get(i));
      catd.setItem(COSName.getPDFName("AF"), newAf);
    } else {
      nameMap.put(key, fs);
      afArray.add(fs.getCOSObject());
    }

    efs.setNames(nameMap);
    names.setEmbeddedFiles(efs);
    cat.setNames(names);
    return fs;
  }

  static void rewriteAttachmentLaunchLinks(PDDocument doc, Map<String, PDComplexFileSpecification> filesByToken) throws Exception {
    if (doc == null || filesByToken == null || filesByToken.isEmpty()) return;
    for (PDPage page : doc.getPages()) {
      List<PDAnnotation> annotations = page.getAnnotations();
      if (annotations == null || annotations.isEmpty()) continue;
      List<PDAnnotation> rewritten = new ArrayList<>();
      for (PDAnnotation annotation : annotations) {
        if (!(annotation instanceof PDAnnotationLink link)) {
          rewritten.add(annotation);
          continue;
        }
        if (!(link.getAction() instanceof PDActionURI uriAction)) {
          rewritten.add(annotation);
          continue;
        }
        String uri = Common.nz(uriAction.getURI());
        if (!uri.startsWith(PdfMailWriter.EMBEDDED_BADGE_LINK_PREFIX) && !uri.startsWith(PdfMailWriter.EMBEDDED_LINK_PREFIX)) {
          rewritten.add(annotation);
          continue;
        }
        String token;
        if (uri.startsWith(PdfMailWriter.EMBEDDED_BADGE_LINK_PREFIX)) token = uri.substring(PdfMailWriter.EMBEDDED_BADGE_LINK_PREFIX.length());
        else token = uri.substring(PdfMailWriter.EMBEDDED_LINK_PREFIX.length());
        try {
          token = URLDecoder.decode(token, StandardCharsets.UTF_8.toString());
        } catch (Exception ignored) {
        }
        PDComplexFileSpecification fileSpec = filesByToken.get(token);
        if (fileSpec == null) {
          rewritten.add(annotation);
          continue;
        }
        String contents = Common.nz(fileSpec.getFileUnicode());
        if (contents.isBlank()) contents = Common.nz(fileSpec.getFile());
        PDAnnotationFileAttachment fileAttachment =
            PdfMailWriter.inlineAttachmentBadgeAnnotation(doc, link.getRectangle(), fileSpec, contents);
        if (!Common.nz(link.getContents()).isBlank()) {
          fileAttachment.setContents(link.getContents().startsWith("Contains original attachment: ")
              ? "Open embedded source email: " + contents + "; " + link.getContents()
              : link.getContents());
        }
        COSName structParent = COSName.getPDFName("StructParent");
        if (link.getCOSObject().containsKey(structParent)) {
          COSBase parent = link.getCOSObject().getItem(structParent);
          COSDictionary original = link.getCOSObject();
          original.clear();
          original.addAll(fileAttachment.getCOSObject());
          original.setItem(structParent, parent);
          fileAttachment = new PDAnnotationFileAttachment(original);
        }
        rewritten.add(fileAttachment);
      }
      page.setAnnotations(rewritten);
    }
  }

  static void configureLinkAnnotation(PDAnnotationLink annotation, PDRectangle rect) {
    if (annotation == null || rect == null) return;
    annotation.setRectangle(rect);
    PDBorderStyleDictionary border = new PDBorderStyleDictionary();
    border.setWidth(0);
    annotation.setBorderStyle(border);
    annotation.setPrinted(true);
  }

  static String findingAidSentDisplay(String value) {
    return Common.nz(value).trim();
  }

  private static boolean findingAidShowsFolderColumn(String profile, boolean collectionMode) {
    String normalized = Common.nz(profile).trim().toLowerCase(Locale.ROOT);
    if ("c".equals(normalized) || "ci".equals(normalized)) return true;
    if ("m".equals(normalized) || "mi".equals(normalized)) return false;
    return collectionMode;
  }

  private static boolean findingAidShowsFolderColumn(String profile) {
    return findingAidShowsFolderColumn(profile, false);
  }

  private static String[] findingAidHeaders(boolean includeFolderColumn) {
    if (includeFolderColumn) return new String[] {"#", "Folder", "From", "To", "Date", "Subject"};
    return new String[] {"#", "From", "To", "Date", "Subject"};
  }

  private static float drawFindingAidHeader(PDPageContentStream cs, PDType0Font font, float margin, float y, float titleSize,
                                            float textSize, float lineGap, String profile,
                                            PdfMailWriter.CollectionRunSummary runSummary,
                                            boolean collectionMode) throws Exception {
    beginText(cs, font, titleSize, margin, y, "Finding Aid");
    y -= 24f;
    beginText(cs, font, textSize, margin, y,
        "This index summarizes structured fields for messages in this EA-PDF 1" + profile + (collectionMode ? " collection." : " archive."));
    y -= lineGap;
    beginText(cs, font, textSize, margin, y, "Harvested email(s): " + Common.nz(runSummary.harvestedEmails));
    y -= lineGap;
    beginText(cs, font, textSize, margin, y,
        "Run stats: " + runSummary.countsText());
    y -= lineGap;
    beginText(cs, font, textSize, margin, y, "Generated: " + Common.nz(runSummary.generatedAt));

    return y;
  }

  private static void drawFindingAidTableHeader(PDPageContentStream cs, PDType0Font font, float margin, float topY,
                                                float[] widths, float height, float fontSize,
                                                boolean includeFolderColumn) throws Exception {
    float x = margin;
    String[] headers = findingAidHeaders(includeFolderColumn);
    cs.setStrokingColor(new java.awt.Color(215, 199, 174));
    cs.setNonStrokingColor(new java.awt.Color(239, 230, 215));
    SemanticStructure.beginGroup(cs, "Artifact");
    cs.addRect(margin, topY - height, sum(widths), height);
    cs.fillAndStroke();
    SemanticStructure.endGroup(cs);
    cs.setNonStrokingColor(new java.awt.Color(63, 52, 42));
    SemanticStructure.beginGroup(cs, "TR");
    for (int i = 0; i < headers.length; i++) {
      SemanticStructure.beginGroup(cs, "TH");
      beginText(cs, font, fontSize, x + 3f, topY - 11.5f, headers[i]);
      SemanticStructure.endGroup(cs);
      x += widths[i];
      if (i < headers.length - 1) PdfMailWriter.drawRule(cs, x, topY, x, topY - height);
    }
    SemanticStructure.endGroup(cs);
    PdfMailWriter.drawRule(cs, margin, topY - height, margin + sum(widths), topY - height);
  }

  private static PdfMailWriter.FindingAidPlacement drawFindingAidRow(PDPageContentStream cs, PDType0Font font, float margin,
                                                                     float topY, float[] widths, float height,
                                                                     float fontSize, PdfMailWriter.FindingAidRow row,
                                                                     boolean includeFolderColumn, int subjectMaxLines) throws Exception {
    final float paddingX = 3f;
    final float paddingTop = 4f;
    final float lineHeight = fontSize + 2f;
    float x = margin;
    List<List<String>> values = new ArrayList<>();
    int widthIndex = 1;
    values.add(List.of(Integer.toString(row.ordinal)));
    if (includeFolderColumn) {
      values.add(wrapFindingAidCell(font, fontSize, row.folder, widths[widthIndex++] - (paddingX * 2f), 2));
    }
    values.add(wrapFindingAidCell(font, fontSize, row.from, widths[widthIndex++] - (paddingX * 2f), 3));
    values.add(wrapFindingAidCell(font, fontSize, row.to, widths[widthIndex++] - (paddingX * 2f), 3));
    values.add(wrapFindingAidCell(font, fontSize, row.sent, widths[widthIndex++] - (paddingX * 2f), 2));
    values.add(wrapFindingAidCell(font, fontSize, row.subject, widths[widthIndex] - (paddingX * 2f), subjectMaxLines));
    cs.setStrokingColor(new java.awt.Color(215, 199, 174));
    PdfMailWriter.drawRule(cs, margin, topY - height, margin + sum(widths), topY - height);
    PDRectangle numberRect = null;
    PDRectangle subjectRect = null;
    SemanticStructure.beginGroup(cs, "TR");
    for (int i = 0; i < values.size(); i++) {
      SemanticStructure.beginGroup(cs, "TD");
      float textY = topY - paddingTop - fontSize;
      for (String line : values.get(i)) {
        beginText(cs, font, fontSize, x + paddingX, textY, line);
        textY -= lineHeight;
      }
      SemanticStructure.endGroup(cs);
      if (i == 0) numberRect = new PDRectangle(x + 1f, topY - height + 1f, widths[i] - 2f, height - 2f);
      if (i == values.size() - 1) subjectRect = new PDRectangle(x + 1f, topY - height + 1f, widths[i] - 2f, height - 2f);
      x += widths[i];
      if (i < values.size() - 1) PdfMailWriter.drawRule(cs, x, topY, x, topY - height);
    }
    SemanticStructure.endGroup(cs);
    return new PdfMailWriter.FindingAidPlacement(row.ordinal, 0, numberRect, subjectRect);
  }

  private static float findingAidRowHeight(PDType0Font font, float fontSize, float[] widths,
                                           PdfMailWriter.FindingAidRow row, float minRowHeight,
                                           boolean includeFolderColumn, int subjectMaxLines) throws Exception {
    final float paddingTop = 4f;
    final float paddingBottom = 4f;
    final float paddingX = 3f;
    final float lineHeight = fontSize + 2f;
    int maxLines = 1;
    int widthIndex = 1;
    if (includeFolderColumn) {
      maxLines = Math.max(maxLines, wrapFindingAidCell(font, fontSize, row.folder, widths[widthIndex++] - (paddingX * 2f), 2).size());
    }
    maxLines = Math.max(maxLines, wrapFindingAidCell(font, fontSize, row.from, widths[widthIndex++] - (paddingX * 2f), 3).size());
    maxLines = Math.max(maxLines, wrapFindingAidCell(font, fontSize, row.to, widths[widthIndex++] - (paddingX * 2f), 3).size());
    maxLines = Math.max(maxLines, wrapFindingAidCell(font, fontSize, row.sent, widths[widthIndex++] - (paddingX * 2f), 2).size());
    maxLines = Math.max(maxLines, wrapFindingAidCell(font, fontSize, row.subject, widths[widthIndex] - (paddingX * 2f), subjectMaxLines).size());
    return Math.max(minRowHeight, paddingTop + paddingBottom + (maxLines * lineHeight));
  }

  private static List<String> wrapFindingAidCell(PDType0Font font, float fontSize, String text, float maxWidth,
                                                 int maxLines) throws Exception {
    String remaining = PdfMailWriter.sanitizePdfText(font, Common.nz(text)).trim().replaceAll("\\s+", " ");
    if (remaining.isBlank()) return List.of("");
    List<String> lines = new ArrayList<>();
    while (!remaining.isEmpty()) {
      if (PdfMailWriter.textWidth(font, fontSize, remaining) <= maxWidth) {
        lines.add(remaining);
        break;
      }
      boolean lastLine = lines.size() == maxLines - 1;
      String suffix = lastLine ? "..." : "";
      float available = maxWidth - PdfMailWriter.textWidth(font, fontSize, suffix);
      int end = 0;
      int lastSpace = -1;
      float used = 0;
      while (end < remaining.length()) {
        int point = remaining.codePointAt(end);
        float width = PdfMailWriter.textWidth(font, fontSize, new String(Character.toChars(point)));
        if (used + width > available) break;
        if (point == ' ') lastSpace = end;
        used += width;
        end += Character.charCount(point);
      }
      if (!lastLine && lastSpace > 0) end = lastSpace;
      if (end == 0) end = Character.charCount(remaining.codePointAt(0));
      lines.add(remaining.substring(0, end).stripTrailing() + suffix);
      if (lastLine) break;
      remaining = remaining.substring(end).stripLeading();
    }
    return lines;
  }

  private static void beginText(PDPageContentStream cs, PDType0Font font, float fontSize, float x, float y,
                                String text) throws Exception {
    PdfMailWriter.drawText(cs, font, fontSize, x, y, text);
  }

  private static float sum(float[] values) {
    float total = 0f;
    for (float value : values) total += value;
    return total;
  }

  static byte[] buildFindingAidSpreadsheetBytes(List<PdfMailWriter.FindingAidRow> rows, String profile) throws Exception {
    if (rows == null) return null;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writeFindingAidSpreadsheet(rows, profile, out);
      return out.toByteArray();
    }
  }

  private static void writeFindingAidSpreadsheet(List<PdfMailWriter.FindingAidRow> rows, String profile, java.io.OutputStream out) throws Exception {
    try (var wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(128)) {
      wb.setCompressTempFiles(true);
      // POI defaults to unconditional ZIP64 local headers, which Calc 24.2
      // rejects even for small workbooks. Preserve large-entry support using
      // the ZIP64 compatibility layout instead.
      wb.setZip64Mode(org.apache.commons.compress.archivers.zip.Zip64Mode.AlwaysWithCompatibility);
      boolean includeFolderColumn = findingAidShowsFolderColumn(profile);
      Sheet sheet = wb.createSheet("Finding Aid");
      SpreadsheetTextWriter text = new SpreadsheetTextWriter(wb, SpreadsheetVersion.EXCEL2007.getMaxRows());
      int rowIndex = 0;
      Row header = sheet.createRow(rowIndex++);
      String[] headers = includeFolderColumn
          ? new String[] {"#", "Folder", "From", "To", "Date", "Subject", "Attachments", "Profile"}
          : new String[] {"#", "From", "To", "Date", "Subject", "Attachments", "Profile"};
      for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
      for (PdfMailWriter.FindingAidRow row : rows) {
        Row x = sheet.createRow(rowIndex++);
        int col = 0;
        x.createCell(col++).setCellValue(row.ordinal);
        if (includeFolderColumn) text.setCell(x, col++, row.ordinal, "Folder", row.folder);
        text.setCell(x, col++, row.ordinal, "From", row.from);
        text.setCell(x, col++, row.ordinal, "To", row.to);
        text.setCell(x, col++, row.ordinal, "Date", row.sent);
        text.setCell(x, col++, row.ordinal, "Subject", row.subject);
        x.createCell(col++).setCellValue(row.attachmentCount);
        x.createCell(col).setCellValue("1" + profile);
      }
      // Fixed readable widths avoid keeping every row/font layout in memory.
      for (int i = 0; i < headers.length; i++)
        sheet.setColumnWidth(i, (headers[i].equals("Subject") ? 80 : headers[i].equals("#") ? 10 : 32) * 256);
      wb.write(out);
    }
  }

  /** Stores oversized index values in ordered, lossless continuation cells. */
  static final class SpreadsheetTextWriter {
    private static final int MAX_TEXT = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
    private final Workbook workbook;
    private final int sheetRowLimit;
    private Sheet continuation;
    private int sheetNumber;

    SpreadsheetTextWriter(Workbook workbook, int sheetRowLimit) {
      if (sheetRowLimit < 2 || sheetRowLimit > SpreadsheetVersion.EXCEL2007.getMaxRows()) {
        throw new IllegalArgumentException("Continuation sheets require a header and at least one data row.");
      }
      this.workbook = workbook;
      this.sheetRowLimit = sheetRowLimit;
    }

    void setCell(Row indexRow, int column, int ordinal, String field, String value) {
      var cell = indexRow.createCell(column);
      if (value == null || value.length() <= MAX_TEXT) {
        cell.setCellValue(value);
        return;
      }
      int offset = 0, part = 1;
      while (offset < value.length()) {
        if (continuation == null || continuation.getLastRowNum() + 1 >= sheetRowLimit) {
          continuation = workbook.createSheet("Continuation " + ++sheetNumber);
          Row header = continuation.createRow(0);
          String[] labels = {"Email #", "Field", "Part", "Text"};
          for (int i = 0; i < labels.length; i++) header.createCell(i).setCellValue(labels[i]);
          continuation.createFreezePane(0, 1);
          continuation.setColumnWidth(0, 12 * 256);
          continuation.setColumnWidth(1, 16 * 256);
          continuation.setColumnWidth(2, 10 * 256);
          continuation.setColumnWidth(3, 80 * 256);
        }
        int rowNumber = continuation.getLastRowNum() + 1;
        if (part == 1) {
          String address = "'" + continuation.getSheetName() + "'!D" + (rowNumber + 1);
          cell.setCellValue("Full text: " + address + " (numbered parts; email #" + ordinal + ", " + field + ")");
          var link = workbook.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
          link.setAddress(address);
          cell.setHyperlink(link);
        }
        int end = (int) Math.min((long) offset + MAX_TEXT, value.length());
        // POI counts UTF-16 units. Do not separate a supplementary character.
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
            && Character.isLowSurrogate(value.charAt(end))) end--;
        Row chunk = continuation.createRow(rowNumber);
        chunk.createCell(0).setCellValue(ordinal);
        chunk.createCell(1).setCellValue(field);
        chunk.createCell(2).setCellValue(part++);
        chunk.createCell(3).setCellValue(value.substring(offset, end));
        offset = end;
      }
    }
  }

}
