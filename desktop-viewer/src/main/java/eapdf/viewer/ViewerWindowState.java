package eapdf.viewer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Stores desktop layout settings. */
final class ViewerWindowState {
    private final Path path;
    private final Properties values = new Properties();
    private double x, y, width, height;

    ViewerWindowState() {
        path = statePath();
        if (path != null && Files.isRegularFile(path)) {
            try (var input = Files.newInputStream(path)) {
                values.load(input);
            } catch (IOException | IllegalArgumentException error) {
                values.clear();
            }
        }
        x = number("x", Double.NaN);
        y = number("y", Double.NaN);
        width = number("width", 1440);
        height = number("height", 920);
    }

    void restore(Stage stage) {
        var screens = Double.isFinite(x) && Double.isFinite(y)
                ? Screen.getScreensForRectangle(x, y, 100, 70) : java.util.List.<Screen>of();
        var screen = screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();
        var bounds = screen.getVisualBounds();
        stage.setWidth(Math.min(Math.max(stage.getMinWidth(), width), bounds.getWidth()));
        stage.setHeight(Math.min(Math.max(stage.getMinHeight(), height), bounds.getHeight()));
        if (!screens.isEmpty()) {
            stage.setX(Math.clamp(x, bounds.getMinX(), bounds.getMaxX() - 100));
            stage.setY(Math.clamp(y, bounds.getMinY(), bounds.getMaxY() - 70));
        }
        stage.setMaximized(flag("maximized", false));
    }

    void track(Stage stage) {
        stage.xProperty().addListener((obs, old, value) -> capture(stage));
        stage.yProperty().addListener((obs, old, value) -> capture(stage));
        stage.widthProperty().addListener((obs, old, value) -> capture(stage));
        stage.heightProperty().addListener((obs, old, value) -> capture(stage));
    }

    private void capture(Stage stage) {
        if (!stage.isShowing() || stage.isMaximized() || stage.isFullScreen() || stage.isIconified()) return;
        if (Double.isFinite(stage.getX()) && Double.isFinite(stage.getY())
                && stage.getWidth() >= stage.getMinWidth() && stage.getHeight() >= stage.getMinHeight()) {
            x = stage.getX();
            y = stage.getY();
            width = stage.getWidth();
            height = stage.getHeight();
        }
    }

    double divider(String name, double fallback) {
        return Math.clamp(number(name, fallback), .05, .95);
    }

    boolean flag(String name, boolean fallback) {
        return Boolean.parseBoolean(values.getProperty(name, Boolean.toString(fallback)));
    }

    String zoom() {
        return values.getProperty("zoom", "Fit page");
    }

    void save(Stage stage, double messages, double files, boolean showMessages, boolean showFiles,
              boolean filesVisibilityChosen, String zoom) {
        if (path == null) return;
        capture(stage);
        put("x", x);
        put("y", y);
        put("width", width);
        put("height", height);
        put("maximized", stage.isMaximized());
        put("messagesDivider", messages);
        put("filesDivider", files);
        put("showMessages", showMessages);
        put("showFiles", showFiles);
        put("filesVisibilityChosen", filesVisibilityChosen);
        put("zoom", zoom);
        Path pending = null;
        try {
            Files.createDirectories(path.getParent());
            pending = Files.createTempFile(path.getParent(), ".viewer-window-", ".properties");
            try (var output = Files.newOutputStream(pending)) {
                values.store(output, "EA-PDF desktop window");
            }
            try {
                Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.getLogger(ViewerWindowState.class.getName()).log(System.Logger.Level.DEBUG, "Saving window settings", error);
        } finally {
            if (pending != null) ViewerSession.deleteTemporaryFile(pending);
        }
    }

    private void put(String name, Object value) {
        values.setProperty(name, String.valueOf(value));
    }

    private double number(String name, double fallback) {
        try {
            double value = Double.parseDouble(values.getProperty(name, Double.toString(fallback)));
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static Path statePath() {
        String configured = System.getProperty("eapdf.viewer.settings");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        String configHome = System.getenv("XDG_CONFIG_HOME");
        Path configDirectory = configHome == null || configHome.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config") : Path.of(configHome);
        return configDirectory.resolve("eapdf-desktop-viewer/window.properties");
    }
}
