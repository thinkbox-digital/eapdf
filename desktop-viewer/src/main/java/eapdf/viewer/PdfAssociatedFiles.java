package eapdf.viewer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Discovers reachable /AF references with a short-lived PDF parser and bounded caches. */
final class PdfAssociatedFiles extends PDFParser {
    private static final COSName AF = COSName.getPDFName("AF");
    private static final int OBJECT_CACHE_SIZE = 4_096;
    private static final int STREAM_CACHE_SIZE = 8;

    record File(COSDictionary specification, boolean catalog) {}
    record Inventory(List<File> files, boolean incomplete) {}

    private record Step(Step parent, Object part) {}

    /** Indirect specifications have no steps. Direct specifications have a path from an indirect object. */
    private record Location(COSObjectKey key, Step path) {
        Location child(Object step) { return new Location(key, new Step(path, step)); }

        COSBase resolve(PDDocument target) {
            COSBase value = key == null ? target.getDocumentCatalog().getCOSObject()
                    : target.getDocument().getObjectFromPool(key).getObject();
            ArrayDeque<Object> steps = new ArrayDeque<>();
            for (Step step = path; step != null; step = step.parent()) steps.push(step.part());
            for (Object step : steps) {
                value = dereference(value);
                if (step instanceof COSName name && value instanceof COSDictionary dictionary) {
                    value = dictionary.getItem(name);
                } else if (step instanceof Integer index && value instanceof COSArray array && index < array.size()) {
                    value = array.get(index);
                } else {
                    return null;
                }
            }
            return dereference(value);
        }
    }

    private record Hit(Location location, boolean catalog) {}
    private record Node(COSBase value, Location location) {}

    private final int limit;
    private final Set<Hit> hits = new LinkedHashSet<>();
    private final Set<Long> scheduled = new HashSet<>();
    private final TreeMap<Long, ArrayDeque<COSObjectKey>> pending = new TreeMap<>();
    private final ArrayDeque<Node> direct = new ArrayDeque<>();
    private final Map<Long, Map<COSObjectKey, COSBase>> streams = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<COSObjectKey, COSBase>> entry) {
            return size() > STREAM_CACHE_SIZE;
        }
    };
    private boolean incomplete;
    private IOException parseFailure;
    private int visited;

    private PdfAssociatedFiles(RandomAccessRead source, COSDocument original, int limit) throws IOException {
        super(source);
        this.limit = limit;
        document.close();
        var offsets = Collections.unmodifiableMap(original.getXrefTable());
        document = new COSDocument(IOUtils.createTempFileOnlyStreamCache(), this) {
            private final Map<Long, COSObject> recent = new LinkedHashMap<>(OBJECT_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, COSObject> entry) {
                    return size() > OBJECT_CACHE_SIZE;
                }
            };

            @Override
            public Map<COSObjectKey, Long> getXrefTable() { return offsets; }

            @Override
            public COSObjectKey getXrefKey(long number, int generation) {
                return original.getXrefKey(number, generation);
            }

            @Override
            public COSObject getObjectFromPool(COSObjectKey key) {
                return key == null ? null : recent.computeIfAbsent(identity(key),
                        ignored -> new COSObject(key, PdfAssociatedFiles.this));
            }
        };
    }

    static Inventory read(RandomAccessRead source, PDDocument target, int limit) throws IOException {
        COSDocument original = target.getDocument();
        var root = original.getTrailer().getItem(COSName.ROOT);
        if (!(root instanceof COSObject)) throw new IOException("The catalog has no indirect reference");
        var parser = new PdfAssociatedFiles(source, original, limit);
        long position = source.getPosition();
        try (var scanning = parser.document) {
            // The active document already validated the xref for this input.
            parser.setLenient(false);
            parser.fileLen = source.length();
            parser.initialParseDone = true;
            parser.discover(root);
            if (parser.parseFailure != null) throw parser.parseFailure;
        } finally {
            if (!source.isClosed()) source.seek(position);
        }
        var hits = parser.hits;
        boolean incomplete = parser.incomplete;
        parser.streams.clear();
        parser.pending.clear();
        parser.direct.clear();
        parser.scheduled.clear();
        List<File> files = new ArrayList<>(hits.size());
        for (Hit hit : hits) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Association inspection canceled");
            if (hit.location().resolve(target) instanceof COSDictionary specification && !(specification instanceof COSStream)) {
                files.add(new File(specification, hit.catalog()));
            } else {
                incomplete = true;
            }
        }
        return new Inventory(files, incomplete);
    }

    private void discover(COSBase root) throws InterruptedIOException {
        COSObjectKey catalog = root instanceof COSObject reference ? reference.getKey() : null;
        enqueue(root, new Location(null, null), null);
        while (!direct.isEmpty() || !pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Association inspection canceled");
            if (visited >= limit) {
                incomplete = true;
                return;
            }
            if (direct.isEmpty()) {
                // Process neighboring objects together so compressed members share a decoded stream.
                var first = pending.firstEntry();
                var key = first.getValue().remove();
                if (first.getValue().isEmpty()) pending.pollFirstEntry();
                enqueue(document.getObjectFromPool(key).getObject(), new Location(key, null), null);
                continue;
            }
            Node node = direct.pop();
            visited++;
            if (node.value() instanceof COSDictionary dictionary) {
                boolean atCatalog = node.location().path() == null
                        && Objects.equals(node.location().key(), catalog);
                collect(dictionary, node.location(), atCatalog);
                for (var item : dictionary.entrySet()) enqueue(item.getValue(), node.location(), item.getKey());
            } else if (node.value() instanceof COSArray array) {
                for (int index = 0; index < array.size(); index++) enqueue(array.get(index), node.location(), index);
            }
        }
    }

    private void collect(COSDictionary dictionary, Location location, boolean atCatalog) {
        if (dictionary.getDictionaryObject(AF) instanceof COSArray array) {
            for (int index = 0; index < array.size(); index++) {
                if (hits.size() >= limit) {
                    incomplete = true;
                    return;
                }
                COSBase value = array.get(index);
                if (value instanceof COSObject reference) {
                    hits.add(new Hit(new Location(reference.getKey(), null), atCatalog));
                } else if (value instanceof COSDictionary) {
                    hits.add(new Hit(location.child(AF).child(index), atCatalog));
                } else {
                    incomplete = true;
                }
            }
        } else if (dictionary.containsKey(AF)) {
            incomplete = true;
        }
    }

    private void enqueue(COSBase value, Location parent, Object step) {
        if (value instanceof COSObject reference) {
            var key = reference.getKey();
            long identity = identity(key);
            if (scheduled.contains(identity)) return;
            if (scheduled.size() >= limit) {
                incomplete = true;
                return;
            }
            scheduled.add(identity);
            pending.computeIfAbsent(offset(key), ignored -> new ArrayDeque<>()).add(key);
        } else if (value instanceof COSDictionary || value instanceof COSArray) {
            if (direct.size() + visited >= limit) incomplete = true;
            else direct.push(new Node(value, step == null ? parent : parent.child(step)));
        }
    }

    private static long identity(COSObjectKey key) {
        // The generation occupies the low bits of PDFBox's packed key.
        return Long.rotateRight(key.getInternalHash(), Short.SIZE);
    }

    private long offset(COSObjectKey key) {
        var xref = document.getXrefTable();
        long offset = xref.getOrDefault(key, Long.MAX_VALUE);
        if (offset >= 0 || offset == Long.MIN_VALUE) return offset;
        return xref.getOrDefault(getObjectKey(-offset, 0), Long.MAX_VALUE);
    }

    @Override
    public COSBase dereferenceCOSObject(COSObject object) throws IOException {
        try {
            return super.dereferenceCOSObject(object);
        } catch (IOException failure) {
            if (parseFailure == null) parseFailure = failure;
            throw failure;
        }
    }

    @Override
    protected COSBase parseObjectStreamObject(long number, COSObjectKey key) throws IOException {
        var objects = streams.get(number);
        if (objects == null) {
            if (!(document.getObjectFromPool(getObjectKey(number, 0)).getObject() instanceof COSStream stream)) {
                throw new IOException("Invalid object stream");
            }
            objects = new PDFObjectStreamParser(stream, document).parseAllObjects();
            streams.put(number, objects);
        }
        return objects.get(key);
    }

    private static COSBase dereference(COSBase value) {
        return value instanceof COSObject reference ? reference.getObject() : value;
    }
}
