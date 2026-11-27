package org.verapdf.parser;
import org.verapdf.as.ASAtom;
import org.verapdf.as.io.ASMemoryInStream;
import org.verapdf.cos.COSArray;
import org.verapdf.cos.COSInteger;
import org.verapdf.cos.COSObject;
import org.verapdf.cos.COSStream;
import org.verapdf.cos.xref.COSXRefInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class XrefStreamParserTests {
 @Test void offsetsAreUnsignedAndShiftedIn64Bits() throws Exception {
  var method=XrefStreamParser.class.getDeclaredMethod("numberFromBytes",byte[].class);method.setAccessible(true);
  for(long value:new long[]{0,2147483647L,2147483648L,2463914353L,4294967295L,4294967296L,Long.MAX_VALUE}) {
   byte[] bytes=java.nio.ByteBuffer.allocate(8).putLong(value).array();
   assertEquals(value,method.invoke(null,(Object)bytes));
   if(value<=4294967295L)assertEquals(value,method.invoke(null,(Object)java.util.Arrays.copyOfRange(bytes,4,8)));
  }
 }

 @Test void sparseRangesKeepObjectNumbersAndReportDecodedWork() throws Exception {
  byte[] data = {1, 0, 42, 0, 0, 0, 2, 0, 17};
  COSStream stream = (COSStream) COSStream.construct(new ASMemoryInStream(data)).get();
  stream.setLength(data.length);
  stream.setKey(ASAtom.W, integerArray(1, 2, 0));
  stream.setKey(ASAtom.INDEX, integerArray(1, 2, 10, 1));
  var section = new COSXRefInfo();
  long[] totals = new long[3];
  try (var progress = new XrefProgress((entries, encoded, decoded) -> {
   totals[0] += entries;
   totals[1] += encoded;
   totals[2] += decoded;
  })) {
   new XrefStreamParser(section, stream).parseStreamAndTrailer();
  }
  assertEquals(42, section.getXRefSection().getEntry(1).offset);
  assertNull(section.getXRefSection().getEntry(2));
  assertEquals(-17, section.getXRefSection().getEntry(10).offset);
  assertArrayEquals(new long[] {3, data.length, data.length}, totals);
 }

 private static COSObject integerArray(long... values) {
  return COSArray.construct(values.length, java.util.Arrays.stream(values)
      .mapToObj(COSInteger::construct).toArray(COSObject[]::new));
 }
}
