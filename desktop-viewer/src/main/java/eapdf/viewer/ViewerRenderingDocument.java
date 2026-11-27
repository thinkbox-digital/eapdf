package eapdf.viewer;

import com.dlsc.pdfviewfx.PDFBoxDocument;
import com.dlsc.pdfviewfx.PDFView;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Keep PDFViewFX's remaining render tasks off a closed PDFBox document. */
final class ViewerRenderingDocument extends PDFBoxDocument {
    private final ReentrantLock rendering = new ReentrantLock();
    private final AtomicBoolean closing = new AtomicBoolean();

    ViewerRenderingDocument(File file) {
        super(file);
    }

    List<PDFView.SearchResult> search(ViewerSession session, String query, BooleanSupplier canceled) throws IOException {
        try (var search = PdfSearchDocument.open(session)) {
            if (search != null) return searchText(search, query, canceled);
        } catch (InterruptedIOException stopped) {
            throw stopped;
        } catch (IOException | UncheckedIOException repair) {
            // The ordinary parser retains PDFBox's recovery behavior.
        }
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Search canceled.");
        }
        return searchOriginal(query, canceled);
    }

    @Override
    public BufferedImage renderPage(int pageNumber, float scale) {
        rendering.lock();
        try {
            // PDFViewFX can dispatch a queued thumbnail after switching documents.
            if (closing.get()) return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            return super.renderPage(pageNumber, scale);
        } finally {
            rendering.unlock();
        }
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        if (rendering.tryLock()) {
            closeAndUnlock();
        } else {
            Thread.ofVirtual().name("eapdf-close-renderer").start(() -> {
                rendering.lock();
                closeAndUnlock();
            });
        }
    }

    private void closeAndUnlock() {
        try {
            super.close();
        } catch (RuntimeException error) {
            System.getLogger(ViewerRenderingDocument.class.getName())
                    .log(System.Logger.Level.DEBUG, "Closing PDF renderer", error);
        } finally {
            rendering.unlock();
        }
    }
}
