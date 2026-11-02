package org.verapdf.parser;
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
}
