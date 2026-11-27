package eapdf.viewer;

import com.google.gson.JsonParser;
import com.sun.javafx.application.ParametersImpl;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

/** Smoke test compiled and launched against the candidate standalone viewer JAR. */
public final class ReleaseReaderSmoke extends Application {
    private static volatile Throwable failure;
    private ViewerApplication viewer;

    static void run(String[] args) {
        launch(ReleaseReaderSmoke.class, args);
        if (failure != null) throw new AssertionError("Desktop reader acceptance failed", failure);
    }

    @Override
    public void start(Stage stage) {
        viewer = new ViewerApplication();
        ParametersImpl.registerParameters(viewer, new ParametersImpl(List.of()));
        viewer.start(stage);
        Thread.ofVirtual().start(() -> {
            try {
                exercise(Path.of(getParameters().getRaw().getFirst()));
            } catch (Throwable error) {
                failure = error;
            } finally {
                Platform.runLater(Platform::exit);
            }
        });
    }

    @Override
    public void stop() {
        viewer.stop();
    }

    private void exercise(Path manifest) throws Exception {
        var input = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        String attachment = input.get("attachment").getAsString();
        Method open = ViewerApplication.class.getDeclaredMethod("openLocal", Path.class);
        open.setAccessible(true);
        var documents = input.getAsJsonObject("documents");
        Path first = Path.of(documents.getAsJsonObject("1s").get("path").getAsString());
        Path next = Path.of(documents.getAsJsonObject("1si").get("path").getAsString());
        fx(() -> {
            open.invoke(viewer, first);
            Thread worker = (Thread) field("opening");
            check(worker != null, "Opening starts a tracked worker");
            ((Button) field("close")).fire();
            check(Boolean.TRUE.equals(field("busy")), "Cancel keeps the viewer busy until cleanup");
            open.invoke(viewer, next);
            check(field("opening") == worker, "Reopen cannot start a replacement worker during cancellation");
            return null;
        });
        await(() -> field("opening") == null && !Boolean.TRUE.equals(field("busy")),
                "canceled opening cleanup");
        check(fx(this::current) == null, "Canceled PDF never became the active document");
        checkMetadataDecoding();
        checkOrientationAndAssociatedFiles();
        runAttachmentImageChecks();
        checkLargeArchiveOpen(open);
        checkImageWindowLifecycle(open, Path.of(documents.getAsJsonObject("1s").get("path").getAsString()));
        for (var entry : documents.entrySet()) {
            String profile = entry.getKey();
            System.out.println("Checking " + profile);
            Path path = Path.of(entry.getValue().getAsJsonObject().get("path").getAsString());
            fx(() -> open.invoke(viewer, path));
            await(() -> current() != null && current().path.equals(path) && rendered(), profile + " rendering");
            if (profile.startsWith("1c")) {
                fx(() -> {
                    var tree = tree();
                    var children = new ArrayList<TreeItem<Object>>();
                    for (var row : rows(tree.getRoot())) {
                        if (rowValue(row, "asset") instanceof PdfNavigation.Asset asset && asset.isCollectionPdf()) {
                            children.add(row);
                        }
                    }
                    check(children.size() == 2, profile + " collection children");
                    select(tree, children.getFirst());
                    return null;
                });
                await(() -> current() != null && current().parent != null && rendered()
                        && !current().mail.messages().isEmpty(), profile + " child opening");
                fx(() -> {
                    ((Button) field("parentButton")).fire();
                    return null;
                });
                await(() -> current() != null && current().path.equals(path) && rendered(), profile + " parent return");
                if (profile.equals("1c")) checkSearchResultOpening();
            } else {
                String subject = input.getAsJsonArray("subjects").get(profile.startsWith("1m") ? 1 : 0).getAsString();
                int page = fx(() -> {
                    var tree = tree();
                    for (var row : rows(tree.getRoot())) {
                        if (rowValue(row, "message") instanceof MailNavigation.Message mail && mail.title().contains(subject)) {
                            select(tree, row);
                            return (int) rowValue(row, "page");
                        }
                    }
                    throw new AssertionError(profile + " missing message " + subject);
                });
                await(() -> field("message") instanceof MailNavigation.Message mail && mail.title().contains(subject)
                        && page >= 0 && ((ContinuousReader) field("viewport")).getPage() == page && rendered(),
                        profile + " message navigation");
            }
            if (profile.equals("1s")) {
                Path target = manifest.resolveSibling("desktop-" + attachment);
                fx(() -> {
                    var session = current();
                    var asset = session.assets.stream().filter(file -> file.name().equals(attachment)).findFirst().orElseThrow();
                    session.pdf.save(asset.variants().getFirst(), target);
                    return null;
                });
                check(Arrays.equals(Files.readAllBytes(target), Files.readAllBytes(manifest.resolveSibling(attachment))),
                        "Desktop attachment export bytes differ");
                var session = fx(this::current);
                var xmp = manifest.resolveSibling("desktop-original.xmp");
                fx(() -> {
                    ViewerTools.exportXmp(session, xmp);
                    return null;
                });
                var catalog = ViewerXmp.collect(session).packets().stream()
                        .filter(packet -> "Catalog".equals(packet.locator())).findFirst().orElseThrow();
                check(Arrays.equals(Files.readAllBytes(xmp), catalog.bytes()), "XMP export and browse bytes differ");
            }
        }
        System.out.println("PASS: desktop opened all six validated PDFs, navigated messages and children, and exported exact bytes.");
    }

    private static void checkOrientationAndAssociatedFiles() throws IOException {
        var pdf = Files.createTempFile("viewer-associations-", ".pdf");
        var af = COSName.getPDFName("AF");
        try {
            try (var document = new PDDocument()) {
                document.addPage(new PDPage(new PDRectangle(300, 600)));
                document.addPage(new PDPage(new PDRectangle(600, 300)));
                var catalog = document.getDocumentCatalog().getCOSObject();
                var rootFile = new PDComplexFileSpecification();
                rootFile.setFile("catalog.bin");
                var catalogFiles = new COSArray();
                catalogFiles.add(rootFile.getCOSObject());
                catalog.setItem(af, catalogFiles);

                var directFile = new PDComplexFileSpecification();
                directFile.setFile("direct.bin");
                directFile.getCOSObject().setDirect(true);
                var associated = new COSArray();
                associated.add(directFile.getCOSObject());
                associated.add(rootFile.getCOSObject());
                var other = new COSDictionary();
                other.setItem(af, associated);
                catalog.setItem(COSName.getPDFName("AssociatedFileTest"), other);
                document.save(pdf.toFile());
            }

            var renderer = new ViewerRenderingDocument(pdf.toFile());
            try {
                check(renderer.getNumberOfPages() == 2 && !renderer.isLandscape(0) && renderer.isLandscape(1),
                        "Page-tree traversal preserves page order and orientation");
            } finally {
                renderer.close();
            }
            try (var input = new RandomAccessReadBufferedFile(pdf.toFile());
                 var navigation = PdfNavigation.open(pdf)) {
                var result = PdfAssociatedFiles.read(input, navigation.document(), 1_000);
                check(!result.incomplete() && result.files().size() == 3, "Inventory includes direct and indirect associations");
                var root = navigation.document().getDocumentCatalog().getCOSObject().getCOSArray(af).getObject(0);
                check(result.files().stream().anyMatch(file -> file.catalog() && file.specification() == root),
                        "Catalog association resolves in the active PDF document");
                check(result.files().stream().anyMatch(file -> !file.catalog() && file.specification() == root),
                        "The same file can also be associated elsewhere");
                check(result.files().stream().anyMatch(file -> !file.catalog()
                                && "direct.bin".equals(file.specification().getString(COSName.F))),
                        "Direct file specifications outside the name tree are preserved");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    private static void checkMetadataDecoding() throws IOException {
        byte[] xml = "<description>Reader metadata</description>".getBytes(StandardCharsets.UTF_8);
        try (var pdf = new PDDocument()) {
            var filters = new COSArray();
            filters.add(COSName.ASCII_HEX_DECODE);
            filters.add(COSName.FLATE_DECODE);
            var metadata = new PDMetadata(pdf).getCOSObject();
            try (var output = metadata.createOutputStream(filters)) {
                output.write(xml);
            }
            // The limit applies to every stage, including the intermediate encoding.
            try (var decoded = PdfMetadataStreams.open(metadata, 256)) {
                check(Arrays.equals(decoded.readAllBytes(), xml), "Decoding changed metadata bytes");
            }
            try (var decoded = PdfMetadataStreams.open(metadata, xml.length - 1)) {
                throw new AssertionError("Metadata exceeded the test's decoded-byte limit");
            } catch (PdfMetadataStreams.Limit expected) {
                // The bounded decoder rejects the stream before returning an input stream.
            }
            var repeated = new PDMetadata(pdf).getCOSObject();
            var repeatedFilters = new COSArray();
            repeatedFilters.add(COSName.FLATE_DECODE);
            repeatedFilters.add(COSName.FLATE_DECODE);
            try (var output = repeated.createOutputStream(repeatedFilters)) {
                output.write(xml);
            }
            try (var decoded = PdfMetadataStreams.open(repeated, 256)) {
                check(Arrays.equals(decoded.readAllBytes(), xml), "Repeated filters changed metadata bytes");
            }
            var attachment = new PDEmbeddedFile(pdf);
            try (var output = attachment.createOutputStream(COSName.FLATE_DECODE)) {
                output.write(xml);
            }
            try (var decoded = PdfMetadataStreams.openAsset(attachment.getCOSObject(), 256)) {
                check(Arrays.equals(decoded.readAllBytes(), xml), "Decoding changed embedded-file bytes");
            }
            try (var ignored = PdfMetadataStreams.open(metadata, 256, () -> true)) {
                throw new AssertionError("Canceled metadata decoding started");
            } catch (InterruptedIOException expected) {
                // The search cancellation guard is honored before an empty or filtered stream can be staged.
            }
        }
    }

    private void checkSearchResultOpening() throws Exception {
        var openHit = ViewerApplication.class.getDeclaredMethod("openArchiveHit", ViewerSession.class,
                String.class, ViewerSearch.Hit.class);
        openHit.setAccessible(true);
        var root = fx(this::current);
        var member = root.assets.stream().filter(PdfNavigation.Asset::isCollectionPdf).findFirst().orElseThrow();
        var hit = new ViewerSearch.Hit(List.of(member.nameTreeKeys().getFirst()), 0, "");
        fx(() -> {
            openHit.invoke(viewer, root, "", hit);
            check(field("opening") != null, "Search result did not start a tracked open");
            ((Button) field("close")).fire();
            return null;
        });
        await(() -> field("opening") == null && !Boolean.TRUE.equals(field("busy")), "canceled search result opening");
        check(fx(this::current) == root, "Canceled search result replaced the collection");
        fx(() -> openHit.invoke(viewer, root, "", hit));
        await(() -> current() != null && current().parent == root && current().parentAsset == member && rendered(),
                "search result opening");
    }

    private void checkLargeArchiveOpen(Method open) throws Exception {
        Path path = Path.of("examples/mbox/mbox_enron_1m.pdf").toAbsolutePath();
        check(Files.isRegularFile(path), "Missing large archive fixture " + path);
        long started = System.nanoTime();
        fx(() -> open.invoke(viewer, path));
        await(() -> showing(path) && rendered() && archiveMessagePaneFilled(),
                "Enron archive first page and populated message pane");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        fx(() -> {
            var viewport = (ContinuousReader) field("viewport");
            var session = current();
            check(viewport.getPage() == 0 && viewport.getPageCount() == 1839, "Unexpected Enron archive pages");
            check(session.mail.messages().size() == 1433, "Unexpected Enron archive message count");
            check(session.assets.size() == 1433, "Unexpected Enron archive file count");
            return null;
        });
        System.out.println("PASS: desktop opened and rendered the Enron archive in " + elapsedMillis + " ms.");
    }

    private boolean archiveMessagePaneFilled() throws ReflectiveOperationException {
        var tree = tree();
        if (tree.getExpandedItemCount() != 1434 || !(tree.lookup(".virtual-flow") instanceof VirtualFlow<?> flow)) {
            return false;
        }
        var first = flow.getFirstVisibleCell();
        var last = flow.getLastVisibleCell();
        if (first == null || first.getIndex() != 0 || Math.abs(first.getLayoutY()) > 1
                || last == null || last.getIndex() <= first.getIndex()) {
            return false;
        }
        double rowHeight = last.getLayoutBounds().getHeight();
        double gap = flow.getViewportLength() - last.getLayoutY() - rowHeight;
        return rowHeight > 0 && gap <= rowHeight;
    }

    private void checkImageWindowLifecycle(Method open, Path other) throws Exception {
        var preview = ViewerApplication.class.getDeclaredMethod("openVariant", PdfNavigation.Asset.class,
                PdfNavigation.AssetStream.class);
        preview.setAccessible(true);
        Path scratch = Files.createTempDirectory("eapdf-desktop-preview-window-");
        Path fixture = scratch.resolve("image-attachment.pdf");
        String name = "preview.png";
        try {
            ReleaseReaderImageSmoke.writeImageFixture(fixture, name,
                    ReleaseReaderImageSmoke.encodeImage(ReleaseReaderImageSmoke.solidImage(960, 720, Color.RED), "png"));
            fx(() -> open.invoke(viewer, fixture));
            await(() -> showing(fixture), "image attachment fixture");
            fx(() -> {
                var asset = current().assets.stream().filter(file -> name.equals(file.name())).findFirst().orElseThrow();
                var stream = asset.variants().getFirst();
                preview.invoke(viewer, asset, stream);
                preview.invoke(viewer, asset, stream);
                check(field("pendingAttachmentPreview") != null, "Second image preview did not queue");
                open.invoke(viewer, other);
                check(field("pendingAttachmentPreview") == null, "Document switch retained an old queued preview");
                return null;
            });
            await(() -> showing(other) && Boolean.FALSE.equals(field("loadingAttachmentPreview")),
                    "document switch and previous preview completion");
            check(fx(() -> field("imagePreviewWindow")) == null, "Old document opened a stale image preview");

            fx(() -> open.invoke(viewer, fixture));
            await(() -> showing(fixture), "return to image attachment fixture");
            fx(() -> {
                var asset = current().assets.stream().filter(file -> name.equals(file.name())).findFirst().orElseThrow();
                return preview.invoke(viewer, asset, asset.variants().getFirst());
            });
            await(() -> field("imagePreviewWindow") instanceof Stage popup && popup.isShowing(), "image preview window");
            fx(() -> {
                var popup = (Stage) field("imagePreviewWindow");
                check(Window.getWindows().stream().filter(window -> window instanceof Stage stage && stage.isShowing()
                        && name.equals(stage.getTitle())).count() == 1, "Expected one visible image preview window");
                check(popup.getScene().getRoot().lookupAll(".image-view").stream().anyMatch(node ->
                        node instanceof ImageView view && view.getImage() != null
                        && view.getImage().getWidth() <= 760 && view.getImage().getHeight() <= 560),
                        "Image preview window did not display a bounded image");
                popup.close();
                return open.invoke(viewer, other);
            });
            await(() -> showing(other), "close image attachment fixture");
        } finally {
            Files.deleteIfExists(fixture);
            Files.deleteIfExists(scratch);
        }
        System.out.println("PASS: desktop discarded queued previews on document switch and opened one bounded image window.");
    }

    private boolean showing(Path path) throws ReflectiveOperationException {
        return current() != null && current().path.equals(path) && !Boolean.TRUE.equals(field("busy"));
    }

    private static void runAttachmentImageChecks() throws IOException, InterruptedException {
        var java = Path.of(System.getProperty("java.home"), "bin", "java");
        var process = new ProcessBuilder(java.toString(), "-Xmx128m", "-Djava.awt.headless=true",
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", System.getProperty("java.class.path"),
                ReleaseReaderImageSmoke.class.getName()).inheritIO().start();
        try {
            check(process.waitFor(75, TimeUnit.SECONDS), "Desktop image attachment checks timed out");
            check(process.exitValue() == 0, "Desktop image attachment checks failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> check, String description) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < limit) {
            if (fx(check)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private boolean rendered() throws ReflectiveOperationException {
        var viewport = (ContinuousReader) field("viewport");
        return !Boolean.TRUE.equals(field("busy")) && viewport.getPageCount() > 0
                && viewport.lookupAll(".image-view").stream()
                        .anyMatch(node -> node instanceof ImageView image && image.getImage() != null);
    }

    private ViewerSession current() throws ReflectiveOperationException {
        return (ViewerSession) field("current");
    }

    private Object field(String name) throws ReflectiveOperationException {
        var field = ViewerApplication.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(viewer);
    }

    @SuppressWarnings("unchecked")
    private TreeView<Object> tree() throws ReflectiveOperationException {
        return (TreeView<Object>) field("tree");
    }

    private static List<TreeItem<Object>> rows(TreeItem<Object> root) {
        var result = new ArrayList<TreeItem<Object>>();
        if (root == null) return result;
        if (root.getValue() != null) result.add(root);
        for (var child : root.getChildren()) result.addAll(rows(child));
        return result;
    }

    private static Object rowValue(TreeItem<Object> item, String name) throws ReflectiveOperationException {
        var method = item.getValue().getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(item.getValue());
    }

    private static void select(TreeView<Object> tree, TreeItem<Object> row) {
        for (var parent = row.getParent(); parent != null; parent = parent.getParent()) parent.setExpanded(true);
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(row);
    }
}

final class ReleaseReaderSmokeLauncher {
    public static void main(String[] args) {
        ReleaseReaderSmoke.run(args);
    }
}

/** Exercises the packaged image decoder and attachment save path with a bounded heap. */
final class ReleaseReaderImageSmoke {
    public static void main(String[] args) throws IOException {
        check(Runtime.getRuntime().maxMemory() <= 128L * 1024 * 1024, "Attachment smoke exceeded its heap cap");
        var source = solidImage(960, 720, Color.RED);
        for (String format : List.of("png", "jpeg", "gif")) {
            byte[] input = encodeImage(source, format);
            for (int attempt = 0; attempt < 24; attempt++) {
                var preview = AttachmentImagePreview.decode(input);
                check(preview.getWidth() <= 760 && preview.getHeight() <= 560,
                        format + " preview exceeded its display size");
                check(preview.getWidth() < source.getWidth() && preview.getHeight() < source.getHeight(),
                        format + " preview did not downsample");
                var pixel = new Color(preview.getRGB(preview.getWidth() / 2, preview.getHeight() / 2));
                check(pixel.getRed() > 240 && pixel.getGreen() < 15 && pixel.getBlue() < 15,
                        format + " preview did not retain the source image");
            }
        }
        var firstFrame = AttachmentImagePreview.decode(animatedGif());
        check(firstFrame.getRGB(0, 0) == Color.RED.getRGB(), "GIF preview did not use the first frame");
        checkImageRejected(new byte[]{1, 2, 3}, "malformed image");
        checkRejectedImageExport(encodeImage(solidImage(8193, 1, Color.BLACK), "png"));
        System.out.println("PASS: desktop repeated image previews under 128 MiB and preserved exact bytes on save after rejection.");
    }

    private static void checkRejectedImageExport(byte[] original) throws IOException {
        Path scratch = Files.createTempDirectory("eapdf-desktop-image-smoke-");
        Path fixture = scratch.resolve("attached-image.pdf");
        Path exported = scratch.resolve("exported.png");
        String name = "attached.png";
        try {
            writeImageFixture(fixture, name, original);
            try (var navigation = PdfNavigation.open(fixture)) {
                var attachment = navigation.assets().stream().filter(asset -> name.equals(asset.name()))
                        .findFirst().orElseThrow();
                var variant = attachment.variants().getFirst();
                checkImageRejected(navigation.preview(variant), "over-width image");
                navigation.save(variant, exported);
            }
            check(Arrays.equals(original, Files.readAllBytes(exported)), "Saved image differed after preview rejection");
        } finally {
            Files.deleteIfExists(exported);
            Files.deleteIfExists(fixture);
            Files.deleteIfExists(scratch);
        }
    }

    static void writeImageFixture(Path fixture, String name, byte[] original) throws IOException {
        try (var document = new PDDocument(); var bytes = new ByteArrayInputStream(original)) {
            document.addPage(new PDPage());
            var embedded = new PDEmbeddedFile(document, bytes);
            embedded.setSubtype("image/png");
            embedded.setSize(original.length);
            var attachment = new PDComplexFileSpecification();
            attachment.setFile(name);
            attachment.setEmbeddedFile(embedded);
            var tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of(name, attachment));
            var catalog = document.getDocumentCatalog();
            var names = new PDDocumentNameDictionary(catalog);
            names.setEmbeddedFiles(tree);
            catalog.setNames(names);
            document.save(fixture.toFile());
        }
    }

    static BufferedImage solidImage(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    static byte[] encodeImage(BufferedImage image, String format) throws IOException {
        var bytes = new ByteArrayOutputStream();
        check(ImageIO.write(image, format, bytes), "Missing smoke fixture writer for " + format);
        return bytes.toByteArray();
    }

    private static byte[] animatedGif() throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (Color color : List.of(Color.RED, Color.BLUE)) {
                writer.writeToSequence(new IIOImage(solidImage(48, 32, color), null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void checkImageRejected(byte[] bytes, String description) {
        try {
            AttachmentImagePreview.decode(bytes);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Desktop preview accepted " + description);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
