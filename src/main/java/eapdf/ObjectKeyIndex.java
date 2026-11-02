package eapdf;

import java.util.HashMap;
import java.util.Map;
import org.apache.pdfbox.cos.COSObjectKey;

/** Canonical xref keys without a boxed hash entry for every dense object number. */
final class ObjectKeyIndex {
  private static final long MAX_DENSE_NUMBER=16L*1024*1024;
  private int count=-1;
  private COSObjectKey[] dense;
  private Map<Long,COSObjectKey> other=Map.of();

  COSObjectKey get(Map<COSObjectKey,Long> xref,long number,int generation) {
    if(xref.size()!=count) {
      long maximum=0;
      for(var key:xref.keySet())maximum=Math.max(maximum,key.getNumber());
      dense=maximum<=MAX_DENSE_NUMBER&&maximum<=Math.max(4096,2L*xref.size())?new COSObjectKey[(int)maximum+1]:null;
      other=new HashMap<>();
      for(var key:xref.keySet()) {
        if(dense!=null&&key.getGeneration()==0)dense[(int)key.getNumber()]=key;
        else other.put(mix(key.getInternalHash()),key);
      }
      count=xref.size();
    }
    COSObjectKey found=dense!=null&&generation==0&&number>=0&&number<dense.length?dense[(int)number]:null;
    if(found==null&&!other.isEmpty())found=other.get(mix(COSObjectKey.computeInternalHash(number,generation)));
    return found==null?new COSObjectKey(number,generation):found;
  }
  // A bijection on 64-bit keys; HashMap still checks the complete Long value.
  private static long mix(long key) {
    key=(key^(key>>>30))*0xbf58476d1ce4e5b9L;
    key=(key^(key>>>27))*0x94d049bb133111ebL;
    return key^(key>>>31);
  }
}
