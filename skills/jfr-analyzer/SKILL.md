---
name: jfr-analyzer
description: Use when the user provides a Java Flight Recorder (.jfr) file, an async-profiler JFR output, or asks to investigate a Java performance problem (slow endpoint, high CPU, latency with low CPU, GC pauses, lock contention, virtual-thread pinning, custom events) from a recording. Drives the jfr-agent MCP tools to find the bottleneck, explain why, and propose a fix.
---

# JFR analyzer

You analyse JFR recordings through the `jfr-agent` MCP server (tools prefixed `jfr_`). Reading is done by Java's own JFR parser, so every recording the JDK can read works: JDK `-XX:StartFlightRecording`, `jcmd JFR.start`, IntelliJ profiler, async-profiler `-o jfr` (wall-clock, cpu, alloc, lock).

The user normally gives you a recording and a **scenario** ("checkout API is slow after 10 s", "CPU 90% during batch job"). Anchor the investigation on the scenario, not on a generic report. Skip noise: idle pool threads parked in `LockSupport.park`, JMX/RMI threads, JIT/GC threads unless the scenario points there.

## Workflow

1. **Environment**: if any tool reports missing `jfr`/JDK/async-profiler, call `jfr_env`, show the user the WARNINGS, and ask for a path. Persist it with `jfr_configure` (`javaHome`, `asyncProfilerHome`, `jfrconvPath`, `jfrPath`). Never guess paths.
2. **Orient**: `jfr_overview` (what data exists: CPU samples? wall samples? allocation? locks? virtual threads? custom events?) then `jfr_diagnose` (severity-ranked opinion + next tool).
3. **Localise in time**: `jfr_timeline`. Find the slow period that matches the scenario. Use that `from`/`to` for every following call. If the scenario names a custom event, use `jfr_custom_events` and `jfr_zoom_custom_event` instead.
4. **Localise in threads**: `jfr_threads`. Pick the thread group that serves the scenario (e.g. `http-nio-*-exec-*`, `<virtual threads>`), pass it as `thread`. Use `virtual_threads=only|exclude` to separate virtual from platform threads.
5. **Find the code**: `jfr_hot_methods` (on-CPU: default `state=runnable`; waiting: `state=sleeping`; blocking events are included). It charges JDK/framework time to the application caller, so the first table answers "which of my methods".
6. **Explain the JDK time**: `jfr_jdk_breakdown` shows which JDK area burns the time (network/file I/O, regex, reflection, crypto, collections, locks/parking, virtual-thread scheduling, native waits, GC, JIT) and which app method drives it.
7. **Visualise**: `jfr_flamegraph` for the window/thread (`kind=cpu|wall|wait|alloc`; `engine=async-profiler` when installed). Give the user the HTML path.
8. **Raw evidence when needed**: `jfr_view` (JDK built-in views: `hot-methods`, `contention-by-site`, `gc-pauses`, `allocation-by-site`...), `jfr_print` (specific events), `jfr_slice` (time-sliced .jfr with all threads and events, filtered .jfr, or JSONL export).
9. **Report** (format below) and offer to implement the fix in the user's code if it is in the workspace.

## Choosing sample type

- `jdk.ExecutionSample` / `jdk.CPUTimeSample` = on-CPU Java stacks (JDK recordings). Short windows (< ~100 ms) hold very few samples: use `mode=aggregate` for custom events, or a longer window.
- `profiler.WallClockSample` (async-profiler wall mode) = all threads incl. sleeping, with a `state` field. "Runnable" is the working subset; "sleeping" is where threads wait.
- Allocation: `jdk.ObjectAllocationSample` (JDK 16+), TLAB events (older), `profiler.*` alloc.

## Interpreting common patterns

See [references/playbooks.md](references/playbooks.md) for symptom → tool sequence → typical root causes → fixes. Quick map:

| Symptom | Look at |
| --- | --- |
| High CPU | `jfr_hot_methods` (runnable), flamegraph `kind=cpu`, regex/serialization/reflection in `jfr_jdk_breakdown` |
| Slow but CPU idle | `jfr_hot_methods` blocking section, `jfr_threads` blocked time, monitor/park/socket events, wall-clock `state=sleeping` |
| GC pauses | `jfr_diagnose` GC finding, `jfr_view gc-pauses`, allocation flamegraph `kind=alloc` |
| Virtual-thread stalls | `jfr_threads` virtual section (pinned events), carrier saturation, `synchronized` + blocking on JDK < 24 |
| One request type is slow | custom event → `jfr_zoom_custom_event` (single occurrence) or `mode=aggregate` |

## Report format

1. **Verdict** in one or two sentences, quantified (e.g. "62% of runnable samples in `OrderService.price` -> `Pattern.matches`, 14 s of 30 s").
2. **Evidence**: tool, window, thread, numbers; one representative stack (app frames first).
3. **Root cause**: why, based on the evidence. Say what is uncertain.
4. **Fix**: concrete code or config change, the expected effect, and how to re-record to verify (command with `settings=profile`).
5. **Other findings** worth knowing, ranked; no filler.

## Rules

- State recording limits honestly: sampling interval, window length, missing event types, JDK version (e.g. pinned events absent on JDK 24+ is normal).
- Do not claim a method is slow from < ~100 samples without saying so.
- Do not write Python or shell parsers for the JFR; use the tools. For raw access use `jfr_print` / `jfr_slice`.
- Times are offsets from recording start (`12.5s`, `250ms`, `2m`) or ISO-8601 instants.
- Large recordings: always narrow with `from`/`to`/`thread`/`top` before printing raw events.
