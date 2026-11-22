package eapdf.viewer;

import com.dlsc.pdfviewfx.PDFView;
import com.dlsc.pdfviewfx.Selection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Continuous layout with document-length scrollbars and a bounded set of page surfaces. */
final class ContinuousReader extends Region implements AutoCloseable {
    private static final double VERTICAL_INSET = 8;
    private static final double HORIZONTAL_INSET = 24;
    private static final double PAGE_GAP = 24;
    private static final double PDF_TO_VIEW = 96.0 / 72.0;
    private static final int MAX_SLOTS = 24;
    private static final int MAX_PAGE_PIXELS = 16_000_000;
    private static final int MAX_CACHED_PIXELS = 32_000_000;

    record Position(int page, double offsetWithinPage, double horizontalFraction) {}

    private record PageSize(double width, double height) {}

    private final IntConsumer pageChanged;
    private final BiConsumer<Integer, Point2D> pageClicked;
    private final Consumer<String> onError;
    private final Pane viewport = new Pane();
    private final Rectangle clip = new Rectangle();
    private final ScrollBar vertical = new ScrollBar();
    private final ScrollBar horizontal = new ScrollBar();
    private final Map<Integer, PageSlot> slots = new HashMap<>();
    private final Map<Integer, Image> images = new LinkedHashMap<>(8, .75f, true);
    private final Map<Integer, List<Rectangle2D>> searchMarkers = new HashMap<>();
    private final ChangeListener<Number> outputScaleChanged = (obs, old, value) -> requestLayout();
    private final ChangeListener<Window> windowChanged = (obs, old, value) -> watchWindow(old, value);

    private PDFView.Document document;
    private List<PageSize> sizes = List.of();
    private double[] offsets = {};
    private int page;
    private int fitReference;
    private int cachedPixels;
    private double scale = 1;
    private double density = 1;
    private double totalWidth;
    private double maxNaturalWidth;
    private double viewportWidth;
    private double viewportHeight;
    private double previousHeight;
    private String zoom = "Fit page";
    private Position pendingPosition;
    private PDFView.SearchResult selectedHit;
    private Selection selection;
    private long generation;
    private long selectionRequest;
    private boolean layingOut;
    private boolean rendering;

    ContinuousReader(IntConsumer pageChanged, BiConsumer<Integer, Point2D> pageClicked, Consumer<String> onError) {
        this.pageChanged = pageChanged;
        this.pageClicked = pageClicked;
        this.onError = onError;

        getStyleClass().add("continuous-reader");
        setAccessibleRole(AccessibleRole.SCROLL_PANE);
        setAccessibleText("PDF pages");
        setFocusTraversable(true);

        viewport.setClip(clip);
        viewport.setOnMousePressed(event -> requestFocus());
        viewport.addEventHandler(ScrollEvent.SCROLL, this::scroll);
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.setUnitIncrement(60);
        vertical.setAccessibleText("PDF vertical scroll");
        horizontal.setOrientation(Orientation.HORIZONTAL);
        horizontal.setUnitIncrement(60);
        horizontal.setAccessibleText("PDF horizontal scroll");
        vertical.valueProperty().addListener((obs, before, after) -> refresh());
        horizontal.valueProperty().addListener((obs, before, after) -> refresh());
        getChildren().addAll(viewport, vertical, horizontal);

        setOnKeyPressed(this::readKeys);
        sceneProperty().addListener((obs, old, value) -> watchScene(old, value));
    }

    private void readKeys(KeyEvent event) {
        if (event.isShortcutDown() && event.getCode() == KeyCode.C && copySelection()) {
            event.consume();
            return;
        }
        if (event.isControlDown() || event.isMetaDown() || event.isAltDown()) return;
        switch (event.getCode()) {
            case UP -> scrollBy(-60);
            case DOWN -> scrollBy(60);
            case PAGE_UP -> scrollBy(-viewportHeight * .9);
            case PAGE_DOWN -> scrollBy(viewportHeight * .9);
            case HOME -> vertical.setValue(0);
            case END -> vertical.setValue(vertical.getMax());
            case LEFT -> move(horizontal, -60);
            case RIGHT -> move(horizontal, 60);
            default -> { return; }
        }
        event.consume();
    }

    private void watchScene(Scene before, Scene after) {
        if (before != null) before.windowProperty().removeListener(windowChanged);
        if (after != null) after.windowProperty().addListener(windowChanged);
        watchWindow(before == null ? null : before.getWindow(), after == null ? null : after.getWindow());
    }

    private void watchWindow(Window before, Window after) {
        if (before != null) before.outputScaleXProperty().removeListener(outputScaleChanged);
        if (after != null) after.outputScaleXProperty().addListener(outputScaleChanged);
        requestLayout();
    }

    void setDocument(PDDocument geometry, PDFView.Document render, int initialPage) {
        clear();
        var pageSizes = new ArrayList<PageSize>();
        for (var pdfPage : geometry.getPages()) {
            var bounds = pdfPage.getCropBox();
            double width = usableDimension(bounds.getWidth());
            double height = usableDimension(bounds.getHeight());
            boolean turned = Math.floorMod(pdfPage.getRotation(), 180) != 0;
            pageSizes.add(new PageSize(turned ? height : width, turned ? width : height));
        }
        sizes = List.copyOf(pageSizes);
        maxNaturalWidth = sizes.stream().mapToDouble(PageSize::width).max().orElse(0);
        document = render;
        if (!sizes.isEmpty()) {
            page = Math.clamp(initialPage, 0, sizes.size() - 1);
            fitReference = page;
            pendingPosition = new Position(page, 0, 0);
        }
        requestLayout();
    }

    private static double usableDimension(double value) {
        return Double.isFinite(value) && value > 0 ? value : 1;
    }

    void setZoom(String value) {
        if (value == null || value.equals(zoom)) return;
        if (pendingPosition == null && offsets.length != 0) pendingPosition = position();
        zoom = value;
        fitReference = page;
        requestLayout();
    }

    int getPage() {
        return page;
    }

    int getPageCount() {
        return sizes.size();
    }

    void goToPage(int index) {
        if (sizes.isEmpty()) return;
        int target = Math.clamp(index, 0, sizes.size() - 1);
        if (zoom.startsWith("Fit") && !sizes.get(fitReference).equals(sizes.get(target))) {
            fitReference = target;
            requestLayout();
        }
        restore(new Position(target, 0, horizontalFraction()));
    }

    Position position() {
        if (pendingPosition != null) return pendingPosition;
        if (offsets.length == 0) return new Position(page, 0, 0);
        int first = pageAt(vertical.getValue());
        double fraction = (vertical.getValue() + VERTICAL_INSET - offsets[first]) / pageHeight(first);
        return new Position(first, fraction, horizontalFraction());
    }

    void restore(Position location) {
        if (location == null || sizes.isEmpty()) return;
        if (offsets.length == 0 || isNeedsLayout()) {
            pendingPosition = location;
            requestLayout();
        } else {
            setPosition(location);
            refresh();
        }
    }

    private void setPosition(Position location) {
        int index = Math.clamp(location.page(), 0, sizes.size() - 1);
        double position = offsets[index] - VERTICAL_INSET + location.offsetWithinPage() * pageHeight(index);
        vertical.setValue(Math.clamp(position, 0, vertical.getMax()));
        horizontal.setValue(Math.clamp(location.horizontalFraction(), 0, 1) * horizontal.getMax());
    }

    private double horizontalFraction() {
        return horizontal.getMax() == 0 ? .5 : horizontal.getValue() / horizontal.getMax();
    }

    boolean scrollBy(double pixels) {
        return move(vertical, pixels);
    }

    double viewportHeight() {
        return viewportHeight;
    }

    boolean atStart() {
        return vertical.getValue() <= 2;
    }

    boolean atEnd() {
        return vertical.getValue() >= vertical.getMax() - 2;
    }

    private static boolean move(ScrollBar bar, double pixels) {
        double before = bar.getValue();
        double after = Math.clamp(before + pixels, 0, bar.getMax());
        if (before == after) return false;
        bar.setValue(after);
        return true;
    }

    private void scroll(ScrollEvent event) {
        if (event.isControlDown() || event.isShortcutDown()) return;
        double dx = event.getDeltaX();
        double dy = event.getDeltaY();
        if (event.isShiftDown() && dx == 0) dx = dy;
        if (event.isShiftDown() || Math.abs(dx) > Math.abs(dy)) move(horizontal, -dx);
        else scrollBy(-dy);
        event.consume();
    }

    void setSearchResults(List<PDFView.SearchResult> results, PDFView.SearchResult hit) {
        searchMarkers.clear();
        selectedHit = hit;
        for (var result : results) {
            searchMarkers.computeIfAbsent(result.getPageNumber(), ignored -> new ArrayList<>()).add(result.getMarker());
        }
        for (var slot : slots.values()) slot.updateOverlays();
    }

    void revealSearchResult(PDFView.SearchResult result) {
        if (result == null || sizes.isEmpty()) return;
        int index = Math.clamp(result.getPageNumber(), 0, sizes.size() - 1);
        if (offsets.length == 0) {
            goToPage(index);
        } else {
            double top = offsets[index] + result.getMarker().getMinY() * scale - viewportHeight / 3;
            vertical.setValue(Math.clamp(top, 0, vertical.getMax()));
            double overflow = sizes.get(index).width() * scale + 2 * HORIZONTAL_INSET - viewportWidth;
            if (overflow > 0) {
                double center = HORIZONTAL_INSET + (result.getMarker().getMinX() + result.getMarker().getWidth() / 2) * scale;
                double fraction = Math.clamp((center - viewportWidth / 2) / overflow, 0, 1);
                horizontal.setValue(fraction * horizontal.getMax());
            }
            refresh();
        }
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        if (width <= 0 || height <= 0) return;

        var position = position();
        layingOut = true;
        try {
            double verticalWidth = sizes.isEmpty() ? 0 : Math.min(width, vertical.prefWidth(-1));
            double nextWidth = Math.max(1, width - verticalWidth);
            if (pendingPosition == null && (viewportWidth != nextWidth || previousHeight != height)) {
                fitReference = page;
            }
            viewportWidth = nextWidth;
            previousHeight = height;
            double nextScale = fitScale(viewportWidth, height);
            totalWidth = maxNaturalWidth * nextScale + 2 * HORIZONTAL_INSET;
            double horizontalHeight = totalWidth > viewportWidth + 1 ? horizontal.prefHeight(-1) : 0;
            viewportHeight = Math.max(1, height - horizontalHeight);

            double nextDensity = outputScale();
            boolean scaleChanged = nextScale != scale;
            if (scaleChanged || density != nextDensity) {
                scale = nextScale;
                density = nextDensity;
                clearImages();
            }
            if (scaleChanged || offsets.length == 0) computeOffsets();
            clip.setWidth(viewportWidth);
            clip.setHeight(viewportHeight);
            viewport.resizeRelocate(0, 0, viewportWidth, viewportHeight);
            vertical.resizeRelocate(viewportWidth, 0, verticalWidth, viewportHeight);
            horizontal.resizeRelocate(0, viewportHeight, viewportWidth, horizontalHeight);
            vertical.setVisible(verticalWidth > 0);
            horizontal.setVisible(horizontalHeight > 0);

            double totalHeight = offsets.length == 0 ? 0 : offsets[offsets.length - 1];
            configure(vertical, totalHeight, viewportHeight);
            configure(horizontal, totalWidth, viewportWidth);
            if (!sizes.isEmpty()) setPosition(position);
            pendingPosition = null;
        } finally {
            layingOut = false;
        }
        refresh();
    }

    private double outputScale() {
        var scene = getScene();
        var window = scene == null ? null : scene.getWindow();
        return window == null ? 1 : Math.clamp(window.getOutputScaleX(), 1.0, 3.0);
    }

    private static void configure(ScrollBar bar, double total, double display) {
        bar.setMax(Math.max(0, total - display));
        bar.setVisibleAmount(display);
        bar.setBlockIncrement(Math.max(1, display * .9));
        bar.setDisable(bar.getMax() == 0);
    }

    private double fitScale(double width, double height) {
        if (sizes.isEmpty()) return 1;
        if (zoom.endsWith("%")) {
            return Math.clamp(Double.parseDouble(zoom.substring(0, zoom.length() - 1)) / 100, .25, 4) * PDF_TO_VIEW;
        }
        var reference = sizes.get(fitReference);
        double fitWidth = Math.max(1, width - 2 * HORIZONTAL_INSET) / reference.width();
        double fitHeight = Math.max(1, height - 2 * VERTICAL_INSET) / reference.height();
        return "Fit width".equals(zoom) ? fitWidth : Math.min(fitWidth, fitHeight);
    }

    private void computeOffsets() {
        if (sizes.isEmpty()) {
            offsets = new double[0];
            return;
        }
        offsets = new double[sizes.size() + 1];
        offsets[0] = VERTICAL_INSET;
        for (int i = 0; i < sizes.size(); i++) {
            double spacing = i == sizes.size() - 1 ? VERTICAL_INSET : PAGE_GAP;
            offsets[i + 1] = offsets[i] + pageHeight(i) + spacing;
        }
    }

    private double pageHeight(int index) {
        return sizes.get(index).height() * scale;
    }

    private int pageAt(double position) {
        int found = Arrays.binarySearch(offsets, position);
        int index = found >= 0 ? found : -found - 2;
        return Math.clamp(index, 0, sizes.size() - 1);
    }

    private void refresh() {
        if (layingOut || document == null || offsets.length == 0) return;
        double top = vertical.getValue();
        double bottom = top + viewportHeight;
        int first = pageAt(top);
        int last = Math.min(pageAt(bottom), first + MAX_SLOTS - 5);
        int mountFirst = Math.max(first - 2, 0);
        int mountLast = Math.min(last + 2, sizes.size() - 1);
        int active = first;
        double best = 0;

        for (int index = first; index <= last; index++) {
            double visible = Math.min(bottom, offsets[index] + pageHeight(index)) - Math.max(top, offsets[index]);
            if (visible > best) {
                best = visible;
                active = index;
            }
        }

        var existing = slots.entrySet().iterator();
        while (existing.hasNext()) {
            var entry = existing.next();
            if (entry.getKey() < mountFirst || entry.getKey() > mountLast) {
                viewport.getChildren().remove(entry.getValue());
                existing.remove();
            }
        }

        for (int i = mountFirst; i <= mountLast; i++) {
            var slot = slots.computeIfAbsent(i, index -> {
                var created = new PageSlot(index);
                viewport.getChildren().add(created);
                return created;
            });
            double pageWidth = Math.max(1 / density, snapPixel(sizes.get(i).width() * scale));
            double pageHeight = Math.max(1 / density, snapPixel(pageHeight(i)));
            double overflow = Math.max(0, pageWidth + 2 * HORIZONTAL_INSET - viewportWidth);
            double x = overflow == 0 ? (viewportWidth - pageWidth) / 2
                    : HORIZONTAL_INSET - horizontalFraction() * overflow;
            double y = snapPixel(offsets[i] - top);
            slot.resizeRelocate(snapPixel(x), y, pageWidth, pageHeight);
            var image = images.get(i);
            if (slot.picture.getImage() != image) slot.setImage(image);
        }
        if (active != page) {
            page = active;
            pageChanged.accept(page);
        }
        renderNext(first, last);
    }

    private double snapPixel(double value) {
        return Math.rint(value * density) / density;
    }

    private void renderNext(int first, int last) {
        if (rendering || document == null) return;
        for (int offset = 0; offset <= MAX_SLOTS; offset++) {
            if (tryRender(page + offset, first, last)) return;
            if (offset != 0 && tryRender(page - offset, first, last)) return;
        }
        tryRender(page + 1, first - 2, last + 2);
        tryRender(page - 1, first - 2, last + 2);
    }

    private boolean tryRender(int index, int first, int last) {
        if (rendering || index < first || index > last || images.containsKey(index)) return false;
        var slot = slots.get(index);
        if (slot == null || slot.failed) return false;
        var size = sizes.get(index);
        double rasterScale = scale * density;
        double needed = Math.min(MAX_PAGE_PIXELS, Math.ceil(size.width() * rasterScale)
                * Math.ceil(size.height() * rasterScale));
        if (cachedPixels + needed > MAX_CACHED_PIXELS) {
            int distance = Math.abs(index - page);
            double reclaimable = 0;
            for (var entry : images.entrySet()) {
                if (Math.abs(entry.getKey() - page) > distance) reclaimable += pixels(entry.getValue());
            }
            // Under an extreme viewport or magnification, keep the closest pages rather than
            // repeatedly evicting and rebuilding equally important visible images.
            if (cachedPixels + needed - reclaimable > MAX_CACHED_PIXELS) return false;
        }
        render(index);
        return true;
    }

    private void render(int index) {
        var renderer = document;
        var size = sizes.get(index);
        long ticket = generation;
        double resolution = scale * density;
        double pixelWidth = Math.max(1, Math.ceil(size.width() * resolution));
        double pixelHeight = Math.max(1, Math.ceil(size.height() * resolution));
        double limited = Math.sqrt(MAX_PAGE_PIXELS / (size.width() * size.height()));
        float rasterScale = (float) Math.min(limited,
                Math.max(pixelWidth / size.width(), pixelHeight / size.height()));

        rendering = true;
        Thread.ofVirtual().name("eapdf-page-render").start(() -> {
            Image image = null;
            String failure = null;
            try {
                var pixels = renderer.renderPage(index, rasterScale);
                if (pixels == null || pixels.getWidth() == 1 && pixels.getHeight() == 1) {
                    throw new IllegalStateException("The renderer returned no page image.");
                }
                image = SwingFXUtils.toFXImage(pixels, null);
            } catch (RuntimeException error) {
                failure = error.getMessage();
                if (failure == null || failure.isBlank()) failure = "Page could not be rendered.";
            }
            Image ready = image;
            String message = failure;
            Platform.runLater(() -> showRendered(index, ticket, ready, message));
        });
    }

    private void showRendered(int index, long ticket, Image image, String failure) {
        rendering = false;
        if (ticket == generation && slots.containsKey(index)) {
            var slot = slots.get(index);
            if (image == null) {
                slot.failed = true;
                slot.placeholder.setText("Page " + (index + 1) + " could not be rendered");
                onError.accept(failure == null ? slot.placeholder.getText() : failure);
            } else {
                int pixels = pixels(image);
                while (cachedPixels + pixels > MAX_CACHED_PIXELS && !images.isEmpty()) {
                    var evictedIndex = images.keySet().stream().max(java.util.Comparator.comparingInt(
                            cached -> Math.abs(cached - page))).orElseThrow();
                    cachedPixels -= pixels(images.remove(evictedIndex));
                    var evicted = slots.get(evictedIndex);
                    if (evicted != null) evicted.setImage(null);
                }
                cachedPixels += pixels;
                images.put(index, image);
                slot.setImage(image);
            }
        }
        refresh();
    }

    private static int pixels(Image image) {
        return (int) (image.getWidth() * image.getHeight());
    }

    private void clearImages() {
        generation++;
        images.clear();
        cachedPixels = 0;
        for (var slot : slots.values()) {
            slot.failed = false;
            slot.placeholder.setText("Page " + (slot.index + 1));
            slot.setImage(null);
            slot.updateOverlays();
        }
    }

    void clear() {
        document = null;
        sizes = List.of();
        maxNaturalWidth = 0;
        offsets = new double[0];
        page = 0;
        fitReference = 0;
        pendingPosition = null;
        clearImages();
        clearSelection();
        searchMarkers.clear();
        selectedHit = null;
        viewport.getChildren().clear();
        slots.clear();
        requestLayout();
    }

    @Override
    public void close() {
        clear();
    }

    private void clearSelection() {
        selectionRequest++;
        if (selection != null) {
            int selectedPage = selection.getPageNumber();
            selection = null;
            var slot = slots.get(selectedPage);
            if (slot != null) slot.updateOverlays();
        }
    }

    private boolean copySelection() {
        if (selection == null || selection.getSelectedText() == null || selection.getSelectedText().isEmpty()) return false;
        var copy = new ClipboardContent();
        copy.putString(selection.getSelectedText());
        Clipboard.getSystemClipboard().setContent(copy);
        return true;
    }

    private void selectText(int index, Point2D from, Point2D to, int clicks) {
        if (!(document instanceof PDFView.SelectableDocument selectable)) return;
        long ticket = generation;
        long request = ++selectionRequest;
        Thread.ofVirtual().name("eapdf-select-text").start(() -> {
            try {
                var selected = selectable.getSelection(index, from, to, Selection.Mode.forClickCount(clicks));
                Platform.runLater(() -> {
                    if (ticket != generation || request != selectionRequest || selected == null) return;
                    selection = selected;
                    var slot = slots.get(index);
                    if (slot != null) slot.updateOverlays();
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> {
                    if (ticket == generation && request == selectionRequest) {
                        onError.accept("Text selection is unavailable on this page.");
                    }
                });
            }
        });
    }

    private final class PageSlot extends Pane {
        final int index;
        final Label placeholder;
        final Region paper = new Region();
        final ImageView picture = new ImageView();
        final Pane markers = new Pane();
        boolean failed;
        Point2D pressed;

        PageSlot(int index) {
            this.index = index;
            getStyleClass().add("continuous-page");
            setManaged(false);
            setPickOnBounds(true);
            setAccessibleRole(AccessibleRole.IMAGE_VIEW);
            setAccessibleText("Page " + (index + 1) + " of " + sizes.size());

            // Shadow only the paper background so the page image can paint at its native pixels.
            paper.setBackground(Background.fill(Color.WHITE));
            paper.setEffect(new DropShadow(7, Color.rgb(29, 41, 57, .18)));
            paper.setMouseTransparent(true);
            placeholder = new Label("Page " + (index + 1));
            placeholder.getStyleClass().add("page-placeholder");
            placeholder.setMouseTransparent(true);
            picture.setSmooth(true);
            picture.setMouseTransparent(true);
            markers.setMouseTransparent(true);
            getChildren().addAll(paper, placeholder, picture, markers);
            updateOverlays();

            setOnMousePressed(event -> {
                if (event.getButton() != MouseButton.PRIMARY) return;
                setCursor(Cursor.TEXT);
                pressed = pointInPage(event.getSceneX(), event.getSceneY());
                clearSelection();
            });
            setOnMouseReleased(event -> {
                if (event.getButton() != MouseButton.PRIMARY || pressed == null) return;
                setCursor(Cursor.DEFAULT);
                var end = pointInPage(event.getSceneX(), event.getSceneY());
                if (!event.isStillSincePress() || event.getClickCount() > 1) {
                    selectText(index, pressed, end, event.getClickCount());
                    event.consume();
                }
                pressed = null;
            });
            setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()
                        || event.getClickCount() != 1 || picture.getImage() == null) return;
                Point2D position = pointInPage(event.getSceneX(), event.getSceneY());
                var size = sizes.get(index);
                pageClicked.accept(index, new Point2D(position.getX() / size.width(), position.getY() / size.height()));
            });
            setOnContextMenuRequested(event -> {
                if (selection == null || selection.getSelectedText().isBlank()) return;
                var copy = new MenuItem("Copy");
                copy.setOnAction(ignored -> copySelection());
                new ContextMenu(copy).show(this, event.getScreenX(), event.getScreenY());
                event.consume();
            });
        }

        private Point2D pointInPage(double sceneX, double sceneY) {
            var point = sceneToLocal(sceneX, sceneY);
            var size = sizes.get(index);
            double x = Math.clamp(point.getX(), 0, getWidth()) * size.width() / Math.max(1, getWidth());
            double y = Math.clamp(point.getY(), 0, getHeight()) * size.height() / Math.max(1, getHeight());
            return new Point2D(x, y);
        }

        void setImage(Image image) {
            picture.setImage(image);
            placeholder.setVisible(image == null);
        }

        void updateOverlays() {
            markers.getChildren().clear();
            var results = searchMarkers.getOrDefault(index, List.of());
            for (int i = 0; i < Math.min(results.size(), 2_000); i++) {
                var result = results.get(i);
                boolean selected = selectedHit != null && selectedHit.getPageNumber() == index
                        && result.equals(selectedHit.getMarker());
                addMarker(result, selected ? Color.rgb(245, 154, 35, .56) : Color.rgb(255, 216, 70, .48));
            }
            if (selection != null && selection.getPageNumber() == index) {
                for (var result : selection.getMarker()) addMarker(result, Color.rgb(90, 150, 230, .40));
            }
        }

        private void addMarker(Rectangle2D bounds, Color fill) {
            var mark = new Rectangle(bounds.getMinX() * scale, bounds.getMinY() * scale,
                    bounds.getWidth() * scale, bounds.getHeight() * scale);
            mark.setFill(fill);
            markers.getChildren().add(mark);
        }

        @Override
        protected void layoutChildren() {
            double width = getWidth();
            double height = getHeight();
            paper.resizeRelocate(0, 0, width, height);
            placeholder.resizeRelocate(18, 18, Math.max(1, width - 36), placeholder.prefHeight(-1));
            picture.setFitWidth(width);
            picture.setFitHeight(height);
            markers.resizeRelocate(0, 0, width, height);
        }
    }
}
