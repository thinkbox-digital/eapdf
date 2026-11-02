package com.pff;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

/** Narrow java-libpst 0.9.3 repair for MS-PST subnode index blocks (SIBLOCK).
 * The upstream parser treats index entries as leaf entries and overruns ANSI
 * blocks. Index entries point to leaf blocks; they are not attachment values.
 */
public final class EaPdfPstFile extends PSTFile {
  public EaPdfPstFile(String path) throws IOException, PSTException { super(path); }
  public EaPdfPstFile(File file) throws IOException, PSTException { super(file); }

  @Override
  HashMap<Integer, PSTDescriptorItem> getPSTDescriptorItems(PSTNodeInputStream input)
      throws PSTException, IOException {
    return descriptors(input, false);
  }

  private HashMap<Integer, PSTDescriptorItem> descriptors(PSTNodeInputStream input, boolean requireLeaf)
      throws PSTException, IOException {
    input.seek(0);
    if (input.read() != 2) throw new PSTException("Invalid subnode block signature");
    int level = input.read();
    if (level < 0 || level > 1 || requireLeaf && level != 0)
      throw new PSTException("Invalid subnode block level: " + level);
    boolean ansi = getPSTFileType() == PST_TYPE_ANSI;
    int header = ansi ? 4 : 8;
    int entry = level == 0 ? (ansi ? 12 : 24) : (ansi ? 8 : 16);
    int count = (int) input.seekAndReadLong(2, 2);
    if (header + (long) count * entry > input.length()) throw new PSTException("Truncated subnode block");
    if (level == 0) return super.getPSTDescriptorItems(input);
    var result = new HashMap<Integer, PSTDescriptorItem>();
    var seen = new java.util.HashSet<Long>();
    for (int i = 0; i < count; i++) {
      long bid = input.seekAndReadLong(header + i * entry + (ansi ? 4 : 8), ansi ? 4 : 8) & ~1L;
      if (bid == 0 || !seen.add(bid)) throw new PSTException("Invalid or repeated subnode leaf block");
      for (var item : descriptors(readLeaf(bid), true).entrySet()) {
        if (result.putIfAbsent(item.getKey(), item.getValue()) != null)
          throw new PSTException("Duplicate subnode identifier: " + item.getKey());
      }
    }
    return result;
  }
}
