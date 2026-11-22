package eapdf.viewer;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/** Verification and optional replay of the Full creator's version 3 page-text index. */
final class EmbeddedTextSearch {
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String INDEX_NS = "https://thinkbox.digital/eapdf/search-index/";
    private static final String DIGEST_MARKER = "<eapdfindex:fileDigest>";
    private static final String SIGNATURE_MARKER = "<eapdfindex:signatureContents>";
    private static final int MIB = 1024 * 1024;
    private static final int MAX_COMPRESSED = 16 * MIB;
    private static final int MAX_ENCODED = 4 * ((MAX_COMPRESSED + 2) / 3);
    private static final int MAX_DECODED = 128 * MIB;
    private static final int MAX_ROW = 16 * MIB;
    private static final int MAX_DOCUMENTS = 100_000;
    private static final int MAX_PAGES = 200_000;
    private static final int MAX_POSTINGS = 5_000_000;
    private static final int MAX_MATCHES = 250;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MD5 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9]{20}:[0-9]{20}");
    private static final Pattern CONTENTS = Pattern.compile("<[0-9a-fA-F]+>");

    private record Envelope(String digest, String payload) {}
    private record Route(List<List<String>> steps) {
        Route parent() { return new Route(steps.subList(0, steps.size() - 1)); }
        List<String> key() { return steps.getLast(); }
    }
    private record DocumentRow(Route route, String md5, long size, int pages) {}
    private record PageRow(int document, int page, int text, String guid) {}
    private record Index(List<DocumentRow> documents, List<PageRow> pages, List<String> texts,
                         Map<Route, List<DocumentRow>> children) {}
    private record Header(int documents, int pages, int texts, int terms, int postings, String digest) {}
    private record Guard(BooleanSupplier canceled, long deadline) {
        void check() throws IOException {
            if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Search canceled.");
            }
            require(System.nanoTime() < deadline);
        }
    }

    private EmbeddedTextSearch() {}

    static Optional<List<ViewerSearch.Hit>> search(ViewerSession root, String query, BooleanSupplier canceled)
            throws IOException {
        if (!Set.of("m", "mi", "c", "ci").contains(root.mail.profile()) || !root.mail.diagnostics().isEmpty()) {
            return Optional.empty();
        }
        var guard = new Guard(canceled, System.nanoTime() + TimeUnit.MINUTES.toNanos(2));
        try {
            guard.check();
            Envelope envelope = envelope(root, guard);
            if (envelope == null) return Optional.empty();
            verifyHost(root.path, envelope.digest(), guard);
            Index index = parse(envelope.payload(), root, guard);
            var children = childBindings(root.assets, index.children().getOrDefault(index.documents().getFirst().route(), List.of()));
            require(!root.pdf.assetsIncomplete());
            return Optional.of(query(index, root, children, normalize(query), guard));
        } catch (InterruptedIOException canceledSearch) {
            throw canceledSearch;
        } catch (IOException | RuntimeException | XMLStreamException unavailable) {
            // An unsupported or stale optimization must not prevent ordinary PDF search.
            if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Search canceled.");
            }
            return Optional.empty();
        }
    }

    private static Envelope envelope(ViewerSession session, Guard guard) throws IOException, XMLStreamException {
        var metadata = session.pdf.document().getDocumentCatalog().getMetadata();
        if (metadata == null) return null;
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External XML resources are unavailable.");
        });
        try (var input = new LimitedInput(metadata.createInputStream(), MAX_DECODED, guard)) {
            var reader = factory.createXMLStreamReader(input);
            try {
                String payload = null, digest = null;
                int depth = 0, rdfDepth = 0, subjectDepth = 0;
                boolean rootSubject = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) throw new IOException("Unsupported XMP document type.");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        var name = reader.getLocalName();
                        var namespace = reader.getNamespaceURI();
                        if (RDF.equals(namespace) && "RDF".equals(name)) rdfDepth = depth;
                        else if (rdfDepth > 0 && depth == rdfDepth + 1 && RDF.equals(namespace) && "Description".equals(name)) {
                            String about = reader.getAttributeValue(RDF, "about");
                            subjectDepth = depth;
                            rootSubject = about == null || about.isEmpty();
                        } else if (subjectDepth > 0 && rootSubject && depth == subjectDepth + 1 && INDEX_NS.equals(namespace)) {
                            if ("fulltext".equals(name)) {
                                require(payload == null);
                                payload = readText(reader, MAX_ENCODED);
                                depth--;
                            } else if ("fileDigest".equals(name)) {
                                require(digest == null);
                                digest = readText(reader, 64);
                                depth--;
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (subjectDepth == depth) subjectDepth = 0;
                        if (rdfDepth == depth) rdfDepth = 0;
                        depth--;
                    }
                }
                if (payload == null) return null;
                require(digest != null && SHA256.matcher(digest).matches() && !payload.isEmpty() && payload.length() % 4 == 0);
                return new Envelope(digest, payload);
            } finally {
                reader.close();
            }
        }
    }

    private static String readText(javax.xml.stream.XMLStreamReader reader, int max) throws XMLStreamException, IOException {
        var value = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) return value.toString();
            require(event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.DTD);
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                require(reader.getTextLength() <= max - value.length());
                value.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
            }
        }
        throw new IOException("Incomplete embedded XMP index property.");
    }

    private static void verifyHost(Path path, String expected, Guard guard) throws IOException {
        try (var channel = FileChannel.open(path)) {
            long length = channel.size();
            byte[] chunk = new byte[MIB];
            long[] positions = {-1, -1};
            List<String> markers = List.of(DIGEST_MARKER, SIGNATURE_MARKER);
            int tailSize = Math.max(DIGEST_MARKER.length(), SIGNATURE_MARKER.length()) - 1;
            String tail = "";
            long offset = 0;
            while (offset < length) {
                guard.check();
                int count = (int) Math.min(chunk.length, length - offset);
                readAt(channel, offset, chunk, count);
                var text = tail + new String(chunk, 0, count, StandardCharsets.ISO_8859_1);
                for (int i = 0; i < markers.size(); i++) {
                    String marker = markers.get(i);
                    for (int from = 0, index; (index = text.indexOf(marker, from)) >= 0; from = index + marker.length()) {
                        long position = offset - tail.length() + index + marker.length();
                        require(positions[i] == -1 || positions[i] == position);
                        positions[i] = position;
                    }
                }
                tail = text.substring(Math.max(0, text.length() - tailSize));
                offset += count;
            }
            long slot = positions[0], descriptor = positions[1];
            require(slot >= 0 && slot + 64 <= length && expected.equals(textAt(channel, slot, 64)));
            long gapStart = length, gapEnd = length;
            if (descriptor >= 0) {
                require(descriptor + 41 <= length);
                String signature = textAt(channel, descriptor, 41);
                require(SIGNATURE.matcher(signature).matches());
                long start = Long.parseLong(signature.substring(0, 20));
                long end = Long.parseLong(signature.substring(21));
                if (start != 0 || end != 0) {
                    require(start >= Math.max(slot + 64, descriptor + 41) && end > start + 2
                            && end <= length && end - start <= MIB && (end - start) % 2 == 0);
                    require(CONTENTS.matcher(textAt(channel, start, (int) (end - start))).matches());
                    gapStart = start;
                    gapEnd = end;
                }
            }
            MessageDigest sha = sha256();
            hashRange(channel, 0, slot, chunk, sha, guard);
            sha.update("0".repeat(64).getBytes(StandardCharsets.US_ASCII));
            hashRange(channel, slot + 64, gapStart, chunk, sha, guard);
            hashRange(channel, gapEnd, length, chunk, sha, guard);
            require(expected.equals(HexFormat.of().formatHex(sha.digest())) && length == channel.size());
        }
    }

    private static void hashRange(FileChannel channel, long start, long end, byte[] chunk, MessageDigest digest, Guard guard)
            throws IOException {
        for (long offset = start; offset < end;) {
            guard.check();
            int count = (int) Math.min(end - offset, chunk.length);
            readAt(channel, offset, chunk, count);
            digest.update(chunk, 0, count);
            offset += count;
        }
    }

    private static String textAt(FileChannel channel, long offset, int count) throws IOException {
        byte[] chunk = new byte[count];
        readAt(channel, offset, chunk, count);
        return new String(chunk, StandardCharsets.US_ASCII);
    }

    private static void readAt(FileChannel channel, long offset, byte[] chunk, int count) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(chunk, 0, count);
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes, offset);
            require(read > 0);
            offset += read;
        }
    }

    private static Index parse(String payload, ViewerSession root, Guard guard) throws IOException {
        byte[] compressed = Base64.getDecoder().decode(payload);
        require(compressed.length <= MAX_COMPRESSED);
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            var lines = new Lines(new LimitedInput(gzip, MAX_DECODED, guard));
            byte[] first = lines.next(1024);
            require(first != null && first.length != 0);
            Header header;
            try (var json = json(first)) {
                header = header(json, root.mail.profile());
            }
            require(header.pages() >= root.pdf.document().getNumberOfPages());
            var parsed = new Parser(header, root, guard);
            MessageDigest sha = sha256();
            for (byte[] row; (row = lines.next(MAX_ROW)) != null;) {
                sha.update(row);
                sha.update((byte) '\n');
                try (var json = json(row)) {
                    parsed.row(json);
                }
            }
            require(header.digest().equals(HexFormat.of().formatHex(sha.digest())));
            return parsed.finish();
        }
    }

    private static Header header(JsonReader json, String profile) throws IOException {
        var entries = new HashSet<String>();
        String format = "", engine = "", declared = "", digest = "";
        int version = -1, documents = -1, pages = -1, texts = -1, terms = -1, postings = -1;
        boolean complete = false;
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            require(entries.add(key));
            switch (key) {
                case "format" -> format = string(json, 64);
                case "engine" -> engine = string(json, 64);
                case "profile" -> declared = string(json, 8);
                case "sha256" -> digest = string(json, 64);
                case "version" -> version = (int) integer(json, 3);
                case "complete" -> complete = json.nextBoolean();
                case "documents" -> documents = (int) integer(json, MAX_DOCUMENTS);
                case "pages" -> pages = (int) integer(json, MAX_PAGES);
                case "texts" -> texts = (int) integer(json, MAX_PAGES);
                case "terms" -> terms = (int) integer(json, 250_000);
                case "postings" -> postings = (int) integer(json, MAX_POSTINGS);
                default -> json.skipValue();
            }
        }
        json.endObject();
        require(json.peek() == JsonToken.END_DOCUMENT
                && "eapdf-search-index".equals(format) && version == 3 && "pdfbox-nfkd-space-fold-v3".equals(engine)
                && complete && declared.equals("1" + profile) && SHA256.matcher(digest).matches()
                && documents > 0 && pages > 0 && texts > 0 && terms >= 0 && postings >= 0);
        return new Header(documents, pages, texts, terms, postings, digest);
    }

    private static final class Parser {
        private final Header header;
        private final ViewerSession root;
        private final Guard guard;
        private final List<DocumentRow> documents = new ArrayList<>();
        private final List<PageRow> pages = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();
        private final Set<Route> routes = new HashSet<>();
        private final Set<String> terms = new HashSet<>();
        private final Map<Route, List<DocumentRow>> children = new HashMap<>();
        private final int[] pageCounts;
        private int phase, characters, expectedPostings, postings;
        private long retained;

        Parser(Header header, ViewerSession root, Guard guard) {
            this.header = header;
            this.root = root;
            this.guard = guard;
            pageCounts = new int[header.documents()];
        }

        void row(JsonReader json) throws IOException {
            guard.check();
            json.beginArray();
            int next = List.of("D", "P", "T", "W").indexOf(string(json, 1));
            require(next >= phase);
            phase = next;
            switch (next) {
                case 0 -> document(json);
                case 1 -> page(json);
                case 2 -> text(json);
                case 3 -> postings(json);
                default -> throw new IOException("Unsupported indexed row.");
            }
            json.endArray();
            require(json.peek() == JsonToken.END_DOCUMENT && retained <= 256L * MIB);
        }

        private void document(JsonReader json) throws IOException {
            require(documents.size() < header.documents());
            json.beginArray();
            json.beginArray();
            var steps = new ArrayList<List<String>>();
            while (json.hasNext()) {
                require(steps.size() < 8);
                json.beginArray();
                var keys = new ArrayList<String>();
                while (json.hasNext()) {
                    require(keys.size() < 256);
                    String key = string(json, 16_384);
                    require(keys.isEmpty() || keys.getLast().compareTo(key) < 0);
                    keys.add(key);
                    retained += 32L + key.length() * 2L;
                }
                json.endArray();
                require(!keys.isEmpty());
                steps.add(List.copyOf(keys));
            }
            json.endArray();
            var route = new Route(List.copyOf(steps));
            String md5 = string(json, 32);
            long size = integer(json, 512L * MIB);
            int pageCount = (int) integer(json, MAX_PAGES);
            json.endArray();
            require(pageCount > 0 && routes.add(route));
            if (documents.isEmpty()) {
                require(steps.isEmpty() && md5.isEmpty() && size == 0 && pageCount == root.pdf.document().getNumberOfPages());
            } else {
                require(!steps.isEmpty() && MD5.matcher(md5).matches() && size > 0 && routes.contains(route.parent()));
            }
            var document = new DocumentRow(route, md5, size, pageCount);
            if (!steps.isEmpty()) children.computeIfAbsent(route.parent(), ignored -> new ArrayList<>()).add(document);
            documents.add(document);
            retained += 128;
        }

        private void page(JsonReader json) throws IOException {
            require(documents.size() == header.documents() && pages.size() < header.pages());
            json.beginArray();
            int doc = (int) integer(json, documents.size() - 1);
            int page = (int) integer(json, documents.get(doc).pages()) - 1;
            int text = (int) integer(json, header.texts() - 1);
            String guid = string(json, 1024);
            json.endArray();
            require(page == pageCounts[doc]++);
            if (doc == 0) require(guid.equals(owner(root.mail, page)));
            pages.add(new PageRow(doc, page, text, guid));
            retained += 64 + guid.length() * 2L;
        }

        private void text(JsonReader json) throws IOException {
            require(pages.size() == header.pages() && texts.size() < header.texts());
            String text = string(json, 1_000_000);
            characters += text.length();
            require(characters <= 32_000_000 && text.equals(normalize(text)));
            if (!text.isEmpty()) {
                expectedPostings++;
                for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ' ') expectedPostings++;
            }
            require(expectedPostings <= MAX_POSTINGS);
            retained += 32 + text.length() * 2L;
            texts.add(text);
        }

        private void postings(JsonReader json) throws IOException {
            require(texts.size() == header.texts() && terms.size() < header.terms());
            json.beginArray();
            String term = string(json, 1_000_000);
            require(!term.isEmpty() && !term.contains(" ") && terms.add(term));
            json.beginArray();
            int text = 0, position = 0, count = 0;
            while (json.hasNext()) {
                if ((postings & 0x3fff) == 0) guard.check();
                int deltaText = (int) integer(json, header.texts() - 1);
                int deltaPosition = (int) integer(json, 2_000_000);
                int priorText = text, priorPosition = position;
                text += deltaText;
                position = deltaPosition + (deltaText == 0 ? position : 0);
                require(text < texts.size() && (count == 0 || text > priorText || position > priorPosition));
                String content = texts.get(text);
                int end = position + term.length();
                require(end <= content.length() && content.startsWith(term, position)
                        && (position == 0 || content.charAt(position - 1) == ' ')
                        && (end == content.length() || content.charAt(end) == ' '));
                count++;
                require(++postings <= header.postings());
            }
            json.endArray();
            json.endArray();
            require(count > 0);
            retained += term.length() * 2L + 64;
        }

        Index finish() throws IOException {
            require(documents.size() == header.documents() && pages.size() == header.pages()
                    && texts.size() == header.texts() && terms.size() == header.terms()
                    && postings == header.postings() && postings == expectedPostings);
            for (int i = 0; i < documents.size(); i++) require(pageCounts[i] == documents.get(i).pages());
            return new Index(documents, pages, texts, children);
        }
    }

    private static Map<DocumentRow, PdfNavigation.Asset> childBindings(List<PdfNavigation.Asset> assets,
                                                                      List<DocumentRow> children) throws IOException {
        var byKeys = new HashMap<List<String>, PdfNavigation.Asset>();
        for (var asset : assets) {
            if (!asset.isCollectionPdf()) continue;
            List<String> keys = asset.nameTreeKeys().stream().distinct().sorted().toList();
            require(!keys.isEmpty() && byKeys.putIfAbsent(keys, asset) == null);
        }
        var bindings = new HashMap<DocumentRow, PdfNavigation.Asset>();
        for (var child : children) {
            var asset = byKeys.remove(child.route().key());
            require(asset != null && asset.variants().size() == 1);
            var stream = asset.variants().getFirst();
            require(stream.isPdf() && Objects.equals(stream.declaredSize(), child.size()) && child.md5().equals(stream.declaredMd5()));
            bindings.put(child, asset);
        }
        require(byKeys.isEmpty());
        return bindings;
    }

    private static List<ViewerSearch.Hit> query(Index index, ViewerSession root, Map<DocumentRow, PdfNavigation.Asset> children,
                                                 String query, Guard guard) throws IOException {
        if (query.isEmpty()) return List.of();
        Map<Integer, String> matches = new HashMap<>();
        for (int i = 0; i < index.texts().size(); i++) {
            guard.check();
            String text = index.texts().get(i);
            for (int from = 0, at, checked = 0; (at = text.indexOf(query, from)) >= 0; from = at + query.length()) {
                if ((checked++ & 4095) == 0) guard.check();
                int end = at + query.length();
                if ((at > 0 && word(text.codePointBefore(at))) || (end < text.length() && word(text.codePointAt(end)))) continue;
                matches.put(i, text.substring(Math.max(0, at - 60), Math.min(text.length(), end + 120)));
                break;
            }
        }
        var result = new ArrayList<ViewerSearch.Hit>();
        Map<DocumentRow, List<PageRow>> found = new LinkedHashMap<>();
        int count = 0;
        for (var page : index.pages()) {
            if (!matches.containsKey(page.text())) continue;
            found.computeIfAbsent(index.documents().get(page.document()), ignored -> new ArrayList<>()).add(page);
            if (++count >= MAX_MATCHES) break;
        }
        Set<Route> routes = new HashSet<>();
        for (var row : found.keySet()) {
            var steps = row.route().steps();
            for (int i = 1; i <= steps.size(); i++) routes.add(new Route(steps.subList(0, i)));
        }
        var rootRow = index.documents().getFirst();
        replay(index, rootRow, children, root.pdf, root.mail, List.of(), found, routes, matches, result, guard);
        require(result.size() == count);
        return List.copyOf(result);
    }

    private static void replay(Index index, DocumentRow row, Map<DocumentRow, PdfNavigation.Asset> children,
                               PdfNavigation pdf, MailNavigation mail, List<String> path,
                               Map<DocumentRow, List<PageRow>> found, Set<Route> routes, Map<Integer, String> snippets,
                               List<ViewerSearch.Hit> result, Guard guard) throws IOException {
        guard.check();
        require(pdf.document().getNumberOfPages() == row.pages());
        for (var page : found.getOrDefault(row, List.of())) {
            require(page.guid().equals(owner(mail, page.page())));
            result.add(new ViewerSearch.Hit(path, page.page(), snippets.get(page.text())));
        }
        for (var child : index.children().getOrDefault(row.route(), List.of())) {
            if (!routes.contains(child.route())) continue;
            guard.check();
            var asset = children.get(child);
            require(asset != null);
            Path extracted = pdf.extract(asset.variants().getFirst());
            try (var childPdf = PdfNavigation.open(extracted)) {
                MailNavigation childMail = MailNavigation.read(childPdf.document());
                var childAssets = childPdf.assets(childMail);
                var childrenOfChild = childBindings(childAssets, index.children().getOrDefault(child.route(), List.of()));
                require(!childPdf.assetsIncomplete() && childMail.diagnostics().isEmpty());
                var route = new ArrayList<>(path);
                String key = child.route().key().getFirst();
                require(ViewerSearch.resolve(pdf.assets(), key) == asset);
                route.add(key);
                replay(index, child, childrenOfChild, childPdf, childMail, List.copyOf(route), found, routes, snippets, result, guard);
            } finally {
                Files.deleteIfExists(extracted);
            }
        }
    }

    private static String owner(MailNavigation navigation, int page) {
        var mail = navigation.ownerAt(page);
        return mail == null ? "" : mail.guid();
    }

    private static boolean word(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_' || switch (Character.getType(cp)) {
            case Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.NON_SPACING_MARK,
                    Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true;
            default -> false;
        };
    }

    private static String normalize(String input) {
        var result = new StringBuilder(input.length());
        boolean space = false;
        for (int from = 0; from < input.length();) {
            int codePoint = input.codePointAt(from), next = from + Character.charCount(codePoint);
            if (codePoint == '-' && next + 1 < input.length() && input.charAt(next) == '\n' && from > 0) {
                int before = input.codePointBefore(from), after = input.codePointAt(next + 1);
                if (Character.isLowerCase(before) && Character.isLowerCase(after)
                        || Character.isUpperCase(before) && Character.isLetter(after)) {
                    from = next + 1;
                    continue;
                }
            }
            from = next;
            String fold = codePoint < 128 ? Character.toString(Character.toLowerCase(codePoint))
                    : Normalizer.normalize(Character.toString(codePoint), Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
            for (int i = 0; i < fold.length();) {
                int value = fold.codePointAt(i);
                i += Character.charCount(value);
                if (value == 0xad) continue;
                if (white(value)) {
                    space = !result.isEmpty();
                } else {
                    if (space) result.append(' ');
                    space = false;
                    result.appendCodePoint(value == 0x3c2 ? 0x3c3 : value);
                }
            }
        }
        return result.toString();
    }

    private static boolean white(int cp) {
        return cp >= 9 && cp <= 13 || cp == 0x20 || cp == 0x85 || cp == 0xa0 || cp == 0x1680
                || cp >= 0x2000 && cp <= 0x200a || cp == 0x2028 || cp == 0x2029 || cp == 0x202f
                || cp == 0x205f || cp == 0x3000;
    }

    private static JsonReader json(byte[] data) throws IOException {
        var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        var json = new JsonReader(new StringReader(text));
        json.setStrictness(Strictness.STRICT);
        return json;
    }

    private static String string(JsonReader json, int max) throws IOException {
        require(json.peek() == JsonToken.STRING);
        String text = json.nextString();
        require(text.length() <= max);
        return text;
    }

    private static long integer(JsonReader json, long max) throws IOException {
        require(json.peek() == JsonToken.NUMBER);
        String text = json.nextString();
        require(text.length() <= 32);
        long value = new BigDecimal(text).longValueExact();
        require(value >= 0 && value <= max);
        return value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static void require(boolean valid) throws IOException {
        if (!valid) throw new IOException("Embedded full-text index is unavailable or does not match this PDF.");
    }

    private static final class LimitedInput extends FilterInputStream {
        private final Guard guard;
        private int remaining;

        LimitedInput(InputStream input, int remaining, Guard guard) {
            super(input);
            this.remaining = remaining;
            this.guard = guard;
        }

        @Override public int read() throws IOException {
            guard.check();
            int value = in.read();
            if (value >= 0) require(remaining-- > 0);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            guard.check();
            if (count == 0) return 0;
            if (remaining == 0) return read();
            int length = in.read(bytes, offset, Math.min(count, remaining));
            if (length > 0) remaining -= length;
            return length;
        }
    }

    private static final class Lines {
        private final InputStream input;
        private final byte[] buffer = new byte[64 * 1024];
        private int offset, length;

        Lines(InputStream input) { this.input = input; }

        byte[] next(int max) throws IOException {
            var row = new ByteArrayOutputStream();
            while (true) {
                if (offset >= length) {
                    length = input.read(buffer);
                    offset = 0;
                    if (length < 0) {
                        require(row.size() == 0);
                        return null;
                    }
                }
                int end = offset;
                while (end < length && buffer[end] != '\n') end++;
                require(end - offset <= max - row.size());
                row.write(buffer, offset, end - offset);
                offset = end;
                if (end < length) {
                    offset++;
                    return row.toByteArray();
                }
            }
        }
    }
}
