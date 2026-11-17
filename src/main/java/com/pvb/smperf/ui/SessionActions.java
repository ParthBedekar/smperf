package com.pvb.smperf.ui;

import com.pvb.smperf.session.SavedConfig;
import com.pvb.smperf.session.SessionService;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Menu items, shortcuts, file dialogs and drag & drop for saving / opening results. */
public final class SessionActions {

    private final SessionService svc;
    private final Workspace ws;

    private final MenuItem openItem  = new MenuItem("Open Results…");
    private final Menu recentMenu    = new Menu("Open Recent");
    private final MenuItem saveItem  = new MenuItem("Save Results as JSON…");
    private final MenuItem csvItem   = new MenuItem("Export Results as CSV…");
    private final MenuItem clearItem = new MenuItem("Clear Results");
    private final MenuItem clearRecent = new MenuItem("Clear Recent");

    public SessionActions(SessionService svc, Workspace ws) {
        this.svc = svc;
        this.ws = ws;

        openItem.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        saveItem.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        csvItem.setAccelerator(KeyCombination.keyCombination("Shortcut+E"));

        openItem.setOnAction(e -> openDialog());
        saveItem.setOnAction(e -> saveJsonDialog());
        csvItem.setOnAction(e -> exportCsvDialog());
        clearItem.setOnAction(e -> clear());
        clearRecent.setOnAction(e -> svc.recent().clear());

        openItem.disableProperty().bind(svc.busy());
        recentMenu.disableProperty().bind(svc.busy());
        saveItem.disableProperty().bind(svc.unavailable());
        csvItem.disableProperty().bind(svc.unavailable());
        clearItem.disableProperty().bind(svc.unavailable());

        svc.recent().items().addListener((ListChangeListener<Path>) c -> rebuildRecent());
        rebuildRecent();

        // little dot on the Session tab while there are unsaved results
        svc.dirty().addListener((o, was, now) -> ws.setTabTitle("session", now ? "Session •" : "Session"));
    }

    /** Adds the items to File (after "Open Executable…") and creates an Edit menu. */
    public void install(MenuBar bar) {
        Menu file = findOrCreate(bar, "File", 0);
        file.getItems().addAll(Math.min(1, file.getItems().size()),
                List.of(new SeparatorMenuItem(), openItem, recentMenu, saveItem, csvItem));
        Menu edit = findOrCreate(bar, "Edit", 1);
        edit.getItems().add(clearItem);
    }

    private static Menu findOrCreate(MenuBar bar, String name, int index) {
        for (Menu m : bar.getMenus()) if (name.equals(m.getText())) return m;
        Menu m = new Menu(name);
        bar.getMenus().add(Math.min(index, bar.getMenus().size()), m);
        return m;
    }

    private void rebuildRecent() {
        recentMenu.getItems().clear();
        if (svc.recent().items().isEmpty()) {
            MenuItem none = new MenuItem("(empty)");
            none.setDisable(true);
            recentMenu.getItems().add(none);
            return;
        }
        for (Path p : svc.recent().items()) {
            MenuItem mi = new MenuItem(p.getFileName().toString());
            mi.setOnAction(e -> open(p));
            recentMenu.getItems().add(mi);
        }
        recentMenu.getItems().addAll(new SeparatorMenuItem(), clearRecent);
    }

    // ------------------------------------------------------------- open

    public void openDialog() {
        if (busy()) return;
        FileChooser fc = chooser("Open results");
        fc.getExtensionFilters().addAll(
                new ExtensionFilter("SMPerf results (*.json, *.csv)", "*.json", "*.csv"),
                new ExtensionFilter("All files", "*.*"));
        File f = fc.showOpenDialog(ws.window());
        if (f != null) open(f.toPath());
    }

    public void open(Path p) {
        if (busy()) return;
        if (svc.dirty().get())
            ws.ask("The current run has unsaved results. Replace them?", "Replace", () -> doOpen(p));
        else
            doOpen(p);
    }

    private void doOpen(Path p) {
        try {
            int n = svc.open(p);
            svc.recent().setLastDir(p.getParent());
            ws.info("Loaded " + n + (n == 1 ? " configuration" : " configurations") + " from " + p.getFileName());
            ws.show("results");
        } catch (IOException | RuntimeException e) {
            ws.error("Could not open " + p.getFileName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- save

    public void saveJsonDialog() {
        if (busy() || svc.results().isEmpty()) return;
        FileChooser fc = chooser("Save results as JSON");
        fc.getExtensionFilters().add(new ExtensionFilter("SMPerf session (*.json)", "*.json"));
        fc.setInitialFileName(suggestName(".json"));
        File f = fc.showSaveDialog(ws.window());
        if (f == null) return;
        Path p = withExt(f.toPath(), ".json");
        try {
            svc.saveJson(p);
            svc.recent().setLastDir(p.getParent());
            ws.info("Saved " + p.getFileName());
        } catch (IOException e) {
            ws.error("Could not save " + p.getFileName() + ": " + e.getMessage());
        }
    }

    public void exportCsvDialog() {
        if (busy() || svc.results().isEmpty()) return;
        FileChooser fc = chooser("Export results as CSV");
        fc.getExtensionFilters().add(new ExtensionFilter("CSV (*.csv)", "*.csv"));
        fc.setInitialFileName(suggestName(".csv"));
        File f = fc.showSaveDialog(ws.window());
        if (f == null) return;
        Path p = withExt(f.toPath(), ".csv");
        try {
            svc.exportCsv(p);
            svc.recent().setLastDir(p.getParent());
            ws.info("Exported " + p.getFileName());
        } catch (IOException e) {
            ws.error("Could not export " + p.getFileName() + ": " + e.getMessage());
        }
    }

    public void clear() {
        if (busy() || svc.results().isEmpty()) return;
        if (svc.dirty().get()) ws.ask("Discard the unsaved results?", "Discard", this::doClear);
        else doClear();
    }

    private void doClear() {
        svc.clear();
        ws.show(ws.currentId());     // re-run onShow so the open tab refreshes
    }

    // ---------------------------------------------------- drag & drop

    public void installDropTarget(Node n) {
        n.setOnDragOver(this::onDragOver);
        n.setOnDragDropped(this::onDrop);
    }

    public void onDragOver(DragEvent e) {
        if (acceptable(e.getDragboard())) {
            e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        }
    }

    public void onDrop(DragEvent e) {
        Dragboard db = e.getDragboard();
        boolean ok = acceptable(db);
        if (ok) open(db.getFiles().get(0).toPath());
        e.setDropCompleted(ok);
        e.consume();
    }

    private static boolean acceptable(Dragboard db) {
        if (!db.hasFiles() || db.getFiles().size() != 1) return false;
        String n = db.getFiles().get(0).getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".json") || n.endsWith(".csv");
    }

    // ---------------------------------------------------------- helpers

    private boolean busy() {
        if (svc.busy().get()) {
            ws.error("Wait for the benchmark to finish first.");
            return true;
        }
        return false;
    }

    private FileChooser chooser(String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        Path d = svc.recent().lastDir();
        if (d == null || !d.toFile().isDirectory()) {
            Path home = Path.of(System.getProperty("user.home"));
            Path docs = home.resolve("Documents");
            d = docs.toFile().isDirectory() ? docs : home;
        }
        fc.setInitialDirectory(d.toFile());
        return fc;
    }
    private String suggestName(String ext) {
        String base = "results";
        SavedConfig c = svc.config().get();
        if (c != null && c.executable() != null && !c.executable().isBlank()) {
            Path n = Path.of(c.executable()).getFileName();
            if (n != null) base = n.toString();
        }
        return "smperf-" + base + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ext;
    }

    private static Path withExt(Path p, String ext) {
        String n = p.getFileName().toString();
        return n.toLowerCase(Locale.ROOT).endsWith(ext) ? p : p.resolveSibling(n + ext);
    }
}