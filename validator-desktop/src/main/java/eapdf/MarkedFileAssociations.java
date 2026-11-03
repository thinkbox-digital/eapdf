package eapdf;

import static eapdf.EaPdfChecks.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Marked-content MCAF files are not discovered by generic AF dictionary traversal. */
final class MarkedFileAssociations {
  private final PDDocument document;
  private final Set<COSDictionary> files = identitySet();
  private final Set<COSDictionary> active = identitySet();
  private long instructions;
  private final long instructionLimit;
  private MarkedFileAssociations(PDDocument document) { this.document = document; instructionLimit = InspectionBudget.instructions(document); }

  static Set<COSDictionary> collect(PDDocument document) throws IOException {
    var scan = new MarkedFileAssociations(document);
    for (var page : document.getPages()) scan.parse(page, page.getResources(), 0);
    return scan.files;
  }

  private COSBase token(COSBase value) {
    if (!(value instanceof COSObject reference)) return value;
    COSBase resolved = reference.getObject();
    if (resolved == null) resolved = document.getDocument().getObjectFromPool(
        new COSObjectKey(reference.getObjectNumber(), reference.getGenerationNumber())).getObject();
    return resolved;
  }

  private void parse(PDContentStream stream, PDResources resources, int depth) throws IOException {
    List<COSBase> operands = new ArrayList<>();
    PDFStreamParser parser = new PDFStreamParser(stream);
    try {
      Object item;
      while ((item = parser.parseNextToken()) != null) {
        if (item instanceof COSBase value) { operands.add(value); continue; }
        if (!(item instanceof Operator operator)) continue;
        if (Set.of("BDC", "DP").contains(operator.getName()) && operands.size() >= 2) {
          COSBase properties = operands.get(1);
          if (properties instanceof COSName name && resources != null && resources.getProperties(name) != null)
            properties = resources.getProperties(name).getCOSObject();
          if (properties instanceof COSDictionary dictionary) {
            COSBase associated = "BDC".equals(operator.getName()) && "AF".equals(name(operands.getFirst()))
                ? token(dictionary.getItem(COSName.getPDFName("MCAF"))) : null;
            if (associated == null) associated = token(dictionary.getItem(COSName.getPDFName("AF")));
            if (associated instanceof COSArray array) {
              for (COSBase entry : array) if (token(entry) instanceof COSDictionary file) files.add(file);
            } else if (associated instanceof COSDictionary file) files.add(file);
          }
        } else if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.getFirst() instanceof COSName name
            && resources != null && resources.getXObject(name) instanceof PDFormXObject form) {
          if (!active.add(form.getCOSObject())) throw new IOException("Recursive form in marked-content association inspection");
          try { parse(form, form.getResources() == null ? resources : form.getResources(), depth + 1); }
          finally { active.remove(form.getCOSObject()); }
        }
        operands.clear();
      }
    } finally { parser.close(); }
  }
}
