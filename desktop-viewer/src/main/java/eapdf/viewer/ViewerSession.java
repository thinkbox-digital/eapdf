package eapdf.viewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;

final class ViewerSession implements AutoCloseable {
    final Path path;
    final Path renderingPath;
    final String name;
    final ViewerSession parent;
    final PdfNavigation.Asset parentAsset;
    final PdfNavigation pdf;
    final MailNavigation mail;
    final List<PdfNavigation.Asset> assets;
    final List<PdfNavigation.Bookmark> bookmarks;
    int page;
    ContinuousReader.Position position;

    private ViewerSession(Path path, Path renderingPath, String name, ViewerSession parent, PdfNavigation.Asset parentAsset,
                          PdfNavigation pdf, MailNavigation mail, List<PdfNavigation.Asset> assets,
                          List<PdfNavigation.Bookmark> bookmarks) {
        this.path = path;
        this.renderingPath = renderingPath;
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
            return new ViewerSession(path, renderingPath, name, parent, asset, pdf, mail, pdf.assets(mail), pdf.bookmarks());
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

    @Override
    public void close() {
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
