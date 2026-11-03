package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.junit.jupiter.api.Test;

class StructureRolesTests {
  private final COSDictionary root = new COSDictionary();
  private final COSDictionary element = new COSDictionary();
  private int next = 1;
  StructureRolesTests() { root.setItem(COSName.ROLE_MAP, new COSDictionary()); root.setItem(COSName.K, element); root.setItem(COSName.getPDFName("Namespaces"), new COSArray()); }
  private COSDictionary map() { return (COSDictionary) root.getDictionaryObject(COSName.ROLE_MAP); }
  private COSObject namespace(String uri) {
    var ns = new COSDictionary(); ns.setString("NS", uri); ns.setItem("RoleMapNS", new COSDictionary());
    var ref = new COSObject(ns, new COSObjectKey(next++, 0));
    ((COSArray) root.getDictionaryObject("Namespaces")).add(ref); return ref;
  }
  private COSDictionary mapping(COSObject ref) { return (COSDictionary) ((COSDictionary) ref.getObject()).getDictionaryObject("RoleMapNS"); }
  private COSArray pair(String role, COSBase ns) { var pair = new COSArray(); pair.add(COSName.getPDFName(role)); pair.add(ns); return pair; }
  private StructureRoles.Result result() { return new StructureRoles(root, true).element(element); }
  @Test void legacyChainsAndStandardOverrides() {
    element.setName(COSName.S, "CustomRoot"); map().setName("CustomRoot", "Alias"); map().setName("Alias", "Document");
    assertEquals("Document", result().role()); map().setName("Document", "Part"); assertEquals("Part", result().role());
    map().setName("Part", "CustomRoot"); assertEquals("", result().role());
  }
  @Test void explicitNamespaceChainsIgnoreConflictingRootMap() {
    var ns = namespace("urn:custom"); var bridge = namespace("urn:bridge"); var standard = namespace(StructureRoles.PDF20);
    element.setName(COSName.S,"CustomRoot"); element.setItem("NS",ns); map().setName("CustomRoot","Part");
    mapping(ns).setItem("CustomRoot",pair("Alias",bridge)); mapping(bridge).setItem("Alias",pair("Document",standard));
    assertEquals("Document",result().role()); mapping(ns).setName("CustomRoot","Document"); assertEquals("Document",result().role());
    assertEquals("Part",new StructureRoles(root,false).element(element).role());
  }
  @Test void invalidTargetsAndUnregisteredNamespacesFail() {
    var ns = namespace("urn:custom"); var missing = new COSDictionary(); missing.setString("NS",StructureRoles.PDF20);
    element.setName(COSName.S,"CustomRoot"); element.setItem("NS",ns);
    for(COSBase target:List.of(new COSString("Document"),COSName.getPDFName("Unknown"),pair("Document",missing),pair("CustomRoot",ns),pair("Document",new COSObject(missing,new COSObjectKey(50,0))))) {
      mapping(ns).setItem("CustomRoot",target); assertEquals("",result().role());
    }
    element.setName(COSName.S,"Document");element.setItem("NS",new COSObject(missing,new COSObjectKey(51,0)));assertEquals("",result().role());
  }
  @Test void namespaceVocabularyAndMissingTargetsAreNotGuessed() {
    var ns=namespace(StructureRoles.PDF20);element.setItem("NS",ns);element.setName(COSName.S,"Art");assertEquals("",result().role());
    element.setName(COSName.S,"H12");assertEquals("H12",result().role());
    element.setItem("NS",namespace("urn:custom"));element.setName(COSName.S,"Document");assertEquals("",result().role());
  }
  @Test void longRoleMappingChainsAreFullyResolved() {
    element.setName(COSName.S,"R0");for(int i=0;i<4100;i++)map().setName("R"+i,"R"+(i+1));map().setName("R4100","Document");
    assertFalse(result().limited()); assertEquals("Document", result().role());
  }
  private JavaValidation.Report report(String path) {
    var report=new JavaValidation.Report(path);report.pdfa=new com.google.gson.JsonObject();report.pdfa.addProperty("status","NOT_RUN");return report;
  }
  private boolean failed(JavaValidation.Report report,String code) {
    return report.items.asList().stream().map(v->v.getAsJsonObject()).anyMatch(v->code.equals(v.get("code").getAsString())&&"FAIL".equals(v.get("level").getAsString()));
  }
  @Test void realExampleMappedRootAcceptedAndWrongNamespaceTargetRejected() throws Exception {
    try(var doc=Loader.loadPDF(Path.of("..","examples","eml","eml_Agenda_and_timeline_slides_1s.pdf").toFile())) {
      var tree=EaPdfChecks.dict(doc.getDocumentCatalog().getCOSObject(),"StructTreeRoot");
      COSBase k=EaPdfChecks.get(tree,"K");if(k instanceof COSArray a)k=a.getObject(0);var top=(COSDictionary) k;
      top.removeItem(COSName.getPDFName("NS"));top.setName(COSName.S,"CustomRoot");EaPdfChecks.dict(tree,"RoleMap").setName("CustomRoot","Document");
      var report=report("real.pdf");new LogicalStructureChecks(doc,report,List.of(),"s",null).run();
      assertFalse(failed(report,"STRUCTTREE_ROOT_DOCUMENT"));assertFalse(failed(report,"ROLEMAPNS_PRESENT"));
      for(COSBase item:(COSArray) EaPdfChecks.get(tree,"Namespaces")) {
        var ns=(COSDictionary) EaPdfChecks.resolve(item);
        if("https://pdfa.org/ns/ea-pdf/mail-1".equals(EaPdfChecks.string(EaPdfChecks.get(ns,"NS"))))EaPdfChecks.dict(ns,"RoleMapNS").setName("Mail_Message","Part");
      }
      report=report("mutated.pdf");new LogicalStructureChecks(doc,report,List.of(),"s",null).run();
      assertTrue(failed(report,"ROLEMAPNS_PRESENT"));assertFalse(failed(report,"ROLEMAP_MAIL_TAGS"));
    }
  }
}
