package eapdf;
import java.util.*;
import java.io.IOException;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;


import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
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

final class PdfAttachmentSupport {
  private PdfAttachmentSupport() {}
  static final String EMBEDDED_LINK_PREFIX = "https://eapdf.local/attachment/";
  static final String EMBEDDED_BADGE_LINK_PREFIX = EMBEDDED_LINK_PREFIX + "badge/";

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
    String key = uniqueAttachmentKey(nameMap, collection ? Common.collectionFilename(filename) : filename);
    PDComplexFileSpecification fs = new PDComplexFileSpecification();
    fs.setFile(collection ? key : Common.portablePdfFilename(filename));
    fs.setFileUnicode(collection ? key : Common.portablePdfFilename(filename));

    PDEmbeddedFile ef = new PDEmbeddedFile(doc, input, COSName.FLATE_DECODE);
    ef.setSubtype(mime);
    ef.setSize(size);
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    ef.setCreationDate(now);
    ef.setModDate(now);
    setEmbeddedChecksum(ef, checksum);
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
        if (!uri.startsWith(EMBEDDED_BADGE_LINK_PREFIX) && !uri.startsWith(EMBEDDED_LINK_PREFIX)) {
          rewritten.add(annotation);
          continue;
        }
        String token;
        if (uri.startsWith(EMBEDDED_BADGE_LINK_PREFIX)) token = uri.substring(EMBEDDED_BADGE_LINK_PREFIX.length());
        else token = uri.substring(EMBEDDED_LINK_PREFIX.length());
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
            inlineAttachmentBadgeAnnotation(doc, link.getRectangle(), fileSpec, contents);
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
static class ArchiveEmbeddedFilePlan {
  final List<EmailRecord> owners = new ArrayList<>();
  final EmailRecord record;
  final AssociatedFile file;
  final String requestedName;
  final String filename;
  final String mime;
  final String relationship;
  final String checksumMd5;

  ArchiveEmbeddedFilePlan(EmailRecord record, AssociatedFile file, String requestedName, String filename, String mime, String relationship, String checksumMd5) {
    this.record = record;
    this.file = file;
    this.requestedName = requestedName;
    this.owners.add(record);
    this.filename = filename;
    this.mime = mime;
    this.relationship = relationship;
    this.checksumMd5 = checksumMd5;
  }
}
static class ArchivePendingFile {
  final EmailRecord record;
  final AssociatedFile file;
  final String requestedName;

  ArchivePendingFile(EmailRecord record, AssociatedFile file, String requestedName) {
    this.record = record;
    this.file = file;
    this.requestedName = requestedName;
  }
}
static class EmbeddedFileBindings {
  final Map<EmailRecord, PDComplexFileSpecification> sourceFiles = new IdentityHashMap<>();
  final Map<String, PDComplexFileSpecification> filesByToken = new LinkedHashMap<>();
}
static String normalizeAfRelationship(String s) {
  String t = Common.nz(s).trim();
  if (t.equals("Source") || t.equals("Data") || t.equals("Alternative") || t.equals("Supplement") || t.equals("EncryptedPayload") || t.equals("FormData") || t.equals("Schema") || t.equals("Unspecified")) return t;
  return "Data";
}
static String numberedAttachmentKey(Map<String, ?> names, String name, int seq, int total) {
  name = Common.portablePdfFilename(name);
  String base = name, ext = "";
  int dot = name.lastIndexOf('.');
  if (dot > 0) {
    base = name.substring(0, dot);
    ext = name.substring(dot);
  }
  int width = Math.max(2, Integer.toString(total).length());
  String numberFormat = "%0" + width + "d";
  String candidate = base + "_" + String.format(numberFormat, seq) + ext;
  if (!names.containsKey(candidate)) return candidate;
  int n = seq;
  while (true) {
    n++;
    candidate = base + "_" + String.format(numberFormat, n) + ext;
    if (!names.containsKey(candidate)) return candidate;
  }
}
static String uniqueAttachmentKey(Map<String, ?> names, String name) {
  name = Common.portablePdfFilename(name);
  String normalizedName = name;
  if (names.keySet().stream().noneMatch(key -> Common.filenameCaseKey(key).equals(Common.filenameCaseKey(normalizedName)))) return name;
  String base = name, ext = "";
  int dot = name.lastIndexOf('.');
  if (dot > 0) {
    base = name.substring(0, dot);
    ext = name.substring(dot);
  }
  int n = 2;
  while (true) {
    String candidate = base + "_" + n + ext;
    if (names.keySet().stream().noneMatch(key -> Common.filenameCaseKey(key).equals(Common.filenameCaseKey(candidate)))) return candidate;
    n++;
  }
}
static String reserveAttachmentKey(Set<String> normalizedNames, String name) {
  name = Common.portablePdfFilename(name);
  if (normalizedNames.add(Common.filenameCaseKey(name))) return name;
  int dot = name.lastIndexOf('.');
  String base = dot > 0 ? name.substring(0, dot) : name;
  String extension = dot > 0 ? name.substring(dot) : "";
  for (int suffix = 2; ; suffix++) {
    String candidate = base + "_" + suffix + extension;
    if (normalizedNames.add(Common.filenameCaseKey(candidate))) return candidate;
  }
}
static List<ArchiveEmbeddedFilePlan> buildArchiveEmbeddedFilePlans(List<EmailRecord> records, java.util.function.Function<EmailRecord, List<AssociatedFile>> embeddedFiles) throws Exception {
  List<ArchivePendingFile> pending = new ArrayList<>();
  int syntheticCounter = 0;

  for (EmailRecord r : records) {
    for (AssociatedFile af : embeddedFiles.apply(r)) {
      if (af == null || !af.hasData() || af.size() == 0) continue;
      String requestedName = Common.nz(af.filename).isBlank()
          ? ("attachment_" + String.format("%03d", ++syntheticCounter) + Common.extensionForMime(af.mime, af.filename))
          : Common.nz(af.filename);
      pending.add(new ArchivePendingFile(r, af, requestedName));
    }

    boolean hasSource = false;
    for (AssociatedFile af : r.associatedFiles) {
      if ("Source".equals(Common.nz(af.afRelationship))) {
        hasSource = true;
        break;
      }
    }
    if (!hasSource && r.embedSourceMessage) {
      AssociatedFile af = new AssociatedFile();
      af.filename = Common.nz(r.sourceFilename).isBlank() ? "message.eml" : r.sourceFilename;
      af.mime = Common.nz(r.sourceMime).isBlank() ? "message/rfc822" : r.sourceMime;
      af.afRelationship = Common.nz(r.afRelationship).isBlank() ? "Source" : r.afRelationship;
      af.bytes = r.sourceBytes == null ? Common.synthesizeEml(r) : r.sourceBytes;
      af.description = Common.sourceDescription(r);
      af.ensureChecksums();
      pending.add(new ArchivePendingFile(r, af, af.filename));
    }
  }

  Map<String, Integer> requestedTotals = new LinkedHashMap<>();
  for (ArchivePendingFile item : pending) {
    requestedTotals.put(item.requestedName, requestedTotals.getOrDefault(item.requestedName, 0) + 1);
  }
  Map<String, Integer> requestedSeen = new LinkedHashMap<>();
  Map<String, Boolean> usedNames = new LinkedHashMap<>();
  Set<String> usedCaseNames = new HashSet<>();
  Map<String, ArchiveEmbeddedFilePlan> embeddedContentKeys = new LinkedHashMap<>();
  List<ArchiveEmbeddedFilePlan> out = new ArrayList<>();
  for (ArchivePendingFile item : pending) {
    int total = requestedTotals.getOrDefault(item.requestedName, 1);
    int seq = requestedSeen.getOrDefault(item.requestedName, 0) + 1;
    requestedSeen.put(item.requestedName, seq);
    String uniqueName = total > 1
        ? numberedAttachmentKey(usedNames, item.requestedName, seq, total)
        : reserveAttachmentKey(usedCaseNames, item.requestedName);
    usedNames.put(uniqueName, Boolean.TRUE);
    usedCaseNames.add(Common.filenameCaseKey(uniqueName));
    String mime = embeddedAssetContentType(item.file, uniqueName);
    String rel = normalizeAfRelationship(item.file.afRelationship);
    item.file.ensureChecksums();
    String checksumMd5 = item.file.checksumMd5;
    String checksumSha256 = item.file.checksumSha256;
    if (!"Source".equals(rel)) {
      String contentKey = rel + "|" + mime + "|" + checksumSha256;
      ArchiveEmbeddedFilePlan existing = embeddedContentKeys.get(contentKey);
      if (existing != null) { existing.owners.add(item.record); continue; }
    }
    ArchiveEmbeddedFilePlan plan = new ArchiveEmbeddedFilePlan(item.record, item.file, item.requestedName, uniqueName, mime, rel, checksumMd5);
    out.add(plan);
    if (!"Source".equals(rel)) embeddedContentKeys.put(rel + "|" + mime + "|" + checksumSha256, plan);
  }
  return out;
}
static PDAppearanceDictionary buildAttachmentBadgeAppearance(PDDocument doc, float width, float height) throws Exception {
  float w = Math.max(8f, Math.min(13f, width));
  float h = Math.max(6f, Math.min(10f, height));
  PDAppearanceStream appearance = new PDAppearanceStream(doc);
  appearance.setResources(new PDResources());
  appearance.setBBox(new PDRectangle(width, height));
  try (PDPageContentStream cs = new PDPageContentStream(doc, appearance)) {
    cs.transform(org.apache.pdfbox.util.Matrix.getTranslateInstance(0, Math.max(0f, height - h)));
    float left = 0.75f;
    float right = w - 0.75f;
    float top = h - 0.75f;
    float bottom = 0.75f;
    float notch = Math.min(Math.max(2.6f, h * 0.32f), Math.max(2.6f, w * 0.22f));
    float holeRadius = Math.max(0.8f, Math.min(1.4f, h * 0.16f));
    float holeCenterX = left + notch + holeRadius + 0.9f;
    float holeCenterY = bottom + (h / 2f);

    cs.setNonStrokingColor(new java.awt.Color(237, 244, 250));
    cs.moveTo(left + notch, top);
    cs.lineTo(right, top);
    cs.lineTo(right, bottom);
    cs.lineTo(left + notch, bottom);
    cs.lineTo(left, bottom + (h / 2f));
    cs.closePath();
    cs.fill();

    cs.setStrokingColor(new java.awt.Color(109, 130, 151));
    cs.setLineWidth(0.9f);
    cs.moveTo(left + notch, top);
    cs.lineTo(right, top);
    cs.lineTo(right, bottom);
    cs.lineTo(left + notch, bottom);
    cs.lineTo(left, bottom + (h / 2f));
    cs.closePath();
    cs.stroke();

    float c = 0.5522847498f * holeRadius;
    cs.setNonStrokingColor(new java.awt.Color(109, 130, 151));
    cs.moveTo(holeCenterX + holeRadius, holeCenterY);
    cs.curveTo(holeCenterX + holeRadius, holeCenterY + c, holeCenterX + c, holeCenterY + holeRadius, holeCenterX, holeCenterY + holeRadius);
    cs.curveTo(holeCenterX - c, holeCenterY + holeRadius, holeCenterX - holeRadius, holeCenterY + c, holeCenterX - holeRadius, holeCenterY);
    cs.curveTo(holeCenterX - holeRadius, holeCenterY - c, holeCenterX - c, holeCenterY - holeRadius, holeCenterX, holeCenterY - holeRadius);
    cs.curveTo(holeCenterX + c, holeCenterY - holeRadius, holeCenterX + holeRadius, holeCenterY - c, holeCenterX + holeRadius, holeCenterY);
    cs.fill();
  }
  PDAppearanceDictionary dict = new PDAppearanceDictionary();
  dict.setNormalAppearance(appearance);
  return dict;
}
static PDAnnotationFileAttachment inlineAttachmentBadgeAnnotation(PDDocument doc, PDRectangle rect,
                                                                  PDComplexFileSpecification fileSpec,
                                                                  String contents) throws Exception {
  PDAnnotationFileAttachment annotation = new PDAnnotationFileAttachment();
  annotation.setFile(fileSpec);
  annotation.setAttachmentName(PDAnnotationFileAttachment.ATTACHMENT_NAME_TAG);
  annotation.setPrinted(true);
  annotation.setRectangle(rect);
  annotation.setAppearance(buildAttachmentBadgeAppearance(doc, rect.getWidth(), rect.getHeight()));
  annotation.setContents(contents);
  return annotation;
}
static void setEmbeddedChecksum(PDEmbeddedFile file, String hexadecimal) {
  if (Common.nz(hexadecimal).isBlank()) return;
  byte[] checksum = HexFormat.of().parseHex(hexadecimal);
  if (checksum.length != 16) throw new IllegalArgumentException("Embedded file MD5 must contain 16 bytes");
  COSDictionary parameters = (COSDictionary) file.getCOSObject().getDictionaryObject(COSName.PARAMS);
  if (parameters == null) {
    parameters = new COSDictionary();
    file.getCOSObject().setItem(COSName.PARAMS, parameters);
  }
  COSString value = new COSString(checksum);
  value.setForceHexForm(true);
  parameters.setItem(COSName.getPDFName("CheckSum"), value);
}
static void setEmbeddedMediaType(PDEmbeddedFile file, String contentType) {
  file.setSubtype(Common.baseMimeType(contentType));
  COSName parameters = COSName.getPDFName("Mail_MediaTypeParameters");
  int separator = contentType.indexOf(';');
  if (separator >= 0) file.getCOSObject().setString(parameters, contentType.substring(separator + 1).trim());
  else file.getCOSObject().removeItem(parameters);
}
static String embeddedAssetContentType(AssociatedFile file, String filename) throws IOException {
  String declared = Common.sourceContentType(file);
  return declared.isBlank() ? Common.detectMimeType(filename, file.mime, file.head()) : declared;
}
}
