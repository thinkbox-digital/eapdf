package eapdf;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import jakarta.mail.internet.SharedInputStream;

/** One disk file and descriptor for independently addressable original MIME messages. */
final class MimeSourceStore implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final Map<EaPdfChecks.Embedded, long[]> ranges = new IdentityHashMap<>();
    MimeSourceStore(List<EaPdfChecks.Embedded> sources) throws IOException {
        path = Files.createTempFile("eapdf-original-mime-", ".bin");
        try {
            long position = 0;
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) {
                for (var source : sources) try (var input = source.open()) {
                    long start = position; position = Math.addExact(position, input.transferTo(output));
                    ranges.put(source, new long[]{start, position});
                }
            }
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (IOException | RuntimeException error) { Files.deleteIfExists(path); throw error; }
    }
    InputStream open(EaPdfChecks.Embedded source) {
        long[] range = ranges.get(source);
        return new Slice(range[0], range[1]);
    }
    private final class Slice extends InputStream implements SharedInputStream {
        private final long start, end;
        private long position, mark;
        private byte[] buffer;
        private long bufferStart = -1;
        private int buffered;
        private boolean closed;
        Slice(long start, long end) { this.start = start; this.end = end; position = start; mark = start; }
        private void check() throws IOException { if (closed || !channel.isOpen()) throw new IOException("MIME source stream is closed"); }
        @Override public int read() throws IOException {
            check(); if (position >= end) return -1;
            if (buffer == null) buffer = new byte[2048];
            if (position < bufferStart || position >= bufferStart + buffered) {
                bufferStart = position;
                buffered = channel.read(ByteBuffer.wrap(buffer, 0, (int)Math.min(buffer.length, end - position)), position);
                if (buffered < 1) throw new EOFException("Original MIME storage ended unexpectedly");
            }
            return buffer[(int)(position++ - bufferStart)] & 255;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length); check();
            if (length == 0) return 0; if (position >= end) return -1;
            int count = channel.read(ByteBuffer.wrap(target, offset, (int)Math.min(length, end - position)), position);
            if (count < 1) throw new EOFException("Original MIME storage ended unexpectedly");
            position += count; return count;
        }
        @Override public long skip(long count) throws IOException { check(); long n = Math.min(Math.max(0, count), end - position); position += n; return n; }
        @Override public int available() throws IOException { check(); return (int)Math.min(Integer.MAX_VALUE, end - position); }
        @Override public boolean markSupported() { return true; }
        @Override public void mark(int limit) { mark = position; }
        @Override public void reset() throws IOException { check(); position = mark; }
        @Override public long getPosition() { return position - start; }
        @Override public InputStream newStream(long from, long to) {
            long length = end - start; if (to == -1) to = length;
            if (from < 0 || to < from || to > length) throw new IllegalArgumentException("Invalid MIME source range");
            return new Slice(start + from, start + to);
        }
        @Override public void close() { closed = true; buffer = null; }
    }
    @Override public void close() throws IOException { try { channel.close(); } finally { Files.deleteIfExists(path); } }
}
