package eapdf.viewer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Local EA-PDF navigation derived from catalog metadata and verified document parts. */
final class MailNavigation {
    private static final String DC = "http://purl.org/dc/elements/1.1/";
    private static final String META = "http://www.pdfa.org/eapdf/ns/meta/";
    private static final String ID = "http://www.pdfa.org/eapdf/ns/id/";
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";
    private static final int MAX_METADATA = 128 * 1024 * 1024;
    private static final int MAX_RECORD_CHARS = 2 * 1024 * 1024;
    private static final int MAX_ARCHIVE_DESCRIPTION = 4_096;
    private static final int MAX_PARTS = 16_384;
    private static final int MAX_OBJECTS = 1_000_000;
    private static final int MAX_TREE_DEPTH = 32;
    private static final Pattern FOLDER_KEY = Pattern.compile("^<(\\d+)>.+$", Pattern.DOTALL);
    private static final Pattern MD5 = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern METADATA_COUNT = Pattern.compile("\\+?[0-9]+");
    private static final long MAX_METADATA_COUNT = 9_007_199_254_740_991L;
    private static final Set<String> KNOWN_PROFILES = Set.of("s", "si", "m", "mi", "c", "ci");
    private static final Set<String> ROOT_EMAIL_FIELDS = Set.of("Mail_GUID", "Message-ID", "messageid", "Subject",
            "From", "To", "Cc", "Bcc", "Raw-From", "Raw-To", "Raw-Cc", "Raw-Bcc", "Sent",
            "NumberAttachments", "In-Reply-To", "References");

    record Message(String guid, String title, String from, String sent,
                   int firstPage, int lastPage, Map<String, String> fields) {}

    record Folder(int id, Integer parentId, String name) {}

    private record FileSignature(long size, String md5) {}

    private record Metadata(String profile, String archiveDescription, List<Map<String, String>> messages,
                            List<Map<String, String>> attachments) {}

    private record Part(COSBase value, COSDictionary parent, int depth, int owner) {}

    private record PageRange(int start, int end, int owner, boolean body) {}

    private record Boundary(int page, int owner, boolean body, int change) {}

    private record FolderNode(COSBase value, Integer parent, int depth) {}

    private record Placements(List<Folder> folders, Integer rootId, boolean certain) {}

    private final List<Message> messages;
    private final int catalogMessageCount;
    private final Long catalogAttachmentCount;
    private final String archiveDescription;
    private final Placements placements;
    private final boolean collection;
    private final String profile;
    private final List<String> diagnostics;
    private final Map<Integer, Message> bodyOwners;
    private final Map<Integer, Message> pageOwners;
    private final Map<COSDictionary, Set<String>> fileOwners;
    private final Map<FileSignature, Set<String>> attachmentOwners;

    private MailNavigation(List<Message> messages, int catalogMessageCount, Long catalogAttachmentCount,
                           String archiveDescription,
                           Placements placements, boolean collection, String profile,
                           Set<String> diagnostics, Map<Integer, Message> bodyOwners,
                           Map<Integer, Message> pageOwners, Map<COSDictionary, Set<String>> fileOwners,
                           Map<FileSignature, Set<String>> attachmentOwners) {
        this.messages = List.copyOf(messages);
        this.catalogMessageCount = catalogMessageCount;
        this.catalogAttachmentCount = catalogAttachmentCount;
        this.archiveDescription = archiveDescription;
        this.placements = placements;
        this.collection = collection;
        this.profile = profile;
        this.diagnostics = List.copyOf(diagnostics);
        this.bodyOwners = Map.copyOf(bodyOwners);
        this.pageOwners = Map.copyOf(pageOwners);
        this.fileOwners = fileOwners;
        this.attachmentOwners = Map.copyOf(attachmentOwners);
    }

    List<Message> messages() { return messages; }
    int catalogMessageCount() { return catalogMessageCount; }
    Long catalogAttachmentCount() { return catalogAttachmentCount; }
    String archiveDescription() { return archiveDescription; }
    List<Folder> folders() { return placements.folders(); }
    boolean collection() { return collection; }
    String profile() { return profile; }
    List<String> diagnostics() { return diagnostics; }
    Message messageAt(int page) { return bodyOwners.get(page); }
    Message ownerAt(int page) { return pageOwners.get(page); }
    Set<String> fileOwners(COSDictionary fileSpec) { return fileOwners.getOrDefault(fileSpec, Set.of()); }

    Set<String> matchedAttachmentOwners(long size, String md5) {
        if (md5 == null) return Set.of();
        return attachmentOwners.getOrDefault(new FileSignature(size, md5.toLowerCase(Locale.ROOT)), Set.of());
    }

    /** A null result means the collection's placement is unavailable or ambiguous. */
    Integer folderId(String nameTreeKey) {
        if (!collection || !placements.certain() || nameTreeKey == null) return null;
        var match = FOLDER_KEY.matcher(nameTreeKey);
        if (!match.matches()) return placements.rootId() == null || placements.rootId() == 0 ? 0 : null;
        try {
            int id = Integer.parseInt(match.group(1));
            if (placements.rootId() != null && id == placements.rootId()) return 0;
            return placements.folders().stream().anyMatch(folder -> folder.id() == id) ? id : null;
        } catch (NumberFormatException oversized) {
            return null;
        }
    }

    static MailNavigation read(PDDocument document) {
        Set<String> warnings = new LinkedHashSet<>();
        var catalog = document.getDocumentCatalog();
        var metadata = readMetadata(catalog.getMetadata(), warnings);
        Map<String, Integer> guids = new HashMap<>();
        Map<String, Integer> messageIds = new HashMap<>();
        for (int index = 0; index < metadata.messages().size(); index++) {
            var fields = metadata.messages().get(index);
            indexIdentity(guids, fields.get("Mail_GUID"), index + 1);
            indexIdentity(messageIds, fields.get("Message-ID"), index + 1);
        }
        if (guids.containsValue(-1)) warnings.add("Duplicate message identifiers have no verified page or file association.");

        var collectionData = catalog.getCOSObject().getDictionaryObject(COSName.getPDFName("Collection"));
        boolean collection = collectionData instanceof COSDictionary;
        if (collectionData != null && !collection) warnings.add("The collection dictionary could not be read.");
        Placements placements = collection ? readFolders((COSDictionary) collectionData, warnings)
                : new Placements(List.of(), null, false);

        Map<COSDictionary, Set<String>> fileOwners = new IdentityHashMap<>();
        int[] body = new int[document.getNumberOfPages()];
        int[] pages = new int[body.length];
        var ranges = readParts(document, metadata.messages(), guids, pages.length, fileOwners, warnings);
        resolvePages(ranges, body, pages);

        Map<Integer, List<String>> attachmentNames = new HashMap<>();
        Map<FileSignature, Set<String>> attachmentOwners = new HashMap<>();
        for (var attachment : metadata.attachments()) {
            var guid = attachment.get("Mail_GUID");
            int id = guid == null ? messageIds.getOrDefault(attachment.get("Message-ID"), 0)
                    : guids.getOrDefault(guid, 0);
            if (id <= 0) continue;
            attachmentNames.computeIfAbsent(id, ignored -> new ArrayList<>())
                    .add(text(attachment, "Filename", "(name not recorded)"));
            var checksum = attachment.get("Checksum");
            String ownerGuid = metadata.messages().get(id - 1).get("Mail_GUID");
            if (ownerGuid == null || checksum == null || !MD5.matcher(checksum).matches()) continue;
            try {
                long size = Long.parseLong(attachment.getOrDefault("SizeInBytes", ""));
                if (size >= 0) attachmentOwners.computeIfAbsent(
                        new FileSignature(size, checksum.toLowerCase(Locale.ROOT)), ignored -> new LinkedHashSet<>())
                        .add(ownerGuid);
            } catch (NumberFormatException invalidSize) {
                // A malformed XMP size cannot identify a physical attachment.
            }
        }
        attachmentOwners.replaceAll((signature, owners) -> Set.copyOf(owners));
        fileOwners.replaceAll((file, owners) -> Set.copyOf(owners));

        var first = new HashMap<Integer, Integer>();
        var last = new HashMap<Integer, Integer>();
        for (int page = 0; page < body.length; page++) {
            if (body[page] <= 0) continue;
            first.putIfAbsent(body[page], page);
            last.put(body[page], page);
        }

        List<Message> messages = new ArrayList<>();
        Map<Integer, Message> byIndex = new HashMap<>();
        Long catalogAttachmentCount = metadata.messages().isEmpty() ? null : 0L;
        for (int id = 1; id <= metadata.messages().size(); id++) {
            var record = metadata.messages().get(id - 1);
            if (catalogAttachmentCount != null) {
                Long count = parseMetadataCount(record.get("NumberAttachments"));
                catalogAttachmentCount = count == null || count > MAX_METADATA_COUNT - catalogAttachmentCount
                        ? null : catalogAttachmentCount + count;
            }
            int start = first.getOrDefault(id, -1);
            if (collection && start < 0) continue;
            var fields = new LinkedHashMap<>(record);
            var names = attachmentNames.get(id);
            if (names != null) fields.put("Attachment names", String.join("; ", names));
            var message = new Message(text(fields, "Mail_GUID", ""), text(fields, "Subject", "Message " + id),
                    text(fields, "From", text(fields, "Raw-From", "")), text(fields, "Sent", ""),
                    start, last.getOrDefault(id, -1), Map.copyOf(fields));
            messages.add(message);
            byIndex.put(id, message);
        }
        return new MailNavigation(messages, metadata.messages().size(), catalogAttachmentCount, metadata.archiveDescription(),
                placements, collection, metadata.profile(), warnings,
                pageMap(body, byIndex), pageMap(pages, byIndex), fileOwners, attachmentOwners);
    }

    static Long parseMetadataCount(String text) {
        if (text == null || !METADATA_COUNT.matcher(text).matches()) return null;
        try {
            long count = Long.parseLong(text);
            return count <= MAX_METADATA_COUNT ? count : null;
        } catch (NumberFormatException oversized) {
            return null;
        }
    }

    private static void indexIdentity(Map<String, Integer> index, String key, int owner) {
        if (key != null && !key.isBlank()) index.merge(key, owner, (first, duplicate) -> -1);
    }

    private static Map<Integer, Message> pageMap(int[] owners, Map<Integer, Message> messages) {
        var result = new HashMap<Integer, Message>();
        for (int page = 0; page < owners.length; page++) {
            var message = messages.get(owners[page]);
            if (message != null) result.put(page, message);
        }
        return result;
    }

    private static List<PageRange> readParts(PDDocument document, List<Map<String, String>> messages,
                                             Map<String, Integer> guids, int pageCount,
                                             Map<COSDictionary, Set<String>> fileOwners, Set<String> warnings) {
        var catalog = document.getDocumentCatalog().getCOSObject();
        var rootKey = COSName.getPDFName("DPartRoot");
        var root = dictionary(catalog, rootKey);
        if (root == null) {
            if (catalog.containsKey(rootKey)) warnings.add("The document-part root could not be read.");
            return List.of();
        }
        var pageIndexes = new IdentityHashMap<COSDictionary, Integer>();
        int pageIndex = 0;
        for (var page : document.getPages()) pageIndexes.put(page.getCOSObject(), pageIndex++);

        var rootNode = root.getDictionaryObject(COSName.getPDFName("DPartRootNode"));
        if (rootNode == null) {
            warnings.add("The document-part tree is missing.");
            return List.of();
        }
        var seen = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
        List<PageRange> ranges = new ArrayList<>();
        Set<COSDictionary> unresolvedFiles = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Part>();
        pending.push(new Part(rootNode, root, 0, 0));
        int inspected = 0, partCount = 0;
        while (!pending.isEmpty()) {
            if (++inspected > MAX_OBJECTS) {
                warnings.add("The document-part inventory reached its inspection limit.");
                break;
            }
            var current = pending.pop();
            if (current.depth() > MAX_TREE_DEPTH || !seen.add(current.value())) {
                warnings.add("The document-part inventory contains a repeated or excessively deep entry.");
                continue;
            }
            if (current.value() instanceof COSArray group) {
                int count = Math.min(group.size(), MAX_OBJECTS - inspected - pending.size());
                if (count < group.size()) warnings.add("The document-part inventory reached its inspection limit.");
                for (int i = count - 1; i >= 0; i--) {
                    var child = group.getObject(i);
                    if (child != null) pending.push(new Part(child, current.parent(), current.depth() + 1, current.owner()));
                }
                continue;
            }
            if (!(current.value() instanceof COSDictionary part)) {
                warnings.add("A document part could not be read.");
                continue;
            }
            if (++partCount > MAX_PARTS) {
                warnings.add("The document-part inventory reached its inspection limit.");
                break;
            }
            var dpm = dictionary(part, COSName.getPDFName("DPM"));
            int owner = current.owner();
            var guidKey = COSName.getPDFName("Mail_GUID");
            var messageIdKey = COSName.getPDFName("Mail_MessageID");
            if (dpm != null && (dpm.containsKey(guidKey) || dpm.containsKey(messageIdKey))) {
                var identity = dpm.getDictionaryObject(guidKey);
                owner = identity instanceof COSString string ? guids.getOrDefault(string.getString(), 0) : 0;
            }
            if (!(part.getItem(COSName.PARENT) instanceof COSObject)
                    || part.getDictionaryObject(COSName.PARENT) != current.parent()) {
                warnings.add("A document part has inconsistent ancestry.");
                owner = 0;
            }
            if (part.getDictionaryObject(COSName.getPDFName("AF")) instanceof COSArray files) {
                String guid = owner > 0 ? messages.get(owner - 1).get("Mail_GUID") : null;
                int limit = Math.min(files.size(), MAX_OBJECTS - inspected);
                for (int index = 0; index < limit; index++) {
                    if (files.getObject(index) instanceof COSDictionary spec) {
                        if (guid == null) unresolvedFiles.add(spec);
                        else fileOwners.computeIfAbsent(spec, ignored -> new LinkedHashSet<>()).add(guid);
                    }
                }
                inspected += limit;
                if (limit < files.size()) warnings.add("The document-part file associations reached their inspection limit.");
            }

            var startKey = COSName.getPDFName("Start");
            var endKey = COSName.getPDFName("End");
            if (part.containsKey(startKey) || part.containsKey(endKey)) {
                int start = part.getItem(startKey) instanceof COSObject
                        ? pageIndexes.getOrDefault(part.getDictionaryObject(startKey), -1) : -1;
                int end = !part.containsKey(endKey) ? start : part.getItem(endKey) instanceof COSObject
                        ? pageIndexes.getOrDefault(part.getDictionaryObject(endKey), -1) : -1;
                if (start < 0 || end < start || end >= pageCount) {
                    warnings.add("A document part identifies an unavailable or invalid page range.");
                } else {
                    var type = dpm == null ? null : dpm.getDictionaryObject(COSName.getPDFName("Mail_ContentSetType"));
                    ranges.add(new PageRange(start, end, Math.max(0, owner),
                            type instanceof COSName name && name.getName().equals("BodyRendering")));
                }
            }
            var children = part.getDictionaryObject(COSName.getPDFName("DParts"));
            if (children != null) pending.push(new Part(children, part, current.depth() + 1, owner));
        }
        unresolvedFiles.forEach(fileOwners::remove);
        return ranges;
    }

    private static void resolvePages(List<PageRange> ranges, int[] body, int[] pages) {
        List<Boundary> boundaries = new ArrayList<>();
        for (var range : ranges) {
            boundaries.add(new Boundary(range.start(), range.owner(), range.body(), 1));
            boundaries.add(new Boundary(range.end() + 1, range.owner(), range.body(), -1));
        }
        boundaries.sort(Comparator.comparingInt(Boundary::page));
        Map<Integer, Integer> bodyActive = new HashMap<>();
        Map<Integer, Integer> pageActive = new HashMap<>();
        int index = 0;
        for (int page = 0; page < pages.length; page++) {
            while (index < boundaries.size() && boundaries.get(index).page() == page) {
                Boundary boundary = boundaries.get(index++);
                updateActive(pageActive, boundary);
                if (boundary.body()) updateActive(bodyActive, boundary);
            }
            if (pageActive.size() == 1) pages[page] = pageActive.keySet().iterator().next();
            if (bodyActive.size() == 1) body[page] = bodyActive.keySet().iterator().next();
        }
    }

    private static void updateActive(Map<Integer, Integer> active, Boundary boundary) {
        int count = active.getOrDefault(boundary.owner(), 0) + boundary.change();
        if (count == 0) active.remove(boundary.owner());
        else active.put(boundary.owner(), count);
    }

    private static Placements readFolders(COSDictionary collection, Set<String> warnings) {
        var key = COSName.getPDFName("Folders");
        var root = dictionary(collection, key);
        if (root == null) {
            if (collection.containsKey(key)) warnings.add("The collection folder tree could not be read.");
            return new Placements(List.of(), null, !collection.containsKey(key));
        }
        var idKey = COSName.getPDFName("ID");
        Integer rootId = folderNumber(root, idKey);
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(root);
        var ids = new HashSet<Integer>();
        if (rootId != null) ids.add(rootId);
        var result = new ArrayList<Folder>();
        var pending = new ArrayDeque<FolderNode>();
        var childKey = COSName.getPDFName("Child");
        var first = root.getDictionaryObject(childKey);
        if (first != null) pending.push(new FolderNode(first, null, 1));

        boolean certain = rootId != null;
        while (!pending.isEmpty()) {
            if (seen.size() >= MAX_OBJECTS) {
                certain = false;
                break;
            }
            var current = pending.pop();
            if (current.depth() > MAX_TREE_DEPTH || !seen.add(current.value())
                    || !(current.value() instanceof COSDictionary folder)) {
                certain = false;
                continue;
            }
            var next = folder.getDictionaryObject(COSName.NEXT);
            if (next != null) pending.push(new FolderNode(next, current.parent(), current.depth()));
            Integer id = folderNumber(folder, idKey);
            if (id == null || id == 0 || !ids.add(id)) {
                certain = false;
                continue;
            }
            String name = folder.getString(COSName.NAME);
            if (name == null || name.isBlank()) {
                certain = false;
                name = "Unnamed folder";
            }
            result.add(new Folder(id, current.parent(), name));
            var child = folder.getDictionaryObject(childKey);
            if (child != null) pending.push(new FolderNode(child, id, current.depth() + 1));
        }
        if (!certain) warnings.add("The collection folder inventory is incomplete or ambiguous.");
        return new Placements(List.copyOf(result), rootId, certain);
    }

    private static Integer folderNumber(COSDictionary folder, COSName key) {
        var number = folder.getDictionaryObject(key);
        if (!(number instanceof COSInteger numeric) || numeric.longValue() < 0 || numeric.longValue() > Integer.MAX_VALUE) return null;
        return numeric.intValue();
    }

    private static Metadata readMetadata(PDMetadata metadata, Set<String> warnings) {
        var empty = new Metadata("", "", List.of(), List.of());
        if (metadata == null) return empty;
        try (var input = new MetadataInput(metadata.createInputStream())) {
            var factory = XMLInputFactory.newDefaultFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setXMLResolver((publicId, systemId, base, namespace) -> {
                throw new XMLStreamException("External XML resources are unavailable.");
            });
            var parser = factory.createXMLStreamReader(input);
            try {
                var dom = DocumentBuilderFactory.newNSInstance().newDocumentBuilder().newDocument();
                var metadataRows = new MetadataRows();
                while (parser.hasNext()) {
                    if (next(parser) == XMLStreamConstants.START_ELEMENT && is(parser, RDF, "RDF")) {
                        readRdf(parser, dom, metadataRows, warnings);
                    }
                }
                if (!metadataRows.descriptions) {
                    warnings.add("The catalog metadata contains no RDF description.");
                    return empty;
                }
                return metadataRows.finish(warnings);
            } finally {
                parser.close();
            }
        } catch (IOException | ParserConfigurationException | XMLStreamException unreadable) {
            Throwable cause = unreadable;
            while (cause != null && !(cause instanceof MetadataTooLarge)) cause = cause.getCause();
            warnings.add(cause != null ? "The catalog metadata exceeds the 128 MiB inspection limit."
                    : "The catalog metadata could not be read.");
            return empty;
        }
    }

    private static final class MetadataRows {
        private final Set<String> profiles = new HashSet<>();
        private final List<Element> roots = new ArrayList<>();
        private final List<Map<String, String>> emails = new ArrayList<>();
        private final List<Map<String, String>> attachments = new ArrayList<>();
        private String archiveDescription = "";
        private boolean unreadableProfile, descriptions, emailContainer, fallbackIncomplete;

        void observeArchiveDescription(String value) {
            if (value == null || !archiveDescription.isEmpty()) return;
            String text = value.strip();
            if (!text.startsWith("EA-PDF email archive;")) return;
            archiveDescription = text.length() <= MAX_ARCHIVE_DESCRIPTION
                    ? text : text.substring(0, MAX_ARCHIVE_DESCRIPTION) + "…";
        }

        Metadata finish(Set<String> warnings) {
            String profile = !unreadableProfile && profiles.size() == 1 ? profiles.iterator().next() : "";
            if (unreadableProfile || profiles.size() > 1 || (!profile.isEmpty() && !KNOWN_PROFILES.contains(profile))) {
                warnings.add("The EA-PDF profile declaration is unavailable or unrecognized.");
                profile = "";
            }
            if (emails.isEmpty() && !emailContainer && Set.of("s", "si").contains(profile)) {
                if (fallbackIncomplete) warnings.add("Some document-level email metadata could not be read.");
                var record = fields(roots, warnings);
                if (record.containsKey("Mail_GUID") || record.containsKey("Message-ID")) emails.add(record);
            }
            return new Metadata(profile, archiveDescription, emails, attachments);
        }
    }

    private static void readRdf(XMLStreamReader parser, Document dom, MetadataRows rows, Set<String> warnings)
            throws XMLStreamException {
        while (parser.hasNext()) {
            int event = next(parser);
            if (event == XMLStreamConstants.END_ELEMENT) return;
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            if (!is(parser, RDF, "Description")) {
                skipElement(parser);
                continue;
            }
            rows.descriptions = true;
            readDescription(parser, dom, rows, warnings);
        }
    }

    private static void readDescription(XMLStreamReader parser, Document dom, MetadataRows rows, Set<String> warnings)
            throws XMLStreamException {
        String profile = parser.getAttributeValue(ID, "conformance");
        if (profile != null) rows.profiles.add(profile);
        rows.observeArchiveDescription(parser.getAttributeValue(DC, "description"));
        String about = parser.getAttributeValue(RDF, "about");
        boolean couldBeStandalone = rows.profiles.isEmpty() || rows.profiles.contains("s") || rows.profiles.contains("si");
        Element fallback = couldBeStandalone && (about == null || about.isEmpty())
                ? dom.createElementNS(RDF, "rdf:Description") : null;
        if (fallback != null) {
            for (String key : ROOT_EMAIL_FIELDS) {
                String text = parser.getAttributeValue(META, key);
                if (text != null) fallback.setAttributeNS(META, "pdfmail:" + key, text);
            }
        }
        int fallbackNodes = 0;
        while (parser.hasNext()) {
            int event = next(parser);
            if (event == XMLStreamConstants.END_ELEMENT) break;
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            if (is(parser, ID, "conformance")) {
                Element field = copyRecord(parser, dom);
                String value = field == null ? null : simpleText(field, false);
                if (value == null) rows.unreadableProfile = true;
                else rows.profiles.add(value);
            } else if (is(parser, DC, "description") && rows.archiveDescription.isEmpty()) {
                var field = copyRecord(parser, dom);
                if (field != null) rows.observeArchiveDescription(descriptionText(field));
            } else if (is(parser, META, "email")) {
                rows.emailContainer = true;
                readRecords(parser, dom, rows.emails, MAX_PARTS, warnings);
            } else if (is(parser, META, "attachments")) {
                readRecords(parser, dom, rows.attachments, MAX_OBJECTS, warnings);
            } else if (fallback != null && META.equals(parser.getNamespaceURI())
                    && ROOT_EMAIL_FIELDS.contains(parser.getLocalName()) && !rows.emailContainer) {
                if (++fallbackNodes > ROOT_EMAIL_FIELDS.size() * 2 || rows.roots.size() >= MAX_PARTS) {
                    rows.fallbackIncomplete = true;
                    skipElement(parser);
                    continue;
                }
                Element field = copyRecord(parser, dom);
                if (field == null) rows.fallbackIncomplete = true;
                else fallback.appendChild(field);
            } else {
                skipElement(parser);
            }
        }
        if (fallback != null && (fallback.hasAttributes() || fallback.hasChildNodes())) {
            if (rows.roots.size() < MAX_PARTS) rows.roots.add(fallback);
            else rows.fallbackIncomplete = true;
        }
    }

    private static String descriptionText(Element field) {
        var alternatives = children(field, RDF, "Alt");
        if (alternatives.size() != 1) return simpleText(field, false);
        var items = children(alternatives.getFirst(), RDF, "li");
        if (items.isEmpty()) return null;
        var defaultText = items.stream().filter(item ->
                "x-default".equals(item.getAttributeNS(XMLConstants.XML_NS_URI, "lang")))
                .findFirst().orElse(items.getFirst());
        return simpleText(defaultText, false);
    }

    private static void readRecords(XMLStreamReader parser, Document dom, List<Map<String, String>> records,
                                    int limit, Set<String> warnings) throws XMLStreamException {
        int bags = 0, first = records.size();
        while (parser.hasNext()) {
            int event = next(parser);
            if (event == XMLStreamConstants.END_ELEMENT) break;
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            if (!is(parser, RDF, "Bag") || ++bags != 1) {
                skipElement(parser);
                continue;
            }
            while (parser.hasNext()) {
                event = next(parser);
                if (event == XMLStreamConstants.END_ELEMENT) break;
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                if (!is(parser, RDF, "li")) {
                    skipElement(parser);
                } else if (records.size() >= limit) {
                    warnings.add("The email metadata inventory reached its inspection limit.");
                    skipElement(parser);
                } else {
                    var item = copyRecord(parser, dom);
                    var nested = item == null ? List.<Element>of() : children(item, RDF, "Description");
                    if (item == null || nested.size() > 1) {
                        warnings.add("An email metadata record could not be read.");
                    } else {
                        records.add(fields(List.of(nested.isEmpty() ? item : nested.getFirst()), warnings));
                    }
                }
            }
        }
        if (bags != 1) {
            records.subList(first, records.size()).clear();
            warnings.add("Some email metadata records have an unsupported format.");
        }
    }

    /** Keep only a bounded relevant record tree, even when the packet contains a large search index. */
    private static Element copyRecord(XMLStreamReader parser, Document dom) throws XMLStreamException {
        var root = element(parser, dom);
        var ancestors = new ArrayDeque<Element>();
        ancestors.push(root);
        int depth = 1, nodes = 1, chars = elementCharacters(parser);
        boolean complete = chars <= MAX_RECORD_CHARS;
        while (depth > 0 && parser.hasNext()) {
            int event = next(parser);
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                chars += elementCharacters(parser);
                complete &= ++nodes <= MAX_PARTS && depth <= MAX_TREE_DEPTH && chars <= MAX_RECORD_CHARS;
                if (complete) {
                    var child = element(parser, dom);
                    ancestors.peek().appendChild(child);
                    ancestors.push(child);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (complete) ancestors.pop();
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                int count = parser.getTextLength();
                chars += count;
                complete &= ++nodes <= MAX_PARTS && chars <= MAX_RECORD_CHARS;
                if (complete && count > 0) ancestors.peek().appendChild(dom.createTextNode(parser.getText()));
            }
        }
        return complete ? root : null;
    }

    private static int elementCharacters(XMLStreamReader parser) {
        int size = parser.getLocalName().length();
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            size += parser.getAttributeLocalName(i).length() + parser.getAttributeValue(i).length();
        }
        return size;
    }

    private static Element element(XMLStreamReader parser, Document dom) {
        var name = parser.getName();
        String qualified = name.getPrefix().isEmpty() ? name.getLocalPart() : name.getPrefix() + ":" + name.getLocalPart();
        var element = dom.createElementNS(name.getNamespaceURI(), qualified);
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            var attr = parser.getAttributeName(i);
            String key = attr.getPrefix().isEmpty() ? attr.getLocalPart() : attr.getPrefix() + ":" + attr.getLocalPart();
            element.setAttributeNS(attr.getNamespaceURI(), key, parser.getAttributeValue(i));
        }
        return element;
    }

    private static boolean is(XMLStreamReader parser, String namespace, String name) {
        return namespace.equals(parser.getNamespaceURI()) && name.equals(parser.getLocalName());
    }

    private static void skipElement(XMLStreamReader parser) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && parser.hasNext()) {
            int event = next(parser);
            if (event == XMLStreamConstants.START_ELEMENT) depth++;
            else if (event == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    private static int next(XMLStreamReader parser) throws XMLStreamException {
        int event = parser.next();
        if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DTD declarations are unavailable.");
        return event;
    }

    private static final class MetadataTooLarge extends IOException {}

    private static final class MetadataInput extends FilterInputStream {
        private int remaining = MAX_METADATA;

        MetadataInput(InputStream input) { super(input); }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1 && remaining-- <= 0) throw new MetadataTooLarge();
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return read();
            int count = in.read(bytes, offset, Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }

    private static Map<String, String> fields(List<Element> rows, Set<String> warnings) {
        Set<String> keys = new LinkedHashSet<>();
        for (Element row : rows) {
            for (int i = 0; i < row.getAttributes().getLength(); i++) {
                var attr = row.getAttributes().item(i);
                if (META.equals(attr.getNamespaceURI())) keys.add(attr.getLocalName());
            }
            for (Element child : children(row, META, null)) keys.add(child.getLocalName());
        }
        keys.removeAll(Set.of("email", "assets", "attachments"));
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : keys) {
            Set<String> values = new HashSet<>();
            boolean valid = true;
            for (Element row : rows) valid &= collect(row, META, key, values, true);
            if (valid && values.size() == 1) result.put(key, values.iterator().next());
            else if (!valid || values.size() > 1) warnings.add("Some email metadata values are unavailable or ambiguous.");
        }
        alias(result, "Message-ID", "messageid", warnings);
        alias(result, "Checksum", "CheckSum", warnings);
        return result;
    }

    private static void alias(Map<String, String> fields, String primary, String secondary, Set<String> warnings) {
        String alternative = fields.get(secondary);
        if (alternative == null) return;
        String current = fields.putIfAbsent(primary, alternative);
        if (current != null && !alternative.equals(current)) {
            fields.remove(primary);
            fields.remove(secondary);
            warnings.add("Conflicting email metadata aliases were omitted.");
        }
    }

    private static boolean collect(Element row, String namespace, String name, Set<String> values, boolean displayFields) {
        if (row.hasAttributeNS(namespace, name)) values.add(row.getAttributeNS(namespace, name));
        boolean valid = true;
        for (Element field : children(row, namespace, name)) {
            String value;
            if (displayFields && Set.of("From", "To", "Cc", "Bcc").contains(name)) value = agents(field);
            else if (displayFields && Set.of("In-Reply-To", "References").contains(name)) value = references(field);
            else value = simpleText(field, displayFields && !"Mail_GUID".equals(name));
            if (value == null) valid = false;
            else values.add(value);
        }
        return valid;
    }

    private static String simpleText(Element element, boolean allowQualified) {
        if (element.hasAttributeNS(RDF, "resource")) return null;
        if (children(element, null, null).isEmpty() && !element.hasAttributeNS(RDF, "parseType")) return element.getTextContent();
        if (!allowQualified) return null;
        var descriptions = children(element, RDF, "Description");
        Element resource = descriptions.size() == 1 ? descriptions.getFirst()
                : "Resource".equals(element.getAttributeNS(RDF, "parseType")) ? element : null;
        if (resource == null) return null;
        var values = children(resource, RDF, "value");
        return values.size() == 1 ? simpleText(values.getFirst(), false) : null;
    }

    private static String agents(Element field) {
        if (children(field, null, null).size() != 1) return null;
        var array = children(field, null, null).getFirst();
        if (!RDF.equals(array.getNamespaceURI()) || !Set.of("Seq", "Bag").contains(array.getLocalName())) return null;
        List<String> agents = new ArrayList<>();
        for (Element item : children(array, RDF, "li")) {
            var nested = children(item, RDF, "Description");
            if (nested.size() > 1) return null;
            var agent = nested.isEmpty() ? item : nested.getFirst();
            String name = agentField(agent, "name");
            String address = agentField(agent, "mbox");
            if (name == null || address == null) return null;
            address = address.replaceFirst("(?i)^mailto:", "");
            if (!address.isBlank()) agents.add(name.isBlank() ? address : name + " <" + address + ">");
            else if (!name.isBlank()) agents.add(name);
        }
        return String.join(", ", agents);
    }

    private static String agentField(Element agent, String key) {
        Set<String> values = new HashSet<>();
        if (agent.hasAttributeNS(FOAF, key)) values.add(agent.getAttributeNS(FOAF, key));
        for (Element field : children(agent, FOAF, key)) {
            var value = "mbox".equals(key) && field.hasAttributeNS(RDF, "resource")
                    ? field.getAttributeNS(RDF, "resource") : simpleText(field, false);
            if (value == null) return null;
            values.add(value);
        }
        return values.size() > 1 ? null : values.isEmpty() ? "" : values.iterator().next();
    }

    private static String references(Element field) {
        var direct = simpleText(field, false);
        if (direct != null) return direct;
        var nodes = field.getElementsByTagNameNS(RDF, "li");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = simpleText((Element) nodes.item(i), false);
            if (value == null) return null;
            values.add(value);
        }
        return values.isEmpty() ? null : String.join(" ", values);
    }

    private static List<Element> children(Element parent, String namespace, String name) {
        var result = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && (namespace == null || namespace.equals(element.getNamespaceURI()))
                    && (name == null || name.equals(element.getLocalName()))) result.add(element);
        }
        return result;
    }

    private static COSDictionary dictionary(COSDictionary parent, COSName name) {
        return parent.getDictionaryObject(name) instanceof COSDictionary dictionary ? dictionary : null;
    }

    private static String text(Map<String, String> fields, String name, String fallback) {
        String value = fields.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
