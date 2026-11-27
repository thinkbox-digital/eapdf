package eapdf.viewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

final class ViewerSession implements AutoCloseable {
    final Path path;
    final Path renderingPath;
    final PdfFileStamp sourceStamp;
    final EmbeddedTextSearch.Cache searchIndex = new EmbeddedTextSearch.Cache();
    final String name;
    final ViewerSession parent;
    final PdfNavigation.Asset parentAsset;
    final PdfNavigation pdf;
    final MailNavigation mail;
    final List<PdfNavigation.Asset> assets;
    final List<PdfNavigation.Bookmark> bookmarks;
    int page;
    ContinuousReader.Position position;

    private ViewerSession(Path path, Path renderingPath, PdfFileStamp sourceStamp, String name, ViewerSession parent, PdfNavigation.Asset parentAsset,
                          PdfNavigation pdf, MailNavigation mail, List<PdfNavigation.Asset> assets,
                          List<PdfNavigation.Bookmark> bookmarks) {
        this.path = path;
        this.renderingPath = renderingPath;
        this.sourceStamp = sourceStamp;
        this.name = name;
        this.parent = parent;
        this.parentAsset = parentAsset;
        this.pdf = pdf;
        this.mail = mail;
        this.assets = assets;
        this.bookmarks = bookmarks;
    }

    static ViewerSession open(Path path, String name, ViewerSession parent, PdfNavigation.Asset asset)
            throws IOException {
        return open(path, name, parent, asset, "");
    }

    static ViewerSession open(Path path, String name, ViewerSession parent, PdfNavigation.Asset asset,
                              String password) throws IOException {
        var sourceStamp = PdfFileStamp.read(path);
        PdfNavigation pdf = null;
        Path renderingPath = path;
        try {
            pdf = PdfNavigation.open(path, password);
            var mail = MailNavigation.read(pdf.document());
            if (pdf.document().isEncrypted() && !password.isEmpty()) {
                renderingPath = Files.createTempFile("eapdf-viewer-render-", ".pdf");
                renderingPath.toFile().deleteOnExit();
                // PDFViewFX's file adapter has no password parameter. Give it a private working copy.
                try (var render = Loader.loadPDF(path.toFile(), password, IOUtils.createTempFileOnlyStreamCache())) {
                    render.setAllSecurityToBeRemoved(true);
                    render.save(renderingPath.toFile());
                }
            }
            return new ViewerSession(path, renderingPath, sourceStamp, name, parent, asset, pdf, mail, pdf.assets(mail), pdf.bookmarks());
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            release(pdf, path, false);
            if (!renderingPath.equals(path)) deleteTemporaryFile(renderingPath);
            throw error;
        }
    }

    String breadcrumb() {
        return parent == null ? name : parent.breadcrumb() + " / " + name;
    }

    int depth() {
        return parent == null ? 0 : parent.depth() + 1;
    }

    byte[] preview(PdfNavigation.Asset asset, PdfNavigation.AssetStream stream) throws IOException {
        return readAsset(asset, stream, PdfNavigation::preview);
    }

    Path extract(PdfNavigation.Asset asset, PdfNavigation.AssetStream stream) throws IOException {
        return readAsset(asset, stream, PdfNavigation::extract);
    }

    @FunctionalInterface
    private interface AssetRead<T> {
        T read(PdfNavigation document, PdfNavigation.AssetStream stream) throws IOException;
    }

    private <T> T readAsset(PdfNavigation.Asset asset, PdfNavigation.AssetStream stream, AssetRead<T> operation)
            throws IOException {
        try (var document = PdfNavigation.open(renderingPath)) {
            var key = stream.file().getCOSObject().getKey();
            if (key != null && path.equals(renderingPath)
                    && document.document().getDocument().getObjectFromPool(key).getObject() instanceof COSStream found) {
                return operation.read(document, new PdfNavigation.AssetStream(stream.name(), stream.mimeType(),
                        stream.mediaTypeParameters(), stream.declaredSize(), stream.declaredMd5(), stream.keys(),
                        new PDEmbeddedFile(found)));
            }
            // PDFBox can renumber objects when it writes the decrypted rendering copy.
            int assetIndex = assets.indexOf(asset);
            int streamIndex = asset.variants().indexOf(stream);
            var reopened = document.assets();
            if (assetIndex >= 0 && assetIndex < reopened.size() && streamIndex >= 0) {
                var candidate = reopened.get(assetIndex);
                if (candidate.name().equals(asset.name()) && candidate.nameTreeKeys().equals(asset.nameTreeKeys())
                        && streamIndex < candidate.variants().size()) {
                    var variant = candidate.variants().get(streamIndex);
                    if (variant.keys().equals(stream.keys()) && variant.mimeType().equals(stream.mimeType())
                            && Objects.equals(variant.declaredSize(), stream.declaredSize())
                            && Objects.equals(variant.declaredMd5(), stream.declaredMd5())) {
                        return operation.read(document, variant);
                    }
                }
            }
            throw new IOException("The selected embedded file is no longer available.");
        }
    }

    @Override
    public void close() {
        searchIndex.close();
        release(pdf, path, parent != null);
        if (!renderingPath.equals(path)) deleteTemporaryFile(renderingPath);
    }

    private static void release(PdfNavigation pdf, Path path, boolean temporary) {
        try {
            if (pdf != null) pdf.close();
        } catch (IOException error) {
            System.getLogger(ViewerSession.class.getName()).log(System.Logger.Level.DEBUG, "Closing PDF", error);
        } finally {
            if (temporary) deleteTemporaryFile(path);
        }
    }

    static void deleteTemporaryFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            path.toFile().deleteOnExit();
        }
    }
}
