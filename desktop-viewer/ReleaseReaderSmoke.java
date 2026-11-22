package eapdf.viewer;

import com.google.gson.JsonParser;
import com.sun.javafx.application.ParametersImpl;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

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
        for (var entry : input.getAsJsonObject("documents").entrySet()) {
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
            }
        }
        System.out.println("PASS: desktop opened all six validated PDFs, navigated messages and children, and exported exact bytes.");
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
