# jfr-agent-plugin

An [Agent Plugin](https://agent-plugins.org/) that lets an AI agent analyse **Java Flight Recorder** and **async-profiler** recordings and help fix the performance bottleneck. You give it a `.jfr` and a scenario ("checkout is slow after 10 s"); the agent drives a Java MCP server that parses the recording with the JDK's own JFR API (`jdk.jfr.consumer`), the `jfr` CLI and, optionally, async-profiler's `jfrconv`.

Requirements: JDK 25 (recommended; the server targets Java 25 and finds the highest installed JDK), Maven 3.9+ or the included wrapper (first run builds the jar), bash (macOS/Linux) or PowerShell+cmd (Windows). async-profiler is optional.

## Layout

```
plugin.json               plugin manifest
mcp.json                  MCP server definition (bash on macOS/Linux, cmd on Windows)
skills/jfr-analyzer/      agent skill: workflow, report format, playbooks
mcp-server/               Java 25 MCP server (Maven, shaded jar)
bin/launch.sh|.cmd        launchers: pick highest JDK, build jar on first run
scripts/call-tool.sh      call any tool from a shell (smoke tests, no MCP client needed)
examples/DemoWorkload.java  workload with custom events, virtual threads, hot spots
docs/                     ARCHITECTURE.md, SPEC.md (roadmap), TASKS.md at root
```

## Install

```bash
git clone <this repo> && cd jfr-agent-plugin
(cd mcp-server && ./mvnw -q -DskipTests package)      # optional; launcher builds it on demand
```
Install the directory as a plugin in an agent client that supports Agent Plugins, or register the server manually:

```json
{ "mcpServers": { "jfr-agent": { "command": "bash", "args": ["/abs/path/jfr-agent-plugin/bin/launch.sh"] } } }
```
Windows: `"command": "cmd", "args": ["/c", "C:\\abs\\path\\jfr-agent-plugin\\bin\\launch.cmd"]`.
`mcp.json` uses a `${PLUGIN_ROOT}` placeholder; if your client uses a different variable name (for example `${CLAUDE_PLUGIN_ROOT}`), change it there.

## JDK and tool discovery

Highest JDK version wins (like Gradle toolchains), searched in: pinned config, `JAVA_HOME`, macOS `/Library/Java/JavaVirtualMachines` + Homebrew, Linux `/usr/lib/jvm` etc., Windows `Program Files\{Java,Eclipse Adoptium,Microsoft,...}`, SDKMAN, asdf, jenv, `~/.jdks`, `~/.gradle/jdks`, `PATH`.

Pin or override (any of):
- env `JFR_AGENT_JAVA_HOME`, `ASYNC_PROFILER_HOME`, `JFR_AGENT_JFRCONV`, `JFR_AGENT_JFR`
- `~/.jfr-agent/config.json`: `{"javaHome":"/path/jdk","asyncProfilerHome":"/path/async-profiler","jfrconvPath":"...","jfrPath":"..."}`
- the `jfr_configure` tool (asks the user for a path when something is missing)

Missing tools are never silent: `jfr_env` and the tools needing them print a WARNING with install and configure instructions. Built-in analysis works with only a JDK 21+.

## Tools

| Tool | Purpose |
| --- | --- |
| `jfr_env`, `jfr_configure` | Show/configure JDKs, `jfr`, async-profiler |
| `jfr_overview` | Duration, JVM/OS/CPU/GC, which data exists |
| `jfr_diagnose` | Opinion: severity-ranked findings, advice, next tool |
| `jfr_events` | All event types and counts, JDK vs async-profiler vs custom |
| `jfr_custom_events` | Custom events: stats, fields, slowest occurrences |
| `jfr_timeline` | Time buckets to find where to zoom |
| `jfr_hot_methods` | Slow methods: CPU/wall samples + blocking events, app-attributed |
| `jfr_jdk_breakdown` | Where/how the JDK spends time and who drives it |
| `jfr_threads` | Thread groups, virtual threads, pinning, blocked time |
| `jfr_zoom_custom_event` | What happened (app + JDK) during one/all occurrences of a custom event |
| `jfr_flamegraph` | HTML + folded; cpu/wall/wait/alloc; window/thread; built-in or async-profiler |
| `jfr_view`, `jfr_print` | JDK `jfr view` / `jfr print` |
| `jfr_slice` | Timestamp-sliced `.jfr` (all threads and events, lossless), filtered `.jfr` (jfr scrub), or JSONL window export |

Common filters on analysis tools: `from`, `to` (`12.5s`, `250ms`, `2m`, ISO-8601), `thread` (substring, `re:regex`, `virtual`), `virtual_threads` (`any|only|exclude`), `source` (`auto|cpu|wall|all`), `state` (`runnable|sleeping|all`).

## Try it

```bash
java -XX:StartFlightRecording:filename=/tmp/demo.jfr,settings=profile examples/DemoWorkload.java
scripts/call-tool.sh jfr_diagnose '{"file":"/tmp/demo.jfr"}'
scripts/call-tool.sh jfr_zoom_custom_event '{"file":"/tmp/demo.jfr","name":"demo.RequestHandled","mode":"aggregate","match":"/regex"}'
scripts/call-tool.sh jfr_flamegraph '{"file":"/tmp/demo.jfr","thread":"virtual"}'
```

## Known limits

- `jfr_slice` with `from`/`to` writes a lossless time-sliced `.jfr` (every thread and event type in the window), using the same writer as `jfr scrub`. Analysis tools also accept `from`/`to` directly.
- Files are streamed per call (no full in-memory load); a metadata pass is cached per file. Very large files (GB) take seconds per tool call.
- Reading a recording from a newer JDK needs a server JDK at least as new; the launcher therefore prefers the highest JDK.
- `mcp.json` variable naming and per-OS override keys differ between clients; see above. The Agent Plugins spec repository was not reachable while building, so the manifest follows the layout on agent-plugins.org.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/SPEC.md](docs/SPEC.md) and [TASKS.md](TASKS.md).
