package com.pvb.smperf.ui;

import com.pvb.smperf.session.SessionService;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the full menu bar + toolbar. Add features by adding items here. */
public final class AppMenus {

    public record Hooks(Runnable openExecutable, Runnable run, Runnable cancel,
                        Runnable clearLog, Runnable copyLog, Runnable about, Runnable exit,
                        Runnable tour) {}

    private static final String REPO = "https://github.com/ParthBedekar/smperf";
    private static final double DEFAULT_FONT = 13;

    private final BorderPane root;
    private final Workspace ws;
    private final SessionService session;
    private final SessionActions actions;
    private final TopologyWindow topology;
    private final ObservableBooleanValue runDisabled;
    private final ObservableBooleanValue cancelDisabled;
    private final Hooks h;
    private double fontSize = DEFAULT_FONT;
    private HBox toolbar;

    public AppMenus(BorderPane root, Workspace ws, SessionService session, SessionActions actions,
                    TopologyWindow topology, ObservableBooleanValue runDisabled,
                    ObservableBooleanValue cancelDisabled, Hooks h) {
        this.root = root;
        this.ws = ws;
        this.session = session;
        this.actions = actions;
        this.topology = topology;
        this.runDisabled = runDisabled;
        this.cancelDisabled = cancelDisabled;
        this.h = h;
    }

    // ------------------------------------------------------------------ menus

    public void buildMenus(MenuBar bar) {
        bar.getMenus().setAll(fileMenu(), editMenu(), viewMenu(), runMenu(), toolsMenu(), helpMenu());
    }

    private Menu fileMenu() {
        // SessionActions.install() inserts Open Results / Recent / Save / Export after item 0.
        return new Menu("File", null,
                item("Open Executable…", "Shortcut+Shift+O", h.openExecutable()),
                new SeparatorMenuItem(),
                item("Exit", "Shortcut+Q", h.exit()));
    }

    private Menu editMenu() {
        // SessionActions.install() appends "Clear Results" here.
        return new Menu("Edit", null,
                item("Clear Log", null, h.clearLog()),
                item("Copy Log", "Shortcut+Shift+C", h.copyLog()),
                new SeparatorMenuItem());
    }

    private Menu viewMenu() {
        CheckMenuItem tb = new CheckMenuItem("Toolbar");
        tb.setSelected(true);
        tb.setOnAction(e -> { if (toolbar != null) toolbar.setVisible(tb.isSelected()); });

        CheckMenuItem sb = new CheckMenuItem("Status Bar");
        sb.setSelected(true);
        Node status = root.getBottom();
        if (status != null) {
            status.managedProperty().bind(status.visibleProperty());
            sb.selectedProperty().bindBidirectional(status.visibleProperty());
        }

        return new Menu("View", null,
                item("Home", "Shortcut+1", () -> ws.show("home")),
                item("Results", "Shortcut+2", () -> ws.show("results")),
                item("Session", "Shortcut+3", () -> ws.show("session")),
                item("Compare", "Shortcut+4", () -> ws.show("compare")),
                new SeparatorMenuItem(),
                item("CPU Topology…", "Shortcut+T", topology::show),
                new SeparatorMenuItem(),
                tb, sb,
                new SeparatorMenuItem(),
                itemKey("Zoom In", key(KeyCode.EQUALS), () -> zoom(+1)),
                itemKey("Zoom Out", key(KeyCode.MINUS), () -> zoom(-1)),
                itemKey("Reset Zoom", key(KeyCode.DIGIT0), () -> { fontSize = DEFAULT_FONT; applyZoom(); }),
                new SeparatorMenuItem(),
                item("Full Screen", "F11", this::toggleFullScreen));
    }

    private Menu runMenu() {
        MenuItem run = item("Run Benchmark", "F5", h.run());
        run.disableProperty().bind(runDisabled);
        MenuItem stop = item("Cancel Run", "Shift+F5", h.cancel());
        stop.disableProperty().bind(cancelDisabled);
        return new Menu("Run", null, run, stop);
    }

    private Menu toolsMenu() {
        return new Menu("Tools", null,
                item("CPU Topology Viewer…", null, topology::show),
                new SeparatorMenuItem(),
                item("Compare Runs…", null, () -> ws.show("compare")),
                item("Benchmark Profiles…", null, () -> soon("Benchmark Profiles")),
                item("Plugins…", null, () -> soon("Plugins")),
                new SeparatorMenuItem(),
                item("Preferences…", "Shortcut+COMMA", () -> soon("Preferences")));
    }

    private Menu helpMenu() {
        return new Menu("Help", null,
                item("Take the Tour", null, h.tour()),
                item("Keyboard Shortcuts", "F1", this::showShortcuts),
                item("Copy Project URL", null, () -> {
                    ClipboardContent c = new ClipboardContent();
                    c.putString(REPO);
                    Clipboard.getSystemClipboard().setContent(c);
                    ws.info("Project URL copied: " + REPO);
                }),
                new SeparatorMenuItem(),
                item("About SMPerf", null, h.about()));
    }

    // ---------------------------------------------------------------- toolbar

    public HBox buildToolbar() {
        Button open = tool("Open…", "Choose the program to benchmark (Ctrl+Shift+O)", h.openExecutable());

        Button run = tool("▶  Run", "Run benchmark (F5)", h.run());
        run.getStyleClass().add("tool-run");
        run.disableProperty().bind(runDisabled);

        Button stop = tool("■  Stop", "Cancel the running benchmark (Shift+F5)", h.cancel());
        stop.getStyleClass().add("tool-stop");
        stop.disableProperty().bind(cancelDisabled);

        Button openRes = tool("Open Results", "Open a saved results file (Ctrl+O)", actions::openDialog);
        openRes.disableProperty().bind(session.busy());

        Button save = tool("Save", "Save results as JSON (Ctrl+S)", actions::saveJsonDialog);
        save.disableProperty().bind(session.unavailable());

        Button csv = tool("Export CSV", "Export results as CSV (Ctrl+E)", actions::exportCsvDialog);
        csv.disableProperty().bind(session.unavailable());

        Button topo = tool("CPU Topology", "Open the topology window (Ctrl+T)", topology::show);
        Button cmp = tool("Compare", "Compare runs (Ctrl+4)", () -> ws.show("compare"));
        toolbar = new HBox(4, open, sep(), run, stop, sep(), openRes, save, csv, sep(), cmp, topo);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.managedProperty().bind(toolbar.visibleProperty());
        return toolbar;
    }

    private Button tool(String text, String tip, Runnable r) {
        Button b = new Button(text);
        b.getStyleClass().add("tool-btn");
        b.setFocusTraversable(false);
        b.setMnemonicParsing(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> r.run());
        return b;
    }

    private static Separator sep() {
        return new Separator(Orientation.VERTICAL);
    }

    // ---------------------------------------------------------------- helpers

    /** Menu item with an accelerator given as a string (or null for none). */
    private static MenuItem item(String text, String accel, Runnable r) {
        MenuItem m = new MenuItem(text);
        if (accel != null) m.setAccelerator(KeyCombination.keyCombination(accel));
        m.setOnAction(e -> r.run());
        return m;
    }

    /** Menu item with a ready-made KeyCombination. */
    private static MenuItem itemKey(String text, KeyCombination accel, Runnable r) {
        MenuItem m = new MenuItem(text);
        m.setAccelerator(accel);
        m.setOnAction(e -> r.run());
        return m;
    }

    private static KeyCombination key(KeyCode c) {
        return new KeyCodeCombination(c, KeyCombination.SHORTCUT_DOWN);
    }

    private void soon(String what) {
        ws.info(what + " isn't implemented yet.");
    }

    private void zoom(double delta) {
        fontSize = Math.max(10, Math.min(20, fontSize + delta));
        applyZoom();
    }

    private void applyZoom() {
        root.setStyle("-fx-font-size: " + fontSize + "px;");
    }

    private void toggleFullScreen() {
        Window w = ws.window();
        if (w instanceof Stage s) s.setFullScreen(!s.isFullScreen());
    }

    private void showShortcuts() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Run benchmark", "F5");
        rows.put("Cancel run", "Shift+F5");
        rows.put("Open executable", "Ctrl+Shift+O");
        rows.put("Open / Save / Export results", "Ctrl+O / S / E");
        rows.put("Home / Results / Session / Compare", "Ctrl+1 / 2 / 3 / 4");
        rows.put("CPU topology window", "Ctrl+T");
        rows.put("Zoom in / out / reset", "Ctrl+= / - / 0");
        rows.put("Full screen", "F11");
        rows.put("Copy run log", "Ctrl+Shift+C");
        GridPane g = new GridPane();
        g.setHgap(28);
        g.setVgap(6);
        int r = 0;
        for (var e : rows.entrySet()) {
            Label k = new Label(e.getKey());
            Label v = new Label(e.getValue());
            v.getStyleClass().add("val");
            g.addRow(r++, k, v);
        }
        ws.showDialog("Keyboard Shortcuts", g);
    }
}