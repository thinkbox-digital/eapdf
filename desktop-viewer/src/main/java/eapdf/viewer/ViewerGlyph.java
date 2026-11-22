package eapdf.viewer;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/** The Full Chrome reader's 24-unit SVG glyphs expressed as native JavaFX paths. */
final class ViewerGlyph {
    private static final Color STROKE = Color.web("#566273");

    private ViewerGlyph() {}

    static Node icon(String kind, int size) {
        String[] paths = switch (kind) {
            case "folder" -> new String[]{"M3 7V5h6l2 2h10v13H3Z"};
            case "folder-open" -> new String[]{"M3 10h18l-3 10H3Z"};
            case "mail", "message" -> new String[]{"M3 5h18v14H3Z", "m3 6 9 7 9-7"};
            case "download" -> new String[]{"M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"};
            case "search" -> new String[]{"M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14", "m15 15 6 6"};
            case "close" -> new String[]{"m6 6 12 12M18 6 6 18"};
            case "arrowUp", "up" -> new String[]{"m5 12 7-7 7 7M12 5v15"};
            case "left" -> new String[]{"m15 5-7 7 7 7"};
            case "right" -> new String[]{"m9 5 7 7-7 7"};
            case "minus" -> new String[]{"M5 12h14"};
            case "plus" -> new String[]{"M5 12h14M12 5v14"};
            default -> new String[]{"M5 3h9l5 5v13H5Z", "M14 3v6h5M8 13h8M8 17h8"};
        };

        var viewBox = new Group(new Rectangle(24, 24, Color.TRANSPARENT));
        for (String data : paths) {
            var path = new SVGPath();
            path.setContent(data);
            path.setFill(Color.TRANSPARENT);
            path.setStroke(STROKE);
            path.setStrokeWidth(2);
            path.setStrokeLineCap(StrokeLineCap.ROUND);
            path.setStrokeLineJoin(StrokeLineJoin.ROUND);
            path.getStyleClass().add("viewer-glyph-path");
            viewBox.getChildren().add(path);
        }
        double scale = size / 24.0;
        viewBox.getTransforms().add(new Scale(scale, scale));
        var result = new Group(viewBox);
        result.getStyleClass().add("viewer-glyph");
        result.setMouseTransparent(true);
        return result;
    }
}
