package com.pvb.smperf.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * First-run guided tour: a dimmed layer over the whole window with a spotlight on one part of the
 * UI at a time and a callout card with Back / Next / Skip.
 *
 * Like the About box it is drawn INSIDE the main window (no extra Stage), so no window manager
 * can minimise or un-maximise the app. While it is open the app underneath is blocked.
 *
 * Keys: Right / Enter / Space = next, Left = back, Esc = skip.
 *
 * The tour knows nothing about SMPerf itself; the step list is supplied by the caller.
 */
public final class GuidedTour {

    /** One stop on the tour. A null target (or one that is not on screen) gives a centred card. */
    public record Step(String title, String body, Supplier<Node> target, Runnable before) {
        public Step(String title, String body, Supplier<Node> target) {
            this(title, body, target, () -> {});
        }

        public static Step centered(String title, String body, Runnable before) {
            return new Step(title, body, () -> null, before);
        }
    }

    // ------------------------------------------------------------ "already seen" flag

    private static final String PREF_KEY = "tourCompleted";

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(GuidedTour.class);
    }

    /** True until the user has finished or skipped the tour once. */
    public static boolean shouldAutoStart() {
        try {
            return !prefs().getBoolean(PREF_KEY, false);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void markSeen() {
        try {
            prefs().putBoolean(PREF_KEY, true);
            prefs().flush();
        } catch (Exception ignored) {
            // preferences unavailable: the tour simply shows again next time
        }
    }

    // ------------------------------------------------------------ layout constants

    private static final double CARD_W = 360;
    private static final double HOLE_PAD = 6;
    private static final double MARGIN = 14;
    private static final Color DIM = Color.rgb(12, 16, 24, 0.62);

    // ------------------------------------------------------------ state

    private final Pane host;                       // the scene root (a StackPane)
    private final Pane layer = new Pane();
    private final Rectangle ring = new Rectangle();
    private final VBox card = new VBox(8);
    private final Label stepLabel = new Label();
    private final Label title = new Label();
    private final Label body = new Label();
    private final HBox dots = new HBox(5);
    private final Button skip = new Button("Skip tour");
    private final Button back = new Button("‹ Back");
    private final Button next = new Button("Next ›");

    private final EventHandler<KeyEvent> keyFilter = this::onKey;
    private final ChangeListener<Number> sizeListener = (o, was, now) -> render();

    private List<Step> steps = List.of();
    private int index;
    private boolean active;
    private Runnable onClose = () -> {};

    public GuidedTour(Pane host) {
        this.host = host;

        layer.getStyleClass().add("tour-layer");
        layer.setPickOnBounds(true);                                  // block the app underneath,
        layer.addEventHandler(MouseEvent.ANY, Event::consume);        // including the spotlit part
        layer.addEventHandler(ScrollEvent.ANY, Event::consume);

        ring.setFill(Color.TRANSPARENT);
        ring.setStroke(Color.WHITE);
        ring.setStrokeWidth(2);
        ring.setArcWidth(10);
        ring.setArcHeight(10);
        ring.setMouseTransparent(true);
        ring.setEffect(new DropShadow(14, Color.rgb(43, 94, 167, 0.95)));

        stepLabel.getStyleClass().add("tour-step");
        title.getStyleClass().add("tour-title");
        title.setWrapText(true);
        body.getStyleClass().add("tour-body");
        body.setWrapText(true);
        dots.setAlignment(Pos.CENTER_LEFT);

        skip.getStyleClass().add("tour-skip");
        skip.setFocusTraversable(false);
        skip.setOnAction(e -> finish());
        back.setOnAction(e -> back());
        back.setMinWidth(78);
        next.getStyleClass().add("primary-button");
        next.setMinWidth(104);
        next.setDefaultButton(true);
        next.setOnAction(e -> next());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, skip, spacer, back, next);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(6, 0, 0, 0));

        card.getStyleClass().add("tour-card");
        card.setPrefWidth(CARD_W);
        card.setMinWidth(CARD_W);
        card.setMaxWidth(CARD_W);
        card.getChildren().addAll(stepLabel, title, body, dots, buttons);
    }

    // ------------------------------------------------------------ public API

    public boolean isActive() {
        return active;
    }

    /** Starts the tour at step 1. {@code onClose} runs after it ends (finished or skipped). */
    public void start(List<Step> tourSteps, Runnable onClose) {
        if (active || tourSteps == null || tourSteps.isEmpty()) return;
        this.steps = List.copyOf(tourSteps);
        this.onClose = onClose == null ? () -> {} : onClose;
        this.index = 0;
        this.active = true;

        dots.getChildren().clear();
        for (int i = 0; i < steps.size(); i++) {
            Region dot = new Region();
            dot.getStyleClass().add("tour-dot");
            dots.getChildren().add(dot);
        }

        layer.getChildren().setAll(new Rectangle(), ring, card);      // slot 0 is the scrim
        host.getChildren().add(layer);
        Scene scene = host.getScene();
        if (scene != null) scene.addEventFilter(KeyEvent.ANY, keyFilter);
        host.widthProperty().addListener(sizeListener);
        host.heightProperty().addListener(sizeListener);

        show(0);
    }

    // ------------------------------------------------------------ navigation

    private void show(int i) {
        index = i;
        try {
            steps.get(i).before().run();                              // e.g. switch tab
        } catch (RuntimeException ignored) {
            // a failing "before" must never trap the user inside the tour
        }
        host.applyCss();
        host.layout();                                                // so target bounds are current
        render();
        next.requestFocus();
        Platform.runLater(this::render);                              // once more after lazy layout
    }

    private void next() {
        if (index >= steps.size() - 1) finish();
        else show(index + 1);
    }

    private void back() {
        if (index > 0) show(index - 1);
    }

    /** Ends the tour (Finish or Skip). Either way it will not auto-start again. */
    private void finish() {
        if (!active) return;
        active = false;
        markSeen();

        host.widthProperty().removeListener(sizeListener);
        host.heightProperty().removeListener(sizeListener);
        Scene scene = host.getScene();
        if (scene != null) scene.removeEventFilter(KeyEvent.ANY, keyFilter);
        host.getChildren().remove(layer);
        layer.getChildren().clear();
        try {
            onClose.run();
        } catch (RuntimeException ignored) {
            // nothing sensible to do
        }
    }

    private void onKey(KeyEvent e) {
        if (!active) return;
        if (e.getEventType() == KeyEvent.KEY_PRESSED) {
            switch (e.getCode()) {
                case RIGHT, ENTER, SPACE, PAGE_DOWN -> next();
                case LEFT, PAGE_UP -> back();
                case ESCAPE -> finish();
                default -> { }
            }
        }
        e.consume();                      // nothing reaches the app underneath while the tour is open
    }

    // ------------------------------------------------------------ drawing

    private void render() {
        if (!active) return;
        double w = host.getWidth(), h = host.getHeight();
        if (w <= 0 || h <= 0) return;

        Step s = steps.get(index);
        boolean first = index == 0, last = index == steps.size() - 1;

        stepLabel.setText("Step " + (index + 1) + " of " + steps.size());
        title.setText(s.title());
        body.setText(s.body());
        back.setDisable(first);
        next.setText(first ? "Start tour" : last ? "Finish" : "Next ›");
        skip.setVisible(!last);
        skip.setManaged(!last);
        for (int i = 0; i < dots.getChildren().size(); i++) {
            Node dot = dots.getChildren().get(i);
            dot.getStyleClass().remove("tour-dot-on");
            if (i == index) dot.getStyleClass().add("tour-dot-on");
        }

        Bounds hole = holeFor(s, w, h);

        // dim everything except the spotlight
        Shape scrim = new Rectangle(0, 0, w, h);
        if (hole != null) {
            Rectangle cut = new Rectangle(hole.getMinX(), hole.getMinY(), hole.getWidth(), hole.getHeight());
            cut.setArcWidth(10);
            cut.setArcHeight(10);
            scrim = Shape.subtract(scrim, cut);
        }
        scrim.setFill(DIM);
        scrim.setStroke(null);
        scrim.setMouseTransparent(true);
        layer.getChildren().set(0, scrim);

        ring.setVisible(hole != null);
        if (hole != null) {
            ring.setX(hole.getMinX());
            ring.setY(hole.getMinY());
            ring.setWidth(hole.getWidth());
            ring.setHeight(hole.getHeight());
        }

        placeCard(hole, w, h);
    }

    /** The highlighted area in layer coordinates, or null for a centred card. */
    private Bounds holeFor(Step s, double w, double h) {
        Node t;
        try {
            t = s.target().get();
        } catch (RuntimeException e) {
            return null;
        }
        if (t == null || t.getScene() == null || !t.isVisible()) return null;

        Bounds b = layer.sceneToLocal(t.localToScene(t.getBoundsInLocal()));
        if (b == null || b.getWidth() < 1 || b.getHeight() < 1) return null;

        double x1 = Math.max(0, b.getMinX() - HOLE_PAD), y1 = Math.max(0, b.getMinY() - HOLE_PAD);
        double x2 = Math.min(w, b.getMaxX() + HOLE_PAD), y2 = Math.min(h, b.getMaxY() + HOLE_PAD);
        if (x2 - x1 < 1 || y2 - y1 < 1) return null;
        return new javafx.geometry.BoundingBox(x1, y1, x2 - x1, y2 - y1);
    }

    private void placeCard(Bounds hole, double w, double h) {
        card.applyCss();
        double ch = card.prefHeight(CARD_W);
        double x, y;

        if (hole == null) {
            x = (w - CARD_W) / 2;
            y = (h - ch) / 2;
        } else if (h - hole.getMaxY() >= ch + 2 * MARGIN) {            // room below
            y = hole.getMaxY() + MARGIN;
            x = clamp(hole.getMinX(), MARGIN, w - CARD_W - MARGIN);
        } else if (hole.getMinY() >= ch + 2 * MARGIN) {                // room above
            y = hole.getMinY() - MARGIN - ch;
            x = clamp(hole.getMinX(), MARGIN, w - CARD_W - MARGIN);
        } else if (w - hole.getMaxX() >= CARD_W + 2 * MARGIN) {        // room to the right
            x = hole.getMaxX() + MARGIN;
            y = clamp(hole.getMinY(), MARGIN, h - ch - MARGIN);
        } else if (hole.getMinX() >= CARD_W + 2 * MARGIN) {            // room to the left
            x = hole.getMinX() - MARGIN - CARD_W;
            y = clamp(hole.getMinY(), MARGIN, h - ch - MARGIN);
        } else {                                                       // tiny window: corner
            x = w - CARD_W - MARGIN;
            y = h - ch - MARGIN;
        }
        card.relocate(clamp(x, 0, w - CARD_W), clamp(y, 0, h - ch));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(v, Math.max(lo, hi)));
    }

    /** Convenience for callers that build step lists. */
    public static List<Step> steps(Step... s) {
        return new ArrayList<>(List.of(s));
    }
}
