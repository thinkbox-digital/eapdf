package eapdf.viewer;

import eapdf.viewer.PdfNavigation.Asset;
import eapdf.viewer.PdfNavigation.AssetStream;
import eapdf.viewer.PdfNavigation.CollectionMember;
import eapdf.viewer.PdfNavigation.PageLink;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionEmbeddedGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDTargetDirectory;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;

/** Catalog and page references inventory file specifications without decoding their payloads. */
final class PdfAssetIndex {
    private static final int MAX_OBJECTS = 1_000_000;
    private static final COSName AF = COSName.getPDFName("AF");
    private static final COSName AF_RELATIONSHIP = COSName.getPDFName("AFRelationship");
    private static final COSName MEDIA_TYPE_PARAMETERS = COSName.getPDFName("Mail_MediaTypeParameters");
    private static final COSName CHECKSUM = COSName.getPDFName("CheckSum");
    private static final List<COSName> STREAM_KEYS = List.of(COSName.UF, COSName.F, COSName.DOS, COSName.MAC, COSName.UNIX);
    private static final List<String> CATEGORY_ORDER = List.of("Email sources", "Email attachments", "Other files",
            "Technical and supporting files");
    private static final Pattern PREMIS = Pattern.compile("(?:^|[_. -])premis(?:[_. -]|$)", Pattern.CASE_INSENSITIVE);

    private static final class AssetEntry {
        final PDComplexFileSpecification spec;
        final Set<COSString> names = new LinkedHashSet<>();
        final Set<Integer> pages = new LinkedHashSet<>();
        boolean catalog;
        boolean associatedObject;
        List<AssetStream> streams;
        Asset asset;

        AssetEntry(COSDictionary spec) { this.spec = new PDComplexFileSpecification(spec); }
    }

    private record Declaration(long size, String md5) {}

    private record LinkTarget(Asset asset, Integer page, boolean broken, String name, boolean parent) {
        static LinkTarget unavailable() { return new LinkTarget(null, null, true, null, false); }
    }

    private final PDDocument document;
    private final List<AssetEntry> entries = new ArrayList<>();
    private final Map<COSDictionary, AssetEntry> bySpec = new IdentityHashMap<>();
    private final Map<COSString, Set<AssetEntry>> byName = new LinkedHashMap<>();
    private final Set<COSString> repeatedNames = new LinkedHashSet<>();
    private final List<Asset> assets;
    private final boolean namesIncomplete;
    private boolean incomplete;

    PdfAssetIndex(PDDocument document, MailNavigation mail) {
        this.document = document;
        COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
        if (catalog.getDictionaryObject(COSName.NAMES) instanceof COSDictionary names) {
            readNames(names.getDictionaryObject(COSName.EMBEDDED_FILES));
        }
        namesIncomplete = incomplete;
        readAssociatedFiles(catalog);
        int page = 0;
        for (PDPage pdfPage : document.getPages()) readAnnotations(pdfPage, page++);

        Map<Declaration, Integer> distinctDeclarations = new LinkedHashMap<>();
        for (AssetEntry entry : entries) {
            entry.streams = readStreams(entry.spec, displayName(entry));
            entry.streams.stream().map(PdfAssetIndex::declaration).filter(Objects::nonNull).distinct()
                    .forEach(key -> distinctDeclarations.merge(key, 1, Integer::sum));
        }
        boolean collection = catalog.getDictionaryObject(COSName.COLLECTION) instanceof COSDictionary;
        for (AssetEntry entry : entries) {
            String name = displayName(entry);
            String relationship = entry.spec.getCOSObject().getNameAsString(AF_RELATIONSHIP, "");
            Set<String> owners = knownOwners(entry, mail, distinctDeclarations);
            entry.asset = new Asset(name, nonNull(entry.spec.getFileDescription()), relationship,
                    category(name, relationship, owners, entry.streams), List.copyOf(entry.pages), owners,
                    entry.names.stream().map(COSString::getString).toList(), entry.streams,
                    collection && !entry.names.isEmpty() && entry.streams.stream().anyMatch(AssetStream::isPdf));
        }
        assets = entries.stream().map(entry -> entry.asset)
                .sorted(Comparator.comparingInt(asset -> CATEGORY_ORDER.indexOf(asset.category()))).toList();
    }

    List<Asset> assets() { return assets; }

    List<CollectionMember> collectionMembers() {
        var members = new ArrayList<CollectionMember>();
        for (var file : entries) {
            for (var key : file.names) {
                members.add(new CollectionMember(key, file.asset, file.spec.getCOSObject()));
            }
        }
        return members;
    }

    Optional<Asset> uniqueCollectionMember(COSString key) {
        var matches = byName.get(key);
        if (matches == null || matches.size() != 1 || repeatedNames.contains(key) || namesIncomplete) {
            return Optional.empty();
        }
        return Optional.of(matches.iterator().next().asset);
    }

    boolean incomplete() { return incomplete; }

    private void readNames(COSBase root) {
        if (root == null) return;
        var pending = new ArrayDeque<COSBase>();
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        int work = 0;
        while (!pending.isEmpty() && work++ < MAX_OBJECTS) {
            COSBase object = dereference(pending.remove());
            if (!(object instanceof COSDictionary node) || !seen.add(node)) {
                incomplete = true;
                continue;
            }
            if (node.getDictionaryObject(COSName.NAMES) instanceof COSArray pairs) {
                if (pairs.size() % 2 != 0) incomplete = true;
                for (int i = 0; i + 1 < pairs.size() && work++ < MAX_OBJECTS; i += 2) {
                    AssetEntry entry = addFile(pairs.getObject(i + 1));
                    if (entry == null) continue;
                    if (pairs.getObject(i) instanceof COSString key) {
                        entry.names.add(key);
                        var matches = byName.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
                        if (!matches.isEmpty()) repeatedNames.add(key);
                        matches.add(entry);
                    } else {
                        incomplete = true;
                    }
                }
            } else if (node.containsKey(COSName.NAMES)) {
                incomplete = true;
            }
            if (node.getDictionaryObject(COSName.KIDS) instanceof COSArray children) {
                for (COSBase child : children) {
                    if (pending.size() >= MAX_OBJECTS) {
                        incomplete = true;
                        break;
                    }
                    if (child != null) pending.add(child);
                }
            } else if (node.containsKey(COSName.KIDS)) {
                incomplete = true;
            }
        }
        if (!pending.isEmpty() || work >= MAX_OBJECTS) incomplete = true;
    }

    private void readAssociatedFiles(COSDictionary catalog) {
        var pending = new ArrayDeque<COSBase>();
        Set<COSBase> queued = Collections.newSetFromMap(new IdentityHashMap<>());
        queue(catalog, pending, queued);
        while (!pending.isEmpty()) {
            COSBase value = pending.remove();
            if (value instanceof COSDictionary dictionary) {
                if (dictionary.getDictionaryObject(AF) instanceof COSArray associated) {
                    for (COSBase candidate : associated) {
                        AssetEntry entry = addFile(candidate);
                        if (entry != null) {
                            if (dictionary == catalog) entry.catalog = true;
                            else entry.associatedObject = true;
                        }
                    }
                } else if (dictionary.containsKey(AF)) {
                    incomplete = true;
                }
                for (var element : dictionary.entrySet()) queue(element.getValue(), pending, queued);
            } else if (value instanceof COSArray array) {
                for (COSBase element : array) queue(element, pending, queued);
            }
        }
    }

    private void queue(COSBase value, ArrayDeque<COSBase> pending, Set<COSBase> queued) {
        value = dereference(value);
        if (!(value instanceof COSDictionary || value instanceof COSArray) || queued.contains(value)) return;
        if (queued.size() >= MAX_OBJECTS) {
            incomplete = true;
            return;
        }
        queued.add(value);
        pending.add(value);
    }

    private void readAnnotations(PDPage page, int index) {
        try {
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationFileAttachment attachment) {
                    var file = attachment.getFile();
                    AssetEntry entry = addFile(file == null ? null : file.getCOSObject());
                    if (entry != null) entry.pages.add(index);
                }
            }
        } catch (IOException | IllegalArgumentException | ClassCastException unavailable) {
            incomplete = true;
        }
    }

    private AssetEntry addFile(COSBase raw) {
        if (!(dereference(raw) instanceof COSDictionary dictionary) || dictionary instanceof COSStream) {
            incomplete = true;
            return null;
        }
        AssetEntry existing = bySpec.get(dictionary);
        if (existing != null) return existing;
        if (entries.size() >= MAX_OBJECTS) {
            incomplete = true;
            return null;
        }
        AssetEntry entry = new AssetEntry(dictionary);
        entries.add(entry);
        bySpec.put(dictionary, entry);
        return entry;
    }

    private List<AssetStream> readStreams(PDComplexFileSpecification spec, String name) {
        COSBase raw = spec.getCOSObject().getDictionaryObject(COSName.EF);
        if (!(raw instanceof COSDictionary embeddedFiles)) return List.of();
        Map<COSStream, List<String>> keys = new LinkedHashMap<>();
        for (COSName key : STREAM_KEYS) {
            if (embeddedFiles.getDictionaryObject(key) instanceof COSStream stream) {
                keys.computeIfAbsent(stream, ignored -> new ArrayList<>()).add("/" + key.getName());
            } else if (embeddedFiles.containsKey(key)) {
                incomplete = true;
            }
        }
        List<AssetStream> variants = new ArrayList<>();
        for (var entry : keys.entrySet()) {
            COSStream stream = entry.getKey();
            String[] mediaType = stream.getNameAsString(COSName.SUBTYPE, "").split(";", 2);
            String mime = mediaType[0].strip().toLowerCase(Locale.ROOT);
            String parameters = stream.getString(MEDIA_TYPE_PARAMETERS, mediaType.length > 1 ? mediaType[1].strip() : "");
            Long size = null;
            String checksum = null;
            if (stream.getDictionaryObject(COSName.PARAMS) instanceof COSDictionary declarations) {
                if (declarations.getDictionaryObject(COSName.SIZE) instanceof COSInteger declared && declared.longValue() >= 0) {
                    size = declared.longValue();
                }
                if (declarations.getDictionaryObject(CHECKSUM) instanceof COSString declared && declared.getBytes().length == 16) {
                    checksum = HexFormat.of().formatHex(declared.getBytes());
                }
            }
            variants.add(new AssetStream(name, mime, parameters, size, checksum, List.copyOf(entry.getValue()),
                    new PDEmbeddedFile(stream)));
        }
        return List.copyOf(variants);
    }

    private static Declaration declaration(AssetStream stream) {
        return stream.declaredSize() == null || stream.declaredMd5() == null ? null
                : new Declaration(stream.declaredSize(), stream.declaredMd5());
    }

    private static Set<String> knownOwners(AssetEntry entry, MailNavigation mail, Map<Declaration, Integer> declarations) {
        Set<String> owners = mail.fileOwners(entry.spec.getCOSObject());
        if (!owners.isEmpty()) return owners;
        if (!entry.pages.isEmpty()) {
            Set<String> annotationOwners = new LinkedHashSet<>();
            for (int page : entry.pages) {
                var message = mail.ownerAt(page);
                if (message == null || message.guid().isBlank()) return Set.of();
                annotationOwners.add(message.guid());
            }
            return Collections.unmodifiableSet(annotationOwners);
        }
        Set<String> attachmentOwners = new LinkedHashSet<>();
        for (AssetStream stream : entry.streams) {
            Declaration declaration = declaration(stream);
            if (declaration != null && declarations.getOrDefault(declaration, 0) == 1) {
                attachmentOwners.addAll(mail.matchedAttachmentOwners(declaration.size(), declaration.md5()));
            }
        }
        if (!attachmentOwners.isEmpty()) return Collections.unmodifiableSet(attachmentOwners);
        if (entry.catalog && !entry.associatedObject && ("s".equals(mail.profile()) || "si".equals(mail.profile()))
                && mail.messages().size() == 1 && !mail.messages().getFirst().guid().isBlank()) {
            return Set.of(mail.messages().getFirst().guid());
        }
        return Set.of();
    }

    private static String category(String name, String relationship, Set<String> owners, List<AssetStream> streams) {
        boolean premis = name.toLowerCase(Locale.ROOT).endsWith(".xml") && PREMIS.matcher(name).find()
                || streams.stream().anyMatch(stream -> stream.mimeType().contains("premis"));
        if (premis || "Supplement".equals(relationship)) return "Technical and supporting files";
        if ("Source".equals(relationship)) return "Email sources";
        return owners.isEmpty() ? "Other files" : "Email attachments";
    }

    List<PageLink> links(int index) throws IOException {
        if (index < 0 || index >= document.getNumberOfPages()) return List.of();
        PDPage page = document.getPage(index);
        List<PageLink> links = new ArrayList<>();
        for (PDAnnotation annotation : page.getAnnotations()) {
            if (annotation.isHidden() || annotation.isNoView() || annotation.getRectangle() == null) continue;
            try {
                LinkTarget target = linkTarget(annotation);
                if (target != null) addLink(links, page, annotation.getRectangle(), target);
            } catch (IOException | IllegalArgumentException | ClassCastException unavailable) {
                addLink(links, page, annotation.getRectangle(), LinkTarget.unavailable());
            }
        }
        return List.copyOf(links);
    }

    private LinkTarget linkTarget(PDAnnotation annotation) throws IOException {
        if (annotation instanceof PDAnnotationFileAttachment attachment) {
            var file = attachment.getFile();
            return targetAsset(file == null ? null : bySpec.get(file.getCOSObject()), null);
        }
        if (!(annotation instanceof PDAnnotationLink link)) return null;
        var action = link.getAction();
        if (action instanceof PDActionEmbeddedGoTo embedded) return embeddedLink(embedded);
        if (action != null && !(action instanceof PDActionGoTo)) return null;
        PDDestination destination = action instanceof PDActionGoTo goTo ? goTo.getDestination() : link.getDestination();
        Integer page = destinationPage(document, destination);
        return page == null ? LinkTarget.unavailable() : new LinkTarget(null, page, false, null, false);
    }

    private LinkTarget embeddedLink(PDActionEmbeddedGoTo action) throws IOException {
        if (action.getFile() != null) return LinkTarget.unavailable();
        PDTargetDirectory target = action.getTargetDirectory();
        if (target == null || target.getTargetDirectory() != null || target.getRelationship() == null) {
            return LinkTarget.unavailable();
        }
        PDDestination destination = action.getDestination();
        String relationship = target.getRelationship().getName();
        if ("P".equals(relationship)) {
            return new LinkTarget(null, numericDestination(destination), false, namedDestination(destination), true);
        }
        if (!"C".equals(relationship)) return LinkTarget.unavailable();
        if (target.getCOSObject().getDictionaryObject(COSName.N) instanceof COSString key) {
            Set<AssetEntry> matches = byName.get(key);
            return targetAsset(matches != null && matches.size() == 1 ? matches.iterator().next() : null, destination);
        }
        Integer page = target.getPageNumber();
        var named = target.getNamedDestination();
        if (named != null) page = destinationPage(document, named);
        if (page == null || page < 0 || page >= document.getNumberOfPages()) return LinkTarget.unavailable();
        List<PDAnnotation> annotations = document.getPage(page).getAnnotations();
        int annotationIndex = target.getAnnotationIndex();
        if (annotationIndex >= 0 && annotationIndex < annotations.size()
                && annotations.get(annotationIndex) instanceof PDAnnotationFileAttachment attachment) {
            var file = attachment.getFile();
            return targetAsset(file == null ? null : bySpec.get(file.getCOSObject()), destination);
        }
        String name = target.getAnnotationName();
        if (name == null) return LinkTarget.unavailable();
        AssetEntry found = null;
        for (PDAnnotation annotation : annotations) {
            if (!(annotation instanceof PDAnnotationFileAttachment attachment) || !name.equals(annotation.getAnnotationName())) continue;
            var file = attachment.getFile();
            if (file == null || found != null) return LinkTarget.unavailable();
            found = bySpec.get(file.getCOSObject());
        }
        return targetAsset(found, destination);
    }

    private static LinkTarget targetAsset(AssetEntry entry, PDDestination destination) {
        if (entry == null || entry.asset.variants().isEmpty()) return LinkTarget.unavailable();
        return new LinkTarget(entry.asset, numericDestination(destination), false, namedDestination(destination), false);
    }

    static Integer destinationPage(PDDocument document, PDDestination destination) throws IOException {
        if (destination instanceof PDNamedDestination named) {
            destination = document.getDocumentCatalog().findNamedDestinationPage(named);
        }
        if (!(destination instanceof PDPageDestination target)) return null;
        PDPage page = target.getPage();
        int index = page == null ? target.getPageNumber() : document.getPages().indexOf(page);
        return index >= 0 && index < document.getNumberOfPages() ? index : null;
    }

    private static Integer numericDestination(PDDestination destination) {
        return destination instanceof PDPageDestination page && page.getPageNumber() >= 0 ? page.getPageNumber() : null;
    }

    private static String namedDestination(PDDestination destination) {
        return destination instanceof PDNamedDestination named ? named.getNamedDestination() : null;
    }

    private static void addLink(List<PageLink> links, PDPage page, PDRectangle box, LinkTarget target) {
        PDRectangle crop = page.getCropBox();
        if (crop.getWidth() <= 0 || crop.getHeight() <= 0) return;
        double[] first = point(page.getRotation(), crop, box.getLowerLeftX(), box.getLowerLeftY());
        double[] last = point(page.getRotation(), crop, box.getUpperRightX(), box.getUpperRightY());
        double left = clamp(Math.min(first[0], last[0])), top = clamp(Math.min(first[1], last[1]));
        double right = clamp(Math.max(first[0], last[0])), bottom = clamp(Math.max(first[1], last[1]));
        if (right > left && bottom > top) {
            links.add(new PageLink(left, top, right, bottom, target.asset(), target.page(), target.broken(), target.name(),
                    target.parent()));
        }
    }

    private static double[] point(int rotation, PDRectangle crop, float horizontal, float vertical) {
        double x = (horizontal - crop.getLowerLeftX()) / crop.getWidth();
        double y = (vertical - crop.getLowerLeftY()) / crop.getHeight();
        return switch (rotation) {
            case 90 -> new double[]{y, x};
            case 180 -> new double[]{1 - x, y};
            case 270 -> new double[]{1 - y, 1 - x};
            default -> new double[]{x, 1 - y};
        };
    }

    private static double clamp(double value) { return Math.max(0, Math.min(value, 1)); }

    private static COSBase dereference(COSBase value) {
        return value instanceof COSObject object ? object.getObject() : value;
    }

    private static String displayName(AssetEntry entry) {
        String name = entry.spec.getFilename();
        return name == null || name.isBlank() ? "Unnamed embedded file" : name;
    }

    private static String nonNull(String value) { return value == null ? "" : value; }
}
