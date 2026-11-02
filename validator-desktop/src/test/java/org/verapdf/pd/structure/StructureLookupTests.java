package org.verapdf.pd.structure;
import java.util.Map;
import org.verapdf.as.ASAtom;
import org.verapdf.cos.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StructureLookupTests {
 static int parses;
 static class CountedTree extends PDNumberTreeNode {
  CountedTree(COSObject value){super(value);}
  @Override public Map<Long,COSObject> parseNums(){parses++;return super.parseNums();}
 }
 static COSObject root(COSObject owner){return COSDictionary.construct(ASAtom.NUMS,COSArray.construct(2,new COSObject[]{COSInteger.construct(5),COSArray.construct(1,new COSObject[]{owner})}));}
 @Test void repeatedWrappersReuseTheIndexButDifferentDocumentsDoNot() {
  StructureElementAccessObject.clearParentTreeCache();parses=0;
  try {
   var page=COSDictionary.construct(ASAtom.STRUCT_PARENTS,5);var lookup=new StructureElementAccessObject(page);
   var owner=COSDictionary.construct();var first=root(owner);
   for(int i=0;i<100;i++)assertSame(owner.getDirectBase(),lookup.getStructureElement(new CountedTree(first),0L).getDirectBase());
   assertEquals(1,parses);
   var other=COSDictionary.construct();var second=root(other);
   assertSame(other.getDirectBase(),lookup.getStructureElement(new CountedTree(second),0L).getDirectBase());assertEquals(2,parses);
   StructureElementAccessObject.clearParentTreeCache();
   assertSame(other.getDirectBase(),lookup.getStructureElement(new CountedTree(second),0L).getDirectBase());assertEquals(3,parses);
  }finally{StructureElementAccessObject.clearParentTreeCache();}
 }
}
