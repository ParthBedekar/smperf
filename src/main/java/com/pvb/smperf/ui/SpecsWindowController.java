package com.pvb.smperf.ui;

import com.pvb.smperf.model.MachineInfo;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

import java.util.*;
import java.util.stream.Collectors;

/** Controller of the "Topology" tab (it used to be a separate pop-up window). */
public class SpecsWindowController {

    // System group
    @FXML private Label modelLbl;
    @FXML private Label coresLbl;
    @FXML private Label threadsLbl;
    @FXML private Label smtLbl;
    @FXML private Label pkgLbl;
    @FXML private Label ramLbl;
    @FXML private Label clockLbl;
    @FXML private Label llcLbl;
    @FXML private Label statusLbl;

    // Cache table (aggregated: identical caches are collapsed into one row with a quantity)
    @FXML private TableView<CacheRow> cacheTable;
    @FXML private TableColumn<CacheRow, String> colCacheName;
    @FXML private TableColumn<CacheRow, Long> colCacheSize;
    @FXML private TableColumn<CacheRow, Integer> colCacheCount;
    @FXML private TableColumn<CacheRow, String> colCacheShared;

    // Logical CPU table
    @FXML private TableView<MachineInfo.Cpu> cpuTable;
    @FXML private TableColumn<MachineInfo.Cpu, Integer> colCpuId;
    @FXML private TableColumn<MachineInfo.Cpu, Integer> colCpuPhys;
    @FXML private TableColumn<MachineInfo.Cpu, Integer> colCpuPkg;
    @FXML private TableColumn<MachineInfo.Cpu, Integer> colCpuCoreId;
    @FXML private TableColumn<MachineInfo.Cpu, String> colCpuType;
    @FXML private TableColumn<MachineInfo.Cpu, Long> colCpuFreq;
    @FXML private TableColumn<MachineInfo.Cpu, String> colCpuSiblings;

    /** One row of the cache table: N identical caches. */
    private record CacheRow(int level, String type, long sizeBytes, int count, int sharedBy) {
        String label() {
            if (type.equalsIgnoreCase("unified")) return "L" + level;
            if (type.equalsIgnoreCase("instruction")) return "L" + level + " Instr";
            return "L" + level + " " + type;
        }
    }

    private record CacheKey(int level, String type, long size, int sharedBy) {}

    @FXML
    private void initialize() {
        cacheTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        cpuTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        cacheTable.setPlaceholder(new Label("No cache information"));
        cpuTable.setPlaceholder(new Label("No CPU information"));

        // ---- cache table ----
        colCacheName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().label()));
        colCacheSize.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().sizeBytes()));
        colCacheSize.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Long v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : fmtBytes(v));
            }
        });
        colCacheCount.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().count()));
        colCacheShared.setCellValueFactory(d -> {
            int n = d.getValue().sharedBy();
            return new SimpleStringProperty(n == 1 ? "1 CPU" : n + " CPUs");
        });

        // ---- cpu table ----
        colCpuId.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().id()));
        colCpuPhys.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().physicalCore()));
        colCpuPkg.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().pkg()));
        colCpuCoreId.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().coreId()));
        colCpuType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().type()));
        colCpuFreq.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().maxKhz()));
        colCpuSiblings.setCellValueFactory(d -> new SimpleStringProperty(ranges(d.getValue().siblings())));

        colCpuType.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String t, boolean empty) {
                super.updateItem(t, empty);
                getStyleClass().removeAll("type-performance", "type-efficiency", "type-uniform");
                if (empty || t == null) {
                    setText(null);
                    return;
                }
                setText(t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1));
                getStyleClass().add("type-" + t);
            }
        });

        colCpuFreq.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Long khz, boolean empty) {
                super.updateItem(khz, empty);
                setText(empty || khz == null ? null : ghz(khz));
            }
        });

        // shade rows per physical core so SMT siblings look like one block
        cpuTable.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(MachineInfo.Cpu c, boolean empty) {
                super.updateItem(c, empty);
                getStyleClass().remove("core-alt");
                if (!empty && c != null && c.physicalCore() % 2 == 1) {
                    getStyleClass().add("core-alt");
                }
            }
        });
    }

    /** Shown while the engine is still detecting, or when it could not. */
    public void showMessage(String model, String status) {
        modelLbl.setText(model);
        statusLbl.setText(status);
    }

    public void populate(MachineInfo m) {
        if (m == null) {
            showMessage("No machine data available", "Detection failed");
            return;
        }

        List<MachineInfo.Cpu> cpus = m.cpus();
        modelLbl.setText(m.model());

        // group logical CPUs by physical core
        Map<Integer, List<MachineInfo.Cpu>> byCore = cpus.stream().collect(
                Collectors.groupingBy(MachineInfo.Cpu::physicalCore, LinkedHashMap::new, Collectors.toList()));

        long pCores = byCore.values().stream().filter(c -> isType(c.get(0), "performance")).count();
        long eCores = byCore.values().stream().filter(c -> isType(c.get(0), "efficiency")).count();
        boolean hybrid = pCores > 0 && eCores > 0;

        coresLbl.setText(m.physicalCount() + (hybrid ? String.format(Locale.ROOT, "  (%d P + %d E)", pCores, eCores) : ""));
        threadsLbl.setText(String.valueOf(m.logicalCount()));

        // SMT
        List<List<MachineInfo.Cpu>> smtCores = byCore.values().stream().filter(c -> c.size() > 1).toList();
        if (smtCores.isEmpty()) {
            smtLbl.setText("Off / not present");
        } else if (smtCores.size() == byCore.size()) {
            smtLbl.setText("On, all cores");
        } else if (smtCores.stream().allMatch(c -> isType(c.get(0), "performance"))) {
            smtLbl.setText("On, P-cores only");
        } else {
            smtLbl.setText("On, " + smtCores.size() + " of " + byCore.size() + " cores");
        }

        pkgLbl.setText(String.valueOf(cpus.stream().map(MachineInfo.Cpu::pkg).distinct().count()));
        ramLbl.setText(String.format(Locale.ROOT, "%.1f GiB", m.ramBytes() / (1024.0 * 1024 * 1024)));

        // clocks
        long pMax = maxKhz(cpus, "performance");
        long eMax = maxKhz(cpus, "efficiency");
        long allMax = cpus.stream().mapToLong(MachineInfo.Cpu::maxKhz).max().orElse(-1);
        if (hybrid && pMax > 0 && eMax > 0) {
            clockLbl.setText("P " + ghz(pMax) + " / E " + ghz(eMax));
        } else {
            clockLbl.setText(ghz(allMax));
        }

        // caches: aggregate identical ones
        Map<CacheKey, Integer> counts = new HashMap<>();
        for (MachineInfo.Cache c : m.caches()) {
            CacheKey k = new CacheKey(c.level(), c.type(), c.sizeBytes(), c.sharedCpus().size());
            counts.merge(k, 1, Integer::sum);
        }
        List<CacheRow> rows = counts.entrySet().stream()
                .map(e -> new CacheRow(e.getKey().level(), e.getKey().type(), e.getKey().size(),
                        e.getValue(), e.getKey().sharedBy()))
                .sorted(Comparator.comparingInt(CacheRow::level)
                        .thenComparing(CacheRow::type)
                        .thenComparingLong(CacheRow::sizeBytes))
                .toList();
        cacheTable.setItems(FXCollections.observableArrayList(rows));

        // last-level cache = highest level present
        m.caches().stream().max(Comparator.comparingInt(MachineInfo.Cache::level)).ifPresentOrElse(
                c -> llcLbl.setText(String.format(Locale.ROOT, "L%d, %s, shared by %d CPUs",
                        c.level(), fmtBytes(c.sizeBytes()), c.sharedCpus().size())),
                () -> llcLbl.setText("-"));

        cpuTable.setItems(FXCollections.observableArrayList(cpus));

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String source = os.contains("mac") ? "sysctl"
                : os.contains("win") ? "Win32 API"
                : "sysfs / procfs";
        statusLbl.setText(String.format(Locale.ROOT,
                "%d logical CPUs  |  %d physical cores  |  %d cache objects  |  source: %s%s",
                m.logicalCount(), m.physicalCount(), m.caches().size(), source,
                m.pinningSupported() ? "" : "  |  CPU pinning: not supported"));
    }

    // ---------- helpers ----------

    private static boolean isType(MachineInfo.Cpu c, String type) {
        return type.equals(c.type());
    }

    private static long maxKhz(List<MachineInfo.Cpu> cpus, String type) {
        return cpus.stream().filter(c -> isType(c, type)).mapToLong(MachineInfo.Cpu::maxKhz).max().orElse(-1);
    }

    private static String ghz(long khz) {
        return khz > 0 ? String.format(Locale.ROOT, "%.2f GHz", khz / 1_000_000.0) : "N/A";
    }

    private static String fmtBytes(long bytes) {
        if (bytes >= 1024L * 1024 && bytes % (1024L * 1024) == 0) return (bytes / (1024 * 1024)) + " MB";
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return (bytes / 1024) + " KB";
    }

    /** [0, 1, 2, 3, 8] -> "0-3, 8" */
    private static String ranges(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return "-";
        List<Integer> s = ids.stream().sorted().toList();
        StringBuilder sb = new StringBuilder();
        int start = s.get(0), prev = start;
        for (int i = 1; i <= s.size(); i++) {
            Integer cur = i < s.size() ? s.get(i) : null;
            if (cur != null && cur == prev + 1) {
                prev = cur;
                continue;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(start == prev ? String.valueOf(start) : start + "-" + prev);
            if (cur != null) start = prev = cur;
        }
        return sb.toString();
    }
}