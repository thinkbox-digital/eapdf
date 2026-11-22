package eapdf.viewer;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Pagination;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.util.DateConverter;

/** Presents ordinary PDF collection members using the file specifications already indexed by the viewer. */
final class PortfolioView {
    private static final int TILE_PAGE_SIZE = 60;
    private static final int MAX_FOLDER_NODES = 1_000_000;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final Field NAME = new Field(COSName.F, "Name", "F", 0, true);
    private static final List<Field> STANDARD_FIELDS = List.of(NAME,
            new Field(COSName.DESC, "Description", "Desc", 1, true),
            new Field(COSName.SIZE, "Size", "Size", 2, true),
            new Field(COSName.MOD_DATE, "Modified", "ModDate", 3, true));

    private record Field(COSName key, String label, String subtype, int order, boolean visible) {}
    private record Sort(Field field, boolean ascending) {}
    private record Value(String text, Object scalar) {
        @Override public String toString() { return text; }
    }
    private record Member(String name, PdfNavigation.Asset asset, COSString key, int folder, Integer childFolder,
                          Map<Field, Value> values, String searchable) {
        boolean directory() { return childFolder != null; }
        @Override public String toString() { return name; }
    }

    private enum Layout {
        DETAILS("D", "Details"), TILES("T", "Tiles"), TREE("Tree", "Folder tree"),
        FILMSTRIP("FilmStrip", "Filmstrip"), LINEAR("Linear", "Preview and details");

        final String code;
        final String label;

        Layout(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override public String toString() { return label; }
    }

    private PortfolioView() {}

    static boolean initiallyHidden(ViewerSession session) {
        return "H".equals(collection(session).getNameAsString(COSName.getPDFName("View")));
    }

    static Optional<PdfNavigation.Asset> initialFile(ViewerSession session) {
        if (!(collection(session).getDictionaryObject(COSName.D) instanceof COSString target)) return Optional.empty();
        return session.pdf.uniqueCollectionMember(target);
    }

    static boolean stacksPreview(ViewerSession session) {
        var collection = collection(session);
        var direction = previewSplit(collection).getNameAsString(COSName.getPDFName("Direction"), "");
        return switch (direction) {
            case "H" -> true;
            case "V", "N" -> false;
            default -> !"C".equals(collection.getNameAsString(COSName.getPDFName("View")))
                    && initialLayout(collection) == Layout.DETAILS;
        };
    }

    static double dividerPosition(ViewerSession session) {
        var split = previewSplit(collection(session));
        var value = split.getDictionaryObject(COSName.getPDFName("Position"));
        if (value instanceof COSNumber position && position.floatValue() >= 0 && position.floatValue() <= 100) {
            return position.floatValue() / 100.0;
        }
        return stacksPreview(session) ? 0.55 : 0.35;
    }

    private static COSDictionary previewSplit(COSDictionary collection) {
        var split = collection.getDictionaryObject(COSName.getPDFName("Split"));
        return split instanceof COSDictionary dictionary ? dictionary : new COSDictionary();
    }

    static void selectFile(Node portfolio, PdfNavigation.Asset asset) {
        if (asset != null && portfolio.getUserData() instanceof Browser browser) browser.selectFile(asset);
    }

    private static Layout initialLayout(COSDictionary collection) {
        return switch (collection.getNameAsString(COSName.getPDFName("View"), "D")) {
            case "T" -> Layout.TILES;
            case "C" -> customLayout(collection);
            default -> Layout.DETAILS;
        };
    }

    private static Layout customLayout(COSDictionary collection) {
        var navigator = collection.getDictionaryObject(COSName.getPDFName("Navigator"));
        if (navigator instanceof COSDictionary dictionary) {
            var layouts = dictionary.getDictionaryObject(COSName.getPDFName("Layout"));
            if (layouts instanceof COSArray array) {
                for (int i = 0; i < array.size(); i++) {
                    var layout = namedLayout(array.getObject(i));
                    if (layout != null) return layout;
                }
            } else {
                var layout = namedLayout(layouts);
                if (layout != null) return layout;
            }
        }
        return Layout.DETAILS;
    }

    private static Layout namedLayout(COSBase value) {
        String name = switch (value) {
            case COSName key -> key.getName();
            case COSString text -> text.getString();
            case null, default -> "";
        };
        for (var layout : Layout.values()) {
            if (layout.code.equals(name)) return layout;
        }
        return null;
    }

    static Node build(ViewerSession session, Consumer<PdfNavigation.Asset> open,
                      Consumer<PdfNavigation.Asset> save, Runnable readPages) {
        COSDictionary collection = collection(session);
        List<Field> schema = fields(collection);
        var target = collection.getDictionaryObject(COSName.D);
        return new Browser(members(session, collection, schema), schema, sortOrder(collection, schema),
                initialLayout(collection), target instanceof COSString key ? key : null, open, save, readPages).view;
    }

    private static final class Browser {
        private final BorderPane view = styled(new BorderPane(), "portfolio-shell");
        private final Label status = styled(new Label(), "portfolio-count");
        private final TextField filter = styled(new TextField(), "search-field");
        private final ComboBox<Layout> layout = new ComboBox<>(FXCollections.observableArrayList(Layout.values()));
        private final FlowPane path = styled(new FlowPane(6, 4), "portfolio-toolbar", "viewer-controls");
        private final ScrollPane details = new ScrollPane();
        private final ObjectProperty<Member> selected = new SimpleObjectProperty<>();
        private final Set<Integer> expanded = new HashSet<>();
        private final List<Integer> locations = new ArrayList<>(List.of(0));
        private final Map<Integer, Member> directories = new HashMap<>();
        private final List<Field> fields;
        private final COSString authoredKey;
        private final Consumer<PdfNavigation.Asset> open;
        private final Consumer<PdfNavigation.Asset> save;
        private final FilteredList<Member> matched;
        private final SortedList<Member> rows;
        private final TableView<Member> table;
        private final Map<Field, CheckMenuItem> columnSettings = new HashMap<>();
        private final MenuButton columns = new MenuButton("Columns");
        private final Button back = button("‹", () -> stepFolder(-1));
        private final Button forward = button("›", () -> stepFolder(1));
        private final Button up = button("↑", this::up);
        private final Button previous = button("Previous file", () -> stepFile(-1));
        private final Button next = button("Next file", () -> stepFile(1));
        private int location;

        Browser(List<Member> members, List<Field> fields, List<Sort> sort, Layout initial, COSString authoredKey,
                Consumer<PdfNavigation.Asset> open, Consumer<PdfNavigation.Asset> save, Runnable readPages) {
            this.fields = fields;
            this.authoredKey = authoredKey;
            this.open = open;
            this.save = save;
            members.stream().filter(Member::directory).forEach(member -> directories.put(member.childFolder(), member));
            matched = new FilteredList<>(FXCollections.observableArrayList(members));
            rows = new SortedList<>(matched, defaultOrder(sort));
            table = makeTable(sort);
            layout.getSelectionModel().select(initial);
            layout.setAccessibleText("Portfolio layout");
            filter.setPromptText("Filter files and metadata");
            filter.setAccessibleText("Filter portfolio files and metadata");
            filter.setPrefColumnCount(18);
            iconButton(back, "Previous folder", "left");
            iconButton(forward, "Next folder", "right");
            iconButton(up, "Up one folder", "up");
            var heading = styled(new Label("Portfolio", ViewerGlyph.icon("folder", 20)), "portfolio-title");
            var toolbar = styled(new FlowPane(8, 6, heading, status,
                    button("Cover page", readPages), filter, layout, columns, previous, next),
                    "portfolio-toolbar", "viewer-controls");
            view.setTop(new VBox(toolbar, path));
            view.setUserData(this);
            view.getStylesheets().add(PortfolioView.class.getResource("portfolio.css").toExternalForm());
            details.setFitToWidth(true);
            details.setPrefViewportHeight(125);
            details.setMaxHeight(165);
            selected.addListener((obs, old, member) -> {
                if (member != null && table.getSelectionModel().getSelectedItem() != member) {
                    table.getSelectionModel().select(member);
                }
                updateDetails();
                updateFileSteps();
                for (var card : view.lookupAll(".portfolio-tile")) {
                    card.pseudoClassStateChanged(SELECTED, card.getUserData() == member);
                }
            });
            filter.textProperty().addListener((obs, old, value) -> refresh());
            layout.valueProperty().addListener((obs, old, value) -> refresh());
            rows.addListener((ListChangeListener<Member>) ignored -> updateFileSteps());
            refresh();
        }

        private int folder() {
            return locations.get(location);
        }

        private void selectFile(PdfNavigation.Asset asset) {
            if (selected.get() != null && selected.get().asset() == asset) return;
            Member chosen = null;
            for (var member : matched.getSource()) {
                if (member.asset() != asset) continue;
                if (chosen == null) chosen = member;
                if (member.key().equals(authoredKey)) {
                    chosen = member;
                    break;
                }
            }
            if (chosen == null) return;
            boolean tree = layout.getValue() == Layout.TREE;
            if (!rows.contains(chosen) || tree && folder() != chosen.folder()) visit(chosen.folder());
            selected.set(chosen);
            table.scrollTo(chosen);
            if (tree) view.setCenter(makeTree());
        }

        private void visit(int folder) {
            if (folder != folder()) {
                locations.subList(location + 1, locations.size()).clear();
                locations.add(folder);
                location++;
            }
            if (filter.getText().isEmpty()) refresh();
            else filter.clear();
        }

        private void stepFolder(int step) {
            location += step;
            refresh();
        }

        private void up() {
            Member current = directories.get(folder());
            if (current != null) visit(current.folder());
        }

        private void refresh() {
            String query = filter.getText().strip().toLowerCase(Locale.ROOT);
            var choice = layout.getValue();
            matched.setPredicate(member -> query.isEmpty()
                    ? choice == Layout.TREE || member.folder() == folder()
                    : member.searchable().contains(query));
            status.setText(rows.size() + (rows.size() == 1 ? " item" : " items"));
            columns.setVisible(choice == Layout.DETAILS);
            columns.setManaged(choice == Layout.DETAILS);
            path.getChildren().setAll(back, forward, up, button("All files", () -> visit(0)));
            back.setDisable(location == 0);
            forward.setDisable(location == locations.size() - 1);
            up.setDisable(folder() == 0);
            var ancestors = new ArrayDeque<Member>();
            for (Member ancestor = directories.get(folder()); ancestor != null; ancestor = directories.get(ancestor.folder())) {
                ancestors.addFirst(ancestor);
            }
            for (var ancestor : ancestors) {
                path.getChildren().addAll(new Label("›"), button(ancestor.name(), () -> visit(ancestor.childFolder())));
            }
            path.setVisible(!directories.isEmpty());
            path.setManaged(path.isVisible());
            Node content = switch (choice) {
                case DETAILS, LINEAR -> table;
                case TREE -> makeTree();
                case TILES, FILMSTRIP -> makeCards(choice == Layout.FILMSTRIP);
            };
            updateColumns();
            view.setCenter(content);
            updateDetails();
            updateFileSteps();
        }

        private void activate(Member member) {
            selected.set(member);
            if (member.directory()) visit(member.childFolder());
            else openIfSupported(member.asset(), open);
        }

        private void stepFile(int step) {
            var files = rows.stream().filter(member -> !member.directory()).toList();
            int index = files.indexOf(selected.get());
            activate(files.get(index < 0 ? 0 : index + step));
        }

        private void updateFileSteps() {
            var files = rows.stream().filter(member -> !member.directory()).toList();
            int index = files.indexOf(selected.get());
            previous.setDisable(index <= 0);
            next.setDisable(index >= files.size() - 1);
        }

        private void updateDetails() {
            var member = selected.get();
            var choice = layout.getValue();
            if (member == null || !rows.contains(member) || choice == Layout.TILES || choice == Layout.FILMSTRIP) {
                view.setBottom(null);
                return;
            }
            var values = styled(new FlowPane(15, 7), "portfolio-toolbar");
            values.getChildren().add(styled(new Label(member.name()), "asset-title"));
            if (choice == Layout.TREE) {
                if (!member.directory()) values.getChildren().add(actions(member));
            } else {
                for (var field : fields) {
                    var value = member.values().get(field);
                    if (!field.subtype().equals("F") && !value.text().isBlank()) {
                        var info = styled(new Label(field.label() + " · " + value.text()), "asset-meta");
                        info.setWrapText(true);
                        info.setMaxWidth(430);
                        values.getChildren().add(info);
                    }
                }
            }
            details.setContent(values);
            view.setBottom(details);
        }

        private TableView<Member> makeTable(List<Sort> initialSort) {
            var table = styled(new TableView<Member>(rows), "portfolio-table");
            table.setPlaceholder(new Label("No files or folders to display"));
            table.getSelectionModel().selectedItemProperty().addListener((obs, old, member) -> {
                if (member != null) selected.set(member);
            });
            table.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER && table.getSelectionModel().getSelectedItem() != null) {
                    activate(table.getSelectionModel().getSelectedItem());
                    event.consume();
                } else if (event.getCode() == KeyCode.BACK_SPACE && folder() != 0) {
                    up();
                    event.consume();
                }
            });
            for (var field : fields) {
                var column = new TableColumn<Member, Value>(field.label());
                column.setUserData(field);
                column.setCellValueFactory(entry -> new ReadOnlyObjectWrapper<>(entry.getValue().values().get(field)));
                column.setCellFactory(ignored -> new TableCell<>() {
                    @Override protected void updateItem(Value value, boolean empty) {
                        super.updateItem(value, empty);
                        String text = empty || value == null ? null : value.text();
                        var member = text == null || getIndex() < 0 || getIndex() >= rows.size()
                                ? null : rows.get(getIndex());
                        boolean name = member != null && field.subtype().equals("F");
                        setText(name ? null : text);
                        setGraphic(name ? memberButton(member) : null);
                        setTooltip(text == null || text.isBlank() ? null : new Tooltip(text));
                    }
                });
                column.setPrefWidth(field.subtype().equals("F") ? 305 : 150);
                var setting = new CheckMenuItem(field.label());
                setting.setSelected(field.visible());
                setting.setDisable(field.subtype().equals("F"));
                setting.selectedProperty().addListener((obs, old, enabled) -> updateColumns());
                columnSettings.put(field, setting);
                columns.getItems().add(setting);
                table.getColumns().add(column);
            }
            var actions = new TableColumn<Member, Member>();
            actions.setSortable(false);
            actions.setReorderable(false);
            actions.setPrefWidth(95);
            actions.setCellValueFactory(entry -> new ReadOnlyObjectWrapper<>(entry.getValue()));
            actions.setCellFactory(ignored -> new TableCell<>() {
                @Override protected void updateItem(Member member, boolean empty) {
                    super.updateItem(member, empty);
                    setGraphic(empty || member.directory() ? null : actions(member));
                }
            });
            table.getColumns().add(actions);
            table.setSortPolicy(control -> {
                var sort = control.getSortOrder().stream()
                        .map(column -> new Sort((Field) column.getUserData(),
                                column.getSortType() == TableColumn.SortType.ASCENDING)).toList();
                rows.setComparator(defaultOrder(sort));
                return true;
            });
            for (var entry : initialSort) {
                for (var column : table.getColumns()) {
                    if (!entry.field().equals(column.getUserData())) continue;
                    column.setSortType(entry.ascending() ? TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
                    table.getSortOrder().add(column);
                }
            }
            return table;
        }

        private void updateColumns() {
            var choice = layout.getValue();
            for (var column : table.getColumns()) {
                if (!(column.getUserData() instanceof Field field)) continue;
                column.setVisible(field.subtype().equals("F") || choice == Layout.DETAILS
                        && columnSettings.get(field).isSelected());
            }
        }

        private TreeView<Member> makeTree() {
            var root = new TreeItem<Member>();
            var items = new HashMap<Integer, TreeItem<Member>>();
            var tree = styled(new TreeView<Member>(root), "portfolio-table");
            tree.setShowRoot(false);
            if (filter.getText().isBlank()) {
                for (var member : rows) {
                    if (!member.directory()) continue;
                    var item = new TreeItem<>(member);
                    item.setExpanded(expanded.contains(member.childFolder()));
                    item.expandedProperty().addListener((obs, old, open) -> {
                        if (open) expanded.add(member.childFolder());
                        else expanded.remove(member.childFolder());
                    });
                    items.put(member.childFolder(), item);
                }
            }
            TreeItem<Member> current = null;
            for (var member : rows) {
                var item = member.directory() ? items.get(member.childFolder()) : null;
                if (item == null) item = new TreeItem<>(member);
                items.getOrDefault(member.folder(), root).getChildren().add(item);
                if (member == selected.get()) current = item;
            }
            for (int id = folder(); items.containsKey(id); id = directories.get(id).folder()) {
                items.get(id).setExpanded(true);
            }
            tree.setCellFactory(ignored -> new TreeCell<>() {
                {
                    setOnMouseClicked(event -> {
                        var item = getTreeItem();
                        if (item != null && event.getButton() == MouseButton.PRIMARY
                                && (!item.getValue().directory() || event.getClickCount() == 2)) {
                            activateTreeItem(item);
                        }
                    });
                }

                @Override protected void updateItem(Member member, boolean empty) {
                    super.updateItem(member, empty);
                    setText(empty ? null : member.name());
                    setGraphic(empty ? null : ViewerGlyph.icon(glyph(member), 17));
                }
            });
            tree.getSelectionModel().select(current);
            tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
                if (item != null) selected.set(item.getValue());
            });
            tree.setOnKeyPressed(event -> {
                var item = tree.getSelectionModel().getSelectedItem();
                if (event.getCode() == KeyCode.ENTER && item != null) {
                    activateTreeItem(item);
                    event.consume();
                } else if (event.getCode() == KeyCode.BACK_SPACE && folder() != 0) {
                    up();
                    event.consume();
                }
            });
            return tree;
        }

        private void activateTreeItem(TreeItem<Member> item) {
            if (item.getValue().directory() && !item.isLeaf()) item.setExpanded(!item.isExpanded());
            else activate(item.getValue());
        }

        private Pagination makeCards(boolean filmstrip) {
            var pages = new Pagination(Math.max(1, (rows.size() + TILE_PAGE_SIZE - 1) / TILE_PAGE_SIZE));
            pages.setMaxPageIndicatorCount(9);
            pages.setPageFactory(index -> {
                var cards = styled(new FlowPane(12, 12), "portfolio-tiles");
                if (rows.isEmpty()) cards.getChildren().add(new Label("No files or folders to display"));
                int start = Math.min(rows.size(), index * TILE_PAGE_SIZE);
                for (var member : rows.subList(start, Math.min(rows.size(), start + TILE_PAGE_SIZE))) {
                    var title = memberButton(member);
                    title.setWrapText(true);
                    title.setTooltip(new Tooltip(member.name()));
                    var badge = styled(new Label(member.directory() ? "Folder" : fileType(member),
                            ViewerGlyph.icon(glyph(member), 29)), "portfolio-card-type");
                    var tile = styled(new VBox(7, badge, title),
                            "portfolio-tile");
                    tile.setPrefWidth(filmstrip ? 165 : 245);
                    tile.setMaxWidth(tile.getPrefWidth());
                    tile.setUserData(member);
                    tile.pseudoClassStateChanged(SELECTED, selected.get() == member);
                    if (!filmstrip) {
                        for (var field : fields) {
                            var value = member.values().get(field);
                            if (field.visible() && !field.subtype().equals("F") && !value.text().isBlank()) {
                                var detail = styled(new Label(field.label() + " · " + value.text()), "asset-meta");
                                detail.setWrapText(true);
                                tile.getChildren().add(detail);
                            }
                        }
                    }
                    if (!member.directory()) tile.getChildren().add(actions(member));
                    cards.getChildren().add(tile);
                }
                if (filmstrip) cards.setMinWidth(Math.max(0, cards.getChildren().size() * 177.0));
                var scroll = new ScrollPane(cards);
                scroll.setFitToWidth(!filmstrip);
                return scroll;
            });
            return pages;
        }

        private HBox actions(Member member) {
            return PortfolioView.actions(member.asset(), ignored -> activate(member), save);
        }

        private Button memberButton(Member member) {
            var button = styled(button(member.name(), () -> activate(member)), "portfolio-name");
            button.setGraphic(ViewerGlyph.icon(glyph(member), 17));
            button.setTooltip(new Tooltip(member.name()));
            button.setMaxWidth(Double.MAX_VALUE);
            return button;
        }
    }

    private static COSDictionary collection(ViewerSession session) {
        var value = session.pdf.document().getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.COLLECTION);
        return value instanceof COSDictionary dictionary ? dictionary : new COSDictionary();
    }

    private static List<Field> fields(COSDictionary collection) {
        if (!(collection.getDictionaryObject(COSName.getPDFName("Schema")) instanceof COSDictionary schema)) {
            return STANDARD_FIELDS;
        }
        var fields = new ArrayList<Field>();
        for (var key : schema.keySet()) {
            if (schema.getDictionaryObject(key) instanceof COSDictionary field) {
                fields.add(new Field(key, field.getString(COSName.N, key.getName()),
                        field.getNameAsString(COSName.SUBTYPE, "S"), field.getInt(COSName.O, fields.size()),
                        field.getBoolean(COSName.V, true)));
            }
        }
        fields.sort(Comparator.comparingInt(Field::order));
        if (fields.stream().noneMatch(field -> field.visible() && field.subtype().equals("F"))) fields.addFirst(NAME);
        return fields;
    }

    private static List<Member> members(ViewerSession session, COSDictionary collection, List<Field> fields) {
        var result = new ArrayList<Member>();
        for (var file : session.pdf.collectionMembers()) {
            var asset = file.asset();
            var values = new LinkedHashMap<Field, Value>();
            for (var field : fields) {
                values.put(field, value(asset, file.specification(), field));
            }
            Integer folder = session.mail.folderId(file.nameTreeKey().getString());
            result.add(new Member(asset.name(), asset, file.nameTreeKey(), folder == null ? 0 : folder, null, values,
                    searchable(asset.name(), asset.description(), values)));
        }
        var folders = folderMetadata(collection);
        for (var folder : session.mail.folders()) {
            var values = new LinkedHashMap<Field, Value>();
            var metadata = folders.getOrDefault(folder.id(), new COSDictionary());
            String description = metadata.getString(COSName.DESC, "");
            for (var field : fields) {
                var value = switch (field.subtype()) {
                    case "F" -> words(folder.name());
                    case "Desc" -> words(description);
                    case "Size", "CompressedSize" -> words("");
                    case "CreationDate" -> date(metadata.getString(COSName.CREATION_DATE));
                    case "ModDate" -> date(metadata.getString(COSName.MOD_DATE));
                    default -> customValue(metadata, field);
                };
                values.put(field, value);
            }
            result.add(new Member(folder.name(), null, null, folder.parentId() == null ? 0 : folder.parentId(),
                    folder.id(), values, searchable(folder.name(), description, values)));
        }
        return result;
    }

    private static String searchable(String name, String description, Map<Field, Value> values) {
        var text = new StringBuilder(name).append(' ').append(description);
        for (var value : values.values()) {
            if (!value.text().isEmpty()) text.append(' ').append(value.text());
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static Map<Integer, COSDictionary> folderMetadata(COSDictionary collection) {
        if (!(collection.getDictionaryObject(COSName.getPDFName("Folders")) instanceof COSDictionary root)) return Map.of();
        var values = new HashMap<Integer, COSDictionary>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());
        var pending = new ArrayDeque<COSDictionary>();
        pending.add(root);
        while (!pending.isEmpty() && visited.size() < MAX_FOLDER_NODES) {
            var folder = pending.removeFirst();
            if (!visited.add(folder)) continue;
            int id = folder.getInt(COSName.getPDFName("ID"), -1);
            if (id >= 0) values.putIfAbsent(id, folder);
            if (folder.getDictionaryObject(COSName.getPDFName("Child")) instanceof COSDictionary child) pending.add(child);
            if (folder.getDictionaryObject(COSName.NEXT) instanceof COSDictionary next) pending.add(next);
        }
        return values;
    }

    private static Value value(PdfNavigation.Asset asset, COSDictionary spec, Field field) {
        var stream = asset.variants().isEmpty() ? null : asset.variants().getFirst();
        var embedded = stream == null ? null : stream.file();
        return switch (field.subtype()) {
            case "F" -> words(asset.name());
            case "Desc" -> words(asset.description());
            case "Size" -> size(stream == null ? null : stream.declaredSize());
            case "CompressedSize" -> size(embedded == null ? null : embedded.getCOSObject().getLength());
            case "CreationDate" -> fileDate(embedded, COSName.CREATION_DATE);
            case "ModDate" -> fileDate(embedded, COSName.MOD_DATE);
            default -> customValue(spec, field);
        };
    }

    private static Value customValue(COSDictionary spec, Field field) {
        if (spec == null || !(spec.getDictionaryObject(COSName.getPDFName("CI"))
                instanceof COSDictionary item)) return words("");
        var raw = item.getDictionaryObject(field.key());
        String prefix = "";
        if (raw instanceof COSDictionary subitem) {
            prefix = subitem.getString(COSName.P, "");
            raw = subitem.getDictionaryObject(COSName.D);
        }
        if (raw instanceof COSString text) {
            var value = field.subtype().equals("D") ? date(text.getString()) : words(text.getString());
            return new Value(prefix + value.text(), value.scalar());
        }
        if (raw instanceof COSNumber number) {
            Number quantity = number instanceof COSInteger integer ? (Number) Long.valueOf(integer.longValue())
                    : Float.valueOf(number.floatValue());
            return new Value(prefix + NumberFormat.getNumberInstance().format(quantity), quantity);
        }
        return words("");
    }

    private static Value fileDate(org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile file, COSName key) {
        if (file != null && file.getCOSObject().getDictionaryObject(COSName.PARAMS) instanceof COSDictionary params) {
            return date(params.getString(key));
        }
        return words("");
    }

    private static Value date(String text) {
        if (text == null || text.isBlank()) return words("");
        var parsed = DateConverter.toCalendar(text);
        if (parsed == null) return words(text);
        Instant instant = parsed.toInstant();
        return new Value(instant.atZone(ZoneId.systemDefault()).toLocalDate().toString(), instant);
    }

    private static Value size(Long bytes) {
        if (bytes == null || bytes < 0) return words("");
        double amount = bytes;
        int unit = 0;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        while (amount >= 1024 && unit < units.length - 1) {
            amount /= 1024;
            unit++;
        }
        var number = NumberFormat.getNumberInstance();
        number.setMaximumFractionDigits(unit == 0 ? 0 : 1);
        return new Value(number.format(amount) + " " + units[unit], bytes);
    }

    private static Value words(String value) {
        return value == null || value.isBlank() ? new Value("", null) : new Value(value, value);
    }

    private static List<Sort> sortOrder(COSDictionary collection, List<Field> fields) {
        if (!(collection.getDictionaryObject(COSName.SORT) instanceof COSDictionary sorting)) {
            return List.of(new Sort(NAME, true));
        }
        var result = new ArrayList<Sort>();
        var keys = sorting.getDictionaryObject(COSName.S);
        var directions = sorting.getDictionaryObject(COSName.A);
        if (keys instanceof COSName key) {
            addSort(result, fields, key, !(directions instanceof COSBoolean direction) || direction.getValue());
        } else if (keys instanceof COSArray list) {
            for (int index = 0; index < list.size(); index++) {
                if (!(list.getObject(index) instanceof COSName key)) continue;
                var direction = directions instanceof COSArray array && index < array.size() ? array.getObject(index) : directions;
                addSort(result, fields, key, !(direction instanceof COSBoolean ascending) || ascending.getValue());
            }
        }
        return result.isEmpty() ? List.of(new Sort(NAME, true)) : result;
    }

    private static void addSort(List<Sort> sort, List<Field> fields, COSName key, boolean ascending) {
        fields.stream().filter(field -> field.key().equals(key)).findFirst()
                .ifPresent(field -> sort.add(new Sort(field, ascending)));
    }

    private static Comparator<Member> defaultOrder(List<Sort> entries) {
        return (left, right) -> {
            if (left.directory() != right.directory()) return left.directory() ? -1 : 1;
            for (Sort entry : entries) {
                Value first = left.values().get(entry.field());
                Value second = right.values().get(entry.field());
                if (first == null) first = words(left.name());
                if (second == null) second = words(right.name());
                if (first.scalar() == null || second.scalar() == null) {
                    if (first.scalar() != second.scalar()) return first.scalar() == null ? 1 : -1;
                    continue;
                }
                int compared = compareValues(first, second);
                if (compared != 0) return entry.ascending() ? compared : -compared;
            }
            return left.name().compareToIgnoreCase(right.name());
        };
    }

    private static int compareValues(Value left, Value right) {
        if (left.scalar() == null) return right.scalar() == null ? 0 : 1;
        if (right.scalar() == null) return -1;
        if (left.scalar() instanceof Long a && right.scalar() instanceof Long b) return a.compareTo(b);
        if (left.scalar() instanceof Number a && right.scalar() instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (left.scalar() instanceof Instant a && right.scalar() instanceof Instant b) return a.compareTo(b);
        return left.text().compareToIgnoreCase(right.text());
    }

    private static String fileType(Member member) {
        int dot = member.name().lastIndexOf('.');
        return dot >= 0 && dot + 1 < member.name().length()
                ? member.name().substring(dot + 1).toUpperCase(Locale.ROOT) : "File";
    }

    private static String glyph(Member member) {
        if (member.directory()) return "folder";
        return "EML".equals(fileType(member)) || "MSG".equals(fileType(member)) ? "mail" : "document";
    }

    private static HBox actions(PdfNavigation.Asset asset, Consumer<PdfNavigation.Asset> open,
                                Consumer<PdfNavigation.Asset> save) {
        var previewStreams = asset.variants().stream().filter(PdfNavigation.AssetStream::canPreview).toList();
        boolean pdf = !previewStreams.isEmpty() && previewStreams.stream().allMatch(PdfNavigation.AssetStream::isPdf);
        var preview = FileCards.previewButton((pdf ? "Open PDF " : "Preview ") + asset.name(), () -> open.accept(asset));
        preview.setDisable(previewStreams.isEmpty());
        var download = FileCards.saveButton("Save original bytes of " + asset.name(), () -> save.accept(asset));
        download.setDisable(asset.variants().isEmpty());
        return styled(new HBox(6, preview, download), "portfolio-actions");
    }

    private static void openIfSupported(PdfNavigation.Asset asset, Consumer<PdfNavigation.Asset> open) {
        if (asset.variants().stream().anyMatch(PdfNavigation.AssetStream::canPreview)) open.accept(asset);
    }

    private static Button button(String label, Runnable action) {
        var button = new Button(label);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static void iconButton(Button button, String text, String glyph) {
        button.setTooltip(new Tooltip(text));
        button.setAccessibleText(text);
        button.getStyleClass().add("icon-button");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setGraphic(ViewerGlyph.icon(glyph, 18));
    }

    private static <T extends Node> T styled(T node, String... styles) {
        node.getStyleClass().addAll(styles);
        return node;
    }
}
