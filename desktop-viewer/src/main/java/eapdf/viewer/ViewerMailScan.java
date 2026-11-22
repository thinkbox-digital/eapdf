package eapdf.viewer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

final class ViewerMailScan {
    private static final int MAX_DOCUMENTS = 128;

    record Located(MailNavigation.Message message, List<String> route) {}

    private final int depthLimit;
    private final BooleanSupplier canceled;
    private final List<Located> matches = new ArrayList<>();
    private int documents = 1;

    private ViewerMailScan(int depthLimit, BooleanSupplier canceled) {
        this.depthLimit = depthLimit;
        this.canceled = canceled;
    }

    static List<Located> scan(ViewerSession root, BooleanSupplier canceled) throws IOException {
        var scan = new ViewerMailScan(Math.max(0, ViewerApplication.MAX_CHILD_DEPTH - root.depth()), canceled);
        scan.checkCanceled();
        try (var pdf = PdfNavigation.open(root.renderingPath)) {
            scan.read(pdf, List.of());
        }
        scan.checkCanceled();
        return List.copyOf(scan.matches);
    }

    private void read(PdfNavigation pdf, List<String> route) throws IOException {
        checkCanceled();
        var mail = MailNavigation.read(pdf.document());
        if (!mail.collection()) {
            for (var message : mail.messages()) {
                matches.add(new Located(message, route));
            }
            return;
        }
        if (route.size() >= depthLimit || documents >= MAX_DOCUMENTS) return;
        var assets = pdf.assets(mail);
        for (var asset : assets) {
            checkCanceled();
            if (documents >= MAX_DOCUMENTS) return;
            if (!asset.isCollectionPdf()) continue;
            var stream = ViewerSearch.preferredStream(asset);
            if (stream == null) continue;
            String key = uniqueKey(assets, asset);
            if (key == null) continue;
            documents++;
            var childRoute = new ArrayList<>(route);
            childRoute.add(key);
            try {
                var extracted = pdf.extract(stream);
                try {
                    checkCanceled();
                    try (var child = PdfNavigation.open(extracted)) {
                        read(child, List.copyOf(childRoute));
                    }
                } finally {
                    ViewerSession.deleteTemporaryFile(extracted);
                }
            } catch (InterruptedIOException interrupted) {
                throw interrupted;
            } catch (IOException | RuntimeException unreadable) {
                // Other collection members can still supply message metadata.
            }
        }
    }

    private static String uniqueKey(List<PdfNavigation.Asset> assets, PdfNavigation.Asset asset) {
        return asset.nameTreeKeys().stream().filter(key -> ViewerSearch.resolve(assets, key) == asset)
                .findFirst().orElse(null);
    }

    private void checkCanceled() throws InterruptedIOException {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Message scan canceled.");
        }
    }
}
