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
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

final class SemanticStructure {
  private static final Set<String> GROUPS = Set.of("Table", "TR", "TH", "TD");
  private static final Set<String> INLINE_ROLES = Set.of("Span", "Link", "Annot", "Quote", "Reference", "Code", "Em", "Strong", "Sub", "Ruby", "Warichu");
  private static final Set<String> INLINE_CONTAINERS = Set.of("P", "H", "H1", "H2", "H3", "H4", "H5", "H6");
  private static final Set<String> LIST_CONTAINERS = Set.of("Div", "P", "TD", "TH", "Sect", "Part", "Art", "BlockQuote", "Document");

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
        if (!Set.of("From", "To", "Date", "Cc", "Bcc", "Reply-To", "Subject", "Message-ID").contains(label)) continue;
        row.attr("title", marker + "field:" + label);
        name.attr("title", marker + "name:" + label);
        if (!value.text().isBlank()) value.attr("title", marker + "value:" + label);
      }
    }
    return document.outerHtml();
  }

  static void headerFields(PDDocument document, String marker) throws IOException {
    if (Common.nz(marker).isBlank()) return;
    COSDictionary tree = dictionary(document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT));
    if (tree == null) return;
    headerFields(tree.getDictionaryObject(COSName.K), marker);
    Map<Integer, COSBase> owners = new LinkedHashMap<>();
    numberEntries(dictionary(tree.getDictionaryObject(COSName.getPDFName("ParentTree"))), owners);
    Set<COSDictionary> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (PDPage page : document.getPages()) {
      COSBase pageOwners = owners.get(page.getCOSObject().getInt(COSName.STRUCT_PARENTS, -1));
      var streams = page.getContentStreams();
      while (streams.hasNext()) headerOperands(streams.next(), page.getResources(), pageOwners);
      headerFormOperands(page.getResources(), owners, visited);
    }
    COSDictionary roles = dictionary(tree.getDictionaryObject(COSName.ROLE_MAP));
    if (roles == null) {
      roles = new COSDictionary();
      tree.setItem(COSName.ROLE_MAP, roles);
    }
    roles.setName(COSName.getPDFName("Mail_Field"), "TR");
    roles.setName(COSName.getPDFName("Mail_FieldName"), "TD");
    roles.setName(COSName.getPDFName("Mail_FieldValue"), "TD");
  }

  private static void headerFormOperands(PDResources resources, Map<Integer, COSBase> owners,
                                          Set<COSDictionary> visited) throws IOException {
    if (resources == null) return;
    for (COSName name : resources.getXObjectNames()) {
      if (!(resources.getXObject(name) instanceof PDFormXObject form) || !visited.add(form.getCOSObject())) continue;
      PDResources effective = form.getResources() == null ? resources : form.getResources();
      headerOperands(form.getContentStream(), effective, owners.get(form.getStructParents()));
      headerFormOperands(effective, owners, visited);
    }
  }

  private static void headerOperands(PDStream stream, PDResources resources, COSBase owners) throws IOException {
    if (owners instanceof org.apache.pdfbox.cos.COSObject reference) owners = reference.getObject();
    if (!(owners instanceof COSArray parents)) return;
    List<Object> tokens = new PDFStreamParser(stream.toByteArray()).parse();
    boolean changed = false;
    for (int index = 2; index < tokens.size(); index++) {
      if (!(tokens.get(index) instanceof Operator operator) || !operator.getName().equals("BDC")
          || !(tokens.get(index - 2) instanceof COSName)) continue;
      Object properties = tokens.get(index - 1);
      if (properties instanceof COSName name && resources != null) {
        var named = resources.getProperties(name);
        properties = named == null ? null : named.getCOSObject();
      }
      if (!(properties instanceof COSDictionary attributes)) continue;
      int mcid = attributes.getInt(COSName.MCID, -1);
      if (mcid < 0 || mcid >= parents.size()) continue;
      COSDictionary owner = dictionary(parents.get(mcid));
      String role = owner == null ? "" : owner.getNameAsString(COSName.S, "");
      if (Set.of("Mail_Field", "Mail_FieldName", "Mail_FieldValue").contains(role)
          && !COSName.getPDFName(role).equals(tokens.get(index - 2))) {
        tokens.set(index - 2, COSName.getPDFName(role));
        changed = true;
      }
    }
    // Keep stream identity: MCR /Stm references may point to this exact object.
    if (changed) try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) {
      new ContentStreamWriter(output).writeTokens(tokens);
    }
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
    attach(document, tree, contentSet, original, firstPage, lastPage, document::getPage);
  }

  static void attach(PDDocument document, COSDictionary tree, COSDictionary contentSet, COSDictionary original,
                     int firstPage, int lastPage, java.util.function.IntFunction<PDPage> pages) throws IOException {
    COSArray children = new COSArray();
    if (original != null) {
      Map<Integer, COSBase> oldParents = new LinkedHashMap<>();
      numberEntries(dictionary(original.getDictionaryObject(COSName.getPDFName("ParentTree"))), oldParents);
      COSDictionary root = dictionary(original.getDictionaryObject(COSName.K));
      if (root != null) {
        COSBase oldChildren = root.getDictionaryObject(COSName.K);
        if (oldChildren instanceof COSArray array) {
          for (COSBase child : array) appendChild(document, contentSet, children, child, oldParents);
        } else if (oldChildren != null) appendChild(document, contentSet, children, oldChildren, oldParents);
      }
      Map<Integer, Integer> remapped = new LinkedHashMap<>();
      for (var entry : oldParents.entrySet()) {
        int key = nextKey(tree);
        parents(document, tree).add(COSInteger.get(key));
        parents(document, tree).add(entry.getValue());
        remapped.put(entry.getKey(), key);
      }
      for (int index = firstPage; index <= lastPage; index++) {
        PDPage page = pages.apply(index);
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
      PDPage page = pages.apply(index);
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

  private static void appendChild(PDDocument document, COSDictionary parent, COSArray children, COSBase child,
                                  Map<Integer, COSBase> oldParents) throws IOException {
    COSDictionary element = dictionary(child);
    if (element == null || !element.containsKey(COSName.S)) return;
    if (!pruneEmpty(element)) return;
    normalizeBlocks(document, element, oldParents, dictionary(parent.getDictionaryObject(COSName.PG)), parent.getNameAsString(COSName.S));
    element.setItem(COSName.P, PdfDocumentParts.indirect(document, parent));
    children.add(PdfDocumentParts.indirect(document, element));
  }

  private static void normalizeBlocks(PDDocument document, COSDictionary element, Map<Integer, COSBase> oldParents,
                                      COSDictionary inheritedPage, String parentRole) throws IOException {
    COSDictionary page = dictionary(element.getDictionaryObject(COSName.PG));
    if (page == null) page = inheritedPage;
    COSBase content = element.getDictionaryObject(COSName.K);
    List<COSBase> entries = new ArrayList<>();
    if (content instanceof COSArray array) for (COSBase child : array) entries.add(child);
    else if (content != null) entries.add(content);
    boolean inline = false;
    boolean block = false;
    String role = element.getNameAsString(COSName.S);
    // The renderer uses a transparent Div for table + caption. Associate the
    // caption with Table so it cannot become a middle child of outer containers.
    // Keep order and existing MCIDs/ParentTree owners unchanged.
    if ("Div".equals(role) && entries.size() == 2) {
      COSDictionary first = dictionary(entries.get(0)), second = dictionary(entries.get(1));
      boolean before = first != null && "Caption".equals(first.getNameAsString(COSName.S));
      COSDictionary table = before ? second : first, caption = before ? first : second;
      if (table != null && caption != null && "Table".equals(table.getNameAsString(COSName.S))
          && "Caption".equals(caption.getNameAsString(COSName.S))) {
        COSArray kids = new COSArray();
        if (before) kids.add(PdfDocumentParts.indirect(document, caption));
        COSBase tableKids = table.getDictionaryObject(COSName.K);
        if (tableKids instanceof COSArray array) for (COSBase kid : array) kids.add(kid);
        else if (tableKids != null) kids.add(tableKids);
        if (!before) kids.add(PdfDocumentParts.indirect(document, caption));
        caption.setItem(COSName.P, PdfDocumentParts.indirect(document, table));
        table.setItem(COSName.K, kids);
        element.setItem(COSName.K, PdfDocumentParts.indirect(document, table));
        entries.clear(); entries.add(PdfDocumentParts.indirect(document, table));
      }
    }
    if ("TR".equals(role)) {
      COSArray cells = new COSArray();
      for (COSBase child : entries) {
        COSDictionary nested = dictionary(child);
        if (nested != null && "Div".equals(nested.getNameAsString(COSName.S, ""))) {
          List<COSBase> wrapped = new ArrayList<>();
          COSBase contents = nested.getDictionaryObject(COSName.K);
          if (contents instanceof COSArray array) for (COSBase candidate : array) wrapped.add(candidate);
          else if (contents != null) wrapped.add(contents);
          boolean onlyCells = !wrapped.isEmpty();
          for (COSBase candidate : wrapped) {
            COSDictionary cell = dictionary(candidate);
            onlyCells &= cell != null && Set.of("TD", "TH").contains(cell.getNameAsString(COSName.S, ""));
          }
          if (onlyCells) {
            // An anonymous renderer wrapper around existing cells is not a cell.
            for (COSBase candidate : wrapped) {
              dictionary(candidate).setItem(COSName.P, PdfDocumentParts.indirect(document, element));
              cells.add(candidate);
            }
            continue;
          }
        }
        if (nested != null && Set.of("TD", "TH").contains(nested.getNameAsString(COSName.S, ""))) {
          cells.add(child);
          continue;
        }
        // The HTML renderer can emit a Div for a cell styled as a block.
        // Retain that content inside a real cell rather than losing the row.
        COSDictionary cell = PdfMailWriter.structElem("TD", PdfDocumentParts.indirect(document, element), page == null ? null : new PDPage(page));
        COSArray cellChildren = new COSArray();
        cellChildren.add(child);
        cell.setItem(COSName.K, cellChildren);
        COSBase owner = PdfDocumentParts.indirect(document, cell);
        if (nested != null && nested.containsKey(COSName.S)) nested.setItem(COSName.P, owner);
        else moveContentParent(child, page, element, owner, oldParents);
        cells.add(owner);
      }
      element.setItem(COSName.K, cells);
      entries.clear();
      for (COSBase child : cells) entries.add(child);
    }
    // Div is transparent to the inclusion rules. Carry the effective parent
    // through renderer wrappers, including wrappers inside headings and lists.
    String contentParentRole = "Div".equals(role) ? parentRole : role;
    if (LIST_CONTAINERS.contains(role) && !"L".equals(contentParentRole)) {
      boolean orphanItems = false;
      for (COSBase child : entries) {
        COSDictionary nested = dictionary(child);
        if (nested != null && "LI".equals(nested.getNameAsString(COSName.S))) { orphanItems = true; break; }
      }
      if (orphanItems) {
        // Legacy HTML sometimes has LI elements without a surrounding list.
        // Supply that structure without changing page content or item order.
        COSArray grouped = new COSArray();
        COSDictionary list = null;
        for (COSBase child : entries) {
          COSDictionary nested = dictionary(child);
          if (nested == null || !"LI".equals(nested.getNameAsString(COSName.S))) {
            grouped.add(child); list = null; continue;
          }
          if (list == null) {
            list = PdfMailWriter.structElem("L", PdfDocumentParts.indirect(document, element), page == null ? null : new PDPage(page));
            list.setItem(COSName.K, new COSArray());
            grouped.add(PdfDocumentParts.indirect(document, list));
          }
          nested.setItem(COSName.P, PdfDocumentParts.indirect(document, list));
          ((COSArray)list.getDictionaryObject(COSName.K)).add(child);
        }
        element.setItem(COSName.K, grouped);
        entries.clear();
        for (COSBase child : grouped) entries.add(child);
      }
    }
    for (COSBase child : entries) {
      COSDictionary nested = dictionary(child);
      if (nested != null && nested.containsKey(COSName.S)) {
        normalizeBlocks(document, nested, oldParents, page, contentParentRole);
        String nestedRole = nested.getNameAsString(COSName.S);
        if (INLINE_ROLES.contains(nestedRole)) inline = true;
        else block = true;
      } else inline = true;
    }
    // Real HTML can place block paragraphs inside styled inline wrappers.
    // Such wrappers (and their enclosing paragraph) must become block groups.
    if (block && ("Span".equals(role) || "P".equals(role))) {
      element.setName(COSName.S, "Div");
      role = "Div";
    }
    // Headings and inline labels can arrive inside anonymous paragraph wrappers.
    if (parentRole != null && INLINE_CONTAINERS.contains(parentRole) && ("P".equals(role) || "Div".equals(role)) && !block) {
      element.setName(COSName.S, "Span");
      return;
    }
    if (!("Div".equals(role) || "BlockQuote".equals(role)) || !inline) return;
    if ("Div".equals(role) && !block) {
      element.setName(COSName.S, "P");
      return;
    }
    // Group inline runs in paragraphs. Div is transparent to inclusion rules;
    // BlockQuote also requires block children while retaining its quote role.
    COSArray grouped = new COSArray();
    COSDictionary paragraph = null;
    for (COSBase child : entries) {
      COSDictionary nested = dictionary(child);
      if (nested != null && nested.containsKey(COSName.S) && !INLINE_ROLES.contains(nested.getNameAsString(COSName.S))) {
        grouped.add(child);
        paragraph = null;
        continue;
      }
      if (paragraph == null) {
        paragraph = PdfMailWriter.structElem("P", PdfDocumentParts.indirect(document, element), page == null ? null : new PDPage(page));
        children(paragraph);
        grouped.add(PdfDocumentParts.indirect(document, paragraph));
      }
      COSBase owner = PdfDocumentParts.indirect(document, paragraph);
      ((COSArray)paragraph.getDictionaryObject(COSName.K)).add(child);
      if (nested != null && nested.containsKey(COSName.S)) nested.setItem(COSName.P, owner);
      else moveContentParent(child, page, element, owner, oldParents);
    }
    element.setItem(COSName.K, grouped);
  }

  private static void moveContentParent(COSBase child, COSDictionary page, COSDictionary previous,
                                        COSBase owner, Map<Integer, COSBase> oldParents) throws IOException {
    COSBase value = child instanceof org.apache.pdfbox.cos.COSObject reference ? reference.getObject() : child;
    COSDictionary item = dictionary(value);
    if (item != null && "OBJR".equals(item.getNameAsString(COSName.TYPE))) {
      COSDictionary object = dictionary(item.getDictionaryObject(COSName.OBJ));
      int key = object == null ? -1 : object.getInt(COSName.getPDFName("StructParent"), -1);
      if (dictionary(oldParents.get(key)) != previous) throw new IOException("Imported object reference has no matching ParentTree owner");
      oldParents.put(key, owner);
      return;
    }
    int mcid = value instanceof COSInteger integer ? integer.intValue() : item == null ? -1 : item.getInt(COSName.MCID, -1);
    COSDictionary scope = item == null ? null : dictionary(item.getDictionaryObject(COSName.getPDFName("Stm")));
    if (scope == null && item != null) scope = dictionary(item.getDictionaryObject(COSName.PG));
    if (scope == null) scope = page;
    int key = scope == null ? -1 : scope.getInt(COSName.getPDFName("StructParents"), -1);
    COSBase entry = oldParents.get(key);
    if (entry instanceof org.apache.pdfbox.cos.COSObject reference) entry = reference.getObject();
    if (!(entry instanceof COSArray parents) || mcid < 0 || mcid >= parents.size()
        || dictionary(parents.get(mcid)) != previous) throw new IOException("Imported marked content has no matching ParentTree owner");
    parents.set(mcid, owner);
  }

  private static boolean pruneEmpty(COSDictionary element) {
    // A blank cell still occupies a column. Removing it makes an otherwise
    // regular table ragged and shifts the relationships read by assistive tools.
    boolean keepCell = PdfMailWriter.pdfUaFeaturesEnabled()
        && Set.of("TD", "TH", "Mail_FieldValue").contains(element.getNameAsString(COSName.S, ""));
    COSBase content = element.getDictionaryObject(COSName.K);
    if (content instanceof COSArray array) {
      for (int index = array.size() - 1; index >= 0; index--) {
        COSDictionary child = dictionary(array.get(index));
        if (child != null && child.containsKey(COSName.S) && !pruneEmpty(child)) array.remove(index);
      }
      if (array.size() == 0) element.removeItem(COSName.K);
      return keepCell || array.size() > 0;
    }
    COSDictionary child = dictionary(content);
    if (child != null && child.containsKey(COSName.S)) {
      if (pruneEmpty(child)) return true;
      element.removeItem(COSName.K);
      return keepCell;
    }
    return keepCell || content != null;
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

  /** Finish the parent number tree after all content/tagging edits. */
  static void balanceParentTree(PDDocument document) throws IOException {
    COSDictionary structure = dictionary(document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT));
    if (structure == null) return;
    COSDictionary root = dictionary(structure.getDictionaryObject(COSName.getPDFName("ParentTree")));
    if (root == null || !(root.getDictionaryObject(COSName.NUMS) instanceof COSArray original) || original.size() <= 128) return;
    if ((original.size() & 1) != 0) throw new IOException("Invalid structure parent number tree");
    var entries = new java.util.TreeMap<Long, COSBase>();
    for (int i = 0; i < original.size(); i += 2) {
      if (!(original.getObject(i) instanceof COSInteger key) || entries.put(key.longValue(), original.get(i + 1)) != null)
        throw new IOException("Invalid or duplicate structure parent key");
    }
    List<COSDictionary> level = new ArrayList<>();
    COSArray numbers = null;
    COSDictionary leaf = null;
    for (var entry : entries.entrySet()) {
      if (numbers == null || numbers.size() == 128) {
        leaf = new COSDictionary(); numbers = new COSArray(); leaf.setItem(COSName.NUMS, numbers); level.add(leaf);
      }
      numbers.add(COSInteger.get(entry.getKey())); numbers.add(entry.getValue());
    }
    for (COSDictionary node : level) {
      COSArray values = (COSArray)node.getDictionaryObject(COSName.NUMS);
      COSArray limits = new COSArray(); limits.add(values.get(0)); limits.add(values.get(values.size() - 2));
      node.setItem(COSName.LIMITS, limits);
    }
    while (level.size() > 64) {
      List<COSDictionary> next = new ArrayList<>();
      for (int i = 0; i < level.size(); i += 64) {
        COSDictionary node = new COSDictionary();
        parentBranch(document, node, level.subList(i, Math.min(i + 64, level.size())), true); next.add(node);
      }
      level = next;
    }
    root.removeItem(COSName.NUMS); root.removeItem(COSName.LIMITS);
    parentBranch(document, root, level, false);
  }

  private static void parentBranch(PDDocument document, COSDictionary node, List<COSDictionary> children, boolean limits) throws IOException {
    COSArray kids = new COSArray();
    for (COSDictionary child : children) kids.add(PdfDocumentParts.indirect(document, child));
    node.setItem(COSName.KIDS, kids);
    if (limits) {
      COSArray range = new COSArray();
      range.add(((COSArray)children.getFirst().getDictionaryObject(COSName.LIMITS)).get(0));
      range.add(((COSArray)children.getLast().getDictionaryObject(COSName.LIMITS)).get(1));
      node.setItem(COSName.LIMITS, range);
    }
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
      COSBase existing = parentValue(numbers, key);
      if (existing instanceof COSArray array) return array;
    }
    COSArray values = new COSArray();
    int key = nextKey(tree);
    numbers.add(COSInteger.get(key));
    numbers.add(values);
    page.getCOSObject().setInt(name, key);
    return values;
  }

  // Keys are allocated in increasing order when entries are appended. Avoid
  // rescanning every earlier page for each text run in a long archive.
  private static COSBase parentValue(COSArray numbers, int key) {
    int low = 0, high = numbers.size() / 2 - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      int candidate = ((COSNumber)numbers.getObject(middle * 2)).intValue();
      if (candidate == key) return numbers.getObject(middle * 2 + 1);
      if (candidate < key) low = middle + 1;
      else high = middle - 1;
    }
    return null;
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
    COSArray contentParents = null;
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
      if (name.equals("BDC") && operands.size() == 2
          && COSName.getPDFName("Span").equals(operands.get(0))) {
        Object properties = operands.get(1);
        if (properties instanceof COSName propertyName && page.getResources() != null) {
          var named = page.getResources().getProperties(propertyName);
          properties = named == null ? null : named.getCOSObject();
        }
        int mcid = properties instanceof COSDictionary attributes ? attributes.getInt(COSName.MCID, -1) : -1;
        if (mcid >= 0) {
          if (contentParents == null) contentParents = pageParents(document, tree, page);
          // The HTML renderer can leave later-page Span markers with null
          // ParentTree owners. Retain their text and bind those existing MCIDs.
          if (mcid < contentParents.size()
              && (contentParents.getObject(mcid) == null || contentParents.getObject(mcid) instanceof org.apache.pdfbox.cos.COSNull)) {
            COSDictionary parent = groups.peek();
            COSArray target = parent == contentSet ? children : (COSArray)parent.getDictionaryObject(COSName.K);
            COSDictionary paragraph = element(document, parent, target, page, "P");
            COSDictionary span = element(document, paragraph, children(paragraph), page, "Span");
            span.setInt(COSName.K, mcid);
            contentParents.set(mcid, PdfDocumentParts.indirect(document, span));
          }
        }
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
        if (PdfMailWriter.pdfUaFeaturesEnabled()
            || annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment) {
          int key = annotation.getCOSObject().getInt(COSName.getPDFName("StructParent"));
          COSDictionary owner = dictionary(parentValue(parents(document, tree), key));
          if (owner != null) {
            if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment)
              owner.setName(COSName.S, "Annot");
            if (PdfMailWriter.pdfUaFeaturesEnabled() && owner.containsKey(COSName.ALT)) {
              String alternate = Common.nz(owner.getString(COSName.ALT));
              String contents = Common.nz(annotation.getContents());
              // Retain an authored description, or use the renderer's visible
              // link text when it supplied an empty Alt entry.
              if (alternate.isBlank() && !contents.isBlank()) owner.setString(COSName.ALT, contents);
              else if (!alternate.isBlank()) annotation.setContents(alternate);
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
