import com.sun.javafx.application.ParametersImpl;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import eapdf.viewer.ViewerApplication;

public final class CaptureReadme {
    private static final String SUBJECT = "Seating Question";
    private static Throwable failure;

    public static void main(String[] args) {
        Application.launch(Capture.class, args);
        if (failure != null) throw new IllegalStateException("Could not capture the Desktop Viewer", failure);
    }

    public static final class Capture extends Application {
        private ViewerApplication viewer;

        @Override
        public void start(Stage stage) throws ReflectiveOperationException {
            List<String> args = getParameters().getUnnamed();
            viewer = new ViewerApplication();
            ParametersImpl.registerParameters(viewer, new ParametersImpl(List.of(args.getFirst())));
            viewer.start(stage);

            var tree = (TreeView<?>) field("tree");
            var pages = (Parent) field("viewport");
            var profile = (Label) field("profile");
            var output = Path.of(args.get(1));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

            new AnimationTimer() {
                private int pageNumber;

                @Override
                public void handle(long now) {
                    try {
                        if (System.nanoTime() >= deadline) {
                            throw new IllegalStateException("Timed out waiting for the selected 1m message to render");
                        }
                        if (pageNumber == 0) {
                            pageNumber = selectMessage(tree);
                            return;
                        }
                        if (!profile.getText().startsWith("EA-PDF 1m") || !pageRendered(pages, pageNumber)) return;

                        var snapshot = stage.getScene().snapshot(null);
                        if (!ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), "png", output.toFile())) {
                            throw new IllegalStateException("The PNG writer is unavailable");
                        }
                    } catch (Exception error) {
                        failure = error;
                    }
                    stop();
                    Platform.exit();
                }
            }.start();
        }

        @Override
        public void stop() {
            if (viewer != null) viewer.stop();
        }

        private Object field(String name) throws ReflectiveOperationException {
            Field field = ViewerApplication.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(viewer);
        }
    }

    private static int selectMessage(TreeView<?> tree) throws ReflectiveOperationException {
        for (int index = 0; index < tree.getExpandedItemCount(); index++) {
            var row = tree.getTreeItem(index).getValue();
            if (row == null || !SUBJECT.equals(rowValue(row, "title")) || rowValue(row, "message") == null) continue;

            tree.getSelectionModel().select(index);
            tree.scrollTo(index);
            tree.requestFocus();
            return (int) rowValue(row, "page") + 1;
        }
        return 0;
    }

    private static Object rowValue(Object row, String name) throws ReflectiveOperationException {
        var accessor = row.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(row);
    }

    private static boolean pageRendered(Parent viewport, int pageNumber) {
        String pageLabel = "Page " + pageNumber + " of ";
        return viewport.lookupAll(".continuous-page").stream()
                .filter(page -> page.getAccessibleText().startsWith(pageLabel))
                .flatMap(page -> page.lookupAll(".image-view").stream())
                .anyMatch(node -> node instanceof ImageView image && image.getImage() != null);
    }
}
