import java.util.List;
import org.verapdf.as.ASAtom;
import org.verapdf.cos.*;
import org.verapdf.gf.model.impl.arlington.*;
import org.verapdf.gf.model.impl.containers.StaticContainers;
import org.verapdf.pdfa.flavours.PDFAFlavour;

/** Exercise actual model values used by the unchanged Arlington predicates. */
public class ArlingtonSemanticsRegression {
  public static void main(String[] args) {
    StaticContainers.setFlavour(List.of(PDFAFlavour.ARLINGTON2_0));
    for (int length : new int[] {15,16,17}) {
      byte[] bytes = new byte[length];bytes[0]=(byte)0xfe;bytes[1]=(byte)0xff;
      COSObject params = COSDictionary.construct(ASAtom.getASAtom("CheckSum"),COSString.construct(bytes));
      Long plain = new GFAEmbeddedFileParameter(params.getDirectBase(),null,"Params").getCheckSumStringSize();
      Long associated = new GFAAFEmbeddedFileParameter(params.getDirectBase(),null,"Params").getCheckSumStringSize();
      if (plain != length || associated != length) throw new AssertionError("Binary length " + length);
      if ((plain == 16) != (length == 16)) throw new AssertionError("Invalid checksum accepted");
    }
    COSObject leaf = COSDictionary.construct();
    COSObject kids = COSArray.construct(1,new COSObject[]{leaf});
    COSObject parent = COSDictionary.construct(ASAtom.getASAtom("Kids"),kids);
    var entry = new GFANumberTreeNodesArrayEntry(leaf.getDirectBase(),kids.getDirectBase(),parent.getDirectBase(),"Kids","0");
    var model = (GFANumberTreeNode) entry.getLinkedObjects("Entry").get(0);
    if (!model.getcontainsparentKids()) throw new AssertionError("Lost dictionary parent through Kids array");
    COSObject catalog = COSDictionary.construct();
    var root = new GFANumberTreeNode(leaf.getDirectBase(),catalog.getDirectBase(),"ParentTree");
    if (root.getcontainsparentKids()) throw new AssertionError("Root falsely treated as child");
    System.out.println("PASS: binary checksum lengths 15/16/17, child parent context, root context");
  }
}
