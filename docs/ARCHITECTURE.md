# Architecture

```
agent  <-stdio MCP->  launch.(sh|cmd)  ->  java -jar jfr-agent-mcp.jar
                                             Main (tool registry, schemas)
                                               |-- Rec      open/cache metadata, stream events, filters, time parsing
                                               |-- Frames   thread/stack helpers, JDK/framework/app classification, virtual threads
                                               |-- Profile  hot methods, JDK breakdown, threads, timeline, pinning
                                               |-- Custom   custom events, zoom (occurrence / aggregate)
                                               |-- Flame    built-in HTML+folded, async-profiler jfrconv
                                               |-- Diagnose health checks -> findings
                                               |-- Cli      env/configure/overview/events + `jfr view|print|scrub`
                                               |-- Env/Proc JDK + tool discovery, config, process runner
```

## Principles
- **Stdout is the protocol.** Logs go to stderr (`slf4j-simple` to `System.err`). Never print to stdout.
- **Stream, don't load.** `Rec.each` reads with `RecordingFile` and applies a `Rec.Filter` (event types, time, thread, virtual-only, extra predicate). Only metadata (counts, event types, start/end) is cached, keyed by path+size+mtime.
- **Attribute to application code.** Samples are charged to the first application frame (not `java.*`, `jdk.*`, `sun.*`, native, or a known framework prefix) so the first table answers "which of my methods". Leaf, inclusive and per-line views remain available.
- **Skip noise by default.** `state=runnable` for samples, idle pool parking reported separately, JMX/RMI/GC/JIT threads show up as their own groups.
- **Tools return markdown** sized for an LLM (top-N tables, representative stacks). Output over 120k chars is truncated with a hint to narrow filters.
- **Virtual threads**: `RecordedThread.isVirtual()`; label `name [virtual#id]`; all virtual threads group into `<virtual threads>`; `thread=virtual` and `virtual_threads` filters; `jdk.VirtualThreadPinned` aggregated by application frame.
- **Sample sources**: `jdk.ExecutionSample`, `jdk.CPUTimeSample`, `jdk.NativeMethodSample`, `profiler.WallClockSample` (state + `samples` weight).

## Zoom model
Every analysis tool accepts `from`/`to` (offset or ISO). `jfr_zoom_custom_event` derives the window from a custom event: `occurrence` = [start,end] of the Nth slowest/earliest occurrence on its thread; `aggregate` = union of all occurrences, matched per thread by binary search over sorted intervals.

## JDK / tool discovery (`Env`, `launch.*`)
Pinned (config/env) > highest version among known install locations + `JAVA_HOME` + `PATH`. `release` file gives the version, no process spawn needed. `jfr`, `jfrconv`, `asprof` resolved from config, env, `ASYNC_PROFILER_HOME`, `PATH`, Homebrew. Missing -> warning text with remediation.

## Flamegraph
Builtin: tree built from stacks (root first), pruned by `min_pct`, serialised to JSON inside a self-contained HTML (canvas, click-to-zoom, regex search); folded stacks written next to it. Weights: samples (cpu/wall), microseconds blocked (wait), bytes (alloc). Async-profiler engine: `jfrconv -o html --cpu|--wall|--alloc|--lock --from --to [--threads --include]`.

## Build
`mcp-server/pom.xml`: Java 25 release, MCP Java SDK 1.1.5 (Jackson 3 inside), shade plugin -> `target/jfr-agent-mcp.jar`. The Maven wrapper is included.

## Testing
`scripts/call-tool.sh <tool> '<json>'` speaks JSON-RPC over stdio. Fixtures: `examples/DemoWorkload.java` (JDK 25: custom events, regex hotspot, fib CPU, alloc churn, virtual threads; JDK 21 additionally produces pinning) and any async-profiler JFR.
