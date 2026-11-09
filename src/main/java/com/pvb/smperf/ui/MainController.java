package com.pvb.smperf.ui;

import com.pvb.smperf.engine.EngineClient;
import com.pvb.smperf.engine.EngineEvent;
import com.pvb.smperf.engine.EngineLocator;
import com.pvb.smperf.model.ConfigResult;
import com.pvb.smperf.model.MachineInfo;
import com.pvb.smperf.model.RunConfig;
import com.pvb.smperf.session.SavedConfig;
import com.pvb.smperf.session.SessionService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainController {

    @FXML private BorderPane root;
    @FXML private MenuBar menuBar;
    @FXML private TextField binaryField;
    @FXML private TextArea argumentsField;
    @FXML private TextArea logArea;
    @FXML private ComboBox<String> threadMode;
    @FXML private TextField threadField;
    @FXML private Spinner<Integer> repeats;
    @FXML private Spinner<Integer> warmup;
    @FXML private TextField timeout;
    @FXML private Button runButton;
    @FXML private Button cancelButton;
    @FXML private Label selectedInfo;
    @FXML private Label mCpu;
    @FXML private Label mCores;
    @FXML private Label mMem;
    @FXML private Label footerStatus;      // the one and only status line (bottom bar)
    @FXML private ProgressBar runProgress;

    private EngineClient engine;
    private Task<Void> activeTask;
    private MachineInfo cachedMachineInfo;

    private final ObservableList<ConfigResult> resultData = FXCollections.observableArrayList();
    private EngineEvent.Result baseline;

    // results + topology views (loaded once, shown inside the shared window)
    private Parent resultsRoot;
    private ResultsViewController resultsController;
    private Parent specsRoot;
    private SpecsWindowController specsController;

    // shell: nav tabs + save/open feature
    private Workspace workspace;
    private SessionService session;
    private SessionActions sessionActions;
    private SavedConfig lastSavedConfig;   // snapshot of the config of the run in progress
    @FXML private Label footerResults;
    @FXML private Label footerEngine;
    private TopologyWindow topologyWindow;
    @FXML
    private void initialize() {
        // Spinners need factories
        repeats.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, 5));
        warmup.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, 1));

        threadMode.setItems(FXCollections.observableArrayList("Automatic", "Manual"));
        threadMode.setValue("Automatic");
        threadField.setDisable(true);
        threadMode.setOnAction(e ->
                threadField.setDisable(!"Manual".equals(threadMode.getValue())));

        argumentsField.setPromptText("e.g.  --size  then  1000000  on the next line");
        MenuItem cp = new MenuItem("Copy");        cp.setOnAction(e -> logArea.copy());
        MenuItem sa = new MenuItem("Select All");  sa.setOnAction(e -> logArea.selectAll());
        MenuItem cl = new MenuItem("Clear Log");   cl.setOnAction(e -> logArea.clear());
        logArea.setContextMenu(new ContextMenu(cp, sa, new SeparatorMenuItem(), cl));
        loadResultsView();
        loadSpecsView();
        buildWorkspace();

        // the Results tab shows how many configurations it holds
        resultData.addListener((ListChangeListener<ConfigResult>) c -> {
            workspace.setTabTitle("results",
                    resultData.isEmpty() ? "Results" : "Results (" + resultData.size() + ")");
            footerResults.setText(resultData.isEmpty() ? "No results"
                    : resultData.size() + (resultData.size() == 1 ? " configuration" : " configurations"));
        });

        try {
            engine = new EngineClient(EngineLocator.find());
            loadMachineAsync();
            footerEngine.setText("Engine: ready");

        } catch (Exception ex) {
            setStatus("Engine unavailable");
            footerEngine.setText("Engine: missing");
            runButton.setDisable(true);
            mCpu.setText("Engine not found");
            mCores.setText("-");
            mMem.setText("-");
            specsController.showMessage("Engine not found", "Topology unavailable");
        }
    }

    private static final java.time.format.DateTimeFormatter CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    private void log(String line) {
        logArea.appendText("[" + java.time.LocalTime.now().format(CLOCK) + "] " + line + "\n");
    }

    /** Single place that writes the status bar. */
    private void setStatus(String text) {
        footerStatus.setText(text);
    }

    // ------------------------------------------------------------------
    // Navigation (all views live in the same window / same scene)
    // ------------------------------------------------------------------

    private void loadResultsView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pvb/smperf/ui/results_view.fxml"));
            resultsRoot = loader.load();
            resultsController = loader.getController();
        } catch (Exception e) {
            e.printStackTrace();
            resultsRoot = new Label("Failed to load results view");
        }
    }

    private void loadSpecsView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pvb/smperf/ui/specs_window.fxml"));
            specsRoot = loader.load();
            specsController = loader.getController();
            specsController.showMessage("Detecting…", "Detecting hardware…");
        } catch (Exception e) {
            e.printStackTrace();
            specsRoot = new Label("Failed to load topology view");
        }
    }

    private void buildWorkspace() {
        Node homeNode = root.getCenter();   // capture BEFORE Workspace takes over the center

        session = new SessionService(resultData);
        workspace = new Workspace(root);
        sessionActions = new SessionActions(session, workspace);

        workspace.register(new Workspace.View("home", "Home", () -> homeNode));
        workspace.register(new Workspace.View("results", "Results",
                () -> resultsRoot,
                () -> { if (resultsController != null) resultsController.setResults(resultData); }));
        workspace.register(new Workspace.View("session", "Session",
                () -> new SessionView(session, sessionActions)));
        workspace.register(new Workspace.View("compare", "Compare", () -> new CompareView(session, workspace)));
        // Topology is no longer a tab: it has its own window.

        topologyWindow = new TopologyWindow(specsRoot, workspace);
        AppMenus menus = new AppMenus(root, workspace, session, sessionActions, topologyWindow,
                runButton.disableProperty(), cancelButton.disableProperty(),
                new AppMenus.Hooks(this::chooseBinary, this::startBenchmark, this::cancelBenchmark,
                        () -> logArea.clear(), this::copyLog, this::showAboutDialog, this::exitApp));
        menus.buildMenus(menuBar);                    // must come BEFORE sessionActions.install
        workspace.insertTop(1, menus.buildToolbar()); // between menu bar and tab strip
        sessionActions.install(menuBar);              // adds Open Results / Recent / Save / Export / Clear Results
        sessionActions.installDropTarget(root);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            menuBar.setUseSystemMenuBar(true);        // macOS: use the global menu bar at the top of the screen
        }

        workspace.show("home");
    }

    private void copyLog() {
        javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
        c.putString(logArea.getText());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
        workspace.info("Run log copied to clipboard");
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    @FXML
    private void chooseBinary() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select executable to benchmark");
        Window window = root.getScene().getWindow();
        File selected = chooser.showOpenDialog(window);
        if (selected == null) return;

        Path path = selected.toPath().toAbsolutePath();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            showAlert("Invalid executable", "The selected file must be a regular executable file.");
            return;
        }
        binaryField.setText(path.toString());
        selectedInfo.setText("Selected: " + selected.getName());
    }

    @FXML
    private void startBenchmark() {
        if (engine == null || activeTask != null) return;

        RunConfig config;
        try {
            config = createConfig();
        } catch (Exception ex) {
            showAlert("Invalid configuration", ex.getMessage());
            return;
        }

        resultData.clear();
        baseline = null;
        if (resultsController != null) resultsController.clear();
        session.beginLive(cachedMachineInfo, lastSavedConfig);

        setRunning(true);
        setStatus("Benchmark initializing…");
        logArea.clear();
        log("Target   " + config.binary().getFileName()
                + (config.argsTemplate().isEmpty() ? "" : "  " + String.join(" ", config.argsTemplate())));
        log("Threads  " + (config.threads().isEmpty() ? "automatic" : config.threads().toString())
                + "   ·   " + config.repeats() + " repetitions, " + config.warmup() + " warmup");
        if (cachedMachineInfo != null && !cachedMachineInfo.pinningSupported()) {
            log("Note     CPU pinning is not supported on this OS; only the thread count varies");
        }

        activeTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                engine.run(config, event -> Platform.runLater(() -> handleEvent(event)));
                return null;
            }
        };

        activeTask.setOnSucceeded(e -> {
            setRunning(false);
            setStatus("Finished · " + resultData.size() + (resultData.size() == 1 ? " configuration" : " configurations"));
            activeTask = null;
        });
        activeTask.setOnFailed(e -> {
            Throwable error = activeTask.getException();
            setRunning(false);
            setStatus("Benchmark failed");
            String why = error == null ? "Unknown error" : error.getMessage();
            log("FAILED: " + why);
            showAlert("Engine error", why);
            activeTask = null;
        });
        activeTask.setOnCancelled(e -> {
            setRunning(false);
            setStatus("Benchmark cancelled");
            activeTask = null;
        });

        Thread worker = new Thread(activeTask, "benchmark-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void cancelBenchmark() {
        if (engine == null || activeTask == null) return;
        setStatus("Cancellation requested…");
        cancelButton.setDisable(true);
        engine.cancel();
    }

    /**
     * About box, drawn INSIDE the window. (A separate Alert/Stage made some window managers
     * minimise or un-maximise the main window; nothing here opens a second window.)
     */
    @FXML
    private void showAboutDialog() {
        Label name = new Label("SMPerf 1.0");
        name.getStyleClass().add("about-name");
        Label what = new Label("CPU Performance & Scaling Analyzer");
        Label how = new Label("Runs a program at several thread counts, pins every run to specific CPUs "
                + "and shows wall time, speedup and efficiency. All measuring is done by the native "
                + "mperf engine; this window only draws the results.");
        how.setWrapText(true);
        how.setMaxWidth(360);
        how.getStyleClass().add("muted");
        workspace.showDialog("About SMPerf", new VBox(6, name, what, how));
    }

    @FXML
    private void exitApp() {
        Platform.exit();
    }

    // ------------------------------------------------------------------
    // Engine helpers
    // ------------------------------------------------------------------

    private void loadMachineAsync() {
        Task<MachineInfo> task = new Task<>() {
            @Override
            protected MachineInfo call() throws Exception {
                return engine.fetchMachine();
            }
        };
        task.setOnSucceeded(e -> {
            cachedMachineInfo = task.getValue();
            updateMachineSummary(cachedMachineInfo);
            specsController.populate(cachedMachineInfo);
        });
        task.setOnFailed(e -> {
            mCpu.setText("Could not detect topology");
            specsController.showMessage("Could not detect topology", "Detection failed");
        });
        Thread t = new Thread(task, "machine-loader");
        t.setDaemon(true);
        t.start();
    }

    private void updateMachineSummary(MachineInfo m) {
        if (m == null) return;
        double gib = m.ramBytes() / (1024.0 * 1024 * 1024);
        long p = m.cpus().stream().filter(c -> "performance".equals(c.type())).map(MachineInfo.Cpu::physicalCore).distinct().count();
        long eff = m.cpus().stream().filter(c -> "efficiency".equals(c.type())).map(MachineInfo.Cpu::physicalCore).distinct().count();
        String mix = (p > 0 && eff > 0) ? String.format(Locale.ROOT, "  (%d P + %d E)", p, eff) : "";
        mCpu.setText(m.model());
        mCores.setText(String.format(Locale.ROOT, "%d%s / %d threads", m.physicalCount(), mix, m.logicalCount()));
        mMem.setText(String.format(Locale.ROOT, "%.1f GiB", gib));
    }

    private RunConfig createConfig() {
        String path = binaryField.getText().trim();
        if (path.isEmpty()) throw new IllegalArgumentException("Select a target executable first.");
        Path binary = Path.of(path);
        if (!Files.isExecutable(binary)) throw new IllegalArgumentException("Target is not executable.");

        List<Integer> threads = new ArrayList<>();
        if ("Manual".equals(threadMode.getValue())) {
            String input = threadField.getText().trim();
            if (input.isEmpty()) throw new IllegalArgumentException("Enter thread counts.");
            for (String value : input.split(",")) {
                try {
                    int t = Integer.parseInt(value.trim());
                    if (t < 1) throw new NumberFormatException();
                    threads.add(t);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Thread counts must be positive integers separated by commas.");
                }
            }
        }

        List<String> args = new ArrayList<>();
        String text = argumentsField.getText();
        if (text != null && !text.isBlank()) {
            for (String line : text.split("\\R")) {
                if (!line.isEmpty()) args.add(line);
            }
        }

        double timeoutValue;
        try {
            timeoutValue = Double.parseDouble(timeout.getText().trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid timeout value.");
        }

        // snapshot for the session file (empty thread list = automatic)
        lastSavedConfig = new SavedConfig(binary.toString(), List.copyOf(args), List.copyOf(threads),
                repeats.getValue(), warmup.getValue(), timeoutValue);

        return new RunConfig(binary, args, threads, repeats.getValue(), warmup.getValue(), timeoutValue);
    }

    private void handleEvent(EngineEvent event) {
        if (event instanceof EngineEvent.Result r) {
            ConfigResult result = ConfigResult.of(r, baseline);
            if (!r.allFailed() && baseline == null) {
                baseline = r;
                result = ConfigResult.of(r, baseline);
            }
            resultData.add(result);
            setStatus(String.format(Locale.ROOT, "%d threads completed · %d/%d successful",
                    r.threads(), r.runs() - r.failed(), r.runs()));
            log(String.format(Locale.ROOT, "%3d threads   %d/%d ok   best %.4f s   mean %.4f s   speedup %s",
                    r.threads(), r.runs() - r.failed(), r.runs(), r.wallBest(), r.wallMean(),
                    Double.isNaN(result.speedup()) ? "—" : String.format(Locale.ROOT, "%.2f×", result.speedup())));
        } else if (event instanceof EngineEvent.Failure f) {
            setStatus("Error at " + f.threads() + " threads: " + f.message());
            log("ERROR at " + f.threads() + " threads: " + f.message());
        } else if (event instanceof EngineEvent.Done d) {
            String end = d.cancelled() ? "Benchmark cancelled"
                    : d.ok() ? "All configurations completed" : "Benchmark terminated with errors";
            setStatus(end);
            log(end);
        }
    }

    private void setRunning(boolean running) {
        session.busy().set(running);   // disables open/save/clear while the engine is working
        runButton.setDisable(running);
        cancelButton.setDisable(!running);
        runProgress.setVisible(running);
        runProgress.setManaged(running);
        binaryField.setDisable(running);
        argumentsField.setDisable(running);
        threadMode.setDisable(running);
        threadField.setDisable(running || !"Manual".equals(threadMode.getValue()));
        repeats.setDisable(running);
        warmup.setDisable(running);
        timeout.setDisable(running);
    }

    /** Errors now show in the in-window message bar instead of a pop-up dialog. */
    private void showAlert(String title, String message) {
        workspace.error(title + ": " + message);
    }
}