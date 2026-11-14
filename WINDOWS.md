# SMPerf on Windows

The Windows port follows the README's strategy: **same engine contract, new native internals.**
The JavaFX app is unchanged apart from a few small OS checks.

## Quick start

Requirements: Windows 10 or 11 (x64), a JDK (the `pom.xml` targets Java 25), internet on the first
run so Maven can fetch JavaFX.

```bat
windows\run.bat
```

A prebuilt `native\bin\mperf.exe` (x64) is included. Rebuild it with MinGW-w64 (`windows\build-engine.bat`,
or `make` in `native\` under MSYS2). From Linux or macOS you can cross-compile:
`make windows WINCC="zig cc -target x86_64-windows-gnu"` (or `x86_64-w64-mingw32-gcc`).
`windows\package.bat` builds a self-contained app folder (`target\app`).

**Before trusting results, run the conformance script once** (it exercises the README checklist
against `mperf.exe`): `powershell -ExecutionPolicy Bypass -File windows\conformance.ps1`

## What was added

| Area | File | Notes |
|---|---|---|
| Topology | `native/platform/windows/topology_windows.c` | `GetLogicalProcessorInformationEx(RelationAll)`: cores, SMT siblings, packages, per-instance caches. P/E from `EfficiencyClass`. Clocks from `CallNtPowerInformation`. Model from the registry. RAM from `GetPhysicallyInstalledSystemMemory`. |
| Run loop | `native/platform/windows/run_once_windows.c` | `CREATE_SUSPENDED` -> Job Object (affinity + kill-on-close) -> `ResumeThread`. Wall time from `QueryPerformanceCounter`; user/sys time from Job accounting (includes child processes); peak working set in KB. |
| Process hooks | `native/platform/windows/platform_windows.c` | UTF-8 `argv`, binary stdout, stdin `stop` command, Ctrl+C / Ctrl+Break. |
| Portable core | `native/src/*`, `native/include/*` | `runner.c` split into portable `run_job` + per-OS `run_once`; `cli.c` uses `platform_*` hooks instead of `sigaction`; `quote_windows_arg()` (unit-tested). |
| Linux / macOS | `native/platform/posix/*` | `run_once` and signals moved here unchanged. |
| Java | `EngineLocator`, `EngineClient`, `MainController`, `MachineInfo` | `mperf.exe` lookup (dev dir, next to the jar, `<jlink image>\bin`); cancel over stdin; `.exe/.com/.bat/.cmd` file filter and check; engine warnings shown in the run log. |

## First-run tour

`GuidedTour` shows a spotlight tour on first launch (flag stored with `java.util.prefs`, which is the registry
under `HKCU\\Software\\JavaSoft\\Prefs` on Windows). It is drawn inside the main window, so it needs no extra stage.
`App` now wraps the UI in a `StackPane` that hosts the tour layer. To see it again, use Help > Take the Tour.
Also fixed: `Workspace` shadowed its `top` field, so the toolbar was never displayed; it now appears under the menu bar.

## Contract extensions (additive, safe for the other ports)

- **stdin `stop`**: writing `stop\n` to the engine's stdin cancels the run. The engine kills the running
  benchmark, prints `done` with `"cancelled": true` and exits 0. Needed because Java's `Process.destroy()`
  is `TerminateProcess` on Windows. If the engine does not exit within 10 s the UI force-kills it.
  Closing stdin (UI crash) also stops the run when stdin is a pipe.
- **`warnings`** in the `topology` JSON: a list of strings. Windows fills it with "running on battery"
  and "Power saver plan" notices; the UI prints them in the run log when a run starts. Other engines emit `[]`.

## Behaviour differences from Linux (by design)

- **Cancel is immediate.** Linux finishes the current thread-count step; Windows aborts the running
  benchmark at once and discards the partial step. Results already emitted are kept.
- **Process tree.** Timeout and cancel kill the whole tree (Job Object), and nothing is orphaned if the
  engine dies. This fixes README known issue 4 on Windows.
- **CPU time** includes child processes; **peak memory** is the peak working set of the benchmark's main
  process (not its children).
- **Programs are launched by absolute path**, like Linux `execv`; `PATH` is not searched.
  `.bat` / `.cmd` targets work, but then the measurement includes `cmd.exe`.
- The benchmark's stdin is `NUL`, its stdout is `NUL`, its stderr is inherited.
- `--threads` still sets `OMP_NUM_THREADS` and expands `{threads}`. MSVC and MinGW OpenMP both honour it.

## Pinning and processor groups

- Up to 64 logical CPUs (one processor group), the common case: a classic affinity mask on the Job Object
  and on the process, set before the first instruction runs.
- More than 64 CPUs: `cpus[].id` is the global index (`group * 64 + bit`) and pinning uses a Job Object group
  affinity list. **A benchmark that is not processor-group aware only runs in one group**, so scaling
  beyond 64 threads is not meaningful for such programs. This path is untested.
- `MAX_LOGICAL` is still 256; machines with more logical CPUs fail with "topology detection failed".

## Known limitations / not done

- **Not run on Windows yet.** The engine was cross-compiled and linked (x64, plus compile checks for
  arm64 and 32-bit x86) and the portable parts were tested on Linux. Run `conformance.ps1` on a real machine.
  The JavaFX changes are small but were reviewed rather than compiled (no JavaFX on the build machine).
- `max_khz` comes from Windows' reported maximum, which on many CPUs is the base clock, not the boost clock.
- ARM64 Windows: should build with `-target aarch64-windows-gnu`; `EfficiencyClass` behaviour is untested.
- Not implemented (ideas only): CPU Sets / EcoQoS modes, PDH or ETW counters, WSL2 comparison, Defender and
  background-noise checks, installer.

## Verified so far

| Check | Where | Result |
|---|---|---|
| Linux engine regression (topology, run, auto, bad args, bad path, non-zero exit, timeout, `{threads}`/env, cancel) | Linux | pass |
| `quote_windows_arg` round trip vs a reference CommandLineToArgvW parser (21 cases + 20,000 fuzz) | Linux | pass |
| `mperf.exe` compile + link, 0 warnings (`-Wall -Wextra`) | zig cc x86_64 / aarch64 / x86 | pass |
| Java client against the engine, old session JSON still parses | Linux (JDK 21) | pass |
| Conformance checklist on Windows | Windows | **to be run** (`windows\conformance.ps1`) |
