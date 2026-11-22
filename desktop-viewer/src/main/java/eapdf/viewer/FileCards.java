package eapdf.viewer;

import eapdf.viewer.PdfNavigation.Asset;
import eapdf.viewer.PdfNavigation.AssetStream;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

final class FileCards {
    private static final int DESCRIPTION_PREVIEW = 155;

    private FileCards() {}

    static Node build(Asset asset, Function<String, String> ownerTitle, BiConsumer<Asset, AssetStream> open,
                      BiConsumer<Asset, AssetStream> save, IntConsumer goPage) {
        String name = asset.name();
        var details = new VBox(5, label(name, "asset-title"));
        details.setMinWidth(0);
        var card = new VBox(5);
        card.getStyleClass().add("asset-card");
        card.setMinWidth(0);
        var actionRail = asset.variants().size() == 1 ? new VBox(6) : null;
        if (actionRail != null) {
            actionRail.getStyleClass().add("asset-actions");
            HBox.setHgrow(details, Priority.ALWAYS);
            card.getChildren().add(new HBox(8, details, actionRail));
        } else {
            card.getChildren().add(details);
        }
        var owners = asset.owners().stream().map(ownerTitle).filter(title -> title != null && !title.isBlank())
                .filter(title -> !title.equals(name)).distinct().toList();
        if (!owners.isEmpty()) details.getChildren().add(label(String.join(" · ", owners), "asset-meta"));
        addDescription(details, asset);

        if (asset.variants().isEmpty()) {
            details.getChildren().add(label("File contents unavailable", "asset-meta"));
        }
        for (var stream : asset.variants()) {
            String variant = stream.variantName();
            String size = sizeLabel(stream.declaredSize());
            var facts = label(asset.variants().size() > 1 ? size + " · " + variant : size, "asset-meta");
            if (stream.declaredSize() != null) {
                facts.setTooltip(new Tooltip("Declared size: " + stream.declaredSize() + " bytes"));
            }
            if (actionRail == null) details.getChildren().add(facts);
            else details.getChildren().add(1, facts);
            Pane controls = actionRail == null ? actions() : actionRail;
            String target = asset.variants().size() > 1 ? name + " (" + variant + ")" : name;
            if (stream.canPreview()) {
                controls.getChildren().add(previewButton((stream.isPdf() ? "Open PDF " : "Preview ") + target,
                        () -> open.accept(asset, stream)));
            }
            controls.getChildren().add(saveButton("Save original bytes of " + target, () -> save.accept(asset, stream)));
            if (actionRail == null) details.getChildren().add(controls);
        }

        if (!asset.pages().isEmpty()) {
            var locations = actions();
            for (int page : asset.pages()) {
                locations.getChildren().add(button("Page " + (page + 1), "Go to attachment page " + (page + 1),
                        () -> goPage.accept(page)));
            }
            card.getChildren().add(locations);
        }
        return card;
    }

    private static void addDescription(VBox card, Asset asset) {
        String description = asset.description();
        if (description.isBlank()) return;
        String compact = asset.shortDescription();
        if (compact.length() > DESCRIPTION_PREVIEW) {
            int end = compact.offsetByCodePoints(0, Math.min(compact.codePointCount(0, compact.length()), DESCRIPTION_PREVIEW));
            compact = compact.substring(0, end).stripTrailing() + "…";
        }
        var summary = label(compact, "asset-meta");
        card.getChildren().add(summary);
        if (compact.equals(description)) return;
        String collapsed = compact;
        var more = new Hyperlink("Show more");
        more.getStyleClass().add("asset-description-link");
        more.setAccessibleText("Show full description of " + asset.name());
        more.setOnAction(event -> {
            boolean expand = summary.getText().equals(collapsed);
            summary.setText(expand ? description : collapsed);
            more.setText(expand ? "Show less" : "Show more");
            more.setAccessibleText((expand ? "Shorten description of " : "Show full description of ") + asset.name());
        });
        card.getChildren().add(more);
    }

    private static String sizeLabel(Long size) {
        if (size == null) return "Size not recorded";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", size / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", size / (1024.0 * 1024));
    }

    private static FlowPane actions() {
        var row = new FlowPane(6, 6);
        row.setMinWidth(0);
        row.setPrefWrapLength(210);
        row.getStyleClass().add("asset-actions");
        return row;
    }

    private static Label label(String value, String style) {
        var label = new Label(value);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMinWidth(0);
        return label;
    }

    private static Button button(String text, String tooltip, Runnable action) {
        var button = new Button(text);
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        button.setOnAction(event -> action.run());
        return button;
    }

    static Button previewButton(String label, Runnable action) {
        return iconButton(label, action, "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z",
                "M9 12a3 3 0 1 0 6 0 3 3 0 1 0-6 0");
    }

    static Button saveButton(String label, Runnable action) {
        return iconButton(label, action, "M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5");
    }

    private static Button iconButton(String label, Runnable action, String... paths) {
        var button = button("", label, action);
        button.getStyleClass().add("icon-button");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        // Keep the same 24 × 24 viewport as the Full extension's viewer-icons.ts.
        var icon = new Group(new Rectangle(24, 24, Color.TRANSPARENT));
        icon.setScaleX(0.75);
        icon.setScaleY(0.75);
        icon.setMouseTransparent(true);
        for (String data : paths) {
            var path = new SVGPath();
            path.setContent(data);
            path.setFill(Color.TRANSPARENT);
            path.strokeProperty().bind(button.textFillProperty());
            path.setStrokeWidth(2);
            path.setStrokeLineCap(StrokeLineCap.ROUND);
            path.setStrokeLineJoin(StrokeLineJoin.ROUND);
            icon.getChildren().add(path);
        }
        button.setGraphic(icon);
        return button;
    }
}
