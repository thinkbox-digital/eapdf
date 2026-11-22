package eapdf.viewer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;

final class ViewerSearch {
    private static final int MAX_DOCUMENTS = 128;
    private static final int MAX_RESULTS = 250;
    private static final int MAX_PAGE_CHARACTERS = 2 * 1024 * 1024;
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    record Hit(List<String> path, int page, String snippet) {}

    private ViewerSearch() {}

    static List<Hit> search(ViewerSession root, String query, BooleanSupplier canceled, Consumer<String> progress)
            throws IOException {
        String phrase = normalized(query);
        if (phrase.isEmpty()) return List.of();
        var search = new Search(Pattern.compile(phrase, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                Math.max(0, ViewerApplication.MAX_CHILD_DEPTH - root.depth()), canceled, progress);
        search.checkCanceled();
        try (var pdf = PdfNavigation.open(root.renderingPath)) {
            search.scan(pdf, root.name, List.of());
        }
        search.checkCanceled();
        if (search.skipped != 0) progress.accept("PDFs skipped because they could not be read: " + search.skipped);
        return List.copyOf(search.hits);
    }

    /** Replays a search route only when the stored name-tree key resolves uniquely. */
    static PdfNavigation.Asset resolve(List<PdfNavigation.Asset> assets, String key) {
        PdfNavigation.Asset found = null;
        for (var asset : assets) {
            if (!asset.nameTreeKeys().contains(key)) continue;
            if (found != null) return null;
            found = asset;
        }
        return found != null && found.isCollectionPdf() ? found : null;
    }

    static PdfNavigation.AssetStream preferredStream(PdfNavigation.Asset asset) {
        return asset == null ? null : asset.variants().stream().filter(PdfNavigation.AssetStream::isPdf)
                .findFirst().orElse(null);
    }

    private static String normalized(String text) { return SPACE.matcher(text).replaceAll(" ").strip(); }

    private static final class Search {
        private final Pattern query;
        private final int depthLimit;
        private final BooleanSupplier canceled;
        private final Consumer<String> progress;
        private final List<Hit> hits = new ArrayList<>();
        private int documents;
        private int skipped;

        Search(Pattern query, int depthLimit, BooleanSupplier canceled, Consumer<String> progress) {
            this.query = query;
            this.depthLimit = depthLimit;
            this.canceled = canceled;
            this.progress = progress;
        }

        void scan(PdfNavigation pdf, String name, List<String> path) throws IOException {
            checkCanceled();
            documents++;
            progress.accept(name);
            var buffer = new PageBuffer();
            try {
                new PDFTextStripper() {
                    @Override protected void startPage(PDPage page) throws IOException {
                        checkCanceled();
                        if (hits.size() >= MAX_RESULTS) throw new ResultLimit();
                        buffer.text.setLength(0);
                    }

                    @Override protected void endPage(PDPage page) throws IOException {
                        checkCanceled();
                        String text = normalized(buffer.text.toString());
                        var matches = query.matcher(text);
                        if (hits.size() < MAX_RESULTS && matches.find()) {
                            int first = Math.max(0, matches.start() - 45);
                            int last = Math.min(text.length(), matches.end() + 70);
                            String excerpt = (first == 0 ? "" : "…") + text.substring(first, last)
                                    + (last == text.length() ? "" : "…");
                            hits.add(new Hit(path, getCurrentPageNo() - 1, excerpt));
                        }
                    }
                }.writeText(pdf.document(), buffer);
            } catch (ResultLimit reached) {
                return;
            }
            if (path.size() >= depthLimit || hits.size() >= MAX_RESULTS) return;
            var mail = MailNavigation.read(pdf.document());
            if (!mail.collection()) return;
            var assets = pdf.assets(mail);
            for (var asset : assets) {
                checkCanceled();
                if (documents >= MAX_DOCUMENTS || hits.size() >= MAX_RESULTS) return;
                var stream = preferredStream(asset);
                if (!asset.isCollectionPdf() || stream == null) continue;
                String key = asset.nameTreeKeys().stream().filter(candidate -> resolve(assets, candidate) == asset)
                        .findFirst().orElse(null);
                if (key == null) {
                    skipped++;
                    continue;
                }
                var route = new ArrayList<>(path);
                route.add(key);
                try {
                    var extracted = pdf.extract(stream);
                    try (var child = PdfNavigation.open(extracted)) {
                        scan(child, asset.name(), List.copyOf(route));
                    } finally {
                        Files.deleteIfExists(extracted);
                    }
                } catch (InterruptedIOException interrupted) {
                    throw interrupted;
                } catch (IOException unreadable) {
                    skipped++;
                }
            }
        }

        void checkCanceled() throws InterruptedIOException {
            if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Search canceled.");
            }
        }
    }

    private static final class PageBuffer extends Writer {
        private final StringBuilder text = new StringBuilder();

        @Override public void write(char[] data, int offset, int length) {
            int remaining = MAX_PAGE_CHARACTERS - text.length();
            if (remaining > 0) text.append(data, offset, Math.min(length, remaining));
        }

        @Override public void flush() {}

        @Override public void close() {}
    }

    private static final class ResultLimit extends IOException {}
}
