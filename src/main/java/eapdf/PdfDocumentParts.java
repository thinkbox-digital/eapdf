package eapdf;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;

final class PdfDocumentParts {
  private static final Map<org.apache.pdfbox.cos.COSDocument, ObjectAllocator> ALLOCATORS = new WeakHashMap<>();
  private final PDDocument document;
  private final COSObject rootNode;
  private final java.util.List<COSDictionary> pages = new java.util.ArrayList<>();

  PdfDocumentParts(PDDocument document) throws IOException {
    this.document = document;
    // Page order is final before document parts are built. Walking the page tree
    // again for every indexed lookup makes large archives quadratic.
    for (var page : document.getPages()) pages.add(page.getCOSObject());
    COSDictionary root = new COSDictionary();
    root.setName(COSName.TYPE, "DPartRoot");
    COSObject rootReference = indirect(document, root);
    rootNode = node(rootReference);
    root.setItem(COSName.getPDFName("DPartRootNode"), rootNode);
    document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("DPartRoot"), rootReference);
  }

  static COSObject indirect(PDDocument document, COSBase value) throws IOException {
    var cosDocument = document.getDocument();
    synchronized (ALLOCATORS) {
      ObjectAllocator allocator = ALLOCATORS.computeIfAbsent(cosDocument, ObjectAllocator::new);
      COSObject existing = allocator.references.get(value);
      if (existing != null) return existing;
      allocator.highest = Math.max(allocator.highest, cosDocument.getHighestXRefObjectNumber());
      COSObjectKey key = value.getKey();
      if (key == null) {
        if (allocator.highest == Long.MAX_VALUE) throw new IOException("PDF object numbers exhausted");
        key = new COSObjectKey(++allocator.highest, 0);
      } else {
        allocator.highest = Math.max(allocator.highest, key.getNumber());
      }
      COSObject reference = new COSObject(value, key);
      allocator.references.put(value, reference);
      cosDocument.setHighestXRefObjectNumber(allocator.highest);
      return reference;
    }
  }

  // Release writer-only object references when a document is no longer modified.
  static void releaseAllocator(PDDocument document) {
    synchronized (ALLOCATORS) {
      ALLOCATORS.remove(document.getDocument());
    }
  }

  private static final class ObjectAllocator {
    final IdentityHashMap<COSBase, COSObject> references = new IdentityHashMap<>();
    long highest;

    ObjectAllocator(org.apache.pdfbox.cos.COSDocument document) {
      highest = document.getHighestXRefObjectNumber();
      for (COSObjectKey key : document.getXrefTable().keySet()) {
        COSObject reference = document.getObjectFromPool(key);
        highest = Math.max(highest, reference.getObjectNumber());
        if (reference.getObject() != null) references.put(reference.getObject(), reference);
      }
    }
  }

  private COSObject node(COSObject parent) throws IOException {
    COSDictionary dictionary = new COSDictionary();
    dictionary.setName(COSName.TYPE, "DPart");
    dictionary.setItem(COSName.getPDFName("Parent"), parent);
    return indirect(document, dictionary);
  }

  private void append(COSObject parent, COSObject child) {
    COSDictionary dictionary = (COSDictionary) parent.getObject();
    COSArray groups = (COSArray) dictionary.getDictionaryObject(COSName.getPDFName("DParts"));
    if (groups == null) {
      groups = new COSArray();
      groups.add(new COSArray());
      dictionary.setItem(COSName.getPDFName("DParts"), groups);
    }
    ((COSArray) groups.getObject(0)).add(child);
  }

  void addMessage(EmailRecord record, int firstPage, int lastPage) throws Exception {
    Common.finalizeRecordMetadata(record);
    COSObject message = node(rootNode);
    COSDictionary metadata = new COSDictionary();
    metadata.setString(COSName.getPDFName("Mail_GUID"), record.mailGuid);
    if (!Common.nz(record.messageId).isBlank()) metadata.setString(COSName.getPDFName("Mail_MessageID"), record.messageId);
    ((COSDictionary) message.getObject()).setItem(COSName.getPDFName("DPM"), metadata);
    append(rootNode, message);
    int bodyLastPage = firstPage + bodyPageCount(record, lastPage - firstPage + 1) - 1;
    addContentSet(message, firstPage, bodyLastPage, "BodyRendering", Common.contentSetSubtype(record),
        Common.nz(record.contentSetDescription).isBlank() ? "Message body" : record.contentSetDescription);
    if (bodyLastPage < lastPage) {
      addContentSet(message, bodyLastPage + 1, lastPage, "AttachmentList", "", "Email attachments");
    }
  }

  static int bodyPageCount(EmailRecord record, int totalPages) {
    return record.renderedBodyPages > 0 && record.renderedBodyPages <= totalPages
        ? record.renderedBodyPages : totalPages;
  }

  void addFrontMatter(int firstPage, int lastPage, String description) throws IOException {
    addContentSet(rootNode, firstPage, lastPage, "FrontMatter", "", description);
  }

  private void addContentSet(COSObject parent, int firstPage, int lastPage, String type, String subtype, String description) throws IOException {
    if (firstPage < 0 || lastPage < firstPage || lastPage >= pages.size()) {
      throw new IllegalArgumentException("Invalid document-part page range");
    }
    COSObject contentSet = node(parent);
    COSDictionary dictionary = (COSDictionary) contentSet.getObject();
    COSDictionary metadata = new COSDictionary();
    metadata.setName(COSName.getPDFName("Mail_ContentSetType"), type);
    if (!subtype.isBlank()) metadata.setName(COSName.getPDFName("Mail_Subtype"), subtype);
    metadata.setString(COSName.getPDFName("Mail_Desc"), description);
    dictionary.setItem(COSName.getPDFName("DPM"), metadata);
    dictionary.setItem(COSName.getPDFName("Start"), indirect(document, pages.get(firstPage)));
    if (lastPage != firstPage) dictionary.setItem(COSName.getPDFName("End"), indirect(document, pages.get(lastPage)));
    append(parent, contentSet);
    for (int index = firstPage; index <= lastPage; index++) {
      pages.get(index).setItem(COSName.getPDFName("DPart"), contentSet);
    }
  }
}
