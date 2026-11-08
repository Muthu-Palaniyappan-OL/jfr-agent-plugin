package com.muthupalaniyappan.jfragent;

import jdk.jfr.consumer.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Sample-based and event-based profile analyses. */
final class Profile {

    // ---------- helpers ----------
    static int intArg(Map<String, Object> a, String k, int def) {
        Object v = a.get(k);
        if (v == null || v.toString().isBlank()) return def;
        return (int) Double.parseDouble(v.toString());
    }

    static String strArg(Map<String, Object> a, String k, String def) {
        Object v = a.get(k);
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    static boolean boolArg(Map<String, Object> a, String k, boolean def) {
        Object v = a.get(k);
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }

    static Set<String> sampleTypes(Rec r, String source) {
        Set<String> cpu = new HashSet<>(Rec.CPU_SAMPLES);
        cpu.addAll(Rec.NATIVE_SAMPLES);
        boolean hasCpu = Rec.CPU_SAMPLES.stream().anyMatch(t -> r.count(t) > 0);
        boolean hasWall = Rec.WALL_SAMPLES.stream().anyMatch(t -> r.count(t) > 0);
        return switch (source) {
            case "cpu" -> cpu;
            case "wall" -> hasWall ? new HashSet<>(Rec.WALL_SAMPLES) : cpu;
            case "all" -> { Set<String> s = new HashSet<>(cpu); s.addAll(Rec.WALL_SAMPLES); yield s; }
            default -> hasCpu ? cpu : new HashSet<>(Rec.WALL_SAMPLES);
        };
    }

    static String sourceNote(Rec r, Set<String> types) {
        List<String> used = new ArrayList<>();
        for (String t : types) if (r.count(t) > 0) used.add(t + " (" + r.count(t) + ")");
        return used.isEmpty() ? "none" : String.join(", ", used);
    }

    /** Filter for samples: types, state, plus the base time/thread filter. */
    static Rec.Filter sampleFilter(Rec r, Map<String, Object> a, Rec.Filter base) {
        Rec.Filter f = base.copy();
        f.types = sampleTypes(r, strArg(a, "source", "auto"));
        String state = strArg(a, "state", "runnable");
        Predicate<jdk.jfr.consumer.RecordedEvent> prev = f.extra;
        Predicate<jdk.jfr.consumer.RecordedEvent> sp = switch (state) {
            case "all" -> e -> true;
            case "sleeping", "waiting" -> e -> !Frames.isRunnable(e);
            default -> Frames::isRunnable;
        };
        f.extra = prev == null ? sp : prev.and(sp);
        return f;
    }

    static String windowText(Rec r, Rec.Filter f) {
        Instant from = f.from == null ? r.start : f.from, to = f.to == null ? r.end : f.to;
        String s = r.rel(from) + " .. " + r.rel(to) + " (" + String.format("%.3f", Duration.between(from, to).toNanos() / 1e9) + " s)";
        if (f.threadContains != null) s += ", thread~'" + f.threadContains + "'";
        if (f.virtualOnly != null) s += f.virtualOnly ? ", virtual threads only" : ", platform threads only";
        return s;
    }

    static List<String> stackText(List<RecordedFrame> st, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, st.size()); i++) out.add(Frames.nameLine(st.get(i)));
        return out;
    }

    // ---------- hot methods ----------
    static Md hot(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException {
        int topN = intArg(a, "top", 15);
        String state = strArg(a, "state", "runnable");
        Rec.Filter f = sampleFilter(r, a, base);
        Agg self = new Agg(), app = new Agg(), incl = new Agg(), lines = new Agg();
        Map<String, List<String>> rep = new HashMap<>();
        Map<String, Agg> appThreads = new HashMap<>();
        long[] state2 = new long[2]; // runnable, sleeping for header (computed over same types ignoring state)
        // One pass: read samples of every state, count them, and aggregate only those matching the requested state.
        Rec.Filter all = sampleFilter(r, Map.of("source", strArg(a, "source", "auto"), "state", "all"), base);
        r.each(all, e -> {
            boolean run = Frames.isRunnable(e);
            int w = Frames.weight(e);
            state2[run ? 0 : 1] += w;
            if (!switch (state) { case "all" -> true; case "sleeping", "waiting" -> !run; default -> run; }) return;
            Frames.SI st = Frames.si(e);
            if (st.empty) { self.add("<no stack>", w); return; }
            self.add(st.leaf, w);
            app.add(st.app, w);
            if (st.appLine != null) lines.add(st.appLine, w);
            rep.putIfAbsent(st.app, st.text12);
            appThreads.computeIfAbsent(st.app, k -> new Agg()).add(Frames.ti(e).group, w);
            for (String n : st.incl) incl.add(n, w);
        });

        Md md = new Md();
        md.p("**Window:** " + windowText(r, base));
        md.p("**Sample source:** " + sourceNote(r, f.types) + "; state filter: `" + state + "`");
        md.p("**Samples in window (all states):** " + (state2[0] + state2[1]) + " (runnable " + state2[0] + ", sleeping/waiting " + state2[1] + ")");
        md.p("**Samples analysed:** " + self.total);
        if (self.total == 0) {
            md.p("\nNo samples matched (short windows can fall between samples: JFR samples every 10-20 ms). Widen the window, use `mode=aggregate` for custom events, try `source=wall` or `state=all`.");
            if (boolArg(a, "include_wait_events", true)) md.sb.append(waitMethods(r, base, a));
            return md;
        }

        md.h(3, "Where application code spends time (leaf attributed to first application frame)");
        md.p("This answers 'which of my methods is slow'. JDK/framework leaf time is charged to the application caller.");
        List<List<String>> rows = new ArrayList<>();
        for (var en : app.top(topN)) {
            Agg th = appThreads.get(en.getKey());
            String ths = th.top(2).stream().map(x -> x.getKey() + " " + Md.pct(x.getValue()[0], en.getValue()[0])).reduce((x, y) -> x + ", " + y).orElse("");
            rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), Md.pct(en.getValue()[0], app.total), ths));
        }
        md.table(List.of("Method", "Samples", "Share", "Top threads"), rows);

        md.h(3, "Leaf (self) frames - what is literally on top of the stack");
        rows = new ArrayList<>();
        for (var en : self.top(topN)) rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), Md.pct(en.getValue()[0], self.total)));
        md.table(List.of("Frame", "Samples", "Share"), rows);

        md.h(3, "Inclusive application/framework methods (method anywhere on the stack)");
        rows = new ArrayList<>();
        for (var en : incl.top(topN)) rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), Md.pct(en.getValue()[0], self.total)));
        md.table(List.of("Method", "Samples", "Share of all samples"), rows);

        if (!lines.m.isEmpty()) {
            md.h(3, "Hot lines (application)");
            rows = new ArrayList<>();
            for (var en : lines.top(Math.min(topN, 10))) rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), Md.pct(en.getValue()[0], lines.total)));
            md.table(List.of("Line", "Samples", "Share"), rows);
        }

        md.h(3, "Representative stacks of the top application methods");
        for (var en : app.top(Math.min(5, topN))) {
            md.p("**" + en.getKey() + "**  (" + Md.pct(en.getValue()[0], app.total) + ")");
            md.p("```");
            for (String s : rep.get(en.getKey())) md.p("  " + s);
            md.p("```");
        }
        if (boolArg(a, "include_wait_events", true)) md.sb.append(waitMethods(r, base, a));
        return md;
    }

    /** Blocking events attributed to application frames, weighted by duration. */
    static String waitMethods(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException {
        Rec.Filter f = base.copy();
        f.types = new HashSet<>(Rec.WAIT_EVENTS);
        f.types.removeIf(t -> r.count(t) == 0);
        if (f.types.isEmpty()) return "";
        Map<String, Agg> byType = new TreeMap<>();
        Map<String, List<String>> rep = new HashMap<>();
        r.each(f, e -> {
            Frames.SI st = Frames.si(e);
            String key = st.app;
            byType.computeIfAbsent(e.getEventType().getName(), k -> new Agg()).add(key, e.getDuration().toNanos(), 1);
            if (!st.empty) rep.putIfAbsent(e.getEventType().getName() + "|" + key, st.text10);
        });
        if (byType.isEmpty()) return "";
        Md md = new Md().h(3, "Blocking / waiting events by application method (by total duration)");
        int n = Math.min(intArg(a, "top", 15), 8);
        for (var t : byType.entrySet()) {
            md.p("**" + t.getKey() + "** - total " + Md.dur(t.getValue().total) + ", events " + t.getValue().m.values().stream().mapToLong(x -> x[1]).sum());
            List<List<String>> rows = new ArrayList<>();
            for (var en : t.getValue().top(n)) rows.add(List.of(en.getKey(), Md.dur(en.getValue()[0]), String.valueOf(en.getValue()[1]), Md.ms(en.getValue()[0] / Math.max(1, en.getValue()[1]))));
            md.table(List.of("Method", "Total", "Events", "Avg"), rows);
        }
        return md.toString();
    }

    // ---------- JDK breakdown ----------
    static Md jdk(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException {
        Rec.Filter f = sampleFilter(r, a, base);
        Agg cat = new Agg(), kind = new Agg();
        Map<String, Agg> catLeaf = new HashMap<>(), catCaller = new HashMap<>();
        Map<String, Agg> pkgs = new HashMap<>();
        r.each(f, e -> {
            Frames.SI st = Frames.si(e);
            int w = Frames.weight(e);
            if (st.empty) { kind.add("no stack", w); return; }
            kind.add(st.leafNative ? "Native / JVM" : st.leafJdk ? "JDK (java/javax/jdk/sun)" : st.leafFramework ? "Framework libraries" : "Application code", w);
            cat.add(st.jdkCat, w);
            catLeaf.computeIfAbsent(st.jdkCat, k -> new Agg()).add(st.leaf, w);
            catCaller.computeIfAbsent(st.jdkCat, k -> new Agg()).add(st.hasApp ? st.appOnly : "<no app frame>", w);
            if (st.jdkPkg != null) pkgs.computeIfAbsent("pkg", k -> new Agg()).add(st.jdkPkg, w);
        });
        Md md = new Md();
        md.p("**Window:** " + windowText(r, base));
        md.p("**Sample source:** " + sourceNote(r, f.types) + "; state `" + strArg(a, "state", "runnable") + "`; samples " + kind.total);
        if (kind.total == 0) { md.p("\nNo samples matched."); md.sb.append(runtimeEvents(r, base)); return md; }
        md.h(3, "Who owns the leaf frame");
        List<List<String>> rows = new ArrayList<>();
        for (var en : kind.top(10)) rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), Md.pct(en.getValue()[0], kind.total)));
        md.table(List.of("Leaf owner", "Samples", "Share"), rows);

        md.h(3, "JDK/runtime categories (how and where the JDK spends time), with the application callers driving them");
        for (var en : cat.top(intArg(a, "top", 8))) {
            md.p("**" + en.getKey() + "** - " + en.getValue()[0] + " samples (" + Md.pct(en.getValue()[0], cat.total) + ")");
            md.p("  - hot leaf: " + catLeaf.get(en.getKey()).top(3).stream().map(x -> x.getKey() + " (" + x.getValue()[0] + ")").reduce((x, y) -> x + "; " + y).orElse(""));
            md.p("  - driven by app code: " + catCaller.get(en.getKey()).top(3).stream().map(x -> x.getKey() + " (" + x.getValue()[0] + ")").reduce((x, y) -> x + "; " + y).orElse(""));
        }
        if (pkgs.containsKey("pkg")) {
            md.h(3, "JDK packages at the leaf");
            rows = new ArrayList<>();
            for (var en : pkgs.get("pkg").top(10)) rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), Md.pct(en.getValue()[0], kind.total)));
            md.table(List.of("Package", "Samples", "Share of samples"), rows);
        }
        md.sb.append(runtimeEvents(r, base));
        return md;
    }

    /** GC, JIT, locks, I/O etc. event totals inside the window. */
    static String runtimeEvents(Rec r, Rec.Filter base) throws IOException {
        String[] types = {"jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.SafepointBegin", "jdk.Compilation", "jdk.ClassLoad", "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait",
                "jdk.ThreadPark", "jdk.ThreadSleep", "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite", "jdk.VirtualThreadPinned", "jdk.VirtualThreadSubmitFailed",
                "jdk.JavaExceptionThrow", "jdk.JavaErrorThrow", "jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB", "jdk.Deoptimization"};
        Rec.Filter f = base.copy();
        f.types = new HashSet<>();
        for (String t : types) if (r.count(t) > 0) f.types.add(t);
        if (f.types.isEmpty()) return "";
        Map<String, long[]> st = new TreeMap<>();
        r.each(f, e -> {
            long[] x = st.computeIfAbsent(e.getEventType().getName(), k -> new long[3]);
            long d = e.getDuration().toNanos();
            x[0]++; x[1] += d; x[2] = Math.max(x[2], d);
        });
        Md md = new Md().h(3, "Runtime / JDK events in the window");
        List<List<String>> rows = new ArrayList<>();
        st.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]))
                .forEach(en -> rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), Md.dur(en.getValue()[1]), Md.ms(en.getValue()[2]))));
        return md.table(List.of("Event", "Count", "Total duration", "Max"), rows).toString();
    }

    // ---------- threads ----------
    static Md threads(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException {
        Rec.Filter f = sampleFilter(r, Map.of("source", strArg(a, "source", "auto"), "state", "all"), base);
        int topN = intArg(a, "top", 15);
        record TS(Set<String> names, long[] c, Agg top) {}
        Map<String, TS> groups = new HashMap<>();
        Map<String, long[]> perThread = new HashMap<>();
        Set<Long> virtualIds = new HashSet<>();
        r.each(f, e -> {
            Frames.TI t = Frames.ti(e);
            TS ts = groups.computeIfAbsent(t.group, k -> new TS(new HashSet<>(), new long[2], new Agg()));
            ts.names.add(t.label);
            int w = Frames.weight(e);
            boolean run = Frames.isRunnable(e);
            ts.c[run ? 0 : 1] += w;
            long[] pt = perThread.computeIfAbsent(t.label, k -> new long[2]);
            pt[run ? 0 : 1] += w;
            if (t.virtual) virtualIds.add(t.javaId);
            if (run) {
                Frames.SI st = Frames.si(e);
                if (!st.empty) ts.top.add(st.app, w);
            }
        });
        Md md = new Md();
        md.p("**Window:** " + windowText(r, base));
        md.p("**Sample source:** " + sourceNote(r, f.types));
        md.h(3, "Thread groups (pool members collapsed; virtual threads aggregated)");
        List<List<String>> rows = new ArrayList<>();
        groups.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().c[0] + y.getValue().c[1], x.getValue().c[0] + x.getValue().c[1])).limit(topN).forEach(en -> {
            TS ts = en.getValue();
            long tot = ts.c[0] + ts.c[1];
            rows.add(List.of(en.getKey(), String.valueOf(ts.names.size()), String.valueOf(tot), Md.pct(ts.c[0], tot),
                    ts.top.top(2).stream().map(x -> x.getKey() + " (" + x.getValue()[0] + ")").reduce((x, y) -> x + "; " + y).orElse("-")));
        });
        md.table(List.of("Thread group", "Threads", "Samples", "Runnable %", "Top app method while runnable"), rows);

        md.h(3, "Busiest individual threads (runnable samples)");
        rows.clear();
        perThread.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(topN)
                .forEach(en -> rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), String.valueOf(en.getValue()[1]))));
        md.table(List.of("Thread", "Runnable samples", "Sleeping/waiting samples"), rows);

        // wait-type events per group
        Rec.Filter wf = base.copy();
        wf.types = new HashSet<>(Rec.WAIT_EVENTS);
        wf.types.removeIf(t -> r.count(t) == 0);
        if (!wf.types.isEmpty()) {
            Map<String, Map<String, long[]>> w = new TreeMap<>();
            r.each(wf, e -> {
                String g = Frames.ti(e).group;
                long[] x = w.computeIfAbsent(g, k -> new TreeMap<>()).computeIfAbsent(e.getEventType().getName(), k -> new long[2]);
                x[0]++; x[1] += e.getDuration().toNanos();
            });
            md.h(3, "Blocking events per thread group");
            rows.clear();
            w.entrySet().stream().sorted((x, y) -> Long.compare(sum(y.getValue()), sum(x.getValue()))).limit(topN).forEach(en -> {
                String desc = en.getValue().entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(4)
                        .map(x -> x.getKey().replace("jdk.", "") + " " + Md.dur(x.getValue()[1]) + " (" + x.getValue()[0] + ")").reduce((x, y) -> x + "; " + y).orElse("");
                rows.add(List.of(en.getKey(), Md.dur(sum(en.getValue())), desc));
            });
            md.table(List.of("Thread group", "Total blocked", "Breakdown"), rows);
        }
        // virtual threads
        long vs = r.count("jdk.VirtualThreadStart"), pinned = r.count("jdk.VirtualThreadPinned");
        if (!virtualIds.isEmpty() || vs > 0 || pinned > 0) {
            md.h(3, "Virtual threads");
            md.li("virtual threads seen in samples: " + virtualIds.size() + "; jdk.VirtualThreadStart events (off by default): " + vs + "; jdk.VirtualThreadPinned events: " + pinned);
            if (pinned > 0) md.sb.append(pinned(r, base));
            else md.li("no pinning events recorded (they are on by default with threshold 20 ms in the `profile`/`default` settings; if absent, no pinning above the threshold happened).");
        }
        md.p("\nTip: pass `thread` (substring, `re:<regex>`, or `virtual`) to any analysis tool to focus on one group.");
        return md;
    }

    static long sum(Map<String, long[]> m) { return m.values().stream().mapToLong(x -> x[1]).sum(); }

    static String pinned(Rec r, Rec.Filter base) throws IOException {
        Rec.Filter f = base.copy();
        f.types = Set.of("jdk.VirtualThreadPinned");
        Agg ag = new Agg();
        Map<String, List<String>> rep = new HashMap<>();
        r.each(f, e -> {
            Frames.SI st = Frames.si(e);
            String key = st.hasApp ? st.appOnlyLine : st.leaf;
            ag.add(key, e.getDuration().toNanos(), 1);
            rep.putIfAbsent(key, st.text10);
        });
        Md md = new Md().p("**Pinned virtual threads (carrier blocked) by application frame**:");
        List<List<String>> rows = new ArrayList<>();
        for (var en : ag.top(8)) rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[1]), Md.dur(en.getValue()[0])));
        md.table(List.of("Where pinned", "Events", "Total pinned time"), rows);
        md.p("Typical cause on JDK 21-23: blocking inside `synchronized` or native frames. JDK 24+ (JEP 491) removed most synchronized pinning; remaining pinning is native frames/`Object.wait` in some cases. Fix: use `ReentrantLock`, upgrade the JDK, or avoid blocking calls in the pinned region.");
        return md.toString();
    }

    // ---------- timeline ----------
    static Md timeline(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException {
        int buckets = Math.max(2, Math.min(200, intArg(a, "buckets", 20)));
        Instant from = base.from == null ? r.start : base.from, to = base.to == null ? r.end : base.to;
        long span = Math.max(1, Duration.between(from, to).toNanos());
        long[] run = new long[buckets], slp = new long[buckets], gc = new long[buckets], blocked = new long[buckets], custom = new long[buckets];
        double[] cpu = new double[buckets]; int[] cpuN = new int[buckets];
        Agg[] top = new Agg[buckets];
        for (int i = 0; i < buckets; i++) top[i] = new Agg();
        Rec.Filter f = base.copy();
        f.types = new HashSet<>(sampleTypes(r, strArg(a, "source", "auto")));
        f.types.addAll(List.of("jdk.GarbageCollection", "jdk.CPULoad", "jdk.JavaMonitorEnter", "jdk.ThreadPark"));
        for (String t : r.counts.keySet()) if (Rec.isCustomName(t)) f.types.add(t);
        Set<String> sampleT = sampleTypes(r, strArg(a, "source", "auto"));
        r.each(f, e -> {
            long off = Duration.between(from, e.getStartTime()).toNanos();
            if (off < 0 || off > span) return;
            int b = (int) Math.min(buckets - 1, off * buckets / span);
            String t = e.getEventType().getName();
            if (sampleT.contains(t)) {
                int w = Frames.weight(e);
                if (Frames.isRunnable(e)) {
                    run[b] += w;
                    Frames.SI st = Frames.si(e);
                    if (st.hasApp) top[b].add(st.appOnly, w);
                } else slp[b] += w;
            } else if (t.equals("jdk.GarbageCollection")) {
                try { gc[b] += e.getDuration("sumOfPauses").toNanos(); } catch (Exception ex) { gc[b] += e.getDuration().toNanos(); }
            } else if (t.equals("jdk.CPULoad")) {
                cpu[b] += e.getDouble("jvmUser") + e.getDouble("jvmSystem"); cpuN[b]++;
            } else if (t.equals("jdk.JavaMonitorEnter") || t.equals("jdk.ThreadPark")) {
                blocked[b] += e.getDuration().toNanos();
            } else custom[b]++;
        });
        long maxRun = Arrays.stream(run).max().orElse(1);
        Md md = new Md();
        md.p("**Window:** " + windowText(r, base) + "; bucket = " + String.format("%.3f s", span / 1e9 / buckets));
        md.p("Use the `from`/`to` of an interesting bucket in other tools to zoom. `*` marks buckets with runnable samples above 1.5x the mean.");
        double mean = Arrays.stream(run).average().orElse(0);
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            Instant s = from.plusNanos(span * i / buckets), en = from.plusNanos(span * (i + 1) / buckets);
            String hot = top[i].top(1).stream().map(x -> x.getKey() + " (" + x.getValue()[0] + ")").findFirst().orElse("-");
            rows.add(List.of(r.rel(s) + " - " + r.rel(en), run[i] + (run[i] > 1.5 * mean && run[i] > 0 ? " *" : ""), String.valueOf(slp[i]),
                    cpuN[i] == 0 ? "-" : String.format("%.0f%%", 100 * cpu[i] / cpuN[i]), gc[i] == 0 ? "-" : Md.ms(gc[i]),
                    blocked[i] == 0 ? "-" : Md.dur(blocked[i]), custom[i] == 0 ? "-" : String.valueOf(custom[i]), hot));
        }
        md.table(List.of("Time", "Runnable samples", "Sleeping samples", "JVM CPU", "GC pause", "Lock/park wait", "Custom events", "Top app method"), rows);
        return md;
    }
}
