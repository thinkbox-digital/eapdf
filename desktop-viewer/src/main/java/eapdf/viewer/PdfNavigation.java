package eapdf.viewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

final class PdfNavigation implements AutoCloseable {
    static final int MAX_ASSET_BYTES = 32 * 1024 * 1024;

    record Bookmark(String title, int page, int depth) {}

    record CollectionMember(COSString nameTreeKey, Asset asset, COSDictionary specification) {}

    record Asset(String name, String description, String relationship, String category, List<Integer> pages,
                 Set<String> owners, List<String> nameTreeKeys, List<AssetStream> variants,
                 boolean isCollectionPdf) {
        String shortDescription() {
            String legacyAttachment = "Attached PST email reconstructed as RFC 822 with headers, body, and nested attachments";
            if (!"Source".equals(relationship)) {
                if (description.equals(legacyAttachment) || description.startsWith(legacyAttachment + ";")) {
                    return "Attached email exported from PST" + description.substring(legacyAttachment.length());
                }
                return description;
            }
            if (description.startsWith("Exported RFC 822 conversion source. MBOX preprocessing:")) {
                return "Email source extracted from MBOX";
            }
            if (description.startsWith("Exported RFC 822 conversion source. PST preprocessing:")) {
                return "Email source exported from PST";
            }
            if (description.equals("Original RFC 822 source") || description.equals("Original international email source")) {
                return "Original EML source";
            }
            return description;
        }
    }

    record AssetStream(String name, String mimeType, String mediaTypeParameters, Long declaredSize,
                       String declaredMd5, List<String> keys, PDEmbeddedFile file) {
        String variantName() { return String.join(" / ", keys); }

        boolean isPdf() {
            return "application/pdf".equals(mimeType) || name.toLowerCase(Locale.ROOT).endsWith(".pdf");
        }

        boolean canPreview() {
            return (declaredSize == null || declaredSize != 0) && (isPdf() || mimeType.startsWith("text/")
                    || mimeType.matches("message/(?:rfc822|global)|image/(?:png|jpeg|gif|svg\\+xml)|application/(?:json|xml)")
                    || name.toLowerCase(Locale.ROOT).matches(".*\\.(?:png|jpe?g|gif|txt|csv|tsv|log|json|xml|html?|eml|svg|md)$"));
        }
    }

    record PageLink(double left, double top, double right, double bottom, Asset asset,
                    Integer targetPage, boolean broken, String namedDestination, boolean parent) {}

    private record OutlineEntry(PDOutlineItem item, int depth) {}

    private final Path source;
    private final PDDocument document;
    private PdfAssetIndex assetIndex;

    private PdfNavigation(Path source, PDDocument document) {
        this.source = source;
        this.document = document;
    }

    static PdfNavigation open(Path path) throws IOException {
        return open(path, "");
    }

    static PdfNavigation open(Path path, String password) throws IOException {
        return new PdfNavigation(path, Loader.loadPDF(path.toFile(), password, IOUtils.createTempFileOnlyStreamCache()));
    }

    PDDocument document() { return document; }

    List<Bookmark> bookmarks() throws IOException {
        var outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null || outline.getFirstChild() == null) {
            return List.of();
        }

        List<Bookmark> bookmarks = new ArrayList<>();
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<OutlineEntry>();
        pending.push(new OutlineEntry(outline.getFirstChild(), 0));
        while (!pending.isEmpty()) {
            var entry = pending.pop();
            var item = entry.item();
            if (!visited.add(item.getCOSObject())) continue;
            var next = item.getNextSibling();
            if (next != null) pending.push(new OutlineEntry(next, entry.depth()));
            var child = item.getFirstChild();
            if (child != null) pending.push(new OutlineEntry(child, entry.depth() + 1));
            var page = item.findDestinationPage(document);
            if (page == null) continue;
            int pageIndex = document.getPages().indexOf(page);
            if (pageIndex >= 0) {
                String title = item.getTitle();
                bookmarks.add(new Bookmark(title == null || title.isBlank() ? "Page " + (pageIndex + 1) : title,
                        pageIndex, entry.depth()));
            }
        }
        return List.copyOf(bookmarks);
    }

    List<Asset> assets() {
        if (assetIndex == null) assetIndex = new PdfAssetIndex(document, MailNavigation.read(document));
        return assetIndex.assets();
    }

    List<Asset> assets(MailNavigation mail) {
        if (assetIndex == null) assetIndex = new PdfAssetIndex(document, mail);
        return assetIndex.assets();
    }

    boolean assetsIncomplete() { return assetIndex != null && assetIndex.incomplete(); }

    List<CollectionMember> collectionMembers() {
        assets();
        return assetIndex.collectionMembers();
    }

    Optional<Asset> uniqueCollectionMember(COSString key) {
        assets();
        return assetIndex.uniqueCollectionMember(key);
    }

    List<PageLink> links(int page) throws IOException {
        assets();
        return assetIndex.links(page);
    }

    Integer namedPage(String name) throws IOException {
        return PdfAssetIndex.destinationPage(document,
                new org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination(name));
    }

    Path extract(AssetStream stream) throws IOException {
        Path copy = Files.createTempFile("eapdf-viewer-", ".pdf");
        try {
            requireMatching(copyBytes(stream, copy));
            return copy;
        } catch (IOException | RuntimeException error) {
            deleteIncomplete(copy, error);
            throw error;
        }
    }

    byte[] preview(AssetStream stream) throws IOException {
        try (var output = new ByteArrayOutputStream()) {
            requireMatching(copyBytes(stream, output));
            return output.toByteArray();
        }
    }

    List<String> save(AssetStream stream, Path destination) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target) && Files.isSameFile(source, target)) {
            throw new IOException("Choose a different path to preserve the open PDF.");
        }
        Path staging = Files.createTempFile(target.getParent(), ".eapdf-viewer-", ".part");
        try {
            List<String> warnings = copyBytes(stream, staging);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return warnings;
        } catch (IOException | RuntimeException error) {
            deleteIncomplete(staging, error);
            throw error;
        }
    }

    static String safeFileName(String name) {
        String safe = name.replaceAll("[\\\\/\\p{Cntrl}<>:\"|?*\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]", "_")
                .replaceAll("^\\.+|[. ]+$", "");
        if (safe.isBlank()) return "embedded-file";
        int characterCount = safe.codePointCount(0, safe.length());
        return characterCount <= 180 ? safe : safe.substring(0, safe.offsetByCodePoints(0, 180));
    }

    private static List<String> copyBytes(AssetStream stream, Path path) throws IOException {
        try (var output = Files.newOutputStream(path)) {
            return copyBytes(stream, output);
        }
    }

    private static List<String> copyBytes(AssetStream stream, OutputStream output) throws IOException {
        if (stream.declaredSize() != null && stream.declaredSize() > MAX_ASSET_BYTES) {
            throw new IOException("Embedded asset exceeds the 32 MiB limit.");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        int size = 0;
        try (var input = new DigestInputStream(stream.file().createInputStream(), digest)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; ) {
                size += read;
                if (size > MAX_ASSET_BYTES) throw new IOException("Embedded asset exceeds the 32 MiB limit.");
                output.write(buffer, 0, read);
            }
        }
        List<String> warnings = new ArrayList<>(2);
        if (stream.declaredSize() != null && stream.declaredSize() != size) {
            warnings.add("Embedded asset size does not match its declaration.");
        }
        if (stream.declaredMd5() != null && !stream.declaredMd5().equals(HexFormat.of().formatHex(digest.digest()))) {
            warnings.add("Embedded asset checksum does not match its declaration.");
        }
        return List.copyOf(warnings);
    }

    private static void requireMatching(List<String> warnings) throws IOException {
        if (!warnings.isEmpty()) throw new IOException(String.join(" ", warnings));
    }

    private static void deleteIncomplete(Path path, Exception error) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupError) {
            error.addSuppressed(cleanupError);
        }
    }

    @Override
    public void close() throws IOException {
        document.close();
    }
}
