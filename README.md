# SMPerf - Simple Multicore Performance Evaluation Framework

SMPerf answers one question: **"If I give this program more threads, how much faster does it actually get, and why (or why not)?"**

You pick an executable, SMPerf runs it at several thread counts (1, 2, 4, 8, …), pins each run to specific CPUs, repeats it, and shows you wall time, speedup, efficiency and variance in a table and a speedup-vs-threads chart with an ideal-scaling line.

This repository currently contains the **Linux reference implementation**. Two friends are building the **Windows** and **macOS** ports. This README explains what exists, exactly what a port has to reproduce, and how much of the overall plan is done.

> Status: early prototype. The whole pipeline works end to end on Linux (detect → configure → run → chart). Many planned features are not started. See [Feature status](#feature-status).

---

## Table of contents

1. [Architecture at a glance](#architecture-at-a-glance)
2. [Repository layout](#repository-layout)
3. [The engine contract (the thing every port must honor)](#the-engine-contract)
4. [How the Linux engine works](#how-the-linux-engine-works)
5. [How the Java app works](#how-the-java-app-works)
6. [Building and running on Linux](#building-and-running-on-linux)
7. [Feature status](#feature-status)
8. [Porting guide](#porting-guide)
   - [Strategy: port the engine, not the UI](#strategy-port-the-engine-not-the-ui)
   - [Linux → Windows → macOS API map](#linux--windows--macos-api-map)
   - [Windows notes](#windows-notes)
   - [macOS notes](#macos-notes)
   - [Java-side changes every port needs](#java-side-changes-every-port-needs)
   - [Conformance checklist](#conformance-checklist)
9. [Known issues and sharp edges](#known-issues-and-sharp-edges)
10. [Roadmap](#roadmap)
11. [Contributing](#contributing)

---

## Architecture at a glance

SMPerf is deliberately split in two so that **all OS-specific code lives in one small native program**.

```
┌───────────────────────────────┐        JSON over stdout        ┌──────────────────────────────┐
│  JavaFX desktop app (Java 21) │ ◄───────────────────────────── │  mperf  (native C engine)    │
│                               │                                │                              │
│  - forms, tables, chart       │   argv: topology | run ...     │  - detects CPU topology      │
│  - speedup / efficiency math  │ ─────────────────────────────► │  - pins + launches benchmark │
│  - NO OS-specific calls       │                                │  - measures wall/user/sys/RSS│
└───────────────────────────────┘                                └──────────────┬───────────────┘
                                                                                │ fork/exec + affinity
                                                                                ▼
                                                                    your benchmark executable
```

- The **UI never touches OS APIs**. It spawns `mperf` as a child process, reads JSON lines, and draws them.
- The **engine is the only thing that has to be rewritten per OS**. If your engine speaks the same command line and the same JSON, the Java app works unchanged (apart from a few small things listed [below](#java-side-changes-every-port-needs)).

This is the key idea for the ports: **keep the contract, replace the internals.**

---

## Repository layout

File names below follow the current tree. Adjust if yours differ slightly.

```
smperf/
├── native/
│   ├── main.c / cli.c      CLI: `topology` and `run` commands, JSON event output, signal handling,
│   │                       placement order, --threads parsing
│   ├── runner.c            run_once / run_job / job_expand_args  (fork, exec, affinity, wait4, timeout)
│   ├── stats.c             best / mean / max / stddev / user / sys / RSS over the clean runs
│   ├── topology.c          sysfs + procfs topology detection, core-type classification, topology JSON
│   ├── util.c              cpu-list parsing ("0-3,8"), small file readers, size parsing
│   ├── include/            runner.h  stats.h  topology.h  util.h
│   └── bin/mperf           build output (git-ignored); this is where the dev run looks for it
└── src/main/
    ├── java/com/pvb/smperf/
    │   ├── engine/         EngineClient (spawns mperf, parses events), EngineEvent, EngineLocator
    │   ├── model/          MachineInfo (topology JSON → records), RunConfig (UI → engine argv),
    │   │                   ConfigResult (raw stats + speedup + efficiency)
    │   └── ui/             MainController, ResultsViewController, SpecsWindowController, MachinePane
    └── resources/com/pvb/smperf/ui/
        ├── main.fxml            home page: machine summary, benchmark configuration
        ├── results_view.fxml    chart + results table (swapped into the same window)
        ├── specs_window.fxml    modal "System Topology & Specs" window
        ├── style.css            main + results styling
        └── specs_style.css      classic grey / retro styling for the specs window
```

**Tech stack:** Java 21 (virtual threads are used), JavaFX 21, Gson, plain C (gnu11) for the engine.

---

## The engine contract

This section is the specification for any port. Match it exactly and the UI will just work.

### Commands

```
mperf topology
mperf run [--threads auto|1,2,4] [--repeats N] [--warmup N] [--timeout SEC] -- <program> [args...]
```

| Option | Default | Range | Meaning |
|---|---|---|---|
| `--threads` | `auto` | `1..logical_count`, comma list | Thread counts to test. `auto` = 1, 2, 4, 8, … doubling, with the last step clamped to the logical CPU count. |
| `--repeats` | 5 | 1..64 | Measured runs per thread count. |
| `--warmup` | 1 | 0..100 | Unmeasured runs before timing, per thread count. |
| `--timeout` | 60 | 0..86400 | Per-run limit in seconds. `0` = no timeout. |

Exit codes: `0` success, `1` runtime failure (topology failed, a run errored), `2` usage / invalid arguments.

### Streams

- **stdout carries JSON only**, one object per line (`topology` prints one pretty-printed object). Flush after every line, the UI renders results live as they arrive.
- **stderr is for human diagnostics.** The UI echoes it to its own stderr with an `[mperf]` prefix.
- The **benchmark's own stdout is discarded** by the engine (sent to the null device) so it can never corrupt the JSON stream. Its stderr is inherited.

### `mperf topology` output

```json
{
  "model": "Intel(R) Core(TM) i7-12700H",
  "ram_bytes": 16637476864,
  "logical_count": 20,
  "physical_count": 14,
  "cpus": [
    {"id": 0, "package": 0, "core_id": 0, "physical_core": 0, "type": "performance", "max_khz": 4700000, "siblings": [0, 1]},
    {"id": 1, "package": 0, "core_id": 0, "physical_core": 0, "type": "performance", "max_khz": 4700000, "siblings": [0, 1]}
  ],
  "caches": [
    {"level": 1, "type": "Data",    "size_bytes": 49152,    "shared_cpus": [0, 1]},
    {"level": 3, "type": "Unified", "size_bytes": 25165824, "shared_cpus": [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]}
  ]
}
```

(Values above are illustrative.) Semantics that the UI relies on:

| Field | Rules |
|---|---|
| `cpus[].id` | The logical CPU id used for pinning. Unique. |
| `cpus[].physical_core` | Dense index `0..physical_count-1`, assigned in order of first appearance. Logical CPUs with the same value are hyperthreads of one core. The UI groups chips and shades table rows by this value. |
| `cpus[].type` | Exactly one of `"performance"`, `"efficiency"`, `"uniform"`. `uniform` = not a hybrid CPU. |
| `cpus[].max_khz` | Max clock in kHz, or `-1` if unknown. The UI shows "N/A" for `-1`. |
| `cpus[].siblings` | All logical CPU ids sharing this physical core (including itself). |
| `caches[]` | **One entry per physical cache instance** (a shared L3 appears once, not once per CPU). `type` is a free string (`Data`, `Instruction`, `Unified` …). The UI aggregates identical caches into a quantity column. |
| `caches[].shared_cpus` | Logical CPUs that share this cache instance. |

Gson ignores unknown fields, so **adding** fields is safe. **Renaming or removing** fields is not.

### `mperf run` output events

```json
{"event":"result","threads":4,"runs":5,"failed":0,"wall_best":0.412300,"wall_mean":0.418800,"wall_max":0.431000,"wall_stddev":0.006100,"user_mean":1.610000,"sys_mean":0.012000,"max_rss_kb":84212}
{"event":"error","threads":8,"code":-3,"message":"program did not start"}
{"event":"done","ok":true,"cancelled":false}
```

- One `result` per thread count, emitted as soon as that step finishes.
- At most one `error`, after which the run stops and `done` follows with `ok:false`.
- `done` is **always** the last line, including on error and cancellation.
- Times are seconds (floating point). `max_rss_kb` is **kilobytes**, see the macOS note.

Error codes:

| Code | Meaning |
|---|---|
| `-1` | `repeats` out of range |
| `-2` | runner failure (fork / wait / pinning failed) |
| `-3` | program did not start (exec failed) |
| `-4` | too many arguments, or one is too long (max 32 args, 256 bytes each) |

### Measurement semantics (must be identical across OSes or results aren't comparable)

For each requested thread count `t`:

1. **Placement.** Pin the run to the first `t` CPUs of the *placement order*: first one logical CPU from every physical core (in `cpus[]` order), then the remaining hyperthread siblings. So `t` ≤ physical cores means "one thread per core, no SMT sharing"; larger `t` starts filling hyperthreads.
2. **Environment.** Set `OMP_NUM_THREADS=t` for the child.
3. **Argument templating.** Every `{threads}` occurrence in the benchmark's arguments is replaced with `t`.
4. **Warm-up** runs (not measured), then **repeats** measured runs, each a fresh process launch.
5. **Per run** record wall time, user CPU time, system CPU time, and peak resident memory.
6. A run is **failed** if it timed out, was killed by a signal, or exited non-zero. A run whose program never started aborts the whole job with `-3`.
7. **Statistics** (`wall_*`, `user_mean`, `sys_mean`) are computed over **clean runs only**; `wall_stddev` is the sample standard deviation (n−1) and is 0 with fewer than two clean runs. `max_rss_kb` is the peak over **all** runs. With no clean runs, the stats stay 0.
8. On timeout: kill the child, mark the run failed, carry on with the remaining repeats.
9. On cancel (termination signal): finish the current step, then emit `done` with `"cancelled": true`.

---

## How the Linux engine works

| Concern | Where | How |
|---|---|---|
| Online CPUs | `read_cpus` | `/sys/devices/system/cpu/online`, parsed by `parse_cpulist` |
| Package / core id / clocks | `read_cpus` | `cpuN/topology/physical_package_id`, `cpuN/topology/core_id`, `cpuN/cpufreq/cpuinfo_max_freq` |
| Hyperthread siblings | `read_cpus` | `cpuN/topology/thread_siblings_list` |
| Physical cores | `group_physical_cores` | Group logical CPUs by `(package, core_id)`; number groups in first-seen order |
| Hybrid P/E classification | `classify_core_types` | Preferred: `/sys/devices/cpu_core/cpus` and `/sys/devices/cpu_atom/cpus` (Intel hybrid). Fallback: compare `max_khz` across CPUs — highest = performance, rest = efficiency, all equal or any unknown = uniform |
| Caches | `read_caches` | `cpuN/cache/indexM/{level,type,size,shared_cpu_list}`; an instance is recorded only when reading from the *first* CPU in its `shared_cpu_list`, so shared caches aren't duplicated |
| Model name / RAM | `read_model`, `read_ram` | `/proc/cpuinfo` (`model name`), `/proc/meminfo` (`MemTotal`) |
| Pinning | `run_once` (child, after `fork`, before `exec`) | `sched_setaffinity` with a `cpu_set_t` built from the job's `cpu_range` list |
| Launch | `run_once` | `fork` + `execv`; stdout → `/dev/null`; exit code 127 = exec failed, 126 = affinity failed (sentinel exit codes) |
| Timing / resources | `run_once` | `CLOCK_MONOTONIC` for wall; `wait4` returning `struct rusage` for user/sys/maxrss |
| Timeout | `run_once` | Polling `wait4(WNOHANG)` every 1 ms; `SIGKILL` on expiry |
| Cancel | `main` | `SIGTERM`/`SIGINT` set a flag checked between thread-count steps |

The pure-logic parts (`stats.c`, CLI parsing, `placement_order`, JSON printing, `{threads}` expansion, `cpu_range` helpers) are OS-independent and should be reused as-is.

---

## How the Java app works

- **`EngineLocator`** finds the engine binary: `-Dmperf.engine=/path`, then `./native/bin/mperf` (dev run), then next to the jar (packaged app).
- **`EngineClient.fetchMachine()`** runs `mperf topology`, concatenates stdout, and hands it to `MachineInfo.fromJson` (Gson, snake_case → camelCase; `package` is mapped with `@SerializedName` because it is a Java keyword).
- **`EngineClient.run()`** builds the command from `RunConfig.toCommand`, starts the process, drains stderr on a virtual thread, and calls the listener for every parsed JSON line. `cancel()` calls `Process.destroy()`.
- **`ConfigResult`** computes the derived numbers. Baseline = first configuration that did not fully fail. `speedup = baseline.wallBest / r.wallBest`, `efficiency = speedup / (r.threads / baseline.threads)`. The ideal-scaling line is `t / baseline.threads`.
- **`MainController`** is the home page and navigation. Results live in a separate view (`results_view.fxml`) swapped into the same window; the specs window is a separate modal.
- **`MachinePane`** and **`SpecsWindowController`** render the topology (core chips grid, cache table aggregated by identical caches, logical-CPU table shaded per physical core).

---

## Building and running on Linux

Prerequisites: a C compiler (gcc/clang), JDK 21+, JavaFX 21 (via your build tool), Gson.

**Engine**

```bash
cd native
mkdir -p bin
gcc -O2 -Wall -std=gnu11 -o bin/mperf main.c runner.c stats.c topology.c util.c -lm
./bin/mperf topology
```

(`-lm` is needed because `stats.c` uses `sqrt`. Use the real source filenames from your tree.)

**App**

<!-- TODO: replace with the repo's actual build/run command (Maven / Gradle / IDE run configuration) -->
```bash
# e.g.  ./gradlew run     or     mvn javafx:run
```

Run from the repository root so `./native/bin/mperf` is found, or pass `-Dmperf.engine=/absolute/path/to/mperf`.

**A tiny benchmark to test with** (`sum.c`):

```c
#include <stdio.h>
#include <omp.h>

int main(void) {
    const long N = 400000000L;
    double s = 0;
    #pragma omp parallel for reduction(+:s)
    for (long i = 0; i < N; i++) s += (double)i * 0.5;
    printf("%f\n", s);          /* stdout is discarded by the engine */
    return 0;
}
```

```bash
gcc -O2 -fopenmp sum.c -o sum
./native/bin/mperf run --threads 1,2,4 --repeats 3 -- ./sum
```

---

## Feature status

Legend: ✅ complete · 🟡 semi-complete · ❌ not started · 🔨 in progress by contributors

**Score: 3 complete, 3 semi-complete, 8 not started (14 goals).**

### MVP

| Goal | Status | What exists / what's missing |
|---|---|---|
| System detection (logical CPUs, physical cores, SMT siblings, P/E cores, caches) | ✅ **Linux only** | Full topology JSON, specs window, machine summary. Windows and macOS need their own detection. |
| Add a benchmark (binary, args, optional input file) | 🟡 | Binary picker and arguments work, with `{threads}` templating. **No dedicated input-file field** (users can pass the path as an argument). Argument entry has a quirk, see [Known issues](#known-issues-and-sharp-edges). |
| Define an experiment (thread counts + CPU sets to pin to) | 🟡 | Thread counts: auto or manual list, works. **CPU sets: the engine always pins using one fixed placement** (physical cores first, then hyperthreads). The UI cannot choose a CPU set and the CLI doesn't accept one yet. The `cpu_range` plumbing in the runner already supports arbitrary sets. |
| Run engine (warm-up, N repeats, timeout, best/mean) | ✅ | Plus max, stddev, user/sys time, peak RSS, failed-run counting, cancel. |
| Results view (table + speedup chart + ideal line) | ✅ | Live updating, tooltips with speedup/efficiency, 12-column table. |
| Export results to CSV / JSON | ❌ | Nothing yet. Needs the experiment config + machine info + results in one file for reproducibility. |

### Next tier

| Goal | Status | What exists / what's missing |
|---|---|---|
| Efficiency and variance (flag noisy runs) | 🟡 | Efficiency (speedup ÷ thread ratio) and stddev are shown. **No noise flagging** yet, e.g. highlight when stddev/mean exceeds a threshold. |
| Compare runs (overlay two benchmarks) | ❌ | Results are cleared at the start of each run. Needs result storage and a multi-series chart. |
| Placement presets (same-core hyperthreads, separate cores, spread, close) | ❌ | Only the implicit "separate cores first" order. The building blocks exist (`physical_core`, `siblings`, `placement_order`, caches' `shared_cpus` for "close"/"spread"). |
| Hardware counters via `perf stat` (cache misses, IPC) | ❌ | Linux-only feature in practice (`perf_event_open`); Windows/macOS need other approaches. |
| Auto-diagnosis (memory-bound vs compute-bound) | ❌ | Depends on counters and variance flagging. |

### Later

| Goal | Status | Notes |
|---|---|---|
| Windows and macOS support | 🔨 | This is what the porting guide below is for. |
| Built-in roofline plot | ❌ | Needs counters (FLOPs and bytes) plus a memory-bandwidth measurement. |
| Saved experiment profiles and run history | ❌ | Related to export and compare. |

---

## Porting guide

### Strategy: port the engine, not the UI

1. Make a native engine that satisfies the [engine contract](#the-engine-contract) on your OS.
2. Name the binary `mperf` (`mperf.exe` on Windows) and put it where `EngineLocator` looks.
3. Apply the [small Java-side changes](#java-side-changes-every-port-needs).
4. Run the [conformance checklist](#conformance-checklist).

Recommended refactor (agree on it with the other porter so you don't diverge): split the native code into a portable core and a platform layer.

```
native/
├── core/          cli, stats, util(cpulist), json printing, placement_order, {threads} expansion   (shared)
└── platform/
    ├── linux/     detect_topology, run_once (pin + launch + measure)
    ├── windows/   same two entry points
    └── macos/     same two entry points
```

In practice there are only **two functions to reimplement**: `detect_topology()` and `run_once()` (plus a monotonic clock and the cancel mechanism). Everything else, including `group_physical_cores`, the `classify_*` helpers, `machine_print_json`, `stats_compute`, `run_job` and `job_expand_args`, is portable once the Linux-only headers are guarded.

### Linux → Windows → macOS API map

| Need | Linux (current) | Windows | macOS |
|---|---|---|---|
| Logical/physical counts, SMT | sysfs `topology/*` | `GetLogicalProcessorInformationEx(RelationAll)`; `RelationProcessorCore` entries, `LTP_PC_SMT` flag, `GroupMask` | `sysctlbyname("hw.logicalcpu")`, `hw.physicalcpu`, `hw.packages` |
| P/E core type | `/sys/devices/cpu_core\|cpu_atom`, freq fallback | `EfficiencyClass` in `PROCESSOR_RELATIONSHIP` (higher = faster); or CPU Sets API | Apple Silicon: `hw.nperflevels`, `hw.perflevel0.*` (performance), `hw.perflevel1.*` (efficiency) |
| Caches | sysfs `cache/indexN` | `RelationCache` entries (`Level`, `Type`, `CacheSize`, `GroupMask`), one entry per instance | `hw.l1dcachesize`, `hw.l1icachesize`, `hw.l2cachesize`, `hw.l3cachesize` (+ per-perflevel variants on Apple Silicon) |
| Max clock | `cpuinfo_max_freq` | `CallNtPowerInformation(ProcessorInformation)` (MaxMhz) or registry `~MHz` | `hw.cpufrequency_max` on Intel; **not available on Apple Silicon** → report `-1` |
| Model name | `/proc/cpuinfo` | Registry `HKLM\HARDWARE\DESCRIPTION\System\CentralProcessor\0\ProcessorNameString` | `machdep.cpu.brand_string` |
| Total RAM | `/proc/meminfo` | `GlobalMemoryStatusEx` or `GetPhysicallyInstalledSystemMemory` | `hw.memsize` |
| Spawn | `fork` + `execv` | `CreateProcessW` (see quoting note) | `fork` + `execv` (unchanged) |
| Pin to CPUs | `sched_setaffinity` | `SetProcessAffinityMask` / group affinity; see notes | **not supported**, see macOS notes |
| Env var for child | `setenv` | `SetEnvironmentVariableW` before spawn, or pass an environment block | `setenv` (unchanged) |
| stdout → nowhere | `open("/dev/null")` + `dup2` | `STARTF_USESTDHANDLES` with an inheritable handle to `NUL` | unchanged |
| Wall clock | `clock_gettime(CLOCK_MONOTONIC)` | `QueryPerformanceCounter` | `clock_gettime` (macOS 10.12+) or `clock_gettime_nsec_np` |
| User/sys CPU time | `wait4` → `ru_utime/ru_stime` | `GetProcessTimes` (100 ns units) or Job Object accounting | `wait4` (unchanged) |
| Peak memory | `ru_maxrss` (**KB**) | `GetProcessMemoryInfo` → `PeakWorkingSetSize` (**bytes**, divide by 1024) | `ru_maxrss` (**bytes**, divide by 1024) |
| Timeout + kill | poll `wait4(WNOHANG)` + `SIGKILL` | `WaitForSingleObject(h, ms)` then `TerminateProcess` / `TerminateJobObject` | unchanged |
| Graceful cancel | `SIGTERM` handler sets a flag | see Windows notes | `SIGTERM` handler (unchanged) |
| Thread-safe tokenizing | `strtok_r` | `strtok_s` | `strtok_r` |

### Windows notes

**Topology.** `GetLogicalProcessorInformationEx` returns variable-length records; walk them by `Size`. Each `RelationProcessorCore` record is one physical core, and its `GroupMask` bits are its logical CPUs, which gives you `physical_core` and `siblings` directly (you can skip `group_physical_cores`). Each `RelationCache` record is one cache *instance*, so no de-duplication is needed. `EfficiencyClass` is 0 on non-hybrid CPUs, and on hybrid parts the highest class is the performance cores.

**More than 64 logical CPUs (processor groups).** Windows splits CPUs into groups of up to 64. Define `cpus[].id` as the **global index** (group × 64 + bit) and keep a private mapping back to `(group, bit)` when pinning. Plain `SetProcessAffinityMask` only reaches one group.

**Pinning without a race.** On Linux the child pins itself between `fork` and `exec`. On Windows the parent must pin the child, so:

1. `CreateProcessW` with `CREATE_SUSPENDED`,
2. set affinity on the process handle,
3. `ResumeThread`.

Otherwise the benchmark may start its threads before the mask is applied.

**Command-line quoting.** `CreateProcessW` takes a single string, not an argv array. Quote each argument following the `CommandLineToArgvW` rules (backslashes before quotes need doubling). Java's `ProcessBuilder` does its own quoting when it launches `mperf.exe`, so only the engine → benchmark hop needs care.

**Use a Job Object.** Put the benchmark in a Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. This gives you three things: timeouts that kill the whole process tree, no orphaned benchmarks if the engine dies, and CPU time/peak memory accounting that includes child processes (closer to what `wait4` reports on Linux).

**Cancel is different.** On Windows, Java's `Process.destroy()` terminates the engine immediately (there is no `SIGTERM`), so the engine can't print its final `done` line. Options:

- Have the UI write a `stop` line to the engine's **stdin** and have the engine poll it (works on all three OSes and is a good candidate for a shared contract extension), or
- use a named event, or `CTRL_BREAK_EVENT` with `CREATE_NEW_PROCESS_GROUP`.

Whatever you choose, make the engine emit `done` with `"cancelled": true` and ensure the Job Object kills the running benchmark.

**Build.** MSVC or MinGW-w64. Replace `unistd.h`, `sys/wait.h`, `sys/resource.h`, `sched.h`, `fork`, `nanosleep`, `sigaction`, `strtok_r`. `_GNU_SOURCE` is irrelevant. Benchmarks use OpenMP via `/openmp` (MSVC) or `-fopenmp` (MinGW). Note MSVC's OpenMP honors `OMP_NUM_THREADS` too.

### macOS notes

**Topology.** Everything comes from `sysctlbyname`. Run `sysctl hw` and `sysctl machdep.cpu` in a terminal to see what your Mac exposes before coding.

- Apple Silicon has **no SMT**, so each logical CPU is its own physical core (`siblings` = just itself). Performance vs efficiency core counts come from `hw.perflevel0.*` and `hw.perflevel1.*`.
- macOS does **not** expose a per-CPU "this id is a P-core" table, so you will have to synthesize the `cpus[]` list (ids `0..logical_count-1`) and assign `type` by counts. Since pinning isn't possible (below), the exact id→type mapping is cosmetic, but document the assumption.
- Intel Macs have hyperthreading: `hw.logicalcpu` > `hw.physicalcpu`. Pairing logical ids into siblings is an assumption (adjacent ids); document it.
- Cache sizes are global, not per-CPU, so emit one entry per cache and fill `shared_cpus` from what you know (e.g. L3 shared by all, L2 per cluster on Apple Silicon via `hw.perflevelN.cpusperl2`).
- `max_khz`: Intel has `hw.cpufrequency_max`; Apple Silicon doesn't expose it, so emit `-1` (the UI already shows "N/A").

**Pinning is not available.** macOS has no equivalent of `sched_setaffinity`. `thread_policy_set(THREAD_AFFINITY_POLICY)` is only an advisory grouping hint and is not honored on Apple Silicon. Realistic options:

- Treat the "placement" part of the experiment as unsupported on macOS: only vary **thread count** (`OMP_NUM_THREADS`) and skip pinning.
- Suggested contract extension: add `"pinning": "supported" | "unsupported"` to the topology JSON so the UI can disable placement controls instead of silently ignoring them. Because Gson ignores unknown fields, adding it is backwards compatible. Coordinate with the Windows porter and the Linux side before merging.
- Worth experimenting with later: QoS / `taskpolicy` to bias work toward efficiency cores. That's a scheduling hint, not pinning, so label it that way if you expose it.

**Units gotcha.** On macOS `ru_maxrss` is in **bytes**, on Linux it's kilobytes. Divide by 1024 in the macOS engine so `max_rss_kb` stays in KB. The UI divides by 1024 again to show MB, so a missed conversion makes memory look 1024× too large.

**What stays the same.** `fork`, `execv`, `wait4`, `sigaction`, `nanosleep`, `strtok_r`, `setenv` and `clock_gettime` all work on macOS, so `run_once` is mostly the Linux code with the affinity block removed.

**Build.** `clang -O2 -Wall -o bin/mperf ...` (no `-lm` needed). Make the build target match your Mac's architecture (arm64 vs x86_64) or build a universal binary with `-arch arm64 -arch x86_64`. If you ship it inside an app bundle, the engine binary needs to be code-signed and notarized along with the app.

**OpenMP for test benchmarks.** Apple's clang doesn't ship OpenMP. Install it with `brew install libomp`, then compile with:

```bash
clang -O2 -Xpreprocessor -fopenmp -I"$(brew --prefix libomp)/include" \
      -L"$(brew --prefix libomp)/lib" -lomp sum.c -o sum
```

### Java-side changes every port needs

These are small, but easy to miss:

1. **`EngineLocator`**: look for `mperf.exe` on Windows. Pick the name using `System.getProperty("os.name")`.
2. **`SpecsWindowController.populate`**: the status line is hardcoded to `source: sysfs / procfs`. Make it OS-specific (e.g. `source: Win32 API`, `source: sysctl`).
3. **File picker** (`MainController.chooseBinary`): optionally add an extension filter (`*.exe` on Windows). The `Files.isExecutable` check is fine on all platforms.
4. **Placement controls** (macOS): once the UI grows placement options, hide or disable them when the engine reports `"pinning": "unsupported"`.
5. **Fonts / look**: `style.css` falls back through `Segoe UI`, `Inter`, `DejaVu Sans`; `specs_style.css` through `Tahoma`. Check these on your OS and add a sensible default (`-apple-system` / `SF Pro` aren't guaranteed in JavaFX, so test).
6. **Menu bar** (macOS): consider `menuBar.setUseSystemMenuBar(true)`.
7. **Packaging**: `jpackage` per platform, with the engine placed next to the jar (that's the third lookup location in `EngineLocator`).

### Conformance checklist

Before opening a PR, check each of these by hand against your engine:

- [ ] `mperf topology` prints valid JSON; `logical_count == cpus.length`; `physical_core` is dense from 0; every `type` is `performance`/`efficiency`/`uniform`.
- [ ] `mperf run --threads 1,2 --repeats 3 -- <program>` prints two `result` lines then `done` with `ok:true`.
- [ ] `--threads auto` produces 1, 2, 4, … ending exactly at the logical CPU count.
- [ ] `--threads 999` exits with code `2` and a message on stderr, with **nothing** on stdout.
- [ ] A bad program path produces an `error` event with code `-3`, then `done` with `ok:false`.
- [ ] A program that exits non-zero counts toward `failed` and is excluded from the timing stats.
- [ ] A program that sleeps longer than `--timeout` is killed, counted as failed, and the run continues.
- [ ] Cancelling mid-run ends with `done` carrying `"cancelled": true`, and **no benchmark process is left running**.
- [ ] `{threads}` inside an argument is replaced with the thread count; `OMP_NUM_THREADS` is set in the child.
- [ ] The benchmark's stdout never appears in the engine's stdout.
- [ ] `max_rss_kb` is in kilobytes (compare with Task Manager / Activity Monitor for a program that allocates a known amount).
- [ ] On a machine with SMT, `--threads <physical_count>` puts one thread per physical core (verify with an OS monitor).
- [ ] The Java app launches, shows the machine summary, runs a benchmark and draws the chart without any Java code changes beyond the list above.

---

## Known issues and sharp edges

These are real behaviors of the current code, listed so they don't surprise you:

1. **Argument entry.** `MainController.createConfig` turns each *line* of the arguments box into **one** argv element, so a line like `--size 1000000` is passed as a single argument `"--size 1000000"`, not two. The placeholder text in the UI suggests otherwise. Fix by tokenizing (shell-style) or by changing the placeholder to one argument per line.
2. **Cancel latency.** The engine only checks the stop flag *between* thread-count steps, so a cancel waits for the current step (all of its repeats) to finish. On the UI side, `Process.destroy()` sends `SIGTERM` on Linux/macOS.
3. **Cancel status text can be overwritten.** After a cancel the engine exits normally, so the Java `Task` completes as *succeeded* and the "Benchmark finished" status can replace the "Benchmark cancelled" message set by the `done` event.
4. **Timeout kills only the direct child.** If the benchmark is a launcher script that spawns other processes, those survive a `SIGKILL` of the parent. (On Windows, use a Job Object; on POSIX consider a process group.)
5. **Wall time includes launch cost.** The timer starts before `fork` and stops after the polling loop notices exit. With a timeout enabled, resolution is about 1 ms. Fine for benchmarks lasting seconds, noisy for very short ones.
6. **Hybrid detection is Intel-specific first.** `/sys/devices/cpu_core` / `cpu_atom` only exist on Intel hybrid CPUs. Other CPUs fall back to comparing max clocks, which fails when `cpufreq` is unavailable (common in VMs): they are reported as `uniform`.
7. **Only `OMP_NUM_THREADS` and `{threads}` control thread count.** Benchmarks that ignore both (fixed thread count, or a different runtime) will show flat scaling for the wrong reason.
8. **Baseline is the first successful configuration**, not necessarily 1 thread. With a manual list like `2,4,8`, speedup is relative to 2 threads and the ideal line is scaled accordingly.
9. **Benchmark output is discarded**, so SMPerf can't check that a faster run still computed the right answer.
10. **No persistence.** Results are cleared on every new run and lost when the app closes.

---

## Roadmap

Rough order we'd tackle things, once the ports reach parity:

1. Export CSV/JSON (config + machine + results), prerequisite for reproducibility, compare and history.
2. Input-file field and argument tokenizing fix.
3. Noise flagging (stddev/mean threshold) on the results table and chart.
4. Placement presets generated from topology; CLI option to pass an explicit CPU set to the engine.
5. Compare runs / run history / saved profiles.
6. `perf stat` counters (Linux first), then auto-diagnosis.
7. Roofline plot.

---

## Contributing

- **Keep the engine contract stable.** Add fields, never rename or remove. If you must extend the protocol (e.g. `pinning`, a stdin `stop` command), open an issue first so all three platforms adopt it together.
- **One platform per branch** (`windows`, `macos`), merged via PR into `main`. Keep platform code under `native/platform/<os>/`.
- **Don't put OS logic in Java.** If the UI needs to know something OS-specific, expose it through the engine's JSON.
- **Run the [conformance checklist](#conformance-checklist)** and paste the results in your PR description.
- When you port a feature, update the [Feature status](#feature-status) table for your platform.
