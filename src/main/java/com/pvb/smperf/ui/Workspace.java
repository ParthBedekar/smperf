package com.pvb.smperf.ui;

import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.*;
import java.util.function.Supplier;

/**
 * Tab strip + view switching inside the SAME scene (no extra stages, so the window manager never
 * gets a chance to minimise / un-maximise the main window), a message bar that replaces pop-up
 * alerts, and an in-window dialog layer that replaces modal pop-up windows.
 * New features = register(new View(...)).
 */
public final class Workspace {

    public record View(String id, String title, Supplier<Node> content, Runnable onShow) {
        public View(String id, String title, Supplier<Node> content) { this(id, title, content, () -> {}); }
    }

    private final BorderPane root;
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, Node> built = new HashMap<>();
    private final Map<String, ToggleButton> tabs = new LinkedHashMap<>();
    private final ToggleGroup group = new ToggleGroup();
    private final HBox strip = new HBox(0);
    private final HBox msgBar = new HBox(8);
    private final Label msgText = new Label();
    private final PauseTransition autoHide = new PauseTransition(Duration.seconds(6));
    private final ReadOnlyStringWrapper current = new ReadOnlyStringWrapper();

    // centre area = page + (optional) dialog layer on top
    private final StackPane page = new StackPane();
    private final StackPane overlay = new StackPane();
    // menu bar + (optional) toolbar + tab strip + message bar; installed as root.top
    private final VBox top = new VBox();

    /** Inserts a node into the top area (0 = above the menu bar, 1 = between menu bar and tabs). */
    public void insertTop(int index, Node n) {
        top.getChildren().add(Math.min(index, top.getChildren().size()), n);
    }
    public Workspace(BorderPane root) {
        this.root = root;
        strip.getStyleClass().add("nav-strip");
        strip.setAlignment(Pos.BOTTOM_LEFT);
        msgBar.getStyleClass().add("msg-bar");
        msgBar.setAlignment(Pos.CENTER_LEFT);
        hideBar();

        if (root.getTop() != null) top.getChildren().add(root.getTop());   // keep the existing MenuBar
        top.getChildren().addAll(strip, msgBar);
        root.setTop(top);

        overlay.getStyleClass().add("overlay");
        overlay.setVisible(false);
        overlay.setManaged(false);
        overlay.setOnMouseClicked(e -> e.consume());     // block clicks on the page underneath
        root.setCenter(new StackPane(page, overlay));

        group.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) { if (old != null) old.setSelected(true); return; }   // never deselect
            activate((String) now.getUserData());
        });
    }

    // ------------------------------------------------------------ views

    public void register(View v) {
        views.put(v.id(), v);
        ToggleButton t = new ToggleButton(v.title());
        t.getStyleClass().add("nav-tab");
        t.setUserData(v.id());
        t.setToggleGroup(group);
        t.setFocusTraversable(false);
        t.setMnemonicParsing(false);
        tabs.put(v.id(), t);
        strip.getChildren().add(t);
    }

    public void show(String id) {
        ToggleButton t = tabs.get(id);
        if (t == null) throw new IllegalArgumentException("unknown view: " + id);
        if (t.isSelected()) activate(id); else t.setSelected(true);
    }

    private void activate(String id) {
        closeDialog();
        View v = views.get(id);
        page.getChildren().setAll(built.computeIfAbsent(id, k -> v.content().get()));
        current.set(id);
        v.onShow().run();            // lets a view refresh itself every time it is opened
    }

    public ReadOnlyStringProperty currentProperty() { return current.getReadOnlyProperty(); }
    public String currentId() { return current.get(); }

    /** The tab button of a view (used by the guided tour to spotlight it), or null. */
    public Node tabNode(String id) {
        return tabs.get(id);
    }

    public void setTabTitle(String id, String text) {
        ToggleButton t = tabs.get(id);
        if (t != null) t.setText(text);
    }

    /** Ctrl+1..n (Cmd on macOS) switches views. Replaces the duplicate entries in the View menu. */
    public void installShortcuts() {
        Runnable install = () -> {
            Scene s = root.getScene();
            if (s == null) return;
            int i = 1;
            for (View v : views.values()) {
                final String id = v.id();
                s.getAccelerators().put(KeyCombination.keyCombination("Shortcut+" + i++), () -> show(id));
            }
        };
        if (root.getScene() != null) install.run();
        else root.sceneProperty().addListener((o, was, now) -> { if (now != null) install.run(); });
    }

    public Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    // ------------------------------------------------------ message bar

    public void info(String text)  { showBar(text, false, false); }
    public void error(String text) { showBar(text, true, false); }

    /** Inline confirmation instead of a modal dialog. */
    public void ask(String text, String yesLabel, Runnable onYes) {
        Button yes = new Button(yesLabel);
        yes.setOnAction(e -> { hideBar(); onYes.run(); });
        Button no = new Button("Cancel");
        no.setOnAction(e -> hideBar());
        showBar(text, false, true, yes, no);
    }

    private void showBar(String text, boolean isError, boolean sticky, Button... actions) {
        autoHide.stop();
        msgText.setText(text);
        msgBar.getStyleClass().remove("msg-error");
        if (isError) msgBar.getStyleClass().add("msg-error");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().add("msg-close");
        close.setOnAction(e -> hideBar());

        msgBar.getChildren().setAll(msgText, spacer);
        msgBar.getChildren().addAll(actions);
        msgBar.getChildren().add(close);
        msgBar.setVisible(true);
        msgBar.setManaged(true);

        if (!sticky) {
            autoHide.setOnFinished(e -> hideBar());
            autoHide.playFromStart();
        }
    }

    private void hideBar() {
        autoHide.stop();
        msgBar.setVisible(false);
        msgBar.setManaged(false);
    }

    // ------------------------------------------------------ dialog layer

    /** A classic dialog box drawn inside the window (title bar, body, OK). Esc / Enter close it. */
    public void showDialog(String title, Node body) {
        Label t = new Label(title);
        t.getStyleClass().add("dlg-title-text");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button x = new Button("✕");
        x.getStyleClass().add("dlg-close");
        x.setFocusTraversable(false);
        x.setOnAction(e -> closeDialog());
        HBox bar = new HBox(6, t, sp, x);
        bar.getStyleClass().add("dlg-title");
        bar.setAlignment(Pos.CENTER_LEFT);

        Button ok = new Button("OK");
        ok.setMinWidth(84);
        ok.setDefaultButton(true);
        ok.setCancelButton(true);
        ok.setOnAction(e -> closeDialog());
        HBox buttons = new HBox(ok);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(14, body, buttons);
        content.setPadding(new Insets(16));

        VBox dialog = new VBox(bar, content);
        dialog.getStyleClass().add("dialog");
        dialog.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        dialog.setMinWidth(380);

        overlay.getChildren().setAll(dialog);
        overlay.setManaged(true);
        overlay.setVisible(true);
        ok.requestFocus();
    }

    public void closeDialog() {
        overlay.setVisible(false);
        overlay.setManaged(false);
        overlay.getChildren().clear();
    }
}
