package eapdf.viewer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.ResourceCache;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

/** A read-only search document with its own file cursor and bounded caches. */
final class PdfSearchDocument extends PDFParser {
    private static final int MIN_XREF_ENTRIES = 100_000;
    private final COSObjectKey catalog;
    private final Map<Long, COSObject> references = limitedMap(4_096);
    private final Map<Long, COSBase> objects = limitedMap(4_096);
    private final Map<Long, Map<COSObjectKey, COSBase>> streams = limitedMap(8);
    private final Set<Long> resolving = new HashSet<>();

    private PdfSearchDocument(RandomAccessReadBufferedFile reader, COSDocument original, COSObjectKey catalog)
            throws IOException {
        super(reader);
        this.catalog = catalog;
        document.close();
        var offsets = Collections.unmodifiableMap(original.getXrefTable());
        document = new COSDocument(IOUtils.createTempFileOnlyStreamCache(), this) {
            @Override public Map<COSObjectKey, Long> getXrefTable() { return offsets; }

            @Override public COSObjectKey getXrefKey(long number, int generation) {
                return original.getXrefKey(number, generation);
            }

            @Override public COSObject getObjectFromPool(COSObjectKey key) {
                return key == null ? null : references.computeIfAbsent(identity(key), ignored -> new Reference(key));
            }
        };
    }

    /** Return null when the session's original offsets cannot be reused. */
    static PDDocument open(ViewerSession session) throws IOException {
        if (!session.path.equals(session.renderingPath) || session.sourceStamp == null
                || !session.sourceStamp.equals(PdfFileStamp.read(session.path))) return null;
        PDDocument active = session.pdf.document();
        if (active.isEncrypted() || active.getDocument().getXrefTable().size() < MIN_XREF_ENTRIES) return null;
        return open(session.path, active.getDocument(), session.sourceStamp);
    }

    static PDDocument open(Path path, COSDocument original, PdfFileStamp stamp) throws IOException {
        if (!(original.getTrailer().getItem(COSName.ROOT) instanceof COSObject root)) return null;
        if (stamp == null || !stamp.equals(PdfFileStamp.read(path))) return null;
        var reader = new RandomAccessReadBufferedFile(path.toFile());
        try {
            if (!stamp.equals(PdfFileStamp.read(path))) throw new IOException("PDF changed while opening for search");
            var parser = new PdfSearchDocument(reader, original, root.getKey());
            PDDocument result = parser.parse(false);
            result.setResourceCache(new SearchResources());
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                reader.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override protected void initialParse() throws IOException {
        fileLen = source.length();
        var trailer = new COSDictionary();
        trailer.setItem(COSName.ROOT, document.getObjectFromPool(catalog));
        document.setTrailer(trailer);
        initialParseDone = true;
    }

    @Override protected COSBase parseObjectStreamObject(long number, COSObjectKey key) throws IOException {
        var parsed = streams.get(number);
        if (parsed == null) {
            var stream = document.getObjectFromPool(getObjectKey(number, 0)).getObject();
            if (!(stream instanceof COSStream data)) throw new IOException("Invalid PDF object stream");
            parsed = new PDFObjectStreamParser(data, document).parseAllObjects();
            streams.put(number, parsed);
        }
        return parsed.get(key);
    }

    // A page or resource dictionary may retain a reference after cache eviction.
    // The reference delegates each read to the cache and owns no parsed object.
    private final class Reference extends COSObject {
        Reference(COSObjectKey key) { super(key, null); }

        @Override public COSBase getObject() {
            long key = identity(getKey());
            COSBase value = objects.get(key);
            if (value == null) {
                if (!resolving.add(key)) return null;
                try {
                    value = PdfSearchDocument.super.dereferenceCOSObject(this);
                    if (value == null) value = COSNull.NULL;
                    objects.put(key, value);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                } finally {
                    resolving.remove(key);
                }
            }
            return value == COSNull.NULL ? null : value;
        }
    }

    private static long identity(COSObjectKey key) {
        return Long.rotateRight(key.getInternalHash(), Short.SIZE);
    }

    private static <K, V> Map<K, V> limitedMap(int limit) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> entry) { return size() > limit; }
        };
    }

    private static final class SearchResources implements ResourceCache {
        private final Map<Long, Object> resources = limitedMap(512);

        private <T> T get(COSObject indirect, Class<T> type) {
            Object value = indirect.getKey() == null ? null : resources.get(identity(indirect.getKey()));
            return type.isInstance(value) ? type.cast(value) : null;
        }

        private void store(COSObject indirect, Object value) {
            if (indirect.getKey() != null) resources.put(identity(indirect.getKey()), value);
        }

        @Override public PDFont getFont(COSObject ref) { return get(ref, PDFont.class); }
        @Override public PDColorSpace getColorSpace(COSObject ref) { return get(ref, PDColorSpace.class); }
        @Override public PDExtendedGraphicsState getExtGState(COSObject ref) { return get(ref, PDExtendedGraphicsState.class); }
        @Override public PDShading getShading(COSObject ref) { return get(ref, PDShading.class); }
        @Override public PDAbstractPattern getPattern(COSObject ref) { return get(ref, PDAbstractPattern.class); }
        @Override public PDPropertyList getProperties(COSObject ref) { return get(ref, PDPropertyList.class); }
        @Override public PDXObject getXObject(COSObject ref) { return get(ref, PDXObject.class); }

        @Override public void put(COSObject ref, PDFont value) { store(ref, value); }
        @Override public void put(COSObject ref, PDColorSpace value) { store(ref, value); }
        @Override public void put(COSObject ref, PDExtendedGraphicsState value) { store(ref, value); }
        @Override public void put(COSObject ref, PDShading value) { store(ref, value); }
        @Override public void put(COSObject ref, PDAbstractPattern value) { store(ref, value); }
        @Override public void put(COSObject ref, PDPropertyList value) { store(ref, value); }
        @Override public void put(COSObject ref, PDXObject value) { store(ref, value); }
    }
}
