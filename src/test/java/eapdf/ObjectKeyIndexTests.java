package eapdf;
import java.util.*;
import org.apache.pdfbox.cos.COSObjectKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ObjectKeyIndexTests {
 @Test void preservesCanonicalStreamIndicesGenerationsAndGrowingXrefs() {
  var index=new ObjectKeyIndex();Map<COSObjectKey,Long> xref=new HashMap<>();
  var compressed=new COSObjectKey(10,0,17);var generation=new COSObjectKey(10,1);
  xref.put(compressed,-5L);xref.put(generation,100L);
  assertSame(compressed,index.get(xref,10,0));assertSame(generation,index.get(xref,10,1));
  assertEquals(new COSObjectKey(999,0),index.get(xref,999,0));
  var added=new COSObjectKey(99,0,23);xref.put(added,-8L);assertSame(added,index.get(xref,99,0));
 }
 @Test void sparseAndLargeObjectNumbersDoNotAllocateHugeArraysOrMergeKeys() {
  var index=new ObjectKeyIndex();Map<COSObjectKey,Long> xref=new HashMap<>();
  for(long n:new long[]{1,65537,16777217,2147483647L})for(int gen:new int[]{0,1,65535})xref.put(new COSObjectKey(n,gen),n);
  for(var key:xref.keySet())assertSame(key,index.get(xref,key.getNumber(),key.getGeneration()));
 }
}
