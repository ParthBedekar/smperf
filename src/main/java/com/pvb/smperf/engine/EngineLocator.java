package com.pvb.smperf.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class EngineLocator {
    private EngineLocator() {}

    /** True when running on Windows (the engine is then mperf.exe and cancel goes over stdin). */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String engineName() {
        return isWindows() ? "mperf.exe" : "mperf";
    }

    public static Path find() {
        List<Path> candidates = new ArrayList<>();

        String override = System.getProperty("mperf.engine");      // manual override
        if (override != null) candidates.add(Path.of(override));

        // Installed layout: <root>/bin/smperf (launcher), <root>/bin/mperf, <root>/lib/runtime = java.home
        String appPath = System.getProperty("jpackage.app-path");  // set by the jpackage launcher
        if (appPath != null) {
            Path bin = Path.of(appPath).toAbsolutePath().getParent();
            if (bin != null) candidates.add(bin.resolve(engineName()));
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path home = Path.of(javaHome);
            Path root = home.getParent() != null ? home.getParent().getParent() : null;  // lib/runtime -> root
            if (root != null) candidates.add(root.resolve("bin").resolve(engineName()));
            candidates.add(home.resolve("bin").resolve(engineName()));                    // jlink image
        }

        candidates.add(Path.of(System.getProperty("user.dir"), "native", "bin", engineName())); // dev run

        Path appDir = appDirectory();                              // classpath run: next to the jar
        if (appDir != null) {
            candidates.add(appDir.resolve(engineName()));
            Path p = appDir;
            for (int i = 0; i < 4 && p != null; i++, p = p.getParent())
                candidates.add(p.resolve("bin").resolve(engineName()));
        }

        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("mperf engine not found. Tried: " + candidates);
    }

    private static Path appDirectory() {
        try {
            Path loc = Path.of(EngineLocator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(loc) ? loc.getParent() : loc;
        } catch (Exception e) {
            return null;
        }
    }
}