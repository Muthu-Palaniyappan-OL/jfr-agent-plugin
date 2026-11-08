package com.muthupalaniyappan.jfragent;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import java.util.*;

/** MCP server entry point (stdio). Never write to stdout except through the transport. */
public final class Main {
    interface Handler { Md call(Map<String, Object> args) throws Exception; }

    static final String VERSION = "1.0.0";
    static final List<SyncToolSpecification> TOOLS = new ArrayList<>();

    // ---- common schema fragments ----
    static final String FILE = "\"file\":{\"type\":\"string\",\"description\":\"Path to the .jfr recording (JDK JFR or async-profiler JFR output)\"}";
    static final String WINDOW = """
            "from":{"type":"string","description":"Zoom start. Offset from recording start ('12.5s','250ms','2m', or plain seconds) or ISO-8601 instant. Use jfr_timeline to find it."},
            "to":{"type":"string","description":"Zoom end, same formats as 'from'."},
            "thread":{"type":"string","description":"Only this thread: case-insensitive substring of the thread name, 're:<regex>', or 'virtual' for all virtual threads."},
            "virtual_threads":{"type":"string","enum":["any","only","exclude"],"description":"Include/exclude virtual threads (default any)."}""";
    static final String SAMPLE = """
            "source":{"type":"string","enum":["auto","cpu","wall","all"],"description":"Which sample events: auto = jdk.ExecutionSample/CPUTimeSample if present, else async-profiler wall-clock."},
            "state":{"type":"string","enum":["runnable","sleeping","all"],"description":"Thread state filter for samples (default runnable = on-CPU/working; sleeping = waiting/parked/blocked)."}""";

    static String schema(boolean needFile, String... props) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        List<String> all = new ArrayList<>(List.of(props));
        if (needFile) all.add(0, FILE);
        sb.append(String.join(",", all)).append("}");
        if (needFile) sb.append(",\"required\":[\"file\"]");
        return sb.append("}").toString();
    }

    static String prop(String name, String type, String desc) {
        return "\"" + name + "\":{\"type\":\"" + type + "\",\"description\":\"" + desc.replace("\"", "'") + "\"}";
    }

    static void tool(String name, String desc, String schema, Handler h) {
        var tool = McpSchema.Tool.builder().name(name).description(desc).inputSchema(McpJsonDefaults.getMapper(), schema).build();
        TOOLS.add(SyncToolSpecification.builder().tool(tool).callHandler((exchange, req) -> {
            try {
                Map<String, Object> args = req.arguments() == null ? Map.of() : req.arguments();
                String text = h.call(args).toString();
                if (text.length() > 120_000) text = text.substring(0, 120_000) + "\n...[truncated, narrow with from/to/thread/top]";
                return CallToolResult.builder().addTextContent(text).isError(false).build();
            } catch (Throwable t) {
                t.printStackTrace(); String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                return CallToolResult.builder().addTextContent("ERROR: " + msg).isError(true).build();
            }
        }).build());
    }

    static void register() {
        tool("jfr_env", "Check the environment: discovered JDKs (highest version preferred), jfr CLI, async-profiler jfrconv/asprof. Returns WARNINGS with instructions when a tool is missing. Call this first if anything fails.",
                schema(false), a -> Cli.env());
        tool("jfr_configure", "Persist custom tool locations in ~/.jfr-agent/config.json: javaHome (custom JDK), asyncProfilerHome, jfrconvPath, jfrPath. Pass '-' to clear a value.",
                schema(false, prop("javaHome", "string", "Custom JDK home"), prop("asyncProfilerHome", "string", "async-profiler install dir"),
                        prop("jfrconvPath", "string", "Explicit jfrconv executable"), prop("jfrPath", "string", "Explicit jfr executable")), Cli::configure);
        tool("jfr_overview", "First look at a recording: duration, JVM/OS/CPU/GC info, which kinds of data it has (CPU samples, wall samples, allocation, locks, virtual threads, custom events) and top event types.",
                schema(true, prop("top", "number", "Rows in the event table (default 15)")), a -> Cli.overview(Rec.open(s(a, "file")), a));
        tool("jfr_diagnose", "Expert opinion on the recording (or a zoomed window): GC, CPU, locks, parking, I/O, virtual-thread pinning, exceptions, allocation, data quality, custom events. Returns severity-ranked findings with advice and the next tool to call. Best second call after jfr_overview.",
                schema(true, WINDOW, SAMPLE), a -> { Rec r = Rec.open(s(a, "file")); return Diagnose.run(r, r.filter(a), a); });
        tool("jfr_events", "List ALL event types present in the recording with counts, origin (JDK / async-profiler / CUSTOM), category, and custom event fields.",
                schema(true, prop("filter", "string", "Substring filter on event name"), prop("include_empty", "boolean", "Also list event types with 0 events")), a -> Cli.events(Rec.open(s(a, "file")), a));
        tool("jfr_custom_events", "Find and document all application-defined (custom) events: count, total/avg/p95/max duration, active time range, fields. Pass 'name' to list the slowest/first occurrences with field values and timestamps (then zoom with jfr_zoom_custom_event).",
                schema(true, WINDOW, prop("name", "string", "Custom event type to list occurrences of"), prop("order", "string", "slowest (default) or time"),
                        prop("match", "string", "Only occurrences whose field values contain this text"), prop("top", "number", "Rows (default 15)")), a -> Custom.list(Rec.open(s(a, "file")), a));
        tool("jfr_hot_methods", "Which methods are slowing the app down. From stack samples (CPU or wall-clock) it attributes time to the first application frame (JDK/framework time is charged to the app caller), also leaf frames, inclusive time, hot lines, representative stacks, and blocking events (locks, park, sleep, I/O, virtual thread pinning) by app method. Supports time window and thread filters.",
                schema(true, WINDOW, SAMPLE, prop("top", "number", "Rows per table (default 15)"), prop("include_wait_events", "boolean", "Add blocking-event section (default true)")),
                a -> { Rec r = Rec.open(s(a, "file")); return Profile.hot(r, r.filter(a), a); });
        tool("jfr_jdk_breakdown", "Where, how and where else the JDK/runtime spends time: leaf ownership (app/framework/JDK/native), JDK categories (locks, network I/O, file I/O, regex, reflection, crypto, collections, virtual-thread scheduling, native waits...) each with hot JDK methods and the application callers driving them, plus GC/JIT/safepoint/lock/I/O event totals.",
                schema(true, WINDOW, SAMPLE, prop("top", "number", "Categories to show (default 8)")), a -> { Rec r = Rec.open(s(a, "file")); return Profile.jdk(r, r.filter(a), a); });
        tool("jfr_threads", "Thread-level view: thread groups (pool members collapsed, virtual threads aggregated), busiest threads, blocking time per group, virtual thread usage and pinning. Use to decide which thread to pass as 'thread' to other tools.",
                schema(true, WINDOW, SAMPLE, prop("top", "number", "Rows (default 15)")), a -> { Rec r = Rec.open(s(a, "file")); return Profile.threads(r, r.filter(a), a); });
        tool("jfr_timeline", "Time-bucketed view (runnable/sleeping samples, JVM CPU, GC pause, lock/park wait, custom events, top method per bucket). Find the slow period, then zoom with from/to.",
                schema(true, WINDOW, SAMPLE, prop("buckets", "number", "Number of buckets (2-200, default 20)")), a -> { Rec r = Rec.open(s(a, "file")); return Profile.timeline(r, r.filter(a), a); });
        tool("jfr_zoom_custom_event", "Zoom into a custom event and show what the application and the JDK did during it. mode=occurrence (default) zooms to one occurrence (rank 1 = slowest) on its thread; mode=aggregate analyses all occurrences together. Output: hot methods + JDK/runtime breakdown for exactly that time.",
                schema(true, prop("name", "string", "Custom event type (see jfr_custom_events)"), prop("mode", "string", "occurrence (default) or aggregate"), prop("rank", "number", "Which occurrence (1 = slowest, or Nth by time if order=time)"),
                        prop("order", "string", "slowest (default) or time"), prop("match", "string", "Only occurrences whose field values contain this text"), prop("all_threads", "boolean", "Include all threads, not just the event's thread (default false)"),
                        prop("pad_ms", "number", "Extend the window by this many ms on both sides"), prop("thread", "string", "Further restrict to threads matching"), prop("virtual_threads", "string", "any|only|exclude"),
                        prop("source", "string", "auto|cpu|wall|all"), prop("state", "string", "runnable|sleeping|all"), prop("top", "number", "Rows (default 15)")),
                a -> Custom.zoom(Rec.open(s(a, "file")), a));
        tool("jfr_flamegraph", "Generate a flamegraph (self-contained interactive HTML + folded stacks) for the whole recording or any time window / thread / virtual threads. kind: cpu (default), wall, wait (blocking events weighted by time), alloc. engine: builtin (default) or async-profiler (uses jfrconv; html, collapsed, pprof...). Returns file paths and top frames.",
                schema(true, WINDOW, SAMPLE, prop("kind", "string", "cpu|wall|wait|alloc|cpu-time"), prop("engine", "string", "builtin|async-profiler"), prop("output", "string", "Output file path (default in temp dir jfr-agent/)"),
                        prop("split_threads", "boolean", "Add thread name as the root frame"), prop("reverse", "boolean", "Leaf-first (icicle by callee)"), prop("min_pct", "number", "Hide frames below this % of total (builtin, default 0.02)"),
                        prop("max_depth", "number", "Max stack depth (builtin)"), prop("format", "string", "async-profiler engine: html|collapsed|pprof|heatmap"), prop("lines", "boolean", "async-profiler engine: show line numbers")),
                a -> { Rec r = Rec.open(s(a, "file")); return Flame.run(r, r.filter(a), a); });
        tool("jfr_view", "Run `jfr view <view>` from the JDK (hot-methods, gc-pauses, contention-by-site, allocation-by-site, longest-compilations, safepoints, ... or an event type). Call without 'view' to list them.",
                schema(true, prop("view", "string", "View or event name"), prop("width", "number", "Table width (default 160)"), prop("verbose", "boolean", "Show the underlying query"), prop("max_chars", "number", "Output cap")), a -> Cli.view(Rec.open(s(a, "file")), a));
        tool("jfr_print", "Run `jfr print` for specific events/categories (text or json) with stack depth; output capped. Use to inspect raw events.",
                schema(true, prop("events", "string", "Comma separated event names, e.g. jdk.JavaMonitorEnter"), prop("categories", "string", "Category filter"), prop("format", "string", "text|json"),
                        prop("stack_depth", "number", "Frames (default 8)"), prop("max_chars", "number", "Output cap (default 30000)")), a -> Cli.print(Rec.open(s(a, "file")), a));
        tool("jfr_slice", "Slice a recording. (1) from/to: a timestamp-sliced .jfr containing ALL threads and ALL event types overlapping the window (lossless, same writer as `jfr scrub`). (2) include/exclude threads/events/categories: a filtered .jfr via `jfr scrub` (JDK filter syntax, globs ok). (3) jfr=false with from/to: JSON-lines export for analysis only.",
                schema(true, WINDOW, prop("include_threads", "string", "jfr scrub filter"), prop("exclude_threads", "string", "jfr scrub filter"), prop("include_events", "string", "jfr scrub filter"),
                        prop("exclude_events", "string", "jfr scrub filter"), prop("include_categories", "string", "jfr scrub filter"), prop("exclude_categories", "string", "jfr scrub filter"),
                        prop("events", "string", "JSONL export only: comma separated event names"), prop("output", "string", "Output .jfr (or .jsonl for jfr=false) path"), prop("limit", "number", "Max JSONL events when jfr=false (default 50000)"), prop("jfr", "boolean", "true (default): write .jfr; false: JSONL export for a window")),
                a -> { Rec r = Rec.open(s(a, "file")); return Cli.slice(r, r.filter(a), a); });
    }

    static String s(Map<String, Object> a, String k) { Object o = a.get(k); return o == null ? null : o.toString(); }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--version")) { System.out.println("jfr-agent-mcp " + VERSION); return; }
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        register();
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        var server = McpServer.sync(transport)
                .serverInfo("jfr-agent", VERSION)
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .instructions("JFR analysis server. Typical flow: jfr_env (if problems) -> jfr_overview -> jfr_diagnose -> jfr_timeline -> jfr_hot_methods / jfr_threads / jfr_jdk_breakdown -> jfr_flamegraph. Custom events: jfr_custom_events -> jfr_zoom_custom_event. Times: offsets from recording start like '12.5s'.")
                .tools(TOOLS)
                .build();
        Thread.currentThread().join();
    }
}
