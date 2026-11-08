package com.muthupalaniyappan.jfragent;

import jdk.jfr.consumer.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** General opinion on a recording: health checks, findings by severity, suggested next steps. */
final class Diagnose {
    record Finding(int sev, String title, String evidence, String advice, String next) {}

    static String sev(int s) { return switch (s) { case 3 -> "HIGH"; case 2 -> "MEDIUM"; case 1 -> "LOW"; default -> "INFO"; }; }

    static double dbl(RecordedEvent e, String f) { try { return e.hasField(f) ? ((Number) e.getValue(f)).doubleValue() : 0; } catch (Exception x) { return 0; } }
    static String str(RecordedEvent e, String f) {
        try {
            if (!e.hasField(f)) return "";
            Object o = e.getValue(f);
            return o instanceof RecordedClass c ? c.getName() : String.valueOf(o);
        } catch (Exception x) { return ""; }
    }

    static Md run(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException {
        long windowNs = Math.max(1, Duration.between(base.from == null ? r.start : base.from, base.to == null ? r.end : base.to).toNanos());
        List<Finding> fs = new ArrayList<>();
        Set<String> sampleT = Profile.sampleTypes(r, Profile.strArg(a, "source", "auto"));

        long gcPause = 0, gcMax = 0, gcCount = 0, sysGc = 0;
        String young = "", old = "", jvm = "", os = "", jvmArgs = "";
        int cores = 0;
        double cpuJvm = 0, cpuMax = 0, cpuMach = 0; int cpuN = 0;
        long mon = 0, monN = 0, park = 0, parkN = 0, sleepD = 0, io = 0, ioN = 0, compile = 0;
        long pin = 0, pinN = 0, exc = 0, vthreadStart = 0;
        long heapMax = 0, heapUsedMax = 0, peakThreads = 0;
        Agg monCls = new Agg(), parkCls = new Agg(), excCls = new Agg(), allocCls = new Agg(), ioKind = new Agg(), pinSite = new Agg();
        Agg appHot = new Agg(), leaf = new Agg();
        long[] samples = new long[2];
        Agg cust = new Agg();
        Map<String, Long> custMax = new HashMap<>();

        Rec.Filter f = base.copy();
        f.types = null;
        Set<String> wanted = new HashSet<>(List.of("jdk.GarbageCollection", "jdk.GCConfiguration", "jdk.JVMInformation", "jdk.OSInformation", "jdk.CPUInformation", "jdk.CPULoad",
                "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ThreadSleep", "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite", "jdk.Compilation",
                "jdk.VirtualThreadPinned", "jdk.VirtualThreadStart", "jdk.JavaExceptionThrow", "jdk.JavaErrorThrow", "jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB",
                "jdk.ObjectAllocationOutsideTLAB", "jdk.GCHeapSummary", "jdk.GCHeapConfiguration", "jdk.JavaThreadStatistics", "jdk.ExceptionStatistics"));
        wanted.addAll(sampleT);
        for (String t : r.counts.keySet()) if (Rec.isCustomName(t)) wanted.add(t);
        f.types = wanted;
        // JVM-level info events are "begin chunk" events outside any window: read unfiltered by time
        Rec.Filter info = new Rec.Filter();
        info.types = Set.of("jdk.GCConfiguration", "jdk.JVMInformation", "jdk.OSInformation", "jdk.CPUInformation", "jdk.GCHeapConfiguration");
        // these are written at the start of each chunk: look only at the first seconds so later chunks are never parsed
        info.from = r.start; info.to = r.start.plusSeconds(5);
        String[] infos = new String[5];
        int[] coresRef = {0};
        long[] heapMaxRef = {0};
        r.each(info, e -> {
            switch (e.getEventType().getName()) {
                case "jdk.GCConfiguration" -> infos[0] = str(e, "youngCollector") + " / " + str(e, "oldCollector");
                case "jdk.JVMInformation" -> { infos[1] = str(e, "jvmVersion"); infos[2] = str(e, "jvmArguments"); }
                case "jdk.OSInformation" -> infos[3] = str(e, "osVersion").split("\n")[0];
                case "jdk.CPUInformation" -> coresRef[0] = (int) dbl(e, "hwThreads");
                case "jdk.GCHeapConfiguration" -> heapMaxRef[0] = (long) dbl(e, "maxSize");
                default -> {}
            }
        });
        if (infos[1] == null) { // not at the start (unusual layout): fall back to scanning everything
            info.from = null; info.to = null;
            r.each(info, e -> {
                switch (e.getEventType().getName()) {
                    case "jdk.GCConfiguration" -> infos[0] = str(e, "youngCollector") + " / " + str(e, "oldCollector");
                    case "jdk.JVMInformation" -> { infos[1] = str(e, "jvmVersion"); infos[2] = str(e, "jvmArguments"); }
                    case "jdk.OSInformation" -> infos[3] = str(e, "osVersion").split("\n")[0];
                    case "jdk.CPUInformation" -> coresRef[0] = (int) dbl(e, "hwThreads");
                    case "jdk.GCHeapConfiguration" -> heapMaxRef[0] = (long) dbl(e, "maxSize");
                    default -> {}
                }
            });
        }
        cores = coresRef[0]; heapMax = heapMaxRef[0];

        Rec.Filter sf = f;
        long[] acc = new long[16];
        double[] dd = new double[4];
        long[] heap = new long[1];
        // CPULoad / GCHeapSummary have no thread, so they are merged into the main pass only when no thread filter is set
        boolean mergeNumeric = base.threadContains == null && base.virtualOnly == null;
        if (mergeNumeric) { sf.types = new HashSet<>(sf.types); sf.types.addAll(List.of("jdk.CPULoad", "jdk.GCHeapSummary")); }
        r.each(sf, e -> {
            String t = e.getEventType().getName();
            long d = e.getDuration().toNanos();
            if (sampleT.contains(t)) {
                boolean run = Frames.isRunnable(e);
                int w = Frames.weight(e);
                samples[run ? 0 : 1] += w;
                if (run) {
                    Frames.SI st = Frames.si(e);
                    if (!st.empty) {
                        leaf.add(st.leaf, w);
                        appHot.add(st.app, w);
                    }
                }
                return;
            }
            switch (t) {
                case "jdk.GarbageCollection" -> {
                    long sp = e.hasField("sumOfPauses") ? e.getDuration("sumOfPauses").toNanos() : d;
                    acc[0] += sp; acc[1] = Math.max(acc[1], e.hasField("longestPause") ? e.getDuration("longestPause").toNanos() : sp); acc[2]++;
                    if (str(e, "cause").contains("System.gc")) acc[3]++;
                }
                case "jdk.CPULoad" -> { dd[0] += dbl(e, "jvmUser") + dbl(e, "jvmSystem"); dd[1] = Math.max(dd[1], dbl(e, "jvmUser") + dbl(e, "jvmSystem")); dd[2] += dbl(e, "machineTotal"); dd[3]++; }
                case "jdk.GCHeapSummary" -> heap[0] = Math.max(heap[0], (long) dbl(e, "heapUsed"));
                case "jdk.JavaMonitorEnter" -> { acc[5] += d; acc[6]++; monCls.add(str(e, "monitorClass"), d); }
                case "jdk.ThreadPark" -> { acc[7] += d; acc[8]++; parkCls.add(str(e, "parkedClass"), d); }
                case "jdk.ThreadSleep" -> acc[9] += d;
                case "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite" -> { acc[10] += d; acc[11]++; ioKind.add(t.replace("jdk.", ""), d); }
                case "jdk.Compilation" -> acc[12] += d;
                case "jdk.VirtualThreadPinned" -> {
                    acc[13] += d; acc[14]++;
                    Frames.SI st = Frames.si(e);
                    pinSite.add(st.hasApp ? st.appOnlyLine : "<no app frame>", d);
                }
                case "jdk.VirtualThreadStart" -> acc[15]++;
                case "jdk.JavaExceptionThrow", "jdk.JavaErrorThrow" -> excCls.add(str(e, "thrownClass"), 1);
                case "jdk.ObjectAllocationSample" -> allocCls.add(str(e, "objectClass"), (long) dbl(e, "weight"));
                case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" -> allocCls.add(str(e, "objectClass"), (long) dbl(e, "allocationSize"));
                default -> {
                    if (Rec.isCustomName(t)) { cust.add(t, d, 1); custMax.merge(t, d, Math::max); }
                }
            }
        });
        if (!mergeNumeric) {
            Rec.Filter cf = new Rec.Filter(); cf.from = base.from; cf.to = base.to;
            cf.types = Set.of("jdk.CPULoad", "jdk.GCHeapSummary");
            r.each(cf, e -> {
                switch (e.getEventType().getName()) {
                    case "jdk.CPULoad" -> { dd[0] += dbl(e, "jvmUser") + dbl(e, "jvmSystem"); dd[1] = Math.max(dd[1], dbl(e, "jvmUser") + dbl(e, "jvmSystem")); dd[2] += dbl(e, "machineTotal"); dd[3]++; }
                    case "jdk.GCHeapSummary" -> heap[0] = Math.max(heap[0], (long) dbl(e, "heapUsed"));
                    default -> {}
                }
            });
        }
        gcPause = acc[0]; gcMax = acc[1]; gcCount = acc[2]; sysGc = acc[3];
        mon = acc[5]; monN = acc[6]; park = acc[7]; parkN = acc[8]; sleepD = acc[9]; io = acc[10]; ioN = acc[11]; compile = acc[12]; pin = acc[13]; pinN = acc[14];
        heapUsedMax = heap[0];
        cpuN = (int) dd[3];
        if (cpuN > 0) { cpuJvm = dd[0] / cpuN; cpuMax = dd[1]; cpuMach = dd[2] / cpuN; }

        // ---- quality of the recording ----
        double secs = windowNs / 1e9;
        long totalSamples = samples[0] + samples[1];
        if (totalSamples == 0)
            fs.add(new Finding(2, "No execution samples in recording/window", "None of " + sampleT + " present.",
                    "Re-record with profiling enabled: `jcmd <pid> JFR.start settings=profile duration=60s filename=out.jfr`, or async-profiler `asprof -e cpu -d 60 -o jfr -f out.jfr <pid>`. Method-level analysis is impossible without samples.", "jfr_events"));
        else if (totalSamples < 200)
            fs.add(new Finding(1, "Few samples (" + totalSamples + ")", "Percentages are noisy under ~200 samples (JFR default 20 ms interval).", "Record longer, or lower the interval: `jdk.ExecutionSample#period=10ms`.", "jfr_overview"));
        if (secs < 10) fs.add(new Finding(1, "Very short window", String.format("%.1f s analysed.", secs), "Prefer 30-120 s covering the slow scenario.", "jfr_timeline"));

        // ---- CPU / app ----
        if (totalSamples > 0 && samples[0] > 0) {
            var top = appHot.top(1).get(0);
            double share = 100.0 * top.getValue()[0] / appHot.total;
            if (share >= 25)
                fs.add(new Finding(share >= 50 ? 3 : 2, "Single hot method dominates runnable time", top.getKey() + " = " + String.format("%.1f%%", share) + " of " + appHot.total + " runnable samples",
                        "Inspect this method first: algorithmic cost, repeated work in loops, N+1 calls, or an expensive JDK call it makes.", "jfr_hot_methods"));
            else if (appHot.total > 0)
                fs.add(new Finding(0, "CPU time is spread out", "Top method " + top.getKey() + " has " + String.format("%.1f%%", share), "No single hotspot; use the flamegraph and thread groups to find hot subtrees.", "jfr_flamegraph"));
        }
        if (totalSamples > 0 && Rec.WALL_SAMPLES.stream().anyMatch(t -> r.count(t) > 0) && samples[1] > samples[0] * 3)
            fs.add(new Finding(1, "Most wall-clock samples are sleeping/waiting", samples[1] + " waiting vs " + samples[0] + " runnable.", "Threads mostly wait (idle pools count). Look at `state=sleeping` hot methods and at the specific threads handling the slow request.", "jfr_hot_methods"));

        // ---- CPU load ----
        if (cpuN > 0) {
            if (cpuMach > 0.9 && cpuJvm < 0.5)
                fs.add(new Finding(2, "Machine CPU saturated by other processes", String.format("JVM avg %.0f%% but whole machine %.0f%%.", cpuJvm * 100, cpuMach * 100), "Another process (or neighbours/containers) is competing for CPU; JVM latency may be CPU steal rather than app code. Re-measure on a quiet host or check cgroup limits.", "jfr_timeline"));
            else if (cpuJvm > 0.85 || cpuMach > 0.9)
                fs.add(new Finding(3, "CPU saturated", String.format("JVM avg %.0f%% (max %.0f%%), machine %.0f%%.", cpuJvm * 100, cpuMax * 100, cpuMach * 100), "Compute-bound: reduce work (see hot methods) or add capacity. If machine >> JVM, another process competes for CPU.", "jfr_hot_methods"));
            else if (cpuJvm < 0.2 && (mon + io) > 0.3 * windowNs)
                fs.add(new Finding(2, "Low CPU but lots of blocking", String.format("JVM CPU avg %.0f%%; blocked time %s.", cpuJvm * 100, Md.dur(mon + io)), "Latency is due to waiting (locks, I/O, downstream), not computing.", "jfr_threads"));
        }
        // ---- GC ----
        if (gcCount > 0) {
            double gcShare = (double) gcPause / windowNs;
            String ev = String.format("%d collections, pause total %s (%.1f%% of window), longest %s, collectors: %s", gcCount, Md.dur(gcPause), 100 * gcShare, Md.dur(gcMax), infos[0]);
            if (gcShare > 0.10 || gcMax > 500_000_000L) fs.add(new Finding(3, "GC pauses are significant", ev, "Check allocation hot spots (`kind=alloc` flamegraph), heap sizing (-Xmx), and collector choice (G1/ZGC). Frequent young GCs = high allocation rate.", "jfr_flamegraph"));
            else if (gcShare > 0.03 || gcMax > 100_000_000L) fs.add(new Finding(1, "Noticeable GC activity", ev, "Likely not the main problem; reduce allocations if latency-sensitive.", "jfr_jdk_breakdown"));
            else fs.add(new Finding(0, "GC looks healthy", ev, "", ""));
            if (sysGc > 0) fs.add(new Finding(2, "Explicit System.gc() calls", sysGc + " collections caused by System.gc()", "Remove calls or add -XX:+DisableExplicitGC (or -XX:+ExplicitGCInvokesConcurrent).", "jfr_events"));
        }
        if (heapMax > 0 && heapUsedMax > 0.9 * heapMax)
            fs.add(new Finding(3, "Heap nearly full", Md.pct(heapUsedMax, heapMax) + " of max heap used at peak (" + heapUsedMax / (1 << 20) + " MiB of " + heapMax / (1 << 20) + " MiB)", "Increase -Xmx or fix a leak/retention (record with `jdk.OldObjectSample#cutoff=infinity`).", "jfr_events"));
        // ---- locks / park ----
        if (monN > 0) {
            double share = (double) mon / windowNs;
            var top = monCls.top(1).get(0);
            fs.add(new Finding(share > 0.1 ? 3 : share > 0.02 ? 2 : 1, "Monitor (synchronized) contention", monN + " events, " + Md.dur(mon) + " total blocked, hottest monitor " + top.getKey() + " (" + Md.dur(top.getValue()[0]) + ")",
                    "Shrink synchronized regions, use concurrent data structures / per-key locks / lock striping. See who is blocked with `jfr_hot_methods` (blocking section).", "jfr_hot_methods"));
        }
        if (parkN > 0 && !parkCls.m.isEmpty()) {
            var top = parkCls.top(1).get(0);
            fs.add(new Finding(park > 0.5 * windowNs * Math.max(1, 1) ? 1 : 0, "Thread parking", parkN + " events, " + Md.dur(park) + " total; most on " + top.getKey(),
                    "Parking on pool queues is usually idle workers. Parking on ReentrantLock/Semaphore/CountDownLatch/CompletableFuture on a request path = contention or waiting for downstream.", "jfr_threads"));
        }
        if (sleepD > 0.05 * windowNs) fs.add(new Finding(1, "Significant Thread.sleep", Md.dur(sleepD) + " total", "Check for polling loops/backoff on request paths.", "jfr_hot_methods"));
        if (ioN > 0) {
            var top = ioKind.top(1).get(0);
            fs.add(new Finding(io > 0.2 * windowNs ? 2 : 0, "I/O time", ioN + " I/O events > threshold, " + Md.dur(io) + " total, mostly " + top.getKey(), "Look for slow downstreams, missing timeouts, chatty protocols, unbatched calls.", "jfr_hot_methods"));
        }
        // ---- virtual threads ----
        if (pinN > 0) {
            var top = pinSite.top(1).get(0);
            fs.add(new Finding(pin > 0.05 * windowNs ? 3 : 2, "Virtual thread pinning", pinN + " pinned events, " + Md.dur(pin) + "; worst site " + top.getKey(), "A pinned virtual thread blocks its carrier. Replace `synchronized` around blocking calls with ReentrantLock, upgrade to JDK 24+ (JEP 491), avoid blocking in native frames.", "jfr_threads"));
        }
        if (acc[15] > 0 || r.count("jdk.VirtualThreadStart") > 0)
            fs.add(new Finding(0, "Virtual threads in use", r.count("jdk.VirtualThreadStart") + " started in recording", "Use `thread=virtual` or `virtual_threads=only|exclude` to separate them from carriers.", "jfr_threads"));
        // ---- exceptions ----
        if (excCls.total > 0) {
            var top = excCls.top(1).get(0);
            fs.add(new Finding(excCls.total > 1000 ? 2 : 0, "Exceptions thrown", excCls.total + " thrown; most common " + top.getKey() + " (" + top.getValue()[0] + ")", "Thousands of exceptions = exception-driven control flow and costly stack traces.", "jfr_print"));
        }
        // ---- allocation ----
        if (allocCls.total > 0) {
            var top = allocCls.top(3);
            fs.add(new Finding(0, "Top allocated types", top.stream().map(x -> x.getKey() + " " + Md.pct(x.getValue()[0], allocCls.total)).reduce((x, y) -> x + ", " + y).orElse(""),
                    "Use `jfr_flamegraph kind=alloc` to see which code allocates them.", "jfr_flamegraph"));
        }
        if (compile > 0.1 * windowNs) fs.add(new Finding(1, "JIT compilation time is high", Md.dur(compile) + " (can be warm-up)", "If the recording starts at JVM startup, discard the warm-up window.", "jfr_timeline"));
        // ---- custom ----
        if (!cust.m.isEmpty()) {
            for (var en : cust.top(5))
                fs.add(new Finding(0, "Custom event " + en.getKey(), en.getValue()[1] + " events, total " + Md.dur(en.getValue()[0]) + ", max " + Md.dur(custMax.get(en.getKey())),
                        "Zoom to the slowest with `jfr_zoom_custom_event name=" + en.getKey() + "`.", "jfr_zoom_custom_event"));
        }
        fs.sort((x, y) -> Integer.compare(y.sev, x.sev));

        Md md = new Md();
        md.h(2, "Recording opinion");
        md.p("**File:** `" + r.path + "`; **recorded:** " + r.start + " -> " + r.end + " (" + String.format("%.1f s", r.length().toNanos() / 1e9) + "), **events:** " + r.total);
        md.p("**Analysed window:** " + Profile.windowText(r, base));
        if (infos[1] != null) md.p("**JVM:** " + infos[1] + (infos[0] != null ? "; GC: " + infos[0] : "") + (cores > 0 ? "; hw threads: " + cores : "") + (infos[3] != null ? "; OS: " + (infos[3].length() > 70 ? infos[3].substring(0, 70) : infos[3]) : ""));
        if (infos[2] != null && !infos[2].isBlank()) md.p("**JVM args:** `" + (infos[2].length() > 400 ? infos[2].substring(0, 400) + "..." : infos[2]) + "`");
        long high = fs.stream().filter(x -> x.sev == 3).count(), med = fs.stream().filter(x -> x.sev == 2).count();
        md.p("**Verdict:** " + (high > 0 ? "problems found - " + high + " high-severity finding(s) below; start there."
                : med > 0 ? "some issues worth investigating (" + med + " medium)." : "no major issues detected from the available events; if the app is still slow check data quality notes."));
        md.h(3, "Findings (most severe first)");
        List<List<String>> rows = new ArrayList<>();
        for (Finding x : fs) rows.add(List.of(sev(x.sev), x.title, x.evidence, x.advice, x.next.isEmpty() ? "" : "`" + x.next + "`"));
        md.table(List.of("Severity", "Finding", "Evidence", "Advice", "Next tool"), rows);
        md.p("Next steps: `jfr_timeline` to find the slow period, `jfr_hot_methods` for the code, `jfr_threads` for the threads, `jfr_flamegraph` for the picture.");
        return md;
    }
}
