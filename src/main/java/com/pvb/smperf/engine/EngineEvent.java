package com.pvb.smperf.engine;

/** One parsed JSON line from "mperf run". */
public sealed interface EngineEvent {

    record Result(
            int threads, int runs, int failed,
            double wallBest, double wallMean, double wallMax, double wallStddev,
            double userMean, double sysMean, long maxRssKb,
            long cycles, long instructions, long cacheRefs, long cacheMisses) implements EngineEvent {

        public boolean allFailed() { return failed == runs; }

        /** False on engines without counters, and in session files saved before this feature. */
        public boolean hasCounters() { return cycles > 0 && instructions > 0; }

        public double ipc() {
            return hasCounters() ? (double) instructions / cycles : Double.NaN;
        }

        public double missRate() {
            return hasCounters() && cacheRefs > 0 ? (double) cacheMisses / cacheRefs : Double.NaN;
        }
    }

    /** The "error" event. Not named Error, to avoid clashing with java.lang.Error. */
    record Failure(int threads, int code, String message) implements EngineEvent {}

    record Done(boolean ok, boolean cancelled) implements EngineEvent {}
}