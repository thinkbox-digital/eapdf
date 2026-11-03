package eapdf;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.IOUtils;

/** Bounded, disk-backed decoding of non-rendering PDF streams. */
final class BoundedPdfStreams {
    private static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();
    private static final Set<String> FILTERS = Set.of("FlateDecode", "Fl", "LZWDecode", "LZW",
        "ASCII85Decode", "A85", "ASCIIHexDecode", "AHx", "RunLengthDecode", "RL");
    static final class Limit extends IOException {
        Limit(String message) {
            super(message);
            Budget budget = CURRENT.get();
            if (budget != null) budget.limited = message;
        }
    }
    private static long setting(String name, long fallback) {
        String value = System.getProperty(name, System.getenv(name));
        return value == null || value.isBlank() ? fallback : Math.max(1, Long.parseLong(value));
    }
    static long streamLimit() { return setting("VALIDATOR_MAX_STREAM_BYTES", 64L * 1024 * 1024); }
    static long metadataLimit() { return setting("VALIDATOR_MAX_METADATA_BYTES", 16L * 1024 * 1024); }
    private static final class Budget {
        long bytes, files;
        String limited;

    }
    static AutoCloseable scope(boolean root) {
        Budget previous = CURRENT.get();
        if (root || previous == null) CURRENT.set(new Budget());
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    static String limitedReason() { return CURRENT.get() == null ? null : CURRENT.get().limited; }
    static void file() throws Limit {
        Budget budget = CURRENT.get();
        if (budget != null) budget.files++;
    }
    static byte[] bytes(COSStream stream, long maximum) throws IOException {
        try (InputStream input = open(stream, maximum)) { return input.readAllBytes(); }
    }
    static InputStream open(COSStream stream, long maximum) throws IOException {
        var filters = new ArrayList<COSName>();
        COSBase rawFilters = stream.getDictionaryObject(COSName.FILTER);
        if (rawFilters instanceof COSName name) filters.add(name);
        else if (rawFilters instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (!(array.getObject(i) instanceof COSName name)) throw new IOException("Invalid PDF stream filter");
                filters.add(name);
            }
        } else if (rawFilters != null) throw new IOException("Invalid PDF stream Filter entry");
        for (var name : filters) if (!FILTERS.contains(name.getName()))
            throw new Limit("Unsupported bounded stream filter: " + name.getName());
        COSBase parameters = stream.getDictionaryObject(COSName.DECODE_PARMS);
        for (int i = 0; i < filters.size(); i++) {
            COSBase item = parameters instanceof COSArray a && i < a.size() ? a.getObject(i) : parameters;
            if (item instanceof COSDictionary d) {
                long columns = d.getLong(COSName.COLUMNS, 1), colors = d.getLong(COSName.COLORS, 1), bits = d.getLong(COSName.BITS_PER_COMPONENT, 8);
                if (columns < 1 || colors < 1 || bits < 1 || bits > 16 || colors > 32
                    || columns > Math.min(maximum, 16L * 1024 * 1024) / colors / bits * 8)
                    throw new Limit("PDF predictor row exceeds the decoding limit");
            }
        }
        Path previous = null;
        try {
            // Even an unfiltered stream is copied through the same byte budget.
            for (int i = 0; i < Math.max(1, filters.size()); i++) {
                Path next = IOUtils.createProtectedTempFile(Path.of(System.getProperty("java.io.tmpdir")), "eapdf-decoded-", ".bin");
                boolean complete = false;
                try {
                  try (InputStream input = previous == null ? stream.createRawInputStream() : Files.newInputStream(previous);
                     OutputStream file = Files.newOutputStream(next);
                     OutputStream output = bounded(file, maximum)) {
                    if (filters.isEmpty()) input.transferTo(output);
                    else FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(input, output, stream, i);
                  }
                    complete = true;
                } finally { if (!complete) Files.deleteIfExists(next); }
                if (previous != null) Files.deleteIfExists(previous);
                previous = next;
            }
            Path decoded = previous;
            InputStream input = Files.newInputStream(decoded);
            previous = null;
            return new FilterInputStream(input) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { Files.deleteIfExists(decoded); }
                }
            };
        } finally { if (previous != null) Files.deleteIfExists(previous); }
    }
    private static OutputStream bounded(OutputStream file, long maximum) {
        return new FilterOutputStream(file) {
            long count;
            private void reserve(int length) throws Limit {
                if (length > maximum - count) throw new Limit("PDF stream exceeds its decoded-byte limit");
                count += length;
            }
            @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
            @Override public void write(byte[] data, int offset, int length) throws IOException { reserve(length); out.write(data, offset, length); }
        };
    }
}
