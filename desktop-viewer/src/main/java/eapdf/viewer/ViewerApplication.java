package eapdf.viewer;

import com.dlsc.pdfviewfx.PDFBoxDocument;
import com.dlsc.pdfviewfx.PDFView;
import com.sun.javafx.stage.WindowHelper;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.print.Printer;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.SplitPaneSkin;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

public final class ViewerApplication extends Application {
    private static final String APP_NAME = "EA-PDF Desktop Viewer";
    private static final String VERSION = viewerVersion();
    private static final boolean LINUX = "Linux".equals(System.getProperty("os.name"));
    private static final double DEFAULT_SIDEBAR_FRACTION = .27;
    private static final int SEARCH_RESULT_HEIGHT = 68;
    static final int MAX_CHILD_DEPTH = 8;
    private static final String STYLE = ViewerApplication.class.getResource("viewer.css").toExternalForm();

    private Stage stage;
    private ViewerSession current;
    private ViewerSession navigationDocument;
    private final Map<ViewerSession, Node> portfolioBrowsers = new HashMap<>();
    private MailNavigation.Message message;
    private Integer selectedFolder;
    private boolean archiveOverview;
    private boolean busy;
    private boolean viewerInputBlocked;
    private boolean syncing;
    private boolean showMessages = true;
    private boolean showFiles = true;
    private boolean filesVisibilityChosen;
    private long request;
    private volatile long searchRequest;
    private volatile long mailRequest;
    private boolean archiveSearchRunning;
    private boolean scanningMail;
    private ViewerSession scannedSession;
    private List<ViewerMailScan.Located> scannedMessages = List.of();
    private long lastFolderScroll;
    private boolean wheelConsumed;
    private boolean restoreTreeFocus;
    private double leftDivider;
    private double rightDivider;
    private double[] pendingSidebarPositions;
    private ViewerWindowState windowState;
    private long documentSearchRequest;
    private ListView<ViewerSearch.Hit> archiveHitList;
    private ViewerSession archiveHitRoot;
    private String archiveHitQuery;

    private final BorderPane shell = styled(new BorderPane(), "viewer-shell");
    private final BorderPane reader = styled(new BorderPane(), "reader-pane");
    private final SplitPane workspace = styled(new SplitPane(), "viewer-workspace");
    private final PDFView view = new PDFView();
    private final ContinuousReader viewport = new ContinuousReader(this::onScrolledPage, this::followPageLink, this::report);
    private final Label fileName = styled(new Label(), "document-title");
    private final HBox documentLocation = styled(new HBox(), "document-location");
    private final Label status = styled(new Label(), "status-message");
    private final Label archiveStats = styled(new Label(), "archive-stats");
    private final Label profile = styled(new Label(), "profile-label");
    private final Label totalPages = styled(new Label(), "page-count");
    private final Label fileCount = styled(new Label(), "sidebar-subtitle");
    private final Label messageCount = styled(new Label(), "sidebar-subtitle");
    private final Label filesTitle = styled(new Label("Files in this PDF"), "sidebar-title");
    private final TreeView<NavigationRow> tree = styled(new TreeView<>(), "sidebar-list", "message-tree");
    private final TreeView<NavigationRow> outlineTree = styled(new TreeView<>(), "sidebar-list", "message-tree");
    private final TitledPane outlines = styled(new TitledPane("PDF bookmarks", outlineTree), "pdf-bookmarks");
    private final ListView<FileRow> files = styled(new ListView<>(), "sidebar-list", "asset-list");
    private final TextField pageNumber = styled(new TextField(), "page-field");
    private final TextField filter = styled(new TextField(), "filter-field");
    private final TextField search = styled(new TextField(), "search-field");
    private final Label searchCount = styled(new Label(), "search-count");
    private final ComboBox<String> searchScope = new ComboBox<>();
    private final Button findAction = button("Find", "Start or cancel PDF search", this::runSearch);
    private final VBox searchBar = styled(new VBox(7), "viewer-search");
    private final VBox archiveResults = styled(new VBox(6), "collection-results");
    private final Button parentButton = button("↑", "Return to parent PDF", this::returnToParent);
    private final Button previous = button("‹", "Previous page", () -> goPage(viewport.getPage() - 1));
    private final Button next = button("›", "Next page", () -> goPage(viewport.getPage() + 1));
    private final Button close = styled(button("×", "Close viewer", this::closeOrCancel), "quiet", "icon-button");
    private final Button fileScope = button("All files", "Show all files in this PDF", this::clearMessage);
    private final Button messagesToggle = button("Show messages", "Show messages pane", this::toggleMessages);
    private final Button filesToggle = button("Show files", "Show files pane", this::toggleFiles);
    private final Button downloadMessage = button("Download this message", "Download this message as a PDF", this::saveMessage);
    private final Button searchToggle = button("Search", "Search PDFs (Ctrl+F)", this::openSearch);
    private final Button portfolioToggle = button("Portfolio view", "Show Portfolio view", this::togglePortfolio);
    private final CheckBox inferThreads = new CheckBox("Group similar subjects");
    private final ComboBox<String> zoom = styled(new ComboBox<>(), "zoom-select");
    private final javafx.beans.property.StringProperty navigationMode = new javafx.beans.property.SimpleStringProperty("Messages");
    private final Button conversationsToggle = styled(button("Conversations", "Show conversations",
            () -> navigationMode.set("Conversations".equals(navigationMode.get()) ? "Messages" : "Conversations")),
            "quiet");
    private final Map<MailNavigation.Message, TreeItem<NavigationRow>> messageRows = new HashMap<>();
    private final java.util.Set<Integer> expandedCollectionFolders = new java.util.HashSet<>();
    private VBox leftPanel;
    private VBox rightPanel;
    private VBox welcome;
    private HBox paneRestorers;
    private final HBox breadcrumbs = styled(new HBox(5), "viewer-breadcrumbs");
    private FlowPane controls;
    private MenuButton tools;

    @Override
    public void start(Stage window) {
        stage = window;
        windowState = new ViewerWindowState();
        leftDivider = windowState.divider("messagesDivider", DEFAULT_SIDEBAR_FRACTION);
        rightDivider = windowState.divider("filesDivider", 1 - DEFAULT_SIDEBAR_FRACTION);
        showMessages = windowState.flag("showMessages", true);
        showFiles = windowState.flag("showFiles", true);
        filesVisibilityChosen = windowState.flag("filesVisibilityChosen", false);
        workspace.getItems().add(reader);
        workspace.getStyleClass().add("reading-surface");
        view.setShowToolBar(false);
        view.setShowThumbnails(false);
        view.setShowSearchResults(false);
        view.setShowAll(true);
        view.setOnPrintError(failure -> showError("Could not print PDF", messageOf(failure)));
        view.getSearchResults().addListener((ListChangeListener<PDFView.SearchResult>) change -> updateSearchCount());
        view.selectedSearchResultProperty().addListener((obs, before, after) -> updateSearchCount());
        viewport.addEventFilter(ScrollEvent.SCROLL, this::continueFolder);

        buildSidebars();
        buildControls();
        buildWelcome();
        var statusBar = styled(new HBox(12, status, archiveStats, spacer(), profile), "viewer-status");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        paneRestorers = styled(new HBox(8, messagesToggle, filesToggle), "reader-heading");
        reader.setTop(new VBox(paneRestorers, controls));
        shell.setTop(new VBox(buildHeader()));
        shell.setCenter(workspace);
        shell.setBottom(statusBar);
        shell.widthProperty().addListener((obs, before, after) -> {
            if (!filesVisibilityChosen && (before.doubleValue() < 1200) != (after.doubleValue() < 1200)) {
                Platform.runLater(this::updatePanels);
            }
        });
        shell.setOnDragOver(event -> {
            if (!busy && event.getDragboard().hasFiles()) event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        shell.setOnDragDropped(event -> {
            var paths = event.getDragboard().getFiles();
            boolean accepted = !busy && paths.size() == 1;
            if (accepted) openLocal(paths.getFirst().toPath());
            event.setDropCompleted(accepted);
            event.consume();
        });

        var searchPanel = styled(new VBox(8, searchHeading(), searchBar, archiveResults), "search-panel");
        searchPanel.visibleProperty().bind(searchBar.visibleProperty());
        searchPanel.managedProperty().bind(searchBar.visibleProperty());
        searchPanel.setMinSize(0, 0);
        searchPanel.setPrefWidth(580);
        searchPanel.setMaxWidth(580);
        searchPanel.setMaxHeight(Region.USE_PREF_SIZE);
        archiveResults.setMinSize(0, 0);
        VBox.setVgrow(archiveResults, Priority.ALWAYS);
        searchBar.setMinHeight(Region.USE_PREF_SIZE);
        var layers = new javafx.scene.layout.StackPane(shell, searchPanel);
        javafx.scene.layout.StackPane.setAlignment(searchPanel, Pos.TOP_RIGHT);
        javafx.scene.layout.StackPane.setMargin(searchPanel, new javafx.geometry.Insets(68, 16, 16, 16));
        shell.getTop().layoutBoundsProperty().addListener((obs, old, bounds) ->
                javafx.scene.layout.StackPane.setMargin(searchPanel, new javafx.geometry.Insets(bounds.getHeight() + 8, 16, 16, 16)));
        var scene = new Scene(layers, 1440, 920);
        scene.getStylesheets().add(STYLE);
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+O"), this::choosePdf);
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+W"), this::closeOrCancel);
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+F"), this::openSearch);
        filterViewerInput(stage, scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && searchBar.isVisible()) {
                showSearch(false);
                searchToggle.requestFocus();
                event.consume();
            }
        });
        stage.setScene(scene);
        stage.setTitle(APP_NAME);
        stage.setMinWidth(850);
        stage.setMinHeight(600);
        windowState.restore(stage);
        windowState.track(stage);
        showDocument(null, null);
        stage.show();
        var arguments = getParameters().getUnnamed();
        if (arguments.size() == 1) {
            try {
                openLocal(Path.of(arguments.getFirst()));
            } catch (IllegalArgumentException error) {
                error("Could not open PDF", error);
            }
        } else if (!arguments.isEmpty()) {
            report("Pass one local PDF path when launching the viewer.");
        }
    }

    @Override
    public void stop() {
        saveDividers();
        if (windowState != null) {
            windowState.save(stage, leftDivider, rightDivider, showMessages, showFiles, filesVisibilityChosen, zoom.getValue());
        }
        shutdown();
        viewport.close();
    }

    private VBox buildHeader() {
        var mark = styled(new Region(), "brand-square");
        var brand = styled(group(mark, new Label("EA-PDF viewer")), "viewer-brand");
        var open = button("Open PDF…", "Open a local PDF (Ctrl+O)", this::choosePdf);
        open.getStyleClass().add("primary-button");
        open.setGraphic(ViewerGlyph.icon("folder", 17));
        searchToggle.setGraphic(ViewerGlyph.icon("search", 17));
        tools.setGraphic(ViewerGlyph.icon("download", 17));
        close.setGraphic(ViewerGlyph.icon("close", 19));
        fileName.setMinWidth(0);
        fileName.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileName, Priority.ALWAYS);
        breadcrumbs.setMinWidth(0);
        HBox.setHgrow(breadcrumbs, Priority.ALWAYS);
        documentLocation.setMinWidth(0);
        documentLocation.getChildren().setAll(fileName, breadcrumbs);
        HBox.setHgrow(documentLocation, Priority.ALWAYS);
        var global = styled(group(portfolioToggle, searchToggle, tools, close), "global-actions");
        var gap = spacer();
        var header = styled(new HBox(12, brand, open, gap, documentLocation, global), "viewer-toolbar");
        header.setAlignment(Pos.CENTER_LEFT);
        var filenameRow = styled(new HBox(), "viewer-filename-row");
        Runnable arrange = () -> {
            boolean narrow = shell.getWidth() > 0 && shell.getWidth() < 1200;
            if (narrow && documentLocation.getParent() == header) {
                header.getChildren().remove(documentLocation);
                filenameRow.getChildren().add(documentLocation);
            } else if (!narrow && documentLocation.getParent() == filenameRow) {
                filenameRow.getChildren().remove(documentLocation);
                header.getChildren().add(header.getChildren().indexOf(global), documentLocation);
            }
            toggle(gap, narrow);
            toggle(filenameRow, narrow && !fileName.getText().isBlank());
        };
        shell.widthProperty().addListener((obs, old, width) -> arrange.run());
        fileName.textProperty().addListener((obs, old, filename) -> arrange.run());
        arrange.run();
        return new VBox(header, filenameRow);
    }

    private void buildControls() {
        pageNumber.setAccessibleText("Page number");
        pageNumber.setPrefColumnCount(4);
        totalPages.setMinWidth(72);
        totalPages.setPrefWidth(72);
        totalPages.setMaxWidth(72);
        pageNumber.setOnAction(event -> {
            try {
                goPage(Integer.parseInt(pageNumber.getText().strip()) - 1);
            } catch (NumberFormatException error) {
                updatePage();
            }
        });
        pageNumber.focusedProperty().addListener((obs, before, focused) -> {
            if (!focused) updatePage();
        });
        zoom.getItems().addAll("Fit width", "Fit page");
        for (int percent = 50; percent <= 200; percent += 10) zoom.getItems().add(percent + "%");
        zoom.setAccessibleText("Zoom");
        zoom.getSelectionModel().select(zoom.getItems().contains(windowState.zoom()) ? windowState.zoom() : "Fit page");
        zoom.valueProperty().addListener((obs, before, value) -> setZoom(value));
        setZoom(zoom.getValue());
        var scaleDown = button("−", "Zoom out", () -> adjustZoom(-1));
        var scaleUp = button("+", "Zoom in", () -> adjustZoom(1));
        iconButton(previous, "left");
        iconButton(next, "right");
        iconButton(scaleDown, "minus");
        iconButton(scaleUp, "plus");
        iconButton(parentButton, "arrowUp");
        downloadMessage.setGraphic(ViewerGlyph.icon("download", 17));
        tools = new MenuButton("Export metadata…");
        tools.getItems().addAll(menu("Export message CSV…", this::exportCsv),
                menu("Export original XMP…", this::exportXmp),
                menu("Browse and export XMP streams…", this::collectXmp),
                menu("Print…", this::printPdf));
        var paging = styled(group(previous, new Label("Page"), pageNumber, totalPages, next), "page-position-controls");
        var magnification = styled(group(scaleDown, zoom, scaleUp), "page-zoom-controls");
        controls = styled(new FlowPane(8, 6, parentButton, paging, magnification, downloadMessage), "viewer-controls");
        controls.setAlignment(Pos.CENTER);

        search.setPromptText("Find in this PDF…");
        search.setAccessibleText("PDF search");
        search.setOnAction(event -> {
            if ("This PDF".equals(searchScope.getValue()) && search.getText().equals(view.getSearchText())) selectSearchHit(1);
            else runSearch();
        });
        search.setMinWidth(90);
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(search, Priority.ALWAYS);
        findAction.setMinWidth(82);
        findAction.setPrefWidth(82);
        findAction.setMaxWidth(82);
        searchScope.getItems().addAll("This PDF", "All files");
        searchScope.getSelectionModel().selectFirst();
        searchScope.setAccessibleText("Search scope");
        searchScope.valueProperty().addListener((obs, before, value) -> {
            stopArchiveSearch();
            clearDocumentSearch();
            clearArchiveResults();
            searchCount.setText("");
        });
        var searchControls = new HBox(8, searchScope, search, findAction,
                button("‹", "Previous search hit", () -> selectSearchHit(-1)),
                button("›", "Next search hit", () -> selectSearchHit(1)));
        searchControls.setAlignment(Pos.CENTER_LEFT);
        searchCount.setMinWidth(0);
        searchCount.setMaxWidth(Double.MAX_VALUE);
        searchBar.getChildren().setAll(searchControls, searchCount);
        search.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (archiveHitList == null || event.isAltDown() || event.isControlDown() || event.isMetaDown()) return;
            if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP) {
                activateArchiveResult(event.getCode() == KeyCode.DOWN ? 0 : archiveHitList.getItems().size() - 1);
                event.consume();
            }
        });
        showSearch(false);
    }

    private HBox searchHeading() {
        var dismiss = styled(button("×", "Close search", () -> {
            showSearch(false);
            searchToggle.requestFocus();
        }), "icon-button");
        iconButton(dismiss, "close");
        return styled(group(styled(new Label("Search"), "sidebar-title"), spacer(), dismiss), "search-panel-heading");
    }

    private void openSearch() {
        if (current == null) return;
        showSearch(true);
        search.requestFocus();
    }

    private void buildWelcome() {
        var title = styled(new Label("Your email—complete, authentic, accessible, structured."), "welcome-title");
        var copy = styled(new Label("Open or drop a PDF to browse its messages and embedded files."), "welcome-copy");
        title.setWrapText(true);
        copy.setWrapText(true);
        var open = button("Choose PDF…", "Choose a local PDF", this::choosePdf);
        open.getStyleClass().add("primary-button");
        var card = styled(new VBox(14, title, copy, open), "welcome-card");
        card.setAlignment(Pos.CENTER);
        welcome = styled(new VBox(card), "welcome-pane");
        welcome.setAlignment(Pos.CENTER);
    }

    private void buildSidebars() {
        outlineTree.setShowRoot(false);
        outlineTree.setPrefHeight(200);
        outlineTree.setAccessibleText("PDF bookmarks");
        outlineTree.setCellFactory(ignored -> new TreeCell<>() {
            @Override
            protected void updateItem(NavigationRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(empty ? null : row.title());
            }
        });
        outlineTree.getSelectionModel().selectedItemProperty().addListener((obs, before, item) -> {
            if (!syncing && item != null && item.getValue() != null) goPage(item.getValue().page());
        });
        outlines.setExpanded(false);
        outlines.setAnimated(false);
        toggle(outlines, false);
        tree.setShowRoot(false);
        tree.setAccessibleText("Messages and collections");
        tree.setCellFactory(ignored -> new TreeCell<>() {
            {
                setPrefWidth(0);
            }

            @Override
            protected void updateItem(NavigationRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                var label = empty ? null : styled(new Label(row.title()), "message-title");
                if (label != null) {
                    label.setWrapText(true);
                    label.maxWidthProperty().bind(widthProperty().subtract(58));
                }
                Node content = label;
                if (!empty && row.message() != null) {
                    var sent = row.message().sent().strip();
                    if (sent.matches("\\d{4}-\\d{2}-\\d{2}.*")) sent = sent.substring(0, 10);
                    var from = styled(new Label(row.message().from()), "message-detail");
                    from.setMinWidth(0);
                    from.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(from, Priority.ALWAYS);
                    var date = styled(new Label(sent), "message-detail");
                    date.setMinWidth(Region.USE_PREF_SIZE);
                    var detail = new HBox(6, from, date);
                    detail.maxWidthProperty().bind(widthProperty().subtract(58));
                    content = new VBox(3, label, detail);
                }
                if (content != null) {
                    var glyph = row.overview() ? "archive" : row.folder() != null ? "folder"
                            : row.message() != null ? "mail" : row.asset() != null ? "file" : null;
                    if (glyph != null) {
                        var item = styled(new HBox(6, ViewerGlyph.icon(glyph, 16), content), "navigation-row");
                        item.setAlignment(Pos.TOP_LEFT);
                        HBox.setHgrow(content, Priority.ALWAYS);
                        content = item;
                    }
                }
                setGraphic(content);
                setTooltip(empty || row.detail().isBlank() ? null : new Tooltip(row.detail()));
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, before, item) -> {
            if (!syncing && item != null && item.getValue() != null) selectRow(item.getValue());
        });
        tree.addEventFilter(KeyEvent.KEY_PRESSED, this::handleTreeKeys);
        tree.setOnMouseClicked(event -> {
            var selected = tree.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue() != null && message == null
                    && selected.getValue().message() != null && selected.getValue().route().isEmpty()) {
                selectRow(selected.getValue());
            }
            if (event.getClickCount() == 2 && selected != null && selected.getValue() != null) {
                var row = selected.getValue();
                if (row.asset() != null && !row.asset().isCollectionPdf()) openNavigationAsset(row.asset());
            }
        });
        filter.setPromptText("Filter subject, sender or date");
        filter.setAccessibleText("Filter messages");
        filter.textProperty().addListener((obs, before, value) -> populateTree());
        navigationMode.addListener((obs, before, value) -> {
            if (!syncing && value != null) {
                updateReader();
                populateTree();
            }
        });
        inferThreads.selectedProperty().addListener((obs, before, value) -> populateTree());
        inferThreads.setVisible(false);
        inferThreads.setManaged(false);
        VBox.setVgrow(tree, Priority.ALWAYS);
        messageCount.getStyleClass().add("badge");
        fileCount.getStyleClass().add("badge");
        leftPanel = styled(new VBox(10, sidebarHeader(styled(new Label("Messages"), "sidebar-title"),
                messageCount, "‹", "Hide messages pane", this::toggleMessages),
                filter, conversationsToggle, inferThreads, outlines, tree), "viewer-sidebar", "viewer-left");

        files.setCellFactory(ignored -> new ListCell<>() {
            {
                setPrefWidth(0);
            }

            @Override
            protected void updateItem(FileRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                var graphic = empty ? null : fileRow(row);
                if (graphic instanceof Region region) {
                    region.maxWidthProperty().bind(widthProperty().subtract(14));
                    region.prefWidthProperty().bind(widthProperty().subtract(14));
                }
                setGraphic(graphic);
            }
        });
        VBox.setVgrow(files, Priority.ALWAYS);
        rightPanel = styled(new VBox(10, sidebarHeader(filesTitle, fileCount, "›", "Hide files pane", this::toggleFiles),
                fileScope, files), "viewer-sidebar", "viewer-right");
        workspace.setSkin(new SplitPaneSkin(workspace) {
            private double previousWidth;

            @Override
            protected void layoutChildren(double x, double y, double width, double height) {
                boolean sidebarResize = previousWidth > 0 && previousWidth != width
                        && (workspace.getItems().contains(leftPanel) || workspace.getItems().contains(rightPanel));
                var positions = pendingSidebarPositions;
                pendingSidebarPositions = null;
                if (positions == null && sidebarResize) positions = workspace.getDividerPositions();
                super.layoutChildren(x, y, width, height);
                previousWidth = width;
                if (positions != null) {
                    // The first pass distributes added pixels. Restore the sidebars' proportions at the new width.
                    workspace.setDividerPositions(positions);
                    super.layoutChildren(x, y, width, height);
                }
            }
        });
    }

    private HBox sidebarHeader(Label label, Label count, String glyph, String action, Runnable toggle) {
        label.setMinWidth(0);
        label.setWrapText(true);
        return styled(group(label, count, spacer(), styled(button(glyph, action, toggle), "quiet", "icon-button")),
                "sidebar-header");
    }

    private void handleTreeKeys(KeyEvent event) {
        if (event.isAltDown() || event.isControlDown() || event.isMetaDown() || tree.getRoot() == null) return;
        var code = event.getCode();
        int row = tree.getFocusModel().getFocusedIndex();
        if (row < 0) row = tree.getSelectionModel().getSelectedIndex();
        var item = row < 0 ? null : tree.getTreeItem(row);
        switch (code) {
            case UP, DOWN -> {
                if (!busy) {
                    int direction = code == KeyCode.DOWN ? 1 : -1;
                    int count = tree.getExpandedItemCount();
                    int start = row < 0 ? direction > 0 ? -1 : count : row;
                    for (int index = start + direction; index >= 0 && index < count; index += direction) {
                        var nextItem = tree.getTreeItem(index);
                        var value = nextItem == null ? null : nextItem.getValue();
                        if (value == null || (value.message() == null && value.asset() == null && value.page() < 0)) continue;
                        tree.getFocusModel().focus(index);
                        tree.getSelectionModel().select(index);
                        revealTreeRow(index);
                        break;
                    }
                }
                event.consume();
            }
            case HOME, END -> {
                int endpoint = code == KeyCode.HOME ? 0 : tree.getExpandedItemCount() - 1;
                if (endpoint >= 0) {
                    tree.getFocusModel().focus(endpoint);
                    revealTreeRow(endpoint);
                }
                event.consume();
            }
            case RIGHT, LEFT -> {
                if (item != null) {
                    var folder = !item.isLeaf() ? item : item.getParent();
                    if (folder != null && folder != tree.getRoot()
                            && (code == KeyCode.LEFT || folder == item)) {
                        folder.setExpanded(code == KeyCode.RIGHT);
                        int index = tree.getRow(folder);
                        tree.getFocusModel().focus(index);
                        revealTreeRow(index);
                    }
                }
                event.consume();
            }
            default -> { }
        }
    }

    private void revealTreeRow(int row) {
        if (row >= 0 && tree.getScene() != null && !rowIsVisible(tree, ".tree-cell", row)) tree.scrollTo(row);
    }

    private static boolean rowIsVisible(Node control, String cellSelector, int row) {
        var flow = control.lookup(".virtual-flow");
        if (flow != null) {
            var clip = flow.lookup(".clipped-container");
            var visibleArea = clip == null ? flow : clip;
            var bounds = visibleArea.localToScene(visibleArea.getBoundsInLocal());
            for (var node : flow.lookupAll(cellSelector)) {
                if (node instanceof javafx.scene.control.IndexedCell<?> cell
                        && cell.isVisible() && !cell.isEmpty() && cell.getIndex() == row) {
                    var visible = cell.localToScene(cell.getBoundsInLocal());
                    if (visible.getMinY() >= bounds.getMinY() - 1 && visible.getMaxY() <= bounds.getMaxY() + 1) return true;
                }
            }
        }
        return false;
    }

    private Node fileRow(FileRow row) {
        if (row.asset() == null) return styled(new Label(row.heading()), "asset-heading");
        return FileCards.build(row.asset(), this::titleFor, this::openVariant, this::saveVariant, this::goPage);
    }

    private String titleFor(String guid) {
        if (current == null) return "";
        return current.mail.messages().stream().filter(item -> item.guid().equals(guid))
                .map(MailNavigation.Message::title).findFirst().orElse("");
    }

    private void choosePdf() {
        if (busy) return;
        var path = choosePath("Open PDF", null);
        if (path != null) openLocal(path);
    }

    private Path choosePath(String title, String suggestedName) {
        if (viewerInputBlocked) return null;
        if (LINUX) return choosePortalPath(title, suggestedName);
        return chooseNativePath(stage, title, suggestedName);
    }

    private Path chooseNativePath(Window owner, String title, String suggestedName) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        File choice;
        if (suggestedName == null) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf", "*.PDF"));
            choice = chooser.showOpenDialog(owner);
        } else {
            chooser.setInitialFileName(suggestedName);
            choice = chooser.showSaveDialog(owner);
        }
        return choice == null ? null : choice.toPath();
    }

    private Path choosePortalPath(String title, String suggestedName) {
        var owner = Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(stage);
        // The Linux JavaFX backend creates X11 windows, including under XWayland.
        var peer = WindowHelper.getPeer(owner);
        long xid = peer == null ? 0 : peer.getRawHandle();
        String parentWindow = xid == 0 ? "" : "x11:" + Long.toHexString(xid);
        var picker = new Task<Path>() {
            @Override
            protected Path call() throws IOException {
                return XdgFileChooser.choose(parentWindow, title, suggestedName);
            }
        };
        picker.setOnSucceeded(event -> Platform.exitNestedEventLoop(picker, null));
        picker.setOnFailed(event -> Platform.exitNestedEventLoop(picker, null));
        withViewerInputBlocked(owner, () -> {
            Thread.ofVirtual().start(picker);
            return Platform.enterNestedEventLoop(picker);
        });
        var failure = picker.getException();
        if (failure instanceof IOException) {
            return chooseNativePath(owner, title, suggestedName);
        }
        if (failure != null) {
            var message = failure.getLocalizedMessage();
            showError("Could not show the system file picker", message == null ? messageOf(failure) : message);
        }
        return picker.getValue();
    }

    private void filterViewerInput(Stage window, Scene scene) {
        scene.addEventFilter(InputEvent.ANY, event -> {
            if (viewerInputBlocked) event.consume();
        });
        window.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            if (viewerInputBlocked) event.consume();
        });
    }

    private boolean avoidNativeModality() {
        // Disabling a maximized owner on GTK can apply its normal window bounds.
        return stage.isMaximized() && LINUX;
    }

    private <T> Optional<T> showDialog(Dialog<T> dialog) {
        if (!avoidNativeModality()) return dialog.showAndWait();
        dialog.initModality(Modality.NONE);
        return withViewerInputBlocked(stage, dialog::showAndWait);
    }

    private <T> T withViewerInputBlocked(Window owner, Supplier<T> show) {
        boolean wasBlocked = viewerInputBlocked;
        viewerInputBlocked = true;
        try {
            return show.get();
        } finally {
            viewerInputBlocked = wasBlocked;
            if (!wasBlocked && owner.isShowing()) owner.requestFocus();
        }
    }

    private void openLocal(Path path) {
        if (busy) return;
        Path source = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            report("Choose a readable local PDF file.");
            return;
        }
        try {
            if (Files.size(source) > 4L * 1024 * 1024 * 1024) {
                report("This file exceeds the Full reader’s 4 GiB PDF limit.");
                return;
            }
        } catch (IOException failure) {
            error("Could not open PDF", failure);
            return;
        }
        load(source, source.getFileName().toString(), null, null);
    }

    private void load(Path path, String name, ViewerSession parent, PdfNavigation.Asset source) {
        load(path, name, parent, source, "");
    }

    private void load(Path path, String name, ViewerSession parent, PdfNavigation.Asset source, String password) {
        load(path, name, parent, source, password, null, false, null);
    }

    private void load(Path path, String name, ViewerSession parent, PdfNavigation.Asset source, String password,
                      FolderStep continuation, boolean lastPage, PageTarget target) {
        if (parent != null) path.toFile().deleteOnExit();
        long token = ++request;
        setBusy(true);
        report("Opening " + name + "…");
        Thread.ofVirtual().start(() -> {
            ViewerSession session = null;
            PDFBoxDocument document = null;
            try {
                session = ViewerSession.open(path, name, parent, source, password);
                if (target != null) session.page = resolvePage(session, target);
                document = new ViewerRenderingDocument(session.renderingPath.toFile());
                var loaded = session;
                var rendered = document;
                Platform.runLater(() -> {
                    if (token != request) {
                        rendered.close();
                        loaded.close();
                        return;
                    }
                    setBusy(false);
                    if (continuation != null && loaded.mail.collection()) {
                        rendered.close();
                        loaded.close();
                        report("Stopped at a nested collection. Select it to continue.");
                        return;
                    }
                    if (continuation != null && loaded.mail.messages().isEmpty()) {
                        rendered.close();
                        loaded.close();
                        openFolderStep(continuation.advance());
                        return;
                    }
                    var previousSession = current;
                    if (lastPage) {
                        loaded.page = rendered.getNumberOfPages() - 1;
                        loaded.position = new ContinuousReader.Position(loaded.page, 1, 0);
                    }
                    showDocument(loaded, rendered);
                    if (parent == null) closeSessions(previousSession, null);
                    else if (previousSession != parent && containsSession(previousSession, parent)) {
                        closeSessions(previousSession, parent);
                    }
                    if (isPortfolio(loaded)) PortfolioView.initialFile(loaded)
                            .ifPresent(asset -> openPortfolioMember(loaded, asset));
                });
            } catch (IOException | RuntimeException failure) {
                if (document != null) document.close();
                if (session != null) session.close();
                Platform.runLater(() -> {
                    if (token != request) {
                        if (parent != null) ViewerSession.deleteTemporaryFile(path);
                        return;
                    }
                    setBusy(false);
                    if (failure instanceof InvalidPasswordException) {
                        var answer = promptPassword(name);
                        if (answer != null) {
                            load(path, name, parent, source, answer, continuation, lastPage, target);
                            return;
                        }
                        report("Opening canceled.");
                    } else error("Could not open PDF", failure);
                    if (parent != null) ViewerSession.deleteTemporaryFile(path);
                });
            }
        });
    }

    private String promptPassword(String name) {
        var dialog = new Dialog<String>();
        dialog.initOwner(stage);
        dialog.setTitle("Open protected PDF");
        dialog.setHeaderText(name);
        dialog.getDialogPane().getStylesheets().add(STYLE);
        var input = new PasswordField();
        input.setPromptText("PDF password");
        input.setAccessibleText("PDF password");
        dialog.getDialogPane().setContent(input);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? input.getText() : null);
        Platform.runLater(input::requestFocus);
        return showDialog(dialog).orElse(null);
    }

    private void closeOrCancel() {
        if (busy) {
            request++;
            setBusy(false);
            report("Opening canceled.");
        } else if (current != null) {
            shutdown();
            showDocument(null, null);
        }
    }

    private void shutdown() {
        request++;
        mailRequest++;
        stopArchiveSearch();
        clearDocumentSearch();
        viewport.clear();
        view.unload();
        closeSessions(current, null);
        current = null;
        portfolioBrowsers.clear();
    }

    private static void closeSessions(ViewerSession from, ViewerSession stop) {
        for (var item = from; item != stop; item = item.parent) item.close();
    }

    private void showDocument(ViewerSession session, PDFView.Document document) {
        showDocument(session, document, false);
    }

    private void showDocument(ViewerSession session, PDFView.Document document, boolean keepSearch) {
        syncing = true;
        if (current != null && viewport.getPageCount() > 0) current.position = viewport.position();
        if (rootOf(current) != rootOf(session)) {
            lastFolderScroll = 0;
            wheelConsumed = false;
        }
        var activeNavigation = navigationFor(session);
        boolean changedNavigation = navigationDocument != activeNavigation;
        var previousPortfolio = portfolioDocument(current);
        var activePortfolio = portfolioDocument(session);
        current = session;
        navigationDocument = activeNavigation;
        portfolioBrowsers.keySet().removeIf(owner -> !containsSession(session, owner));
        if (changedNavigation) {
            mailRequest++;
            scanningMail = false;
            scannedSession = null;
            scannedMessages = List.of();
            expandedCollectionFolders.clear();
        }
        message = null;
        archiveOverview = session != null && session == activeNavigation && isArchive(session);
        selectedFolder = null;
        clearDocumentSearch();
        viewport.clear();
        if (document == null) view.unload();
        else {
            view.setDocument(document);
            view.setPage(Math.max(0, Math.min(session.page, document.getNumberOfPages() - 1)));
            viewport.setDocument(session.pdf.document(), document, view.getPage());
            if (session.position != null) viewport.restore(session.position);
        }
        if (previousPortfolio != activePortfolio || changedNavigation && activePortfolio == null) {
            navigationMode.set(activePortfolio != null && !PortfolioView.initiallyHidden(activePortfolio)
                    ? "Portfolio" : "Messages");
        }
        if (changedNavigation) {
            filter.clear();
            archiveStats.setText(session == null ? "" : ViewerArchiveStats.summary(rootOf(session)));
            toggle(archiveStats, !archiveStats.getText().isBlank());
        }
        fileName.setText(session == null ? "" : session.name);
        fileName.setTooltip(session == null ? null : new Tooltip(session.name));
        toggle(fileName, session == null || session.parent == null);
        updateBreadcrumbs(session);
        updateBookmarks(session);
        String declared = session == null ? "" : session.mail.profile().isBlank() ? "PDF" : "EA-PDF 1" + session.mail.profile();
        profile.setText((declared.isBlank() ? "" : declared + " · ") + "viewer " + VERSION);
        if (!keepSearch) {
            search.clear();
            showSearch(false);
        }
        syncing = false;
        updateReader();
        if (changedNavigation || tree.getRoot() == null) populateTree();
        markNavigationPosition();
        updatePage();
        updateFiles();
        updateControls();
        if (session == null) report("Open or drop a PDF to begin.");
        else if (!session.mail.diagnostics().isEmpty() || session.pdf.assetsIncomplete()) {
            report(readingStatus(session) + " · Some document information is unavailable");
        } else report(readingStatus(session));
    }

    private static ViewerSession navigationFor(ViewerSession session) {
        var root = rootOf(session);
        return root != null && root.mail.collection() && List.of("c", "ci").contains(root.mail.profile()) ? root : session;
    }

    private static boolean isArchive(ViewerSession session) {
        return session != null && List.of("m", "mi", "c", "ci").contains(session.mail.profile());
    }

    private String readingStatus(ViewerSession session) {
        return session.name + " · Page " + (viewport.getPage() + 1) + " of " + pageCount();
    }

    private void updateBreadcrumbs(ViewerSession session) {
        var trail = new ArrayList<Node>();
        if (session != null && session.parent != null) {
            for (var next = session; next != null; next = next.parent) {
                var destination = next;
                if (!trail.isEmpty()) trail.addFirst(new Label("/"));
                if (next == session) {
                    var current = styled(new Label(next.name), "breadcrumb-current");
                    current.setMinWidth(0);
                    current.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(current, Priority.ALWAYS);
                    current.setTooltip(new Tooltip(session.breadcrumb()));
                    trail.addFirst(current);
                } else {
                    var part = styled(button(next.name, "Return to " + next.name,
                            () -> returnTo(destination)), "quiet", "breadcrumb-part");
                    part.setMinWidth(0);
                    part.setMaxWidth(180);
                    trail.addFirst(part);
                }
            }
        }
        breadcrumbs.getChildren().setAll(trail);
        toggle(breadcrumbs, session != null && session.parent != null);
    }

    private void setBusy(boolean value) {
        if (value && !busy && tree.isFocused()) restoreTreeFocus = true;
        busy = value;
        updateControls();
        tree.setDisable(value);
        files.setDisable(value);
        if (!value && restoreTreeFocus) {
            restoreTreeFocus = false;
            Platform.runLater(() -> {
                if (tree.getScene() != null && !busy) tree.requestFocus();
            });
        }
    }

    private void updateControls() {
        controls.setDisable(busy);
        close.setDisable(current == null && !busy);
        close.setText(busy ? "Cancel" : "×");
        close.setContentDisplay(busy ? javafx.scene.control.ContentDisplay.TEXT_ONLY
                : javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        close.setAccessibleText(busy ? "Cancel opening" : "Close viewer");
        if (busy) close.getStyleClass().remove("icon-button");
        else if (!close.getStyleClass().contains("icon-button")) close.getStyleClass().add("icon-button");
        parentButton.setDisable(current == null || current.parent == null);
        parentButton.setVisible(current != null && current.parent != null);
        // Retain the same toolbar width on the cover and in a member PDF.
        parentButton.setManaged(current != null);
        pageNumber.setDisable(current == null);
        zoom.setDisable(current == null);
        tools.setDisable(current == null);
        searchToggle.setDisable(current == null);
        downloadMessage.setDisable(current == null || selectedMessage() == null);
        portfolioToggle.setDisable(busy);
        toggle(portfolioToggle, portfolioDocument(current) != null);
    }

    private void toggleMessages() {
        saveDividers();
        showMessages = !showMessages;
        updatePanels();
    }

    private void toggleFiles() {
        saveDividers();
        showFiles = !workspace.getItems().contains(rightPanel);
        filesVisibilityChosen = true;
        updatePanels();
    }

    private void saveDividers() {
        if (pendingSidebarPositions != null) return;
        double[] values = workspace.getDividerPositions();
        if (workspace.getItems().contains(leftPanel) && values.length > 0) leftDivider = values[0];
        if (workspace.getItems().contains(rightPanel) && values.length > 0) rightDivider = values[values.length - 1];
    }

    private void updatePanels() {
        var panels = new ArrayList<Node>();
        var portfolio = "Portfolio".equals(navigationMode.getValue()) ? portfolioDocument(current) : null;
        boolean left = current != null && portfolio == null && showMessages;
        boolean right = current != null && portfolio == null && (filesVisibilityChosen ? showFiles : shell.getWidth() >= 1200);
        if (portfolio != null) panels.add(portfolioBrowsers.computeIfAbsent(portfolio, owner ->
                PortfolioView.build(owner, asset -> openPortfolioMember(owner, asset),
                        asset -> saveAsset(owner, asset), () -> {
                            if (current != owner) returnTo(owner);
                            if (current == owner && !busy) goPage(0);
                        })));
        if (left) panels.add(leftPanel);
        panels.add(reader);
        if (right) panels.add(rightPanel);
        if (!workspace.getItems().equals(panels)) {
            saveDividers();
            workspace.setOrientation(portfolio != null && PortfolioView.stacksPreview(portfolio)
                    ? Orientation.VERTICAL : Orientation.HORIZONTAL);
            workspace.getItems().setAll(panels);
            pendingSidebarPositions = null;
            if (portfolio != null) workspace.setDividerPositions(PortfolioView.dividerPosition(portfolio));
            else if (left && right) pendingSidebarPositions = new double[] {leftDivider, rightDivider};
            else if (left) pendingSidebarPositions = new double[] {leftDivider};
            else if (right) pendingSidebarPositions = new double[] {rightDivider};
            Platform.runLater(this::enableKeyboardDividers);
        }
        toggle(messagesToggle, current != null && portfolio == null && !left);
        toggle(filesToggle, current != null && portfolio == null && !right);
        toggle(paneRestorers, messagesToggle.isVisible() || filesToggle.isVisible());
        toggle(controls, current != null);
    }

    private void enableKeyboardDividers() {
        workspace.applyCss();
        boolean stacked = workspace.getOrientation() == Orientation.VERTICAL;
        var nodes = workspace.lookupAll(".split-pane-divider").stream()
                .sorted(java.util.Comparator.comparingDouble(node -> stacked
                        ? node.getBoundsInParent().getMinY() : node.getBoundsInParent().getMinX())).toList();
        for (int i = 0; i < nodes.size(); i++) {
            int index = i;
            var divider = nodes.get(i);
            divider.setFocusTraversable(true);
            divider.setAccessibleText("Resize reader pane");
            divider.setOnKeyPressed((KeyEvent event) -> {
                boolean decrease = event.getCode() == (stacked ? KeyCode.UP : KeyCode.LEFT);
                boolean increase = event.getCode() == (stacked ? KeyCode.DOWN : KeyCode.RIGHT);
                if ((decrease || increase) && index < workspace.getDividers().size()) {
                    var position = workspace.getDividers().get(index);
                    var delta = decrease ? -.02 : .02;
                    position.setPosition(Math.clamp(position.getPosition() + delta, .12, .88));
                    event.consume();
                }
            });
        }
    }

    private static boolean isPortfolio(ViewerSession session) {
        return session != null && session.mail.collection()
                && !List.of("c", "ci").contains(session.mail.profile());
    }

    private static ViewerSession portfolioDocument(ViewerSession session) {
        for (var owner = session; owner != null; owner = owner.parent) {
            if (isPortfolio(owner)) return owner;
        }
        return null;
    }

    private void updateReader() {
        var mode = navigationMode.getValue();
        toggle(conversationsToggle, navigationDocument != null && !navigationDocument.mail.profile().isBlank());
        conversationsToggle.setText("Conversations".equals(mode) ? "Browse messages" : "Conversations");
        inferThreads.setVisible("Conversations".equals(mode));
        inferThreads.setManaged(inferThreads.isVisible());
        if (current == null) reader.setCenter(welcome);
        else if (reader.getCenter() != viewport) reader.setCenter(viewport);
        portfolioToggle.setText("Portfolio".equals(mode) ? "Read PDF" : "Portfolio view");
        updatePanels();
        if ("Portfolio".equals(mode)) {
            var owner = portfolioDocument(current);
            var browser = portfolioBrowsers.get(owner);
            if (browser != null && current != owner) {
                var member = current;
                while (member.parent != owner) member = member.parent;
                PortfolioView.selectFile(browser, member.parentAsset);
            }
        }
    }

    private void togglePortfolio() {
        if (portfolioDocument(current) != null) {
            navigationMode.set("Portfolio".equals(navigationMode.getValue()) ? "Messages" : "Portfolio");
        }
    }

    private void populateTree() {
        if (syncing) return;
        var root = new TreeItem<NavigationRow>();
        root.setExpanded(true);
        messageRows.clear();
        syncing = true;
        try {
            tree.setRoot(root);
            if (current == null || navigationDocument == null) return;
            var browse = root;
            if ("Messages".equals(navigationMode.get()) && isArchive(navigationDocument) && filter.getText().isBlank()) {
                browse = new TreeItem<>(new NavigationRow(navigationDocument.name, "", null, null, -1, null,
                        List.of(), true));
                browse.setExpanded(true);
                root.getChildren().add(browse);
                if (archiveOverview && current == navigationDocument) tree.getSelectionModel().select(browse);
            }
            switch (navigationMode.getValue()) {
                case "Conversations" -> addConversations(root);
                case "Portfolio" -> addCollection(root, false);
                default -> {
                    if (navigationDocument.mail.collection()) {
                        if (filter.getText().isBlank()) addCollection(browse, true);
                        else addFilteredArchiveMessages(browse);
                    }
                    else for (var entry : navigationDocument.mail.messages()) addMessage(browse, entry);
                }
            }
            messageCount.setText(java.text.NumberFormat.getIntegerInstance().format(
                    Math.max(navigationDocument.mail.catalogMessageCount(), navigationDocument.mail.messages().size())));
            if (root.getChildren().isEmpty()) {
                root.getChildren().add(new TreeItem<>(new NavigationRow(
                        "Nothing to show", "", null, null, -1, null)));
            }
        } finally {
            syncing = false;
        }
        markNavigationPosition();
    }

    private void updateBookmarks(ViewerSession session) {
        var root = new TreeItem<NavigationRow>();
        root.setExpanded(true);
        if (session != null) addBookmarks(root);
        outlineTree.setRoot(root);
        toggle(outlines, !root.getChildren().isEmpty());
    }

    private void addBookmarks(TreeItem<NavigationRow> root) {
        var parents = new ArrayList<TreeItem<NavigationRow>>();
        parents.add(root);
        for (var bookmark : current.bookmarks) {
            int depth = Math.min(bookmark.depth(), parents.size() - 1);
            var item = new TreeItem<>(new NavigationRow(bookmark.title(), "Page " + (bookmark.page() + 1),
                    null, null, bookmark.page(), null));
            item.setExpanded(true);
            parents.get(depth).getChildren().add(item);
            parents.subList(depth + 1, parents.size()).clear();
            parents.add(item);
        }
    }

    private void addCollection(TreeItem<NavigationRow> root, boolean emailOnly) {
        var navigation = navigationDocument;
        var folders = new HashMap<Integer, TreeItem<NavigationRow>>();
        folders.put(0, root);
        for (var folder : navigation.mail.folders()) {
            if (folder.id() != 0) {
                var node = new TreeItem<>(new NavigationRow(folder.name(), "", null, null, -1, folder.id()));
                node.setExpanded(expandedCollectionFolders.contains(folder.id()));
                node.expandedProperty().addListener((obs, before, expanded) -> {
                    if (expanded) expandedCollectionFolders.add(folder.id());
                    else expandedCollectionFolders.remove(folder.id());
                });
                folders.put(folder.id(), node);
            }
        }
        for (var folder : navigation.mail.folders()) {
            var node = folders.get(folder.id());
            if (node == null || node == root) continue;
            var target = folder.parentId() == null ? root : folders.getOrDefault(folder.parentId(), root);
            if (target != node) target.getChildren().add(node);
        }
        for (var asset : navigation.assets) {
            if (emailOnly && !asset.isCollectionPdf()) continue;
            if (!emailOnly && asset.nameTreeKeys().isEmpty()) continue;
            if (!matches(asset.name(), filter.getText())) continue;
            var placements = asset.nameTreeKeys().stream().map(navigation.mail::folderId).distinct().toList();
            var row = new NavigationRow(asset.name(), asset.category(), null, asset, -1, null);
            if (placements.isEmpty()) root.getChildren().add(new TreeItem<>(row));
            for (var id : placements) {
                var target = id == null ? root : folders.getOrDefault(id, root);
                target.getChildren().add(new TreeItem<>(row));
            }
        }
    }

    private void addMessage(TreeItem<NavigationRow> parent, MailNavigation.Message entry) {
        addMessage(parent, entry, List.of());
    }

    private void addMessage(TreeItem<NavigationRow> parent, MailNavigation.Message entry, List<String> route) {
        if (!matches(entry.title() + " " + entry.from() + " " + entry.sent(), filter.getText())) return;
        var node = new TreeItem<>(new NavigationRow(entry.title(), (entry.from() + "   " + entry.sent()).strip(),
                entry, null, entry.firstPage(), null, route));
        node.setExpanded(true);
        parent.getChildren().add(node);
        if (route.isEmpty()) messageRows.put(entry, node);
    }

    private void addConversations(TreeItem<NavigationRow> root) {
        if (navigationDocument.mail.collection() && !ensureScannedMessages(root)) return;
        var routes = new java.util.IdentityHashMap<MailNavigation.Message, List<String>>();
        if (navigationDocument.mail.collection()) {
            for (var located : scannedMessages) routes.put(located.message(), located.route());
        }
        var archiveMessages = navigationDocument.mail.collection()
                ? scannedMessages.stream().map(ViewerMailScan.Located::message).toList() : navigationDocument.mail.messages();
        var rows = ViewerTools.conversations(archiveMessages, inferThreads.isSelected());
        for (var thread : rows) {
            if (thread.size() == 1) {
                addMessage(root, thread.getFirst(), routes.getOrDefault(thread.getFirst(), List.of()));
                continue;
            }
            var group = new TreeItem<>(new NavigationRow(thread.getFirst().title(), thread.size() + " messages",
                    null, null, -1, null));
            for (var entry : thread) addMessage(group, entry, routes.getOrDefault(entry, List.of()));
            if (!group.getChildren().isEmpty()) {
                group.setExpanded(true);
                root.getChildren().add(group);
            }
        }
    }

    private void addFilteredArchiveMessages(TreeItem<NavigationRow> root) {
        if (!ensureScannedMessages(root)) {
            addCollection(root, true);
            return;
        }
        for (var located : scannedMessages) addMessage(root, located.message(), located.route());
    }

    private boolean ensureScannedMessages(TreeItem<NavigationRow> root) {
        if (scannedSession == navigationDocument) return true;
        root.getChildren().add(new TreeItem<>(new NavigationRow("Reading archive message metadata…",
                "", null, null, -1, null)));
        if (scanningMail) return false;
        var session = navigationDocument;
        long token = ++mailRequest;
        scanningMail = true;
        Thread.ofVirtual().start(() -> {
            try {
                var found = ViewerMailScan.scan(session, () -> token != mailRequest);
                Platform.runLater(() -> {
                    if (token != mailRequest) return;
                    scanningMail = false;
                    scannedSession = session;
                    scannedMessages = found;
                    populateTree();
                });
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> {
                    if (token != mailRequest) return;
                    scanningMail = false;
                    scannedSession = session;
                    scannedMessages = List.of();
                    populateTree();
                    report("Some archive message metadata is unavailable.");
                });
            }
        });
        return false;
    }

    private void selectRow(NavigationRow row) {
        if (row.overview()) {
            if (current != navigationDocument) returnTo(navigationDocument, false);
            archiveOverview = true;
            clearMessage();
            return;
        }
        if (!row.route().isEmpty()) {
            openArchiveHit(navigationDocument, "", new ViewerSearch.Hit(row.route(), Math.max(0, row.page()), ""));
            return;
        }
        if (row.asset() != null) {
            if (row.asset().isCollectionPdf()) openNavigationAsset(row.asset());
            else files.getSelectionModel().select(new FileRow("", row.asset()));
            return;
        }
        if (row.message() != null) {
            archiveOverview = false;
            message = row.message();
            selectedFolder = null;
            updateFiles();
            updateControls();
            if (row.page() < 0) report("The body page is not identified in this PDF.");
        }
        if (row.folder() != null && current == navigationDocument) {
            selectedFolder = row.folder();
            message = null;
            updateFiles();
        }
        if (row.page() >= 0) goPage(row.page());
    }

    private void clearMessage() {
        message = null;
        selectedFolder = null;
        updateFiles();
    }

    private void updateFiles() {
        var result = new ArrayList<FileRow>();
        int count = 0;
        if (current != null) {
            var groups = new java.util.LinkedHashMap<String, List<PdfNavigation.Asset>>();
            for (var asset : current.assets) {
                if (message != null && !asset.owners().contains(message.guid())) continue;
                if (selectedFolder != null && asset.nameTreeKeys().stream().map(current.mail::folderId)
                        .noneMatch(selectedFolder::equals)) continue;
                groups.computeIfAbsent(asset.category(), ignored -> new ArrayList<>()).add(asset);
                count++;
            }
            groups.forEach((category, assets) -> {
                result.add(new FileRow(category, null));
                for (var asset : assets) result.add(new FileRow("", asset));
            });
        }
        fileCount.setText(java.text.NumberFormat.getIntegerInstance().format(count));
        filesTitle.setText(message == null ? "Files in this PDF" : "Files for this message");
        fileScope.setVisible(message != null || selectedFolder != null);
        fileScope.setManaged(fileScope.isVisible());
        files.getItems().setAll(result);
        files.setPlaceholder(new Label(message == null ? "No embedded files" : "No files linked to this message"));
    }

    private int pageCount() {
        return viewport.getPageCount();
    }

    private void goPage(int index) {
        if (current != null && pageCount() > 0) viewport.goToPage(Math.clamp(index, 0, pageCount() - 1));
    }

    private void onScrolledPage(int page) {
        if (!syncing && current != null && page != current.page) archiveOverview = false;
        if (view.getDocument() != null && view.getPage() != page) view.setPage(page);
        updatePage();
    }

    private void updatePage() {
        int pages = pageCount();
        int index = viewport.getPage();
        pageNumber.setText(pages == 0 ? "" : String.valueOf(index + 1));
        totalPages.setText("of " + pages);
        previous.setDisable(pages == 0 || index <= 0);
        next.setDisable(pages == 0 || index >= pages - 1);
        if (current == null || syncing || pages == 0) return;
        current.page = index;
        var owner = archiveOverview ? null : current.mail.messageAt(index);
        if (owner != null && !owner.equals(message)) {
            message = owner;
            selectedFolder = null;
            updateFiles();
        }
        var row = current == navigationDocument ? messageRows.get(owner) : null;
        if (row != null && tree.getSelectionModel().getSelectedItem() != row) {
            syncing = true;
            tree.getSelectionModel().select(row);
            revealTreeRow(tree.getRow(row));
            syncing = false;
        }
        updateControls();
        if (!busy) report(readingStatus(current));
    }

    private void setZoom(String value) {
        if (value != null) viewport.setZoom(value);
    }

    private void adjustZoom(int step) {
        var selected = zoom.getValue();
        int percent = selected != null && selected.endsWith("%")
                ? Integer.parseInt(selected.substring(0, selected.length() - 1)) : 100;
        zoom.getSelectionModel().select(Math.clamp(percent + step * 10, 50, 200) + "%");
    }

    private void returnToParent() {
        if (current != null && current.parent != null) returnTo(current.parent);
    }

    private void returnTo(ViewerSession destination) {
        returnTo(destination, true);
    }

    private void returnTo(ViewerSession destination, boolean markChild) {
        if (busy) return;
        try {
            var document = new ViewerRenderingDocument(destination.renderingPath.toFile());
            var previousSession = current;
            var directChild = previousSession;
            while (directChild != null && directChild.parent != destination) directChild = directChild.parent;
            showDocument(destination, document);
            if (markChild && directChild != null) markCollectionMember(directChild.parentAsset);
            closeSessions(previousSession, destination);
        } catch (RuntimeException failure) {
            error("Could not return to parent PDF", failure);
        }
    }

    private void markCollectionMember(PdfNavigation.Asset member) {
        if (tree.getRoot() == null || member == null) return;
        var selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null && selected.getValue().asset() == member
                && tree.getRow(selected) >= 0) return;
        var pending = new java.util.ArrayDeque<TreeItem<NavigationRow>>();
        pending.add(tree.getRoot());
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            var row = node.getValue();
            if (row != null && row.asset() == member) {
                syncing = true;
                for (var ancestor = node.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                    ancestor.setExpanded(true);
                }
                tree.getSelectionModel().select(node);
                revealTreeRow(tree.getRow(node));
                syncing = false;
                return;
            }
            pending.addAll(node.getChildren());
        }
    }

    private void markNavigationPosition() {
        if (navigationDocument == null || navigationDocument == current) return;
        var child = current;
        while (child != null && child.parent != navigationDocument) child = child.parent;
        if (child != null) markCollectionMember(child.parentAsset);
    }

    private void openNavigationAsset(PdfNavigation.Asset asset) {
        if (navigationDocument == null || current == null || busy || asset.variants().isEmpty()) return;
        if (current.parent == navigationDocument && current.parentAsset == asset) return;
        var variant = chooseVariant(asset);
        if (variant != null) openVariant(navigationDocument, asset, variant, null);
    }

    private void openPortfolioMember(ViewerSession owner, PdfNavigation.Asset asset) {
        if (busy || !containsSession(current, owner) || asset.variants().isEmpty()) return;
        if (current.parent == owner && current.parentAsset == asset) return;
        var variant = chooseVariant(asset);
        if (variant != null) openVariant(owner, asset, variant, null);
    }

    private void openAsset(PdfNavigation.Asset asset, PageTarget target) {
        if (current == null || busy || asset.variants().isEmpty()) return;
        var variant = chooseVariant(asset);
        if (variant == null) return;
        openVariant(asset, variant, target);
    }

    private void openVariant(PdfNavigation.Asset asset, PdfNavigation.AssetStream variant) {
        openVariant(asset, variant, null);
    }

    private void openVariant(PdfNavigation.Asset asset, PdfNavigation.AssetStream variant, PageTarget target) {
        openVariant(current, asset, variant, target);
    }

    private void openVariant(ViewerSession source, PdfNavigation.Asset asset,
                             PdfNavigation.AssetStream variant, PageTarget target) {
        if (source == null || busy) return;
        if (variant.isPdf()) {
            if (source.depth() >= MAX_CHILD_DEPTH) {
                report("This document reached the eight-level embedded PDF limit.");
                return;
            }
            try {
                load(source.pdf.extract(variant), asset.name(), source, asset, "", null, false, target);
            } catch (IOException | RuntimeException failure) {
                error("Could not open embedded PDF", failure);
            }
        } else if (variant.canPreview()) {
            try {
                showPreview(asset.name(), variant, source.pdf.preview(variant));
            } catch (IOException | RuntimeException failure) {
                error("Could not preview embedded file", failure);
            }
        } else {
            saveVariant(source, asset, variant);
        }
    }

    private void showPreview(String title, PdfNavigation.AssetStream stream, byte[] payload) throws IOException {
        Node content;
        String media = stream.mimeType();
        if (media.matches("image/(?:png|jpeg|gif)")
                || (!media.startsWith("text/") && title.toLowerCase(Locale.ROOT).matches(".*\\.(?:png|jpe?g|gif)$"))) {
            var image = new Image(new ByteArrayInputStream(payload));
            if (image.isError()) throw new IOException("The image could not be decoded.", image.getException());
            var picture = new ImageView(image);
            picture.setPreserveRatio(true);
            picture.setFitWidth(760);
            picture.setFitHeight(560);
            var scroll = new ScrollPane(picture);
            scroll.setFitToWidth(true);
            content = scroll;
        } else {
            var charset = StandardCharsets.UTF_8;
            var parameter = java.util.regex.Pattern.compile("(?i)\\bcharset\\s*=\\s*\"?([^\";\\s]+)")
                    .matcher(java.util.Objects.toString(stream.mediaTypeParameters(), ""));
            if (parameter.find()) {
                try {
                    charset = java.nio.charset.Charset.forName(parameter.group(1));
                } catch (IllegalArgumentException ignored) {
                    // Unknown declarations are shown using the default preview encoding.
                }
            }
            var text = new TextArea(new String(payload, 0, Math.min(payload.length, 1_000_000), charset));
            text.setEditable(false);
            text.setWrapText(true);
            content = text;
        }
        showWindow(title, content, 820, 600);
    }

    private PdfNavigation.AssetStream chooseVariant(PdfNavigation.Asset asset) {
        var variants = asset.variants();
        if (variants.isEmpty()) return null;
        if (variants.size() == 1) return variants.getFirst();
        var labels = variants.stream().map(PdfNavigation.AssetStream::variantName).toList();
        var dialog = new javafx.scene.control.ChoiceDialog<>(labels.getFirst(), labels);
        dialog.initOwner(stage);
        dialog.setTitle("Choose embedded file data");
        dialog.setHeaderText(asset.name());
        dialog.getDialogPane().getStylesheets().add(STYLE);
        return showDialog(dialog).map(label -> variants.get(labels.indexOf(label))).orElse(null);
    }

    private void saveAsset(ViewerSession owner, PdfNavigation.Asset asset) {
        if (busy || !containsSession(current, owner)) return;
        var selected = chooseVariant(asset);
        if (selected != null) saveVariant(owner, asset, selected);
    }

    private void saveVariant(PdfNavigation.Asset asset, PdfNavigation.AssetStream variant) {
        saveVariant(current, asset, variant);
    }

    private void saveVariant(ViewerSession source, PdfNavigation.Asset asset, PdfNavigation.AssetStream variant) {
        Path target = savePath("Save embedded file", PdfNavigation.safeFileName(asset.name()));
        if (target == null) return;
        try {
            var warnings = source.pdf.save(variant, target);
            report(warnings.isEmpty() ? "Saved " + target.getFileName() : String.join(" · ", warnings));
        } catch (IOException | RuntimeException failure) {
            error("Could not save embedded file", failure);
        }
    }

    private void continueFolder(ScrollEvent event) {
        if (event.isInertia() || Math.abs(event.getDeltaY()) <= Math.abs(event.getDeltaX())
                || event.isControlDown() || event.isMetaDown()) return;
        long now = System.nanoTime();
        if (now - lastFolderScroll > 200_000_000L) wheelConsumed = false;
        lastFolderScroll = now;
        if (wheelConsumed || busy || current == null || current.parent == null) return;
        int direction = event.getDeltaY() < 0 ? 1 : -1;
        boolean atEdge = direction > 0 ? viewport.atEnd() : viewport.atStart();
        if (!atEdge || current.mail.messages().isEmpty() || current.mail.collection()
                || !current.parent.mail.collection()) return;
        var parent = current.parent;
        var placements = current.parentAsset.nameTreeKeys().stream().map(parent.mail::folderId).distinct().toList();
        if (placements.size() != 1 || placements.getFirst() == null) return;
        int folder = placements.getFirst();
        var siblings = parent.assets.stream().filter(PdfNavigation.Asset::isCollectionPdf).filter(asset -> {
            var ids = asset.nameTreeKeys().stream().map(parent.mail::folderId).distinct().toList();
            return ids.size() == 1 && java.util.Objects.equals(ids.getFirst(), folder);
        }).toList();
        int index = siblings.indexOf(current.parentAsset);
        if (index < 0 || current.parentAsset.variants().stream().filter(PdfNavigation.AssetStream::isPdf).count() != 1) return;
        wheelConsumed = true;
        event.consume();
        openFolderStep(new FolderStep(parent, siblings, index + direction, direction));
    }

    private void openFolderStep(FolderStep step) {
        if (step.index() < 0 || step.index() >= step.siblings().size()) {
            report(step.direction() > 0 ? "End of messages in this folder." : "Beginning of messages in this folder.");
            return;
        }
        var sibling = step.siblings().get(step.index());
        var streams = sibling.variants().stream().filter(PdfNavigation.AssetStream::isPdf).toList();
        if (streams.size() != 1) {
            report(streams.isEmpty() ? "Missing embedded PDF: " + sibling.name() : "Ambiguous PDF streams: " + sibling.name());
            return;
        }
        try {
            load(step.parent().pdf.extract(streams.getFirst()), sibling.name(), step.parent(), sibling,
                    "", step, step.direction() < 0, null);
        } catch (IOException | RuntimeException failure) {
            error("Could not open the next PDF in this folder", failure);
        }
    }

    private void followPageLink(int page, Point2D position) {
        if (busy || current == null) return;
        double x = position.getX();
        double y = position.getY();
        try {
            for (var link : current.pdf.links(page)) {
                if (x < link.left() || x > link.right() || y < link.top() || y > link.bottom()) continue;
                var target = link.targetPage() == null && link.namedDestination() == null ? null
                        : new PageTarget(link.targetPage(), link.namedDestination());
                if (link.asset() != null) openAsset(link.asset(), target);
                else if (link.parent() && current.parent != null) {
                    var parent = current.parent;
                    Integer destination = target == null ? null : resolvePage(parent, target);
                    returnTo(parent);
                    if (destination != null && current == parent) goPage(destination);
                }
                else if (link.targetPage() != null) goPage(link.targetPage());
                else if (link.broken()) report("The attachment or page link is unavailable.");
                return;
            }
        } catch (IOException failure) {
            report("Could not read links on this page.");
        }
    }

    private static int resolvePage(ViewerSession session, PageTarget target) throws IOException {
        Integer page = target.page() != null ? target.page() : session.pdf.namedPage(target.name());
        if (page == null || page < 0 || page >= session.pdf.document().getNumberOfPages()) {
            throw new IOException("The link’s destination page is unavailable.");
        }
        return page;
    }

    private void showSearch(boolean visible) {
        searchBar.setVisible(visible);
        searchBar.setManaged(visible);
        if (!visible) {
            stopArchiveSearch();
            clearDocumentSearch();
            clearArchiveResults();
            searchCount.setText("");
        }
    }

    private void runSearch() {
        if (archiveSearchRunning) {
            stopArchiveSearch();
            searchCount.setText("Search canceled");
            return;
        }
        var query = search.getText().strip();
        if ("All files".equals(searchScope.getValue()) && !query.isEmpty()) {
            clearArchiveResults();
            runArchiveSearch(query);
            return;
        }
        searchThisPdf(query);
    }

    private void searchThisPdf(String query) {
        clearDocumentSearch();
        if (query.isEmpty() || !(view.getDocument() instanceof PDFView.SearchableDocument document)) return;
        long token = ++documentSearchRequest;
        view.setSearchText(query);
        boolean reportMatches = "This PDF".equals(searchScope.getValue());
        if (reportMatches) searchCount.setText("Searching…");
        Thread.ofVirtual().start(() -> {
            try {
                var results = document.getSearchResults(query);
                Platform.runLater(() -> {
                    if (token != documentSearchRequest) return;
                    view.getSearchResults().setAll(results);
                    viewport.setSearchResults(results, null);
                    updateSearchCount();
                });
            } catch (RuntimeException failure) {
                Platform.runLater(() -> {
                    if (token == documentSearchRequest && reportMatches) searchCount.setText("Could not search this PDF");
                });
            }
        });
    }

    private void clearDocumentSearch() {
        documentSearchRequest++;
        view.setSearchText(null);
        view.setSelectedSearchResult(null);
        view.getSearchResults().clear();
        viewport.setSearchResults(List.of(), null);
    }

    private void selectSearchHit(int step) {
        if ("All files".equals(searchScope.getValue())) {
            moveArchiveResult(step);
            return;
        }
        var hits = view.getSearchResults();
        if (hits.isEmpty()) return;
        int old = hits.indexOf(view.getSelectedSearchResult());
        int nextHit = old < 0 ? (step < 0 ? hits.size() - 1 : 0) : Math.floorMod(old + step, hits.size());
        var selected = hits.get(nextHit);
        view.setSelectedSearchResult(selected);
        viewport.setSearchResults(hits, selected);
        viewport.revealSearchResult(selected);
    }

    private void updateSearchCount() {
        if (!"This PDF".equals(searchScope.getValue())) return;
        var hits = view.getSearchResults();
        if (hits.isEmpty()) {
            searchCount.setText(search.getText().isBlank() ? "" : "0 hits");
        } else {
            int index = hits.indexOf(view.getSelectedSearchResult());
            searchCount.setText((index < 0 ? "" : (index + 1) + " of ") + hits.size() + " hits");
        }
    }

    private void runArchiveSearch(String query) {
        var root = rootOf(current);
        if (root == null) return;
        long token = ++searchRequest;
        archiveSearchRunning = true;
        findAction.setText("Cancel");
        searchCount.setText("Searching…");
        Thread.ofVirtual().start(() -> {
            try {
                var indexed = EmbeddedTextSearch.search(root, query, () -> token != searchRequest);
                var hits = indexed.isPresent() ? indexed.get() : ViewerSearch.search(root, query, () -> token != searchRequest, name ->
                        Platform.runLater(() -> {
                            if (token == searchRequest) searchCount.setText("Searching " + name + "…");
                        }));
                Platform.runLater(() -> {
                    if (token != searchRequest) return;
                    stopArchiveSearch();
                    searchCount.setText(hits.size() + (hits.size() == 1 ? " result" : " results"));
                    if (!hits.isEmpty()) showArchiveHits(root, query, hits);
                });
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> {
                    if (token == searchRequest) {
                        stopArchiveSearch();
                        error("Could not search all files", failure);
                    }
                });
            }
        });
    }

    private void stopArchiveSearch() {
        searchRequest++;
        archiveSearchRunning = false;
        findAction.setText("Find");
    }

    private void showArchiveHits(ViewerSession root, String query, List<ViewerSearch.Hit> hits) {
        var result = styled(new ListView<ViewerSearch.Hit>(), "sidebar-list", "archive-hit-list");
        archiveHitList = result;
        archiveHitRoot = root;
        archiveHitQuery = query;
        result.getItems().setAll(hits);
        result.setAccessibleText("Search results");
        result.setMinSize(0, 0);
        result.setFixedCellSize(SEARCH_RESULT_HEIGHT);
        result.setPrefHeight((double) SEARCH_RESULT_HEIGHT * hits.size() + 2);
        result.setCellFactory(ignored -> new ListCell<>() {
            private final Label title = styled(new Label(), "message-title");
            private final Label snippet = styled(new Label(), "message-detail");
            private final VBox row = new VBox(4, title, snippet);

            {
                setPrefWidth(0);
                title.setMinWidth(0);
                snippet.setMinWidth(0);
                snippet.setWrapText(true);
                snippet.setMaxHeight(34);
                row.setMinWidth(0);
                row.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(0, widthProperty().subtract(23)));
                setOnMouseClicked(event -> {
                    if (!isEmpty() && event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                        activateArchiveResult(getIndex());
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(ViewerSearch.Hit hit, boolean empty) {
                super.updateItem(hit, empty);
                if (empty || hit == null) {
                    setGraphic(null);
                    setAccessibleText(null);
                } else {
                    var name = hit.path().isEmpty() ? root.name : hit.path().getLast().replaceFirst("^<\\d+>", "");
                    title.setText(name + " · Page " + (hit.page() + 1));
                    snippet.setText(hit.snippet());
                    setAccessibleText(title.getText() + ". " + hit.snippet());
                    setGraphic(row);
                }
            }
        });
        result.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isAltDown() || event.isControlDown() || event.isMetaDown()) return;
            switch (event.getCode()) {
                case UP, DOWN -> moveArchiveResult(event.getCode() == KeyCode.DOWN ? 1 : -1);
                case ENTER, SPACE -> activateArchiveResult(result.getSelectionModel().getSelectedIndex());
                default -> { return; }
            }
            event.consume();
        });
        VBox.setVgrow(result, Priority.ALWAYS);
        archiveResults.getChildren().setAll(result);
        toggle(archiveResults, true);
    }

    private void moveArchiveResult(int direction) {
        var list = archiveHitList;
        if (list == null || list.getItems().isEmpty()) return;
        int selected = list.getSelectionModel().getSelectedIndex();
        activateArchiveResult(selected < 0 ? direction > 0 ? 0 : list.getItems().size() - 1 : selected + direction);
    }

    private void activateArchiveResult(int index) {
        var list = archiveHitList;
        if (busy || list == null || index < 0 || index >= list.getItems().size()) return;
        list.getSelectionModel().select(index);
        list.getFocusModel().focus(index);
        if (!rowIsVisible(list, ".list-cell", index)) list.scrollTo(index);
        list.requestFocus();
        openArchiveHit(archiveHitRoot, archiveHitQuery, list.getItems().get(index));
    }

    private void clearArchiveResults() {
        archiveHitList = null;
        archiveHitRoot = null;
        archiveHitQuery = null;
        archiveResults.getChildren().clear();
        toggle(archiveResults, false);
    }

    private void openArchiveHit(ViewerSession root, String query, ViewerSearch.Hit hit) {
        if (busy || !containsSession(current, root)) return;
        if (isActiveRoute(root, hit.path())) {
            goPage(hit.page());
            if (!query.isBlank() && !query.equals(view.getSearchText())) searchThisPdf(query);
            return;
        }
        var found = root;
        try {
            for (var key : hit.path()) {
                var asset = ViewerSearch.resolve(found.assets, key);
                if (asset == null) throw new IOException("The search result’s PDF is no longer available.");
                var stream = ViewerSearch.preferredStream(asset);
                if (stream == null) throw new IOException("The search result has no embedded PDF data.");
                var path = found.pdf.extract(stream);
                try {
                    found = ViewerSession.open(path, asset.name(), found, asset);
                } catch (IOException | RuntimeException error) {
                    ViewerSession.deleteTemporaryFile(path);
                    throw error;
                }
            }
            var document = new ViewerRenderingDocument(found.renderingPath.toFile());
            var previousSession = current;
            found.page = hit.page();
            showDocument(found, document, !query.isBlank());
            closeSessions(previousSession, root);
            if (!query.isBlank()) {
                searchThisPdf(query);
            }
        } catch (IOException | RuntimeException failure) {
            closeSessions(found, root);
            error("Could not open search result", failure);
        }
    }

    private boolean isActiveRoute(ViewerSession root, List<String> route) {
        var active = current;
        for (int i = route.size() - 1; i >= 0; i--) {
            if (active == root || active.parent == null
                    || ViewerSearch.resolve(active.parent.assets, route.get(i)) != active.parentAsset) return false;
            active = active.parent;
        }
        return active == root;
    }

    private static ViewerSession rootOf(ViewerSession session) {
        if (session == null) return null;
        while (session.parent != null) session = session.parent;
        return session;
    }

    private static boolean containsSession(ViewerSession session, ViewerSession wanted) {
        for (var value = session; value != null; value = value.parent) {
            if (value == wanted) return true;
        }
        return false;
    }

    private MailNavigation.Message selectedMessage() {
        return message != null ? message : current == null ? null : current.mail.messageAt(viewport.getPage());
    }

    private void saveMessage() {
        var entry = selectedMessage();
        if (entry == null || current == null) {
            report("Select a message to save its source.");
            return;
        }
        Path path = savePath("Save this message as PDF", PdfNavigation.safeFileName(entry.title() + ".pdf"));
        if (path == null) return;
        try {
            ViewerTools.saveMessage(current, entry, path);
            report("Saved " + path.getFileName());
        } catch (IOException | RuntimeException failure) {
            error("Could not save message PDF", failure);
        }
    }

    private void exportCsv() {
        if (current == null) return;
        var options = csvOptions();
        if (options == null) return;
        Path path = savePath("Export message CSV", "messages.csv");
        if (path == null) return;
        try {
            ViewerTools.exportCsv(current, path, options.columns(), options.selected());
            report("Saved " + path.getFileName());
        } catch (IOException | RuntimeException failure) {
            error("Could not export message CSV", failure);
        }
    }

    private void printPdf() {
        if (current == null || busy || viewerInputBlocked || view.isPrinting()) return;
        if (Printer.getDefaultPrinter() != null) {
            view.print(stage);
            return;
        }

        var session = current;
        var saveCopy = new ButtonType("Save PDF copy…", ButtonBar.ButtonData.OK_DONE);
        var dialog = new Alert(Alert.AlertType.INFORMATION,
                "Add a printer in your system settings, or save a copy of the open PDF.",
                saveCopy, ButtonType.CANCEL);
        dialog.initOwner(stage);
        dialog.setTitle("Print PDF");
        dialog.setHeaderText("No printer is configured");
        dialog.getDialogPane().getStylesheets().add(STYLE);
        if (showDialog(dialog).orElse(ButtonType.CANCEL) != saveCopy) return;

        var target = savePath("Save PDF copy", PdfNavigation.safeFileName(session.name));
        if (target == null) return;
        try {
            ViewerTools.savePdfCopy(session, target);
            report("Saved " + target.getFileName());
        } catch (IOException | RuntimeException failure) {
            error("Could not save PDF copy", failure);
        }
    }

    private CsvOptions csvOptions() {
        var selected = selectedMessage();
        var scope = new ComboBox<String>();
        scope.getItems().add("All messages in this PDF");
        if (selected != null) scope.getItems().add("Selected message");
        scope.getSelectionModel().selectFirst();
        var columns = new ArrayList<CheckBox>();
        for (String key : ViewerTools.CSV_FIELDS) {
            var box = new CheckBox(key);
            box.setSelected(true);
            columns.add(box);
        }
        var fields = new FlowPane(12, 10);
        fields.setPrefWrapLength(460);
        fields.getChildren().addAll(columns);
        var dialog = new Dialog<CsvOptions>();
        dialog.initOwner(stage);
        dialog.setTitle("Export message CSV");
        dialog.getDialogPane().getStylesheets().add(STYLE);
        dialog.getDialogPane().setContent(new VBox(14, scope, fields));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(
                javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> columns.stream().noneMatch(CheckBox::isSelected),
                        columns.stream().map(CheckBox::selectedProperty).toArray(javafx.beans.Observable[]::new)));
        dialog.setResultConverter(button -> button != ButtonType.OK ? null : new CsvOptions(
                columns.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList(),
                scope.getSelectionModel().getSelectedIndex() == 1 ? selected : null));
        return showDialog(dialog).orElse(null);
    }

    private void exportXmp() {
        if (current == null) return;
        var selected = selectedMessage();
        if (selected != null) {
            var scope = new javafx.scene.control.ChoiceDialog<>("This PDF",
                    "This PDF", "Selected message’s PDF");
            scope.initOwner(stage);
            scope.setTitle("Export original XMP");
            scope.setContentText("Scope");
            scope.getDialogPane().getStylesheets().add(STYLE);
            var choice = showDialog(scope);
            if (choice.isEmpty()) return;
            if ("This PDF".equals(choice.get())) selected = null;
        }
        Path path = savePath("Export original XMP", "metadata.xmp");
        if (path == null) return;
        try {
            ViewerTools.exportXmp(current, path, selected);
            report("Saved " + path.getFileName());
        } catch (IOException | RuntimeException failure) {
            error("Could not export XMP", failure);
        }
    }

    private void collectXmp() {
        if (current == null || busy) return;
        var session = current;
        long token = ++request;
        setBusy(true);
        report("Reading original XMP streams…");
        Thread.ofVirtual().start(() -> {
            try {
                var result = ViewerXmp.collect(session);
                Platform.runLater(() -> {
                    if (token == request) {
                        setBusy(false);
                        showXmpStreams(session, result);
                    }
                });
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> {
                    if (token == request) {
                        setBusy(false);
                        error("Could not read XMP streams", failure);
                    }
                });
            }
        });
    }

    private void showXmpStreams(ViewerSession source, ViewerXmp.Result result) {
        if (result.packets().isEmpty()) {
            report(result.issues().isEmpty() ? "No XMP streams are recorded." : result.issues().getFirst());
            return;
        }
        var list = styled(new ListView<ViewerXmp.Packet>(), "sidebar-list");
        list.getItems().setAll(result.packets());
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setAccessibleText("XMP streams");
        list.setMinWidth(0);
        list.setCellFactory(ignored -> new ListCell<>() {
            {
                setPrefWidth(0);
                setWrapText(true);
            }

            @Override
            protected void updateItem(ViewerXmp.Packet value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value.toString());
            }
        });
        var text = xmpPreview();
        list.getSelectionModel().selectedItemProperty().addListener((obs, previous, packet) ->
                text.setText(packet == null ? "" : ViewerXmp.preview(packet)));
        list.getSelectionModel().selectAll();
        list.getSelectionModel().select(0);
        var preview = button("Preview", "Preview the selected XMP stream", () -> {
            var packet = list.getSelectionModel().getSelectedItem();
            if (packet == null) return;
            var larger = xmpPreview();
            larger.setText(text.getText());
            showWindow(packet.toString(), larger, 1080, 780);
        });
        preview.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        var save = button("Save ZIP…", "Save the selected XMP streams with their manifest", () -> {
            var selected = List.copyOf(list.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) return;
            var path = savePath("Export XMP streams", "metadata.zip");
            if (path == null) return;
            try {
                if (isSourcePath(path, source)) {
                    report("Choose a different filename to preserve the source PDF.");
                    return;
                }
                ViewerXmp.save(path, result, selected);
                report("Saved " + path.getFileName());
            } catch (IOException | RuntimeException failure) {
                error("Could not save XMP streams", failure);
            }
        });
        save.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(list.getSelectionModel().getSelectedItems()));
        String note = result.packets().size() + " XMP streams";
        if (!result.issues().isEmpty()) note += " · Some streams could not be read";
        var panes = new SplitPane(list, text);
        panes.setDividerPositions(.33);
        VBox.setVgrow(panes, Priority.ALWAYS);
        showWindow("Browse and select XMP streams",
                styled(new VBox(12, new Label(note), panes, group(preview, save)), "xmp-browser"), 1220, 790);
        report(note);
    }

    private static TextArea xmpPreview() {
        var text = styled(new TextArea(), "xmp-preview");
        text.setEditable(false);
        text.setWrapText(true);
        text.setAccessibleText("Original XMP preview");
        return text;
    }

    private Path savePath(String title, String name) {
        var path = choosePath(title, name);
        if (path == null) return null;
        var target = path.toAbsolutePath().normalize();
        try {
            if (isSourcePath(target, current)) {
                report("Choose a different filename to preserve the open PDF.");
                return null;
            }
        } catch (IOException error) {
            report(messageOf(error));
            return null;
        }
        return target;
    }

    private static boolean isSourcePath(Path target, ViewerSession source) throws IOException {
        for (var session = source; session != null; session = session.parent) {
            if (target.equals(session.path.toAbsolutePath().normalize())
                    || (Files.exists(target) && Files.exists(session.path) && Files.isSameFile(target, session.path))) {
                return true;
            }
        }
        return false;
    }

    private void showWindow(String title, Node content, int width, int height) {
        var popup = new Stage();
        popup.initOwner(stage);
        popup.setTitle(title);
        var root = styled(new BorderPane(content), "preview-window");
        var screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        var bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        var scene = new Scene(root,
                Math.clamp(width, 1.0, Math.max(1.0, bounds.getWidth() - 64)),
                Math.clamp(height, 1.0, Math.max(1.0, bounds.getHeight() - 64)));
        scene.getStylesheets().add(STYLE);
        filterViewerInput(popup, scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && !viewerInputBlocked) {
                event.consume();
                popup.close();
            }
        });
        popup.setScene(scene);
        popup.show();
    }

    private void report(String message) {
        status.setText(message);
    }

    private void error(String title, Exception failure) {
        showError(title, messageOf(failure));
    }

    private void showError(String title, String message) {
        report(message);
        var alert = new Alert(Alert.AlertType.ERROR, message);
        alert.initOwner(stage);
        if (avoidNativeModality()) alert.initModality(Modality.NONE);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.getDialogPane().getStylesheets().add(STYLE);
        alert.show();
    }

    private static String messageOf(Throwable error) {
        var actual = error.getCause() == null ? error : error.getCause();
        return actual.getLocalizedMessage() == null ? actual.getClass().getSimpleName() : actual.getLocalizedMessage();
    }

    private static boolean matches(String value, String query) {
        if (query == null || query.isBlank()) return true;
        String haystack = " " + value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        for (String word : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank() && !haystack.contains(" " + word + " ")) return false;
        }
        return true;
    }

    private static MenuItem menu(String label, Runnable action) {
        var item = new MenuItem(label);
        item.setOnAction(event -> action.run());
        return item;
    }

    private static Button button(String label, String tooltip, Runnable action) {
        var button = new Button(label);
        button.setAccessibleText(tooltip);
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> action.run());
        return button;
    }

    private static void iconButton(Button button, String glyph) {
        if (!button.getStyleClass().contains("icon-button")) button.getStyleClass().add("icon-button");
        button.setGraphic(ViewerGlyph.icon(glyph, 18));
        button.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
    }

    private static HBox group(Node... children) {
        var row = styled(new HBox(7, children), "toolbar-group");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Region spacer() {
        var region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static <T extends Node> T styled(T node, String... classes) {
        node.getStyleClass().addAll(classes);
        return node;
    }

    private static void toggle(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static String viewerVersion() {
        try (var input = ViewerApplication.class.getResourceAsStream("viewer-build.properties")) {
            if (input != null) {
                var build = new Properties();
                build.load(input);
                String version = build.getProperty("version", "");
                if (!version.isBlank() && !version.startsWith("$")) return version;
            }
        } catch (IOException unavailable) {
            // An unpacked development run may not include filtered resources.
        }
        return "development";
    }

    private record NavigationRow(String title, String detail, MailNavigation.Message message,
                                 PdfNavigation.Asset asset, int page, Integer folder, List<String> route, boolean overview) {
        NavigationRow(String title, String detail, MailNavigation.Message message,
                      PdfNavigation.Asset asset, int page, Integer folder) {
            this(title, detail, message, asset, page, folder, List.of(), false);
        }

        NavigationRow(String title, String detail, MailNavigation.Message message,
                      PdfNavigation.Asset asset, int page, Integer folder, List<String> route) {
            this(title, detail, message, asset, page, folder, route, false);
        }
    }

    private record FileRow(String heading, PdfNavigation.Asset asset) {}

    private record PageTarget(Integer page, String name) {}

    private record FolderStep(ViewerSession parent, List<PdfNavigation.Asset> siblings, int index, int direction) {
        FolderStep advance() { return new FolderStep(parent, siblings, index + direction, direction); }
    }

    private record CsvOptions(List<String> columns, MailNavigation.Message selected) {}
}
