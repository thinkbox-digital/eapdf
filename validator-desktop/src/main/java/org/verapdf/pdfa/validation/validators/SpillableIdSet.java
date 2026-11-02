/* Exact, bounded-heap identity storage for the local veraPDF integration.
 * Project code, Apache License 2.0. No probabilistic membership decisions. */
package org.verapdf.pdfa.validation.validators;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.io.IOUtils;

final class SpillableIdSet extends AbstractSet<String> implements AutoCloseable {
    private final int threshold;
    private final Path temporaryRoot;
    private Set<String> memory = new HashSet<>();
    private MappedFile keys, table;
    private long capacity, end = 8;
    private int count, changes;
    private final Map<String, Boolean> recent = new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> entry) { return size() > 2048; }
    };
    private void remember(String text) { if (text.length() <= 1024) recent.put(text, true); }

    SpillableIdSet() { this(100_000, Path.of(System.getProperty("java.io.tmpdir"))); }
    SpillableIdSet(int threshold, Path root) { this.threshold = Math.max(1, threshold); temporaryRoot = root; }
    @Override public int size() { return memory != null ? memory.size() : count; }
    @Override public boolean contains(Object value) {
        if (!(value instanceof String text)) return false;
        if (memory != null) return memory.contains(text);
        if (recent.containsKey(text)) return true;
        try { boolean found = table.getLong(slot(text)) != 0; if (found) remember(text); return found; }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
    @Override public boolean add(String text) {
        Objects.requireNonNull(text);
        if (memory != null && (memory.size() < threshold || memory.contains(text))) {
            boolean added = memory.add(text); if (added) changes++; return added;
        }
        if (recent.containsKey(text)) return false;
        try {
            if (memory != null) spill();
            long slot = slot(text);
            if (table.getLong(slot) != 0) { remember(text); return false; }
            if (count == Integer.MAX_VALUE) throw new IOException("Too many validation identities");
            if ((count + 1L) * 10 > capacity * 7) { grow(); slot = slot(text); }
            long position = end;
            boolean ascii = true;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= 128) { ascii = false; break; }
            // Private scratch format: the sign bit marks one-byte ASCII storage.
            // Other strings retain exact UTF-16 code units, including lone surrogates.
            keys.putInt(position, text.length() | (ascii ? Integer.MIN_VALUE : 0));
            keys.putInt(position + 4, text.hashCode());
            for (int i = 0; i < text.length(); i++) {
                if (ascii) keys.putByte(position + 8 + i, (byte) text.charAt(i));
                else keys.putChar(position + 8 + 2L * i, text.charAt(i));
            }
            end = next(position, text.length(), ascii);
            table.putLong(slot, position); count++; changes++; remember(text); return true;
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private void spill() throws IOException {
        Set<String> previous = memory;
        try {
            keys = new MappedFile(temporaryRoot, "eapdf-vera-ids-");
            table = new MappedFile(temporaryRoot, "eapdf-vera-index-");
            capacity = 16;
            while (capacity < previous.size() * 2L) capacity <<= 1;
            memory = null;
            for (String value : previous) add(value);
        } catch (IOException | RuntimeException error) {
            try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    private long slot(String text) throws IOException {
        long index = bucket(text.hashCode(), capacity);
        for (;;) {
            long offset = index * 8, position = table.getLong(offset);
            if (position == 0 || equal(position, text)) return offset;
            index = (index + 1) & (capacity - 1);
        }
    }
    private boolean equal(long position, String text) throws IOException {
        int encoded = keys.getInt(position);
        if (keys.getInt(position + 4) != text.hashCode() || (encoded & Integer.MAX_VALUE) != text.length()) return false;
        boolean ascii = encoded < 0;
        for (int i = 0; i < text.length(); i++) {
            char actual = ascii ? (char) Byte.toUnsignedInt(keys.getByte(position + 8 + i))
                : keys.getChar(position + 8 + 2L * i);
            if (actual != text.charAt(i)) return false;
        }
        return true;
    }
    private static long bucket(int hash, long capacity) {
        long value = Integer.toUnsignedLong(hash);
        value ^= value >>> 16; value *= 0x7feb352dL; value ^= value >>> 15;
        value *= 0x846ca68bL; value ^= value >>> 16;
        return value & (capacity - 1);
    }
    private static long next(long position, int length, boolean ascii) {
        return (position + 8 + (ascii ? 1L : 2L) * length + 7) & ~7L;
    }
    private void grow() throws IOException {
        MappedFile replacement = new MappedFile(temporaryRoot, "eapdf-vera-index-");
        long expanded = Math.multiplyExact(capacity, 2);
        try {
            for (long i = 0; i < capacity; i++) {
                long position = table.getLong(i * 8);
                if (position == 0) continue;
                long index = bucket(keys.getInt(position + 4), expanded);
                while (replacement.getLong(index * 8) != 0) index = (index + 1) & (expanded - 1);
                replacement.putLong(index * 8, position);
            }
        } catch (IOException | RuntimeException error) {
            try { replacement.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        MappedFile previous = table; table = replacement; capacity = expanded; previous.close();
    }
    @Override public Iterator<String> iterator() {
        if (memory != null) return Collections.unmodifiableSet(memory).iterator();
        int expectedChanges = changes;
        return new Iterator<>() {
            long position = 8;
            public boolean hasNext() { check(); return position < end; }
            public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                try {
                    int encoded = keys.getInt(position), length = encoded & Integer.MAX_VALUE;
                    boolean ascii = encoded < 0; char[] value = new char[length];
                    for (int i = 0; i < length; i++) value[i] = ascii
                        ? (char) Byte.toUnsignedInt(keys.getByte(position + 8 + i))
                        : keys.getChar(position + 8 + 2L * i);
                    position = SpillableIdSet.next(position, length, ascii); return new String(value);
                } catch (IOException error) { throw new UncheckedIOException(error); }
            }
            private void check() { if (changes != expectedChanges) throw new ConcurrentModificationException(); }
        };
    }
    @Override public void clear() {
        IOException failure = null;
        for (MappedFile file : new MappedFile[]{keys, table}) if (file != null) try { file.close(); }
        catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        keys = null; table = null; recent.clear(); memory = new HashSet<>(); count = 0; end = 8; capacity = 0; changes++;
        if (failure != null) throw new UncheckedIOException(failure);
    }
    @Override public void close() { clear(); }

    /** Fixed-size mappings permit files larger than ByteBuffer's 2 GiB address range. */
    private static final class MappedFile implements AutoCloseable {
        private static final int CHUNK = 16 * 1024 * 1024;
        private final Path path;
        private final FileChannel channel;
        private final List<MappedByteBuffer> chunks = new ArrayList<>();
        MappedFile(Path root, String prefix) throws IOException {
            path = IOUtils.createProtectedTempFile(root, prefix, ".bin");
            FileChannel opened;
            try { opened = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE); }
            catch (IOException error) { Files.deleteIfExists(path); throw error; }
            channel = opened;
        }
        private MappedByteBuffer chunk(long position) throws IOException {
            int index = Math.toIntExact(position / CHUNK);
            while (chunks.size() <= index) chunks.add(channel.map(FileChannel.MapMode.READ_WRITE, (long)chunks.size() * CHUNK, CHUNK));
            return chunks.get(index);
        }
        long getLong(long p) throws IOException { return chunk(p).getLong((int)(p % CHUNK)); }
        void putLong(long p, long v) throws IOException { chunk(p).putLong((int)(p % CHUNK), v); }
        int getInt(long p) throws IOException { return chunk(p).getInt((int)(p % CHUNK)); }
        void putInt(long p, int v) throws IOException { chunk(p).putInt((int)(p % CHUNK), v); }
        byte getByte(long p) throws IOException { return chunk(p).get((int)(p % CHUNK)); }
        void putByte(long p, byte v) throws IOException { chunk(p).put((int)(p % CHUNK), v); }
        char getChar(long p) throws IOException { return chunk(p).getChar((int)(p % CHUNK)); }
        void putChar(long p, char v) throws IOException { chunk(p).putChar((int)(p % CHUNK), v); }
        @Override public void close() throws IOException {
            try { for (var chunk : chunks) IOUtils.unmap(chunk); }
            finally {
                chunks.clear();
                try { channel.close(); } finally { Files.deleteIfExists(path); }
            }
        }
    }
}
