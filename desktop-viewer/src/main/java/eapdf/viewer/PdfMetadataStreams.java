package eapdf.viewer;

import java.io.FilterOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.IOUtils;

/** Decodes non-rendering streams without letting PDFBox buffer unbounded filter output. */
final class PdfMetadataStreams {
    private static final int MAX_FILTERS = 8;
    private static final int INPUT_ALLOWANCE = 64 * 1024;
    private static final Set<String> FILTER_NAMES = Set.of("FlateDecode", "Fl", "LZWDecode", "LZW",
            "ASCII85Decode", "A85", "ASCIIHexDecode", "AHx", "RunLengthDecode", "RL", "Crypt");

    private PdfMetadataStreams() {}

    static final class Limit extends IOException {
        Limit(String message) { super(message); }
    }

    static InputStream open(COSStream stream, int maximum) throws IOException {
        return open(stream, maximum, null, "Metadata stream");
    }

    static InputStream open(COSStream stream, int maximum, BooleanSupplier canceled) throws IOException {
        return open(stream, maximum, canceled, "Metadata stream");
    }

    static InputStream openAsset(COSStream stream, int maximum) throws IOException {
        return open(stream, maximum, null, "Embedded file");
    }

    private static InputStream open(COSStream stream, int maximum, BooleanSupplier canceled, String description) throws IOException {
        checkCanceled(canceled, description);
        List<COSName> filters = filters(stream, maximum, description);
        Path previous = null;
        try {
            // Decode every stage to disk, including an unfiltered stream. COSStream#createInputStream
            // otherwise materializes all decoded filter output before its caller can enforce a cap.
            for (int i = 0; i < Math.max(1, filters.size()); i++) {
                Path stage = IOUtils.createProtectedTempFile(Path.of(System.getProperty("java.io.tmpdir")),
                        "eapdf-viewer-stream-", ".part");
                boolean complete = false;
                try {
                    try (var input = new LimitedInput(previous == null ? stream.createRawInputStream() : Files.newInputStream(previous),
                            previous == null ? encodedLimit(maximum) : maximum, canceled, description);
                            var output = new LimitedOutput(Files.newOutputStream(stage), maximum, canceled, description)) {
                        if (filters.isEmpty()) copy(input, output, canceled, description);
                        else FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(input, output, stream, i);
                    }
                    complete = true;
                } finally {
                    if (!complete) ViewerSession.deleteTemporaryFile(stage);
                }
                if (previous != null) ViewerSession.deleteTemporaryFile(previous);
                previous = stage;
            }
            Path decoded = previous;
            InputStream input = Files.newInputStream(decoded);
            previous = null;
            return new FilterInputStream(input) {
                @Override public void close() throws IOException {
                    try { super.close(); }
                    finally { ViewerSession.deleteTemporaryFile(decoded); }
                }
            };
        } finally {
            if (previous != null) ViewerSession.deleteTemporaryFile(previous);
        }
    }

    private static List<COSName> filters(COSStream stream, int maximum, String description) throws IOException {
        COSBase entry = stream.getDictionaryObject(COSName.FILTER);
        List<COSName> filters = new ArrayList<>();
        if (entry == null) return filters;
        if (entry instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) addFilter(filters, array.getObject(i), description);
        } else {
            addFilter(filters, entry, description);
        }
        COSBase parameters = stream.getDictionaryObject(COSName.DECODE_PARMS);
        for (int i = 0; i < filters.size(); i++) {
            COSDictionary dictionary = parameters(parameters, filters.size(), i);
            if (dictionary != null && predictor(filters.get(i))) checkPredictor(dictionary, maximum, description);
        }
        return filters;
    }

    private static void addFilter(List<COSName> filters, COSBase entry, String description) throws IOException {
        if (!(entry instanceof COSName name) || !FILTER_NAMES.contains(name.getName())) {
            throw new IOException("Unsupported " + description.toLowerCase(java.util.Locale.ROOT) + " filter.");
        }
        if (filters.size() >= MAX_FILTERS) {
            throw new IOException("Too many " + description.toLowerCase(java.util.Locale.ROOT) + " filters.");
        }
        // A filter array is an ordered pipeline. Keep duplicate names and their matching DecodeParms indexes.
        filters.add(name);
    }

    private static int encodedLimit(int maximum) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(maximum, 4L * maximum + INPUT_ALLOWANCE));
    }

    private static COSDictionary parameters(COSBase entry, int filterCount, int index) {
        if (filterCount == 1 && entry instanceof COSDictionary dictionary) return dictionary;
        return entry instanceof COSArray array && index < array.size() && array.getObject(index) instanceof COSDictionary dictionary
                ? dictionary : null;
    }

    private static boolean predictor(COSName filter) {
        return "FlateDecode".equals(filter.getName()) || "Fl".equals(filter.getName())
                || "LZWDecode".equals(filter.getName()) || "LZW".equals(filter.getName());
    }

    private static void checkPredictor(COSDictionary parameters, int maximum, String description) throws Limit {
        if (parameters.getInt(COSName.PREDICTOR, 1) <= 1) return;
        long bits = parameters.getLong(COSName.BITS_PER_COMPONENT, 8);
        long colors = parameters.getLong(COSName.COLORS, 1);
        long columns = parameters.getLong(COSName.COLUMNS, 1);
        if (bits < 1 || bits > 16 || colors < 1 || colors > 32 || columns < 1
                || colors > Long.MAX_VALUE / bits || columns > 8L * maximum / (bits * colors)) {
            throw new Limit(description + " predictor row exceeds the decoded-byte limit.");
        }
    }

    private static void copy(InputStream input, OutputStream output, BooleanSupplier canceled, String description) throws IOException {
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) != -1; ) {
            checkCanceled(canceled, description);
            output.write(buffer, 0, read);
        }
    }

    private static void checkCanceled(BooleanSupplier canceled, String description) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || canceled != null && canceled.getAsBoolean()) {
            throw new InterruptedIOException(description + " decoding was canceled.");
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private int remaining;
        private final BooleanSupplier canceled;
        private final String description;

        LimitedInput(InputStream input, int maximum, BooleanSupplier canceled, String description) {
            super(input);
            remaining = maximum;
            this.canceled = canceled;
            this.description = description;
        }

        @Override public int read() throws IOException {
            checkCanceled(canceled, description);
            if (remaining == 0) {
                if (in.read() < 0) return -1;
                throw new Limit(description + " encoded data exceeds the input-byte limit.");
            }
            int value = in.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCanceled(canceled, description);
            if (length == 0) return 0;
            if (remaining == 0) return read();
            int count = in.read(bytes, offset, Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }

        @Override public long skip(long count) throws IOException {
            checkCanceled(canceled, description);
            if (count <= 0) return 0;
            if (remaining == 0) {
                read();
                return 0;
            }
            long skipped = in.skip(Math.min(count, remaining));
            remaining -= (int) skipped;
            return skipped;
        }
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private int remaining;
        private final BooleanSupplier canceled;
        private final String description;

        LimitedOutput(OutputStream output, int maximum, BooleanSupplier canceled, String description) {
            super(output);
            remaining = maximum;
            this.canceled = canceled;
            this.description = description;
        }

        private void reserve(int count) throws IOException {
            checkCanceled(canceled, description);
            if (count > remaining) throw new Limit(description + " exceeds the decoded-byte limit.");
            remaining -= count;
        }

        @Override public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }
    }
}
