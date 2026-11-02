package eapdf;

import java.util.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

/** Small PDF dictionaries need only ordered pairs, not a hash table and one node per pair. */
final class CompactPdfDictionary extends COSDictionary {
  CompactPdfDictionary(int capacity) { items = new Pairs(capacity); }

  private static final class Pairs extends AbstractMap<COSName, COSBase> {
    private Object[] pairs;
    private int size, revision;
    private Map<COSName, COSBase> large;
    Pairs(int capacity) { pairs = new Object[Math.min(16, capacity) * 2]; }
    private int index(Object key) {
      for (int i = 0; i < size; i++) if (Objects.equals(key, pairs[i * 2])) return i;
      return -1;
    }
    @Override public int size() { return large == null ? size : large.size(); }
    @Override public boolean containsKey(Object key) { return large == null ? index(key) >= 0 : large.containsKey(key); }
    @Override public COSBase get(Object key) {
      if (large != null) return large.get(key);
      int i = index(key); return i < 0 ? null : (COSBase)pairs[i * 2 + 1];
    }
    @Override public COSBase put(COSName key, COSBase value) {
      if (large != null) return large.put(key, value);
      int i = index(key);
      if (i >= 0) { COSBase old = (COSBase)pairs[i * 2 + 1]; pairs[i * 2 + 1] = value; return old; }
      revision++;
      if (size == 16) {
        large = new LinkedHashMap<>();
        for (int j = 0; j < size; j++) large.put((COSName)pairs[j * 2], (COSBase)pairs[j * 2 + 1]);
        pairs = null;
        return large.put(key, value);
      }
      if (size * 2 == pairs.length) pairs = Arrays.copyOf(pairs, Math.min(32, Math.max(4, pairs.length + 4)));
      pairs[size * 2] = key; pairs[size * 2 + 1] = value; size++; return null;
    }
    @Override public COSBase remove(Object key) {
      if (large != null) return large.remove(key);
      int i = index(key); if (i < 0) return null;
      COSBase old = (COSBase)pairs[i * 2 + 1];
      System.arraycopy(pairs, (i + 1) * 2, pairs, i * 2, (size - i - 1) * 2);
      size--; pairs[size * 2] = null; pairs[size * 2 + 1] = null; revision++; return old;
    }
    @Override public void clear() {
      if (large != null) large.clear();
      else { Arrays.fill(pairs, null); size = 0; revision++; }
    }
    @Override public Set<Entry<COSName, COSBase>> entrySet() {
      if (large != null) return large.entrySet();
      return new AbstractSet<>() {
        @Override public int size() { return Pairs.this.size(); }
        @Override public void clear() { Pairs.this.clear(); }
        @Override public Iterator<Entry<COSName, COSBase>> iterator() {
          if (large != null) return large.entrySet().iterator();
          return new Iterator<>() {
            int next, previous = -1, expected = revision;
            private void check() { if (expected != revision) throw new ConcurrentModificationException(); }
            @Override public boolean hasNext() { check(); return next < size; }
            @Override public Entry<COSName, COSBase> next() {
              check(); if (next >= size) throw new NoSuchElementException();
              previous = next++;
              COSName key = (COSName)pairs[previous * 2];
              return new SimpleEntry<>(key, Pairs.this.get(key)) {
                @Override public COSBase setValue(COSBase value) {
                  COSBase old = Pairs.this.put(key, value); super.setValue(value); return old;
                }
              };
            }
            @Override public void remove() {
              check(); if (previous < 0) throw new IllegalStateException();
              Pairs.this.remove(pairs[previous * 2]); next = previous; previous = -1; expected = revision;
            }
          };
        }
      };
    }
  }
}
