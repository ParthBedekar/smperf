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

        candidates.add(Path.of(System.getProperty("user.dir"), "native", "bin", engineName())); // dev run

        Path appDir = appDirectory();                              // jpackage: next to the jar
        if (appDir != null) candidates.add(appDir.resolve(engineName()));

        String javaHome = System.getProperty("java.home");         // jlink image: <image>/bin/mperf[.exe]
        if (javaHome != null) candidates.add(Path.of(javaHome, "bin", engineName()));

        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toAbsolutePath();
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