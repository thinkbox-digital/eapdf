package eapdf;
import java.nio.file.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDParentTreeValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ParentNumberTreeTests {
 @TempDir Path temporary;
 @Test void largeParentTreePreservesEveryLookupAfterSaving() throws Exception {
  Path saved=temporary.resolve("tree.pdf");
  try(var doc=new PDDocument()) {
   doc.addPage(new PDPage());var tree=new COSDictionary();var parent=new COSDictionary();var nums=new COSArray();
   doc.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT,tree);
   tree.setItem(COSName.getPDFName("ParentTree"),parent);parent.setItem(COSName.NUMS,nums);
   for(int i=0;i<5000;i++) {var value=new COSArray();value.add(new COSString("owner-"+i));nums.add(COSInteger.get(i*2L));nums.add(value);}
   SemanticStructure.balanceParentTree(doc);SemanticStructure.balanceParentTree(doc);
   assertFalse(parent.containsKey(COSName.NUMS));assertTrue(((COSArray)parent.getDictionaryObject(COSName.KIDS)).size()<=64);
   doc.save(saved.toFile());
  }
  try(var doc=IndexedPdfLoader.load(saved)) {
   var tree=(COSDictionary)doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
   var root=(COSDictionary)tree.getDictionaryObject(COSName.getPDFName("ParentTree"));
   var index=new PDNumberTreeNode(root,PDParentTreeValue.class);
   for(int i=0;i<5000;i++) {
    var value=(COSArray)((PDParentTreeValue)index.getValue(i*2)).getCOSObject();assertEquals("owner-"+i,((COSString)value.getObject(0)).getString());
    assertNull(index.getValue(i*2+1));
   }
   assertNull(index.getValue(-1));assertNull(index.getValue(10000));
  }
 }
}
