package eapdf;

import static eapdf.EaPdfChecks.*;
import java.util.*;
import org.apache.pdfbox.cos.*;

/** ISO 32000-2 Tables 354/356 and 14.8.6.2. Namespace-aware semantic roles. */
final class StructureRoles {
  static final String PDF17 = "http://iso.org/pdf/ssn", PDF20 = "http://iso.org/pdf2/ssn";
  static final Set<String> COMMON = Set.of("Document", "Part", "Sect", "Div", "NonStruct", "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "L", "LI", "Lbl", "LBody", "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot", "Caption", "Span", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP", "Figure", "Formula", "Form");
  static final Set<String> LEGACY = Set.of("Art", "BlockQuote", "TOC", "TOCI", "Index", "Private", "Quote", "Note", "Reference", "BibEntry", "Code");
  static final Set<String> MODERN = Set.of("DocumentFragment", "Aside", "Title", "FENote", "Sub", "Em", "Strong", "Artifact");
  record Result(String role, String error) { boolean limited() { return "Role mapping inspection limit reached".equals(error); } }
  private record Key(COSDictionary namespace, String role) {}
  private final COSDictionary root;
  private final boolean pdf20;
  private final Set<COSDictionary> registered = identitySet();
  private final Map<Key, Result> cache = new HashMap<>();
  StructureRoles(COSDictionary root, boolean pdf20) {
    this.root = root; this.pdf20 = pdf20;
    if (get(root, "Namespaces") instanceof COSArray array) for (COSBase item : array)
      if (item instanceof COSObject && resolve(item) instanceof COSDictionary ns && !(ns instanceof COSStream)) registered.add(ns);
  }
  Result element(COSDictionary element) {
    COSBase raw = pdf20 ? get(element, "NS") : null;
    if (raw != null && (!(element.getItem(COSName.getPDFName("NS")) instanceof COSObject) || !(raw instanceof COSDictionary) || raw instanceof COSStream))
      return new Result("", "Invalid structure namespace reference");
    return role(name(get(element, "S")), (COSDictionary) raw);
  }
  Result role(String role, COSDictionary namespace) {
    Key initial = new Key(namespace, role); Result cached = cache.get(initial); if (cached != null) return cached;
    Set<Key> seen = new HashSet<>(); Result result;
    while (true) {
      if (seen.size() >= 4096) { result = new Result("", "Role mapping inspection limit reached"); break; }
      if (role.isEmpty() || !seen.add(new Key(namespace, role))) { result = new Result("", "Missing or cyclic role mapping"); break; }
      String uri = namespace == null ? PDF17 : string(get(namespace, "NS"));
      if (namespace != null && (!registered.contains(namespace) || uri.isEmpty())) { result = new Result("", "Unregistered or unnamed namespace"); break; }
      COSBase mappingObject = get(namespace == null ? root : namespace, namespace == null ? "RoleMap" : "RoleMapNS");
      if (mappingObject != null && (!(mappingObject instanceof COSDictionary) || mappingObject instanceof COSStream)) { result = new Result("", "Invalid role-map dictionary"); break; }
      COSBase mapping = get((COSDictionary) mappingObject, role);
      if (mapping == null) {
        boolean standard = (PDF17.equals(uri) && (COMMON.contains(role) || LEGACY.contains(role)))
            || (PDF20.equals(uri) && (COMMON.contains(role) || MODERN.contains(role) || role.matches("H[1-9][0-9]*")));
        result = new Result(standard ? role : "", standard ? "" : "Unresolved standard role"); break;
      }
      if (mapping instanceof COSName target) { role = target.getName(); namespace = null; continue; }
      if (namespace != null && mapping instanceof COSArray pair && pair.size() == 2
          && pair.getObject(0) instanceof COSName target && pair.get(1) instanceof COSObject
          && pair.getObject(1) instanceof COSDictionary targetNamespace && !(targetNamespace instanceof COSStream)) {
        role = target.getName(); namespace = targetNamespace; continue;
      }
      result = new Result("", "Invalid role-map target"); break;
    }
    cache.put(initial, result); return result;
  }
}
