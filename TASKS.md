# JFR Agent Plugin - Task List

Legend: [x] done, [ ] open

## A. Foundation
- [x] A1 Maven project, Java 25, MCP Java SDK, shaded jar (`mcp-server/`)
- [x] A2 JDK discovery (highest version wins, configurable) `Env`
- [x] A3 External tool discovery (jfr, jfrconv, asprof) with warnings `Env`
- [x] A4 Recording reader, metadata cache, filters (time, thread, virtual threads) `Rec`
- [x] A5 Frame/thread helpers, JDK vs framework vs app classification, virtual-thread labels `Frames`

## B. MCP tools (`Main` registers all)
- [x] B1 `jfr_env`, `jfr_configure` - environment check, custom paths
- [x] B2 `jfr_overview` - recording summary, JVM info, window
- [x] B3 `jfr_events` - all event types with counts
- [x] B4 `jfr_custom_events` - custom events, stats, top occurrences
- [x] B5 `jfr_hot_methods` - slow methods (CPU/wall + wait events)
- [x] B6 `jfr_jdk_breakdown` - where the JDK spends time
- [x] B7 `jfr_threads` - thread-level view (groups, virtual threads, pinning)
- [x] B8 `jfr_timeline` - bucketed timeline to find where to zoom
- [x] B9 `jfr_zoom_custom_event` - what happened during a custom event
- [x] B10 `jfr_flamegraph` - built-in HTML + folded, async-profiler jfrconv engine, time/thread windows
- [x] B11 `jfr_diagnose` - opinion on the recording + findings + next steps
- [x] B12 `jfr_view`, `jfr_print` - wrap the JDK `jfr` CLI
- [x] B13 `jfr_slice` - lossless time-sliced JFR (all threads and events), scrub by thread/event (jfr scrub), JSONL export

## C. Packaging
- [x] C1 Launchers `bin/launch.sh`, `bin/launch.cmd`, `bin/find-jdk.ps1` (JDK search, auto build)
- [x] C2 Maven wrapper
- [x] C3 `plugin.json`, `mcp.json`
- [x] C4 Skill `skills/jfr-analyzer` + references
## D. Docs and verification
- [x] D1 README, docs/ARCHITECTURE.md, docs/SPEC.md (future enhancements)
- [x] D2 Smoke test every tool against `/private/tmp/tmp/profile.jfr` (async-profiler wall-clock)
- [x] D3 Smoke test against a JDK-recorded JFR with virtual threads and custom events (JDK 25 and JDK 21 pinning)

## E. Open follow-ups (see docs/SPEC.md roadmap)
- [ ] E1 Validate `bin/launch.cmd` + `find-jdk.ps1` on Windows and `launch.sh` on Linux (written, not run)
- [ ] E2 Unit/CI tests (GitHub Actions matrix) - none yet; smoke tests via scripts/call-tool.sh only
- [ ] E3 Differential analysis, request tracing (time-sliced JFR done, see B13)
