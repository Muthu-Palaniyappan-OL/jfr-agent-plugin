# Spec and roadmap

## Goal
Given a JFR and a scenario, find and explain the performance bottleneck and guide the fix, while skipping irrelevant parts of the recording.

## Capability matrix (status)
| Capability | Status | Tool |
| --- | --- | --- |
| Parse and list all events | done | `jfr_events`, `jfr_overview` |
| Record custom events | done | `jfr_custom_events` |
| Which method is slowing things down | done | `jfr_hot_methods` |
| Where the JDK spends time | done | `jfr_jdk_breakdown` |
| Flamegraphs (cpu/wall/wait/alloc) | done | `jfr_flamegraph` |
| Thread-level view | done | `jfr_threads`, `thread` filter |
| Virtual threads | done (pinning, grouping, filters) | `jfr_threads`, `virtual_threads` |
| Zoom to time stamp | done | `from`/`to` on all tools, `jfr_timeline` |
| Zoom to custom event | done | `jfr_zoom_custom_event` |
| General opinion | done (rule-based) | `jfr_diagnose` |
| Sub-timing flamegraph | done | `jfr_flamegraph from= to=` |
| Sub-timing JFR | done: lossless `.jfr` time slice (all threads and events), `.jfr` by thread/event/category, JSONL export | `jfr_slice` |
| Custom Java MCP + `jfr` CLI + async-profiler | done | all |
| JDK discovery + custom JDK + warnings | done | `jfr_env`, `jfr_configure`, launchers |
| macOS / Linux / Windows | launchers written for all three; **tested on macOS only** | `bin/` |

## Tool contract
- Input: always `file`; optional `from`, `to`, `thread`, `virtual_threads`, `source`, `state`, `top`.
- Output: markdown text; errors as `isError=true` with `ERROR: <Type>: <message>`.
- Time: offset from recording start (`12.5s`, `250ms`, `2m`, bare number = seconds) or ISO-8601 instant.
- Thread filter: case-insensitive substring of thread label, `re:<regex>`, or `virtual`.
- Defaults must never emit more than ~120k chars.

## Acceptance checks (run with `scripts/call-tool.sh`)
1. `jfr_env` lists JDKs highest-first and warns on missing `jfr`/async-profiler.
2. On a JDK recording of `DemoWorkload`, `jfr_diagnose` flags `slowRegex` as dominant; `jfr_custom_events` shows `demo.RequestHandled` with count and p95.
3. `jfr_zoom_custom_event mode=aggregate match=/regex` attributes samples to `slowRegex`, split platform vs `<virtual threads>`.
4. On a JDK 21 recording, `jfr_threads` shows `jdk.VirtualThreadPinned` sites; on JDK 25 none.
5. `jfr_flamegraph` writes an HTML (valid JS) and `.folded`; `engine=async-profiler` works when `jfrconv` exists, else prints a warning.
6. On an async-profiler wall-clock JFR, `jfr_hot_methods` splits runnable vs sleeping and does not crash on native-only frames.

## Future enhancements (prioritised)
1. **Differential analysis**: compare two recordings (before/after fix) for methods, GC, locks; diff flamegraph (jfrconv `--diff` or built-in).
2. **True time-sliced JFR**: done via `jdk.jfr.internal.consumer.JdkJfrConsumer` (see `Slicer.java`); requires `--add-exports` flags, set in the launchers.
3. **Wall-clock-like view for JDK recordings**: combine ExecutionSample + ThreadPark/Sleep/Monitor/Socket durations into per-thread state timelines; request-level latency breakdown.
4. **Request tracing**: correlate custom events with `jdk.ThreadPark`, `SocketRead` and samples via thread+time and optional span/trace-id field; percentile breakdown per endpoint.
5. **Allocation and leak analysis**: `jdk.OldObjectSample` reference chains, allocation rate per phase, retained-size hints.
6. **Native memory / off-heap**: `profiler.Malloc/Free`, `jdk.NativeMemoryUsage*`.
7. **Smarter opinion**: configurable rule thresholds (`~/.jfr-agent/rules.json`), framework recognition (Spring/Netty/Hibernate/JDBC pool) with specific remedies, container limits (cgroup CPU throttling from `jdk.ContainerCPUThrottling`).
8. **Live attach**: start/stop recordings via `jcmd`/`asprof` (`jfr_record`) and analyse straight away; guarded behind explicit user confirmation.
9. **Heatmap/time-resolved flamegraph** and per-bucket flamegraph links in `jfr_timeline`.
10. **Persistent index cache** for multi-GB recordings (event offsets, per-thread sample index) to make zooms O(window).
11. **Packaging**: prebuilt release jar (skip Maven for users), `jlink` runtime, CI matrix macOS/Linux/Windows with JDK 21/25, Windows validation.
12. **MCP 2.x SDK migration** and structured (`outputSchema`) results beside markdown.
13. **Source linking**: map frames to files in the user's workspace and propose patches.

## Non-goals
Python tooling for parsing, GUI, shipping JMC, modifying the target application.

## Known risks
- Client-specific `mcp.json` conventions (plugin root variable, OS overrides) may require small edits.
- Framework classification list in `Frames.FRAMEWORK` is heuristic; unusual package names are treated as application code.
- Wall-clock "runnable" from async-profiler can include threads parked in native code that it marked runnable; use the JDK category table to cross-check.
