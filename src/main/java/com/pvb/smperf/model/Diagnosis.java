package com.pvb.smperf.model;

import com.pvb.smperf.engine.EngineEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rule-of-thumb explanation of why scaling falls short. Heuristics, not proof. */
public final class Diagnosis {

    public record Finding(String headline, List<String> details) {}

    private Diagnosis() {}

    public static Finding analyse(List<ConfigResult> rows, double noiseThreshold) {
        List<ConfigResult> good = rows.stream().filter(r -> !r.stats().allFailed()).toList();
        if (good.size() < 2)
            return new Finding("Not enough data", List.of("Run at least two thread counts to get a diagnosis."));

        EngineEvent.Result base = good.get(0).stats();
        ConfigResult last = good.get(good.size() - 1);
        EngineEvent.Result t = last.stats();
        double eff = last.efficiency();
        List<String> why = new ArrayList<>();

        // noise first: unreliable numbers make everything below shaky
        double n = noise(t);
        if (!Double.isNaN(n) && n > noiseThreshold)
            why.add(String.format(Locale.ROOT,
                    "Noisy measurements at %d threads (%.1f%% spread): treat these results with care.",
                    t.threads(), n * 100));

        if (eff >= 0.85) {
            return new Finding(String.format(Locale.ROOT,
                    "Scales well: %.0f%% efficiency at %d threads", eff * 100, t.threads()), why);
        }

        String headline = String.format(Locale.ROOT, "Efficiency drops to %.0f%% at %d threads",
                eff * 100, t.threads());

        if (!base.hasCounters() || !t.hasCounters()) {
            why.add("Hardware counters are not available here, so the cause cannot be narrowed down.");
            return new Finding(headline, why);
        }

        double instrRatio = (double) t.instructions() / base.instructions();
        double ipcRatio = t.ipc() / base.ipc();
        double missDelta = t.missRate() - base.missRate();
        double clockRatio = effGhz(t) / effGhz(base);
        double sysShare = t.userMean() > 0 ? t.sysMean() / t.userMean() : 0;

        boolean extraWork = instrRatio > 1.10;
        boolean ipcDrop = ipcRatio < 0.90;
        boolean missUp = missDelta > 0.10;
        boolean slowClock = clockRatio < 0.90;
        boolean osHeavy = sysShare > 0.05;

        if (extraWork) why.add(String.format(Locale.ROOT,
                "Total instructions grew %.0f%%: more work is done at higher thread counts (spinning, "
                        + "synchronisation or duplicated work).", (instrRatio - 1) * 100));
        if (missUp) why.add(String.format(Locale.ROOT,
                "Cache miss rate rose %.0f points: threads compete for cache or memory bandwidth.",
                missDelta * 100));
        if (ipcDrop) why.add(String.format(Locale.ROOT,
                "IPC fell %.0f%%: each thread does less per cycle (shared core resources, such as "
                        + "hyperthreads, or memory stalls).", (1 - ipcRatio) * 100));
        if (slowClock) why.add(String.format(Locale.ROOT,
                "Average clock fell from %.1f to %.1f GHz: threads landed on slower cores (E-cores) "
                        + "or the CPU throttled. Try the P-cores only placement.",
                effGhz(base), effGhz(t)));
        if (osHeavy) why.add(String.format(Locale.ROOT,
                "System time is %.0f%% of user time: kernel overhead, such as locking or page faults.",
                sysShare * 100));

        if (missUp)         headline += ": likely memory or cache bound";
        else if (slowClock) headline += ": likely slower cores or clock speed";
        else if (extraWork) headline += ": likely parallel overhead";
        else if (ipcDrop)   headline += ": likely shared core resources";
        else if (osHeavy)   headline += ": likely kernel or synchronisation overhead";
        else                headline += ": no single cause stands out";

        return new Finding(headline, why);
    }

    /** Average clock while running: cycles per user-second, in GHz. */
    private static double effGhz(EngineEvent.Result r) {
        return r.userMean() > 0 ? r.cycles() / r.userMean() / 1e9 : Double.NaN;
    }

    private static double noise(EngineEvent.Result s) {
        int ok = s.runs() - s.failed();
        if (ok < 2 || s.wallMean() <= 0) return Double.NaN;
        return s.wallStddev() / s.wallMean();
    }
}