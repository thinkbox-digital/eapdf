package eapdf;

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
}
