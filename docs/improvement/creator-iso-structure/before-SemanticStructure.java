package eapdf;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;

final class SemanticStructure {
  private static final Set<String> GROUPS = Set.of("Table", "TR", "TH", "TD");

  static String headerHtml(String html, String marker) {
    var document = org.jsoup.Jsoup.parse(html, "", org.jsoup.parser.Parser.xmlParser());
    document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml).prettyPrint(false);
    for (var table : document.select("table[data-eapdf-header]")) {
      if (!marker.equals(table.attr("data-eapdf-header"))) continue;
      table.removeAttr("data-eapdf-header");
      for (var row : table.select("tr")) {
        var name = row.selectFirst(".label");
        var value = row.selectFirst(".value");
        if (name == null || value == null) continue;
        String label = name.text().replaceFirst(":$", "");
        if (!Set.of("From", "To", "Date", "Cc", "Bcc", "Subject", "Message-ID").contains(label)) continue;
        row.attr("title", marker + "field:" + label);
        name.attr("title", marker + "name:" + label);
        if (!value.text().isBlank()) value.attr("title", marker + "value:" + label);
      }
    }
    return document.outerHtml();
  }

  static void headerFields(PDDocument document, String marker) {
    if (Common.nz(marker).isBlank()) return;
    COSDictionary tree = dictionary(document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT));
    if (tree == null) return;
    headerFields(tree.getDictionaryObject(COSName.K), marker);
    COSDictionary roles = dictionary(tree.getDictionaryObject(COSName.ROLE_MAP));
    if (roles == null) {
      roles = new COSDictionary();
      tree.setItem(COSName.ROLE_MAP, roles);
    }
    roles.setName(COSName.getPDFName("Mail_Field"), "TR");
    roles.setName(COSName.getPDFName("Mail_FieldName"), "TD");
    roles.setName(COSName.getPDFName("Mail_FieldValue"), "TD");
  }

  private static void headerFields(COSBase content, String marker) {
    if (content instanceof COSArray array) {
      for (COSBase child : array) headerFields(child, marker);
      return;
    }
    COSDictionary element = dictionary(content);
    if (element == null || !element.containsKey(COSName.S)) return;
    String alternate = Common.nz(element.getString(COSName.ALT));
    if (alternate.startsWith(marker)) {
      String kind = alternate.substring(marker.length()).split(":", 2)[0];
      String role = switch (kind) {
        case "field" -> "Mail_Field";
        case "name" -> "Mail_FieldName";
        case "value" -> "Mail_FieldValue";
        default -> "";
      };
      String originalRole = element.getNameAsString(COSName.S);
      if (!role.isBlank() && (kind.equals("field") ? "TR".equals(originalRole) : Set.of("TD", "TH").contains(originalRole))) {
        element.setName(COSName.S, role);
      }
      element.removeItem(COSName.ALT);
    }
    headerFields(element.getDictionaryObject(COSName.K), marker);
  }

  static void beginGroup(PDPageContentStream content, String role) throws IOException {
    if (PdfMailWriter.pdfUaFeaturesEnabled()) content.beginMarkedContent(COSName.getPDFName(role));
  }

  static void endGroup(PDPageContentStream content) throws IOException {
    if (PdfMailWriter.pdfUaFeaturesEnabled()) content.endMarkedContent();
  }

  static COSDictionary importedTree(PDDocument target, PDDocument source, org.apache.pdfbox.multipdf.PDFCloneUtility cloner) throws IOException {
    COSDictionary tree = (COSDictionary)source.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
    return tree == null ? null : (COSDictionary)cloner.cloneForNewDocument(tree);
  }

  static void addDestinations(PDDocument document, COSDictionary tree) throws IOException {
    Map<COSDictionary, COSDictionary> targets = new java.util.IdentityHashMap<>();
    findTargets(dictionary(tree.getDictionaryObject(COSName.K)), null, targets);
    var outline = document.getDocumentCatalog().getDocumentOutline();
    if (outline != null) addOutlineDestinations(document, outline.getFirstChild(), targets);
    for (PDPage page : document.getPages()) for (var annotation : page.getAnnotations()) {
      COSDictionary action = dictionary(annotation.getCOSObject().getDictionaryObject(COSName.A));
      if (action != null && "GoTo".equals(action.getNameAsString(COSName.S))) addActionDestination(document, action, targets);
    }
  }

  private static void findTargets(COSDictionary element, COSDictionary inheritedPage, Map<COSDictionary, COSDictionary> targets) {
    if (element == null) return;
    COSDictionary page = dictionary(element.getDictionaryObject(COSName.PG));
    if (page == null) page = inheritedPage;
    COSBase content = element.getDictionaryObject(COSName.K);
    List<COSBase> entries = new ArrayList<>();
    if (content instanceof COSArray array) for (COSBase child : array) entries.add(child);
    else if (content != null) entries.add(content);
    boolean firstContent = true;
    for (COSBase entry : entries) {
      COSDictionary child = dictionary(entry);
      if (child != null && child.containsKey(COSName.S)) findTargets(child, page, targets);
      else if (firstContent) {
        COSDictionary targetPage = child == null ? page : dictionary(child.getDictionaryObject(COSName.PG));
        if (targetPage == null) targetPage = page;
        if (targetPage != null) targets.putIfAbsent(targetPage, element);
        firstContent = false;
      }
    }
  }

  private static COSArray structureDestination(PDDocument document, COSBase value, Map<COSDictionary, COSDictionary> targets) throws IOException {
    if (!(value instanceof COSArray array) || array.size() == 0) return null;
    COSDictionary page = dictionary(array.get(0));
    COSDictionary target = targets.get(page);
    if (target == null) return null;
    COSArray destination = new COSArray();
    destination.add(PdfDocumentParts.indirect(document, target));
    for (int index = 1; index < array.size(); index++) destination.add(array.get(index));
    return destination;
  }

  private static void addActionDestination(PDDocument document, COSDictionary action, Map<COSDictionary, COSDictionary> targets) throws IOException {
    COSArray destination = structureDestination(document, action.getDictionaryObject(COSName.D), targets);
    if (destination != null) action.setItem(COSName.getPDFName("SD"), destination);
  }

  private static void addOutlineDestinations(PDDocument document, org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem item,
                                              Map<COSDictionary, COSDictionary> targets) throws IOException {
    while (item != null) {
      COSDictionary dictionary = item.getCOSObject();
      COSBase legacy = dictionary.getDictionaryObject(COSName.DEST);
      COSArray destination = structureDestination(document, legacy, targets);
      if (destination != null) {
        COSDictionary action = new COSDictionary();
        action.setName(COSName.S, "GoTo");
        action.setItem(COSName.D, legacy);
        action.setItem(COSName.getPDFName("SD"), destination);
        dictionary.removeItem(COSName.DEST);
        dictionary.setItem(COSName.A, action);
      }
      addOutlineDestinations(document, item.getFirstChild(), targets);
      item = item.getNextSibling();
    }
  }

  static void attach(PDDocument document, COSDictionary tree, COSDictionary contentSet, COSDictionary original,
                     int firstPage, int lastPage) throws IOException {
    COSArray children = new COSArray();
    if (original != null) {
      COSDictionary root = dictionary(original.getDictionaryObject(COSName.K));
      if (root != null) {
        COSBase oldChildren = root.getDictionaryObject(COSName.K);
        if (oldChildren instanceof COSArray array) {
          for (COSBase child : array) appendChild(document, contentSet, children, child);
        } else if (oldChildren != null) appendChild(document, contentSet, children, oldChildren);
      }
      Map<Integer, COSBase> oldParents = new LinkedHashMap<>();
      numberEntries(dictionary(original.getDictionaryObject(COSName.getPDFName("ParentTree"))), oldParents);
      Map<Integer, Integer> remapped = new LinkedHashMap<>();
      for (var entry : oldParents.entrySet()) {
        int key = nextKey(tree);
        parents(document, tree).add(COSInteger.get(key));
        parents(document, tree).add(entry.getValue());
        remapped.put(entry.getKey(), key);
      }
      for (int index = firstPage; index <= lastPage; index++) {
        PDPage page = document.getPage(index);
        remap(page.getCOSObject(), "StructParents", remapped);
        for (var annotation : page.getAnnotations()) remap(annotation.getCOSObject(), "StructParent", remapped);
      }
      COSDictionary oldRoles = dictionary(original.getDictionaryObject(COSName.ROLE_MAP));
      COSDictionary roles = dictionary(tree.getDictionaryObject(COSName.ROLE_MAP));
      if (oldRoles != null && roles != null) {
        for (COSName name : oldRoles.keySet()) if (!roles.containsKey(name)) roles.setItem(name, oldRoles.getItem(name));
      }
    }
    for (int index = firstPage; index <= lastPage; index++) {
      PDPage page = document.getPage(index);
      tagUnmarkedContent(document, tree, contentSet, children, page);
      attachAnnotations(document, tree, contentSet, children, page);
      page.getCOSObject().setName(COSName.getPDFName("Tabs"), "S");
    }
    contentSet.setItem(COSName.K, children);
  }

  private static COSDictionary dictionary(COSBase value) {
    if (value instanceof org.apache.pdfbox.cos.COSObject reference) value = reference.getObject();
    return value instanceof COSDictionary dictionary ? dictionary : null;
  }

  private static void appendChild(PDDocument document, COSDictionary parent, COSArray children, COSBase child) throws IOException {
    COSDictionary element = dictionary(child);
    if (element == null || !element.containsKey(COSName.S)) return;
    if (!pruneEmpty(element)) return;
    normalizeBlocks(element);
    element.setItem(COSName.P, PdfDocumentParts.indirect(document, parent));
    children.add(PdfDocumentParts.indirect(document, element));
  }

  private static void normalizeBlocks(COSDictionary element) {
    COSBase content = element.getDictionaryObject(COSName.K);
    List<COSBase> entries = new ArrayList<>();
    if (content instanceof COSArray array) for (COSBase child : array) entries.add(child);
    else if (content != null) entries.add(content);
    boolean inline = false;
    boolean block = false;
    for (COSBase child : entries) {
      COSDictionary nested = dictionary(child);
      if (nested != null && nested.containsKey(COSName.S)) {
        normalizeBlocks(nested);
        String role = nested.getNameAsString(COSName.S);
        if (Set.of("Span", "Link", "Annot", "Quote", "Reference", "Code", "Em", "Strong", "Sub", "Ruby", "Warichu").contains(role)) inline = true;
        else block = true;
      } else inline = true;
    }
    if ("Div".equals(element.getNameAsString(COSName.S)) && inline && !block) element.setName(COSName.S, "P");
  }

  private static boolean pruneEmpty(COSDictionary element) {
    COSBase content = element.getDictionaryObject(COSName.K);
    if (content instanceof COSArray array) {
      for (int index = array.size() - 1; index >= 0; index--) {
        COSDictionary child = dictionary(array.get(index));
        if (child != null && child.containsKey(COSName.S) && !pruneEmpty(child)) array.remove(index);
      }
      return array.size() > 0;
    }
    COSDictionary child = dictionary(content);
    if (child != null && child.containsKey(COSName.S)) return pruneEmpty(child);
    return content != null;
  }

  private static void remap(COSDictionary object, String name, Map<Integer, Integer> remapped) {
    COSName key = COSName.getPDFName(name);
    if (object.containsKey(key)) {
      Integer replacement = remapped.get(object.getInt(key));
      if (replacement != null) object.setInt(key, replacement);
    }
  }

  private static void numberEntries(COSDictionary tree, Map<Integer, COSBase> entries) {
    if (tree == null) return;
    COSArray numbers = (COSArray)tree.getDictionaryObject(COSName.NUMS);
    if (numbers != null) for (int index = 0; index + 1 < numbers.size(); index += 2) {
      entries.put(((COSNumber)numbers.getObject(index)).intValue(), numbers.get(index + 1));
    }
    COSArray kids = (COSArray)tree.getDictionaryObject(COSName.KIDS);
    if (kids != null) for (COSBase kid : kids) numberEntries(dictionary(kid), entries);
  }

  private static COSArray parents(PDDocument document, COSDictionary tree) throws IOException {
    COSDictionary parentTree = dictionary(tree.getDictionaryObject(COSName.getPDFName("ParentTree")));
    if (parentTree == null) {
      parentTree = new COSDictionary();
      parentTree.setItem(COSName.NUMS, new COSArray());
      tree.setItem(COSName.getPDFName("ParentTree"), PdfDocumentParts.indirect(document, parentTree));
    }
    return (COSArray)parentTree.getDictionaryObject(COSName.NUMS);
  }

  private static int nextKey(COSDictionary tree) {
    COSName name = COSName.getPDFName("ParentTreeNextKey");
    int key = tree.getInt(name, 0);
    tree.setInt(name, key + 1);
    return key;
  }

  private static COSArray pageParents(PDDocument document, COSDictionary tree, PDPage page) throws IOException {
    COSName name = COSName.getPDFName("StructParents");
    COSArray numbers = parents(document, tree);
    if (page.getCOSObject().containsKey(name)) {
      int key = page.getCOSObject().getInt(name);
      for (int index = 0; index < numbers.size(); index += 2) {
        if (((COSNumber)numbers.getObject(index)).intValue() == key) return (COSArray)numbers.getObject(index + 1);
      }
    }
    COSArray values = new COSArray();
    int key = nextKey(tree);
    numbers.add(COSInteger.get(key));
    numbers.add(values);
    page.getCOSObject().setInt(name, key);
    return values;
  }

  private static COSDictionary element(PDDocument document, COSDictionary parent, COSArray children, PDPage page, String role) throws IOException {
    COSDictionary element = PdfMailWriter.structElem(role, PdfDocumentParts.indirect(document, parent), page);
    children.add(PdfDocumentParts.indirect(document, element));
    return element;
  }

  private static COSArray children(COSDictionary element) {
    COSArray children = new COSArray();
    element.setItem(COSName.K, children);
    return children;
  }

  private static void tagUnmarkedContent(PDDocument document, COSDictionary tree, COSDictionary contentSet,
                                         COSArray children, PDPage page) throws IOException {
    PDFStreamParser parser = new PDFStreamParser(page);
    List<Object> input = parser.parse();
    List<Object> output = new ArrayList<>();
    List<Object> operands = new ArrayList<>();
    Deque<Boolean> marked = new ArrayDeque<>();
    Deque<COSDictionary> groups = new ArrayDeque<>();
    groups.push(contentSet);
    boolean text = false;
    boolean wrapping = false;
    for (int index = 0; index < input.size(); index++) {
      Object token = input.get(index);
      if (!(token instanceof Operator operator)) {
        operands.add(token);
        continue;
      }
      String name = operator.getName();
      if (name.equals("BMC") && operands.size() == 1 && operands.get(0) instanceof COSName role && GROUPS.contains(role.getName())) {
        COSDictionary parent = groups.peek();
        COSArray target = parent == contentSet ? children : (COSArray)parent.getDictionaryObject(COSName.K);
        COSDictionary group = element(document, parent, target, page, role.getName());
        children(group);
        if (role.getName().equals("TH")) {
          COSDictionary attribute = new COSDictionary();
          attribute.setName(COSName.O, "Table");
          attribute.setName(COSName.getPDFName("Scope"), "Column");
          group.setItem(COSName.A, attribute);
        }
        groups.push(group);
        marked.push(false);
        operands.clear();
        continue;
      }
      if (name.equals("BDC") || name.equals("BMC")) marked.push(true);
      if (name.equals("EMC") && !marked.isEmpty()) {
        if (!marked.pop()) {
          groups.pop();
          operands.clear();
          continue;
        }
      }
      boolean image = name.equals("Do") && !operands.isEmpty() && operands.get(0) instanceof COSName resource
          && page.getResources().isImageXObject(resource);
      if ((name.equals("BT") || image) && marked.stream().noneMatch(Boolean::booleanValue)) {
        String role = image ? "Figure" : textRole(input, index);
        COSDictionary parent = groups.peek();
        COSArray target = parent == contentSet ? children : (COSArray)parent.getDictionaryObject(COSName.K);
        COSDictionary element = element(document, parent, target, page, role);
        if (image) element.setString(COSName.ALT, "Inline image from the email");
        COSArray pageParents = pageParents(document, tree, page);
        int mcid = pageParents.size();
        pageParents.add(PdfDocumentParts.indirect(document, element));
        element.setInt(COSName.K, mcid);
        COSDictionary properties = new COSDictionary();
        properties.setInt(COSName.MCID, mcid);
        output.add(COSName.getPDFName(role));
        output.add(properties);
        output.add(Operator.getOperator("BDC"));
        wrapping = true;
      }
      output.addAll(operands);
      operands.clear();
      output.add(operator);
      if (name.equals("BT")) text = true;
      if (wrapping && (name.equals("ET") && text || image)) {
        output.add(Operator.getOperator("EMC"));
        wrapping = false;
      }
      if (name.equals("ET")) text = false;
    }
    output.addAll(operands);
    PDStream stream = new PDStream(document);
    try (var content = stream.createOutputStream(COSName.FLATE_DECODE)) {
      new ContentStreamWriter(content).writeTokens(output);
    }
    page.setContents(stream);
  }

  private static String textRole(List<Object> tokens, int start) {
    for (int index = start + 1; index < tokens.size(); index++) {
      Object token = tokens.get(index);
      if (token instanceof Operator operator) {
        if (operator.getName().equals("ET")) break;
        if (operator.getName().equals("Tf") && tokens.get(index - 1) instanceof COSNumber size) return size.floatValue() >= 18 ? "H1" : "P";
      }
    }
    return "P";
  }

  private static void attachAnnotations(PDDocument document, COSDictionary tree, COSDictionary contentSet,
                                         COSArray children, PDPage page) throws IOException {
    for (var annotation : page.getAnnotations()) {
      if (annotation.getCOSObject().containsKey(COSName.getPDFName("StructParent"))) {
        if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment) {
          int key = annotation.getCOSObject().getInt(COSName.getPDFName("StructParent"));
          COSArray numbers = parents(document, tree);
          for (int index = 0; index < numbers.size(); index += 2) {
            if (((COSNumber)numbers.getObject(index)).intValue() == key) {
              COSDictionary owner = dictionary(numbers.get(index + 1));
              if (owner != null) owner.setName(COSName.S, "Annot");
            }
          }
        }
        continue;
      }
      COSDictionary element = element(document, contentSet, children, page, annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink ? "Link" : "Annot");
      String description = Common.nz(annotation.getContents());
      if (description.isBlank()) description = "Open linked email or finding-aid entry";
      element.setString(COSName.ALT, description);
      COSDictionary reference = new COSDictionary();
      reference.setName(COSName.TYPE, "OBJR");
      reference.setItem(COSName.PG, PdfDocumentParts.indirect(document, page.getCOSObject()));
      reference.setItem(COSName.OBJ, annotation.getCOSObject());
      element.setItem(COSName.K, reference);
      int key = nextKey(tree);
      annotation.getCOSObject().setInt(COSName.getPDFName("StructParent"), key);
      parents(document, tree).add(COSInteger.get(key));
      parents(document, tree).add(PdfDocumentParts.indirect(document, element));
    }
  }
}
