
package com.pvb.smperf.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvb.smperf.model.MachineInfo;
import com.pvb.smperf.model.RunConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

public class EngineClient {

    private final Path enginePath;
    private volatile Process activeProcess;

    public EngineClient(Path enginePath) {
        this.enginePath = enginePath.toAbsolutePath();
    }

    public MachineInfo fetchMachine() throws Exception {

        Process process = new ProcessBuilder(
                enginePath.toString(),
                "topology"
        ).start();

        String output;

        try (var reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8))) {

            output = reader.lines()
                    .reduce("", (a, b) -> a + b);
        }

        String error = new String(
                process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        int exit = process.waitFor();

        if (exit != 0) {
            throw new IllegalStateException(
                    "Topology detection failed: " + error
            );
        }

        return MachineInfo.fromJson(output);
    }

    public void run(
            RunConfig config,
            Consumer<EngineEvent> listener
    ) throws Exception {

        Process process = new ProcessBuilder(
                config.toCommand(enginePath)
        ).start();

        activeProcess = process;
        // On Windows the engine reads "stop" from this pipe. Elsewhere stdin is unused.

        Thread stderrReader = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(
                            process.getErrorStream(),
                            StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    System.err.println("[mperf] " + line);
                }

            } catch (Exception ignored) {
            }
        });

        try (var reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                EngineEvent event = parseEvent(line);

                if (event != null) {
                    listener.accept(event);
                }
            }
        } finally {
            int exit = process.waitFor();
            stderrReader.join();
            activeProcess = null;

            if (exit != 0) {
                throw new IllegalStateException(
                        "mperf exited with status " + exit
                );
            }
        }
    }

    /** How long the engine gets to stop on its own before it is killed (Windows only). */
    private static final long STOP_GRACE_MS = 10_000;

    public void cancel() {

        Process process = activeProcess;

        if (process == null || !process.isAlive()) return;

        if (!EngineLocator.isWindows()) {
            process.destroy();                       // SIGTERM: the engine finishes the current step
            return;
        }

        // Windows has no SIGTERM: destroy() would TerminateProcess the engine and the final "done"
        // line would never be printed. Ask it to stop over stdin instead. The engine kills the
        // running benchmark (Job Object) and prints done{cancelled:true}; if it does not exit in
        // time, kill it - closing its Job Object handle also kills the benchmark.
        try {
            var stdin = process.getOutputStream();
            stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (java.io.IOException ignored) {
            process.destroyForcibly();
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
                    process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private EngineEvent parseEvent(String line) {

        JsonObject json = JsonParser.parseString(line)
                .getAsJsonObject();

        String event = json.get("event").getAsString();

        return switch (event) {

            case "result" -> new EngineEvent.Result(
                    json.get("threads").getAsInt(),
                    json.get("runs").getAsInt(),
                    json.get("failed").getAsInt(),
                    json.get("wall_best").getAsDouble(),
                    json.get("wall_mean").getAsDouble(),
                    json.get("wall_max").getAsDouble(),
                    json.get("wall_stddev").getAsDouble(),
                    json.get("user_mean").getAsDouble(),
                    json.get("sys_mean").getAsDouble(),
                    json.get("max_rss_kb").getAsLong()
            );

            case "error" -> new EngineEvent.Failure(
                    json.get("threads").getAsInt(),
                    json.get("code").getAsInt(),
                    json.get("message").getAsString()
            );

            case "done" -> new EngineEvent.Done(
                    json.get("ok").getAsBoolean(),
                    json.get("cancelled").getAsBoolean()
            );

            default -> null;
        };
    }
}
