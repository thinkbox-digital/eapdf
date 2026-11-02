package eapdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UserPropertiesArrayTests {
  private COSDictionary properties(String guid) {
    COSDictionary attr = new COSDictionary();
    attr.setName(COSName.O, "UserProperties");
    COSDictionary prop = new COSDictionary();
    prop.setString(COSName.N, "Mail_GUID");
    prop.setString(COSName.V, guid);
    COSArray values = new COSArray();
    values.add(prop);
    attr.setItem(COSName.P, values);
    return attr;
  }

  private String guid(COSDictionary attr) {
    COSArray values = (COSArray) attr.getDictionaryObject(COSName.P);
    for (int index = 0; index < values.size(); index++) {
      COSDictionary prop = (COSDictionary) values.getObject(index);
      if ("Mail_GUID".equals(prop.getString(COSName.N))) return prop.getString(COSName.V);
    }
    return null;
  }

  @Test void updateTargetsLastOwnerAndDoesNotTouchEarlierValue() {
    COSDictionary earlier = properties("uuid:earlier");
    COSDictionary later = properties("uuid:later");
    COSArray attrs = new COSArray();
    attrs.add(earlier);
    attrs.add(COSInteger.get(5));
    attrs.add(later);
    attrs.add(COSInteger.ZERO);
    COSDictionary element = new COSDictionary();
    element.setInt(COSName.R, 5);
    element.setItem(COSName.A, attrs);
    PdfMailWriter.addStringUserProperty(element, "Mail_GUID", "uuid:updated");
    COSArray current = (COSArray) element.getDictionaryObject(COSName.A);
    Assertions.assertEquals("uuid:earlier", guid(earlier));
    Assertions.assertEquals("uuid:updated", guid((COSDictionary) current.getObject(2)));
    Assertions.assertEquals(5, ((COSInteger) current.getObject(3)).intValue());
    Assertions.assertEquals(5, element.getInt(COSName.R));
  }

  @Test void classValuesAreCopiedLocallyAndSharedClassRemainsUnchanged() {
    COSDictionary shared = properties("uuid:class");
    COSDictionary map = new COSDictionary();
    map.setItem(COSName.getPDFName("Email"), shared);
    COSDictionary tree = new COSDictionary();
    tree.setName(COSName.TYPE, "StructTreeRoot");
    tree.setItem(COSName.getPDFName("ClassMap"), map);
    COSDictionary element = new COSDictionary();
    element.setItem(COSName.P, tree);
    element.setName(COSName.C, "Email");
    PdfMailWriter.addStringUserProperty(element, "Mail_MessageID", "<local@example.invalid>");
    COSDictionary local = (COSDictionary) element.getDictionaryObject(COSName.A);
    Assertions.assertEquals("uuid:class", guid(local));
    Assertions.assertEquals("uuid:class", guid(shared));
    Assertions.assertEquals(1, ((COSArray) shared.getDictionaryObject(COSName.P)).size());
    PdfMailWriter.addStringUserProperty(element, "Mail_GUID", "uuid:local");
    Assertions.assertEquals("uuid:local", guid((COSDictionary) element.getDictionaryObject(COSName.A)));
    Assertions.assertEquals("uuid:class", guid(shared));
  }

  @Test void updateDoesNotMutateSharedDirectAttributeOrArray() {
    COSDictionary shared = properties("uuid:shared");
    COSArray attrs = new COSArray();
    attrs.add(shared);
    attrs.add(COSInteger.ZERO);
    COSDictionary first = new COSDictionary();
    COSDictionary peer = new COSDictionary();
    first.setItem(COSName.A, attrs);
    peer.setItem(COSName.A, attrs);
    first.setInt(COSName.R, 3);
    PdfMailWriter.addStringUserProperty(first, "Mail_GUID", "uuid:first");
    Assertions.assertEquals("uuid:shared", guid((COSDictionary)
        ((COSArray) peer.getDictionaryObject(COSName.A)).getObject(0)));
    COSArray current = (COSArray) first.getDictionaryObject(COSName.A);
    Assertions.assertEquals("uuid:first", guid((COSDictionary) current.getObject(0)));
    Assertions.assertEquals(3, ((COSInteger) current.getObject(1)).intValue());
    Assertions.assertEquals(0, ((COSInteger) attrs.getObject(1)).intValue());
  }

  @Test void classArrayOrderAndDirectWholePControlLocalUpdate() {
    COSDictionary first = properties("uuid:first");
    COSDictionary last = properties("uuid:last");
    COSDictionary map = new COSDictionary();
    map.setItem(COSName.getPDFName("First"), first);
    COSArray classAttributes = new COSArray();
    COSDictionary layout = new COSDictionary();
    layout.setName(COSName.O, "Layout");
    classAttributes.add(layout);
    classAttributes.add(last);
    map.setItem(COSName.getPDFName("Last"), classAttributes);
    COSDictionary tree = new COSDictionary();
    tree.setName(COSName.TYPE, "StructTreeRoot");
    tree.setItem(COSName.getPDFName("ClassMap"), map);
    COSDictionary parent = new COSDictionary();
    parent.setItem(COSName.P, tree);
    COSDictionary element = new COSDictionary();
    element.setItem(COSName.P, parent);
    COSArray classes = new COSArray();
    classes.add(COSName.getPDFName("First"));
    classes.add(COSInteger.get(5));
    classes.add(COSName.getPDFName("Last"));
    classes.add(COSInteger.ZERO);
    element.setItem(COSName.C, classes);
    PdfMailWriter.addStringUserProperty(element, "Mail_MessageID", "<local@example.invalid>");
    COSDictionary local = (COSDictionary) element.getDictionaryObject(COSName.A);
    Assertions.assertEquals("uuid:last", guid(local));
    Assertions.assertEquals("uuid:first", guid(first));
    Assertions.assertEquals(1, ((COSArray) last.getDictionaryObject(COSName.P)).size());
    COSDictionary empty = new COSDictionary();
    empty.setName(COSName.O, "UserProperties");
    empty.setItem(COSName.P, new COSArray());
    element.setItem(COSName.A, empty);
    PdfMailWriter.addStringUserProperty(element, "Mail_MessageID", "<direct@example.invalid>");
    local = (COSDictionary) element.getDictionaryObject(COSName.A);
    Assertions.assertNull(guid(local));
    Assertions.assertEquals(1, ((COSArray) local.getDictionaryObject(COSName.P)).size());
    Assertions.assertEquals(0, ((COSArray) empty.getDictionaryObject(COSName.P)).size());
  }

  @Test void addingMailPropertyRetainsExistingAttributeArrayAndProperties() {
    COSDictionary layout = new COSDictionary();
    layout.setName(COSName.O, "Layout");
    COSDictionary properties = new COSDictionary();
    properties.setName(COSName.O, "UserProperties");
    COSArray values = new COSArray();
    COSDictionary existing = new COSDictionary();
    existing.setString(COSName.N, "Mail_GUID");
    existing.setString(COSName.V, "uuid:one");
    values.add(existing);
    properties.setItem(COSName.P, values);
    COSArray attributes = new COSArray();
    attributes.add(layout);
    attributes.add(COSInteger.ZERO);
    attributes.add(properties);
    attributes.add(COSInteger.ZERO);
    COSDictionary element = new COSDictionary();
    element.setItem(COSName.A, attributes);

    PdfMailWriter.addStringUserProperty(element, "Mail_MessageID", "<one@example.org>");
    COSArray current = (COSArray) element.getDictionaryObject(COSName.A);
    Assertions.assertSame(layout, current.getObject(0));
    Assertions.assertEquals(4, current.size());
    COSArray currentValues = (COSArray) ((COSDictionary) current.getObject(2)).getDictionaryObject(COSName.P);
    Assertions.assertEquals(2, currentValues.size());
    Assertions.assertEquals("uuid:one", ((COSDictionary) currentValues.getObject(0)).getString(COSName.V));
    PdfMailWriter.addStringUserProperty(element, "Mail_GUID", "uuid:updated");
    current = (COSArray) element.getDictionaryObject(COSName.A);
    currentValues = (COSArray) ((COSDictionary) current.getObject(2)).getDictionaryObject(COSName.P);
    Assertions.assertEquals(2, currentValues.size());
    Assertions.assertEquals("uuid:updated", ((COSDictionary) currentValues.getObject(0)).getString(COSName.V));
    Assertions.assertEquals(1, values.size());
    Assertions.assertEquals("uuid:one", existing.getString(COSName.V));
  }

  @Test void addingUserPropertiesPreservesAnExistingLayoutDictionary() {
    COSDictionary layout = new COSDictionary();
    layout.setName(COSName.O, "Layout");
    COSDictionary element = new COSDictionary();
    element.setItem(COSName.A, layout);
    PdfMailWriter.addStringUserProperty(element, "Mail_GUID", "uuid:one");
    Assertions.assertInstanceOf(COSArray.class, element.getDictionaryObject(COSName.A));
    COSArray attributes = (COSArray) element.getDictionaryObject(COSName.A);
    Assertions.assertEquals(2, attributes.size());
    Assertions.assertSame(layout, attributes.getObject(0));
    Assertions.assertEquals("UserProperties", ((COSDictionary) attributes.getObject(1)).getNameAsString(COSName.O));
  }
}
