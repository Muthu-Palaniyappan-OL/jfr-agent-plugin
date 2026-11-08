package com.muthupalaniyappan.jfragent;

import jdk.jfr.consumer.*;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Environment, overview, event catalogue, and wrappers around the JDK `jfr` CLI. */
final class Cli {

    static Md env() {
        Md md = new Md().h(2, "JFR agent environment");
        List<String> warnings = new ArrayList<>();
        md.p("**OS:** " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + "; **MCP server JVM:** " + System.getProperty("java.version") + " (" + System.getProperty("java.home") + ")");
        md.p("**Config file:** `" + Env.CONFIG + "` " + (Files.exists(Env.CONFIG) ? "(exists)" : "(not created; optional)"));
        List<Env.Jdk> jdks = Env.discoverJdks();
        md.h(3, "JDKs found (highest version first; configured one wins)");
        List<List<String>> rows = new ArrayList<>();
        for (Env.Jdk j : jdks) rows.add(List.of(String.valueOf(j.major()), j.home().toString(), j.hasJfr() ? "yes" : "no", j.source()));
        md.table(List.of("Major", "Home", "Has jfr", "Found via"), rows);
        if (jdks.isEmpty()) warnings.add("No JDK found. Install JDK 25 (recommended; 21+ works) and set JAVA_HOME or call `jfr_configure javaHome=...`.");
        Optional<Env.Jdk> best = Env.bestJdkWithJfr();
        best.ifPresent(j -> md.p("**Selected for CLI work:** JDK " + j.major() + " at `" + j.home() + "`"));
        if (best.isPresent() && best.get().major() < 21) warnings.add("Selected JDK is " + best.get().major() + ". Virtual-thread events and `jfr view` need 21+; JDK 25 recommended. A JFR written by a newer JDK may not parse on an older one.");
        if (Env.jfrCli().isEmpty()) warnings.add("`jfr` CLI not found (needs a JDK, not a JRE). Set `jfr_configure jfrPath=/path/to/jfr`. Built-in analysis still works; `jfr_view`, `jfr_print`, `jfr_slice` need it.");
        md.p("**jfr CLI:** " + Env.jfrCli().map(Path::toString).orElse("NOT FOUND"));
        md.p("**async-profiler jfrconv:** " + Env.jfrconv().map(Path::toString).orElse("not found (optional)"));
        md.p("**async-profiler asprof:** " + Env.asprof().map(Path::toString).orElse("not found (optional)"));
        if (Env.jfrconv().isEmpty()) warnings.add("async-profiler not found (optional). For `engine=async-profiler` flamegraphs download from https://github.com/async-profiler/async-profiler/releases, then put `jfrconv` on PATH, set ASYNC_PROFILER_HOME, or call `jfr_configure asyncProfilerHome=/path/to/async-profiler`.");
        if (!warnings.isEmpty()) {
            md.h(3, "WARNINGS - action needed");
            warnings.forEach(w -> md.li("**" + w + "**"));
        } else md.p("\nAll tools available.");
        return md;
    }

    static Md configure(Map<String, Object> a) throws IOException {
        ObjectNode cfg = Env.config();
        Md md = new Md();
        for (String k : new String[]{"javaHome", "asyncProfilerHome", "jfrconvPath", "jfrPath"}) {
            Object v = a.get(k);
            if (v == null) continue;
            String s = v.toString().trim();
            if (s.isEmpty() || s.equals("-")) { cfg.remove(k); md.li("cleared " + k); continue; }
            Path p = Path.of(s.replaceFirst("^~", System.getProperty("user.home")));
            if (!Files.exists(p)) { md.li("**WARNING:** `" + k + "` path does not exist: " + p + " (not saved)"); continue; }
            cfg.put(k, p.toString());
            md.li("saved " + k + " = " + p);
        }
        Env.saveConfig(cfg);
        md.p("Saved to `" + Env.CONFIG + "`. Equivalent env vars: JFR_AGENT_JAVA_HOME, ASYNC_PROFILER_HOME, JFR_AGENT_JFRCONV, JFR_AGENT_JFR. Note: the MCP server JVM itself is chosen by the launcher (`bin/launch.*`) at startup, a changed javaHome applies to the CLI tools now and to the server after restart.");
        md.sb.append(env());
        return md;
    }

    static Md overview(Rec r, Map<String, Object> a) throws IOException {
        Md md = new Md().h(2, "Recording overview");
        md.p("**File:** `" + r.path + "` (" + Files.size(r.path) / 1024 + " KiB)");
        md.p("**Start:** " + r.start + "  **End:** " + r.end + "  **Length:** " + String.format("%.2f s", r.length().toNanos() / 1e9) + "  **Events:** " + r.total + "  **Event types with data:** " + r.counts.size());
        md.p("**Layout:** " + r.chunks.size() + " chunk(s), largest " + (r.chunks.stream().mapToLong(Rec.Chunk::size).max().orElse(0) >> 20) + " MiB; heap " + (Runtime.getRuntime().maxMemory() >> 20) + " MiB");
        if (!r.warning.isBlank()) md.p("**WARNING:** " + r.warning);
        Rec.Filter info = new Rec.Filter();
        info.types = Set.of("jdk.JVMInformation", "jdk.OSInformation", "jdk.CPUInformation", "jdk.GCConfiguration", "jdk.InitialSystemProperty", "jdk.ActiveRecording");
        info.from = r.start; info.to = r.start.plusSeconds(5); // written at the start of each chunk; avoids parsing the rest of a large file
        Map<String, String> props = new TreeMap<>();
        r.each(info, e -> {
            switch (e.getEventType().getName()) {
                case "jdk.JVMInformation" -> { props.put("JVM", Diagnose.str(e, "jvmName") + " " + Diagnose.str(e, "jvmVersion")); props.put("JVM args", Diagnose.str(e, "jvmArguments")); props.put("Java args", Diagnose.str(e, "javaArguments")); props.put("PID", Diagnose.str(e, "pid")); }
                case "jdk.OSInformation" -> props.put("OS", Diagnose.str(e, "osVersion").split("\n")[0]);
                case "jdk.CPUInformation" -> props.put("CPU", Diagnose.str(e, "cpu").split("\n")[0] + " (" + Diagnose.str(e, "hwThreads") + " hw threads, " + Diagnose.str(e, "cores") + " cores)");
                case "jdk.GCConfiguration" -> props.put("GC", Diagnose.str(e, "youngCollector") + " / " + Diagnose.str(e, "oldCollector"));
                case "jdk.InitialSystemProperty" -> { String k = Diagnose.str(e, "key"); if (k.equals("java.vm.vendor") || k.equals("sun.java.command") || k.equals("java.version")) props.put(k, Diagnose.str(e, "value")); }
                case "jdk.ActiveRecording" -> props.put("Recording name/settings", Diagnose.str(e, "name") + ", maxAge=" + Diagnose.str(e, "maxAge"));
                default -> {}
            }
        });
        props.forEach((k, v) -> md.li("**" + k + ":** " + (v.length() > 300 ? v.substring(0, 300) + "..." : v)));
        long cpu = Rec.CPU_SAMPLES.stream().mapToLong(r::count).sum(), wall = Rec.WALL_SAMPLES.stream().mapToLong(r::count).sum();
        md.h(3, "Analysis capabilities");
        md.li("CPU samples: " + cpu + (cpu == 0 ? " (none)" : ""));
        md.li("Wall-clock samples (async-profiler): " + wall);
        md.li("Allocation events: " + (r.count("jdk.ObjectAllocationSample") + r.count("jdk.ObjectAllocationInNewTLAB") + r.count("jdk.ObjectAllocationOutsideTLAB") + r.count("profiler.Malloc")));
        md.li("Lock / park events: " + (r.count("jdk.JavaMonitorEnter") + r.count("jdk.ThreadPark")));
        md.li("Virtual thread events: " + (r.count("jdk.VirtualThreadStart") + r.count("jdk.VirtualThreadPinned")));
        md.li("Custom event types: " + Custom.customTypes(r));
        md.h(3, "Top event types by count");
        List<List<String>> rows = new ArrayList<>();
        r.counts.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(Profile.intArg(a, "top", 15))
                .forEach(en -> rows.add(List.of(en.getKey(), String.valueOf(en.getValue()))));
        md.table(List.of("Event", "Count"), rows);
        md.p("Next: `jfr_diagnose` for an opinion, `jfr_events` for the full catalogue.");
        return md;
    }

    static Md events(Rec r, Map<String, Object> a) {
        String filter = Profile.strArg(a, "filter", null);
        boolean includeEmpty = Profile.boolArg(a, "include_empty", false);
        Md md = new Md().h(2, "Event types");
        List<List<String>> rows = new ArrayList<>();
        Set<String> names = new TreeSet<>(r.types.keySet());
        names.addAll(r.counts.keySet());
        for (String n : names) {
            long c = r.count(n);
            if (c == 0 && !includeEmpty) continue;
            if (filter != null && !n.toLowerCase().contains(filter.toLowerCase())) continue;
            var t = r.types.get(n);
            String cat = t == null ? "" : String.join(" / ", t.getCategoryNames());
            rows.add(List.of(n, String.valueOf(c), Rec.isCustomName(n) ? "CUSTOM" : n.startsWith("profiler.") ? "async-profiler" : "JDK", cat,
                    t == null || !Rec.isCustomName(n) ? "" : Custom.fieldSummary(t)));
        }
        rows.sort((x, y) -> Long.compare(Long.parseLong(y.get(1)), Long.parseLong(x.get(1))));
        md.p(rows.size() + " event types listed" + (includeEmpty ? "" : " (with at least one event; `include_empty=true` for all)") + ".");
        return md.table(List.of("Event", "Count", "Origin", "Category", "Fields (custom only)"), rows);
    }

    static Path jfrOrThrow() {
        return Env.jfrCli().orElseThrow(() -> new IllegalStateException("`jfr` CLI not found. Install a JDK (21+, 25 recommended) or call `jfr_configure jfrPath=/path/to/jfr` / `javaHome=...`."));
    }

    static Md view(Rec r, Map<String, Object> a) throws IOException, InterruptedException {
        String view = Profile.strArg(a, "view", null);
        List<String> cmd = Proc.cmd(jfrOrThrow().toString(), "view");
        if (Profile.boolArg(a, "verbose", false)) cmd.add("--verbose");
        cmd.add("--width"); cmd.add(String.valueOf(Profile.intArg(a, "width", 160)));
        if (view == null) {
            var res = Proc.run(List.of(jfrOrThrow().toString(), "help", "view"), null, 30, 20000);
            return new Md().p("`view` is required (e.g. `hot-methods`, `gc-pauses`, `contention-by-site`, `allocation-by-site`, `longest-compilations`, `class-loaders`, `safepoints`, or any event type). Available:\n```\n" + res.out() + "\n```");
        }
        cmd.add(view); cmd.add(r.path.toString());
        var res = Proc.run(cmd, null, 120, Profile.intArg(a, "max_chars", 40000));
        return new Md().p("```\n" + res.out() + "\n```" + (res.truncated() ? "\n(output truncated)" : ""));
    }

    static Md print(Rec r, Map<String, Object> a) throws IOException, InterruptedException {
        List<String> cmd = Proc.cmd(jfrOrThrow().toString(), "print");
        if (Profile.strArg(a, "format", "text").equals("json")) cmd.add("--json");
        String ev = Profile.strArg(a, "events", null), cat = Profile.strArg(a, "categories", null);
        if (ev == null && cat == null) throw new IllegalArgumentException("Give `events` (e.g. jdk.JavaMonitorEnter) or `categories`, otherwise output is huge. See jfr_events.");
        if (ev != null) { cmd.add("--events"); cmd.add(ev); }
        if (cat != null) { cmd.add("--categories"); cmd.add(cat); }
        cmd.add("--stack-depth"); cmd.add(String.valueOf(Profile.intArg(a, "stack_depth", 8)));
        cmd.add(r.path.toString());
        var res = Proc.run(cmd, null, 300, Profile.intArg(a, "max_chars", 30000));
        return new Md().p("```\n" + res.out() + "\n```" + (res.truncated() ? "\n(output truncated at max_chars; narrow `events` or use jfr_slice for a time window)" : ""));
    }

    /**
     * Slice a recording. Output is a valid .jfr in all cases:
     *  - from/to given (jfr=true, default): timestamp slice keeping every thread and every event type that overlaps the window.
     *  - include/exclude thread/event/category filters: `jfr scrub`.
     *  - jfr=false with from/to: JSON-lines export (analysis only, not a JFR).
     */
    static Md slice(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException, InterruptedException {
        Md md = new Md();
        boolean timeWindow = base.from != null || base.to != null;
        String out = Profile.strArg(a, "output", null);
        boolean wantJfr = Profile.boolArg(a, "jfr", true);
        if (wantJfr && timeWindow) {
            Path o = out != null ? Path.of(out) : Path.of(System.getProperty("java.io.tmpdir"), "jfr-agent", r.path.getFileName().toString().replace(".jfr", "") + "-slice.jfr");
            Files.createDirectories(o.toAbsolutePath().getParent());
            long n = Slicer.writeWindow(r, base.from, base.to, o);
            md.p("**Time slice written:** `" + o + "` - " + n + " events, all threads and event types overlapping window " + Profile.windowText(r, base) + " (lossless JFR copy, usable with every tool).");
        }
        if (wantJfr && (Profile.strArg(a, "include_threads", null) != null || Profile.strArg(a, "exclude_threads", null) != null
                || Profile.strArg(a, "include_events", null) != null || Profile.strArg(a, "exclude_events", null) != null
                || Profile.strArg(a, "include_categories", null) != null || Profile.strArg(a, "exclude_categories", null) != null)) {
            Path o = out != null ? Path.of(out) : Path.of(System.getProperty("java.io.tmpdir"), "jfr-agent", r.path.getFileName().toString().replace(".jfr", "") + "-scrubbed.jfr");
            Files.createDirectories(o.toAbsolutePath().getParent());
            List<String> cmd = Proc.cmd(jfrOrThrow().toString(), "scrub");
            for (String[] p : new String[][]{{"include_events", "--include-events"}, {"exclude_events", "--exclude-events"}, {"include_categories", "--include-categories"},
                    {"exclude_categories", "--exclude-categories"}, {"include_threads", "--include-threads"}, {"exclude_threads", "--exclude-threads"}})
                if (Profile.strArg(a, p[0], null) != null) { cmd.add(p[1]); cmd.add(Profile.strArg(a, p[0], null)); }
            cmd.add(r.path.toString()); cmd.add(o.toString());
            var res = Proc.run(cmd, null, 600, 10000);
            md.p(res.exit() == 0 ? "**Filtered JFR written:** `" + o + "` (valid JFR, usable with every tool)" : "**jfr scrub failed:**\n```\n" + res.out() + "\n```");
        }
        if (!wantJfr && timeWindow) {
            Path o = Path.of(System.getProperty("java.io.tmpdir"), "jfr-agent", r.path.getFileName().toString().replace(".jfr", "") + "-window.jsonl");
            if (out != null && out.endsWith(".jsonl")) o = Path.of(out);
            Files.createDirectories(o.toAbsolutePath().getParent());
            Rec.Filter f = base.copy();
            String ev = Profile.strArg(a, "events", null);
            if (ev != null) f.types = new HashSet<>(Arrays.asList(ev.split(",")));
            int limit = Profile.intArg(a, "limit", 50000);
            int[] n = {0};
            try (var w = Files.newBufferedWriter(o, StandardCharsets.UTF_8)) {
                r.eachOrdered(f, e -> {
                    if (n[0] >= limit) return;
                    try {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("type", e.getEventType().getName());
                        m.put("start", r.rel(e.getStartTime()));
                        m.put("durationNs", e.getDuration().toNanos());
                        m.put("thread", Frames.threadLabel(Frames.thread(e)));
                        m.put("virtual", Frames.thread(e) != null && Frames.thread(e).isVirtual());
                        m.put("fields", Custom.values(e, 12));
                        m.put("stack", Profile.stackText(Frames.stack(e), 12));
                        w.write(Env.JSON.writeValueAsString(m));
                        w.newLine();
                        n[0]++;
                    } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
                });
            }
            md.p("**Window export:** `" + o + "` - " + n[0] + " events as JSON lines (window " + Profile.windowText(r, base) + ").");
        }
        if (md.sb.length() == 0) md.p("Nothing to do. Provide `from`/`to` for a time-sliced JFR, `include_threads`/`exclude_threads`/`include_events`/... for a filtered JFR, or `jfr=false` with `from`/`to` for a JSONL export.");
        return md;
    }
}
