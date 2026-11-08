package com.muthupalaniyappan.jfragent;

import jdk.jfr.EventType;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Custom (application-defined) events: listing, stats, and zooming to occurrences. */
final class Custom {
    static final Set<String> STD_FIELDS = Set.of("startTime", "duration", "eventThread", "stackTrace");

    static List<String> customTypes(Rec r) {
        List<String> out = new ArrayList<>();
        for (var en : r.types.entrySet()) {
            String n = en.getKey();
            if (Rec.isCustomName(n) || (Set.of("profiler.UserEvent", "profiler.Span", "profiler.Window").contains(n) && r.count(n) > 0)) out.add(n);
        }
        return out;
    }

    static String fieldSummary(EventType t) {
        return t.getFields().stream().filter(v -> !STD_FIELDS.contains(v.getName()))
                .map(v -> v.getTypeName().replace("java.lang.", "") + " " + v.getName()).collect(Collectors.joining(", "));
    }

    static String values(RecordedEvent e, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ValueDescriptor v : e.getEventType().getFields()) {
            if (STD_FIELDS.contains(v.getName())) continue;
            if (n++ >= max) { sb.append("..."); break; }
            Object o;
            try { o = e.getValue(v.getName()); } catch (Exception ex) { continue; }
            String s = o instanceof RecordedThread t ? Frames.threadLabel(t) : o instanceof RecordedClass c ? c.getName() : String.valueOf(o);
            if (s.length() > 80) s = s.substring(0, 77) + "...";
            sb.append(v.getName()).append('=').append(s).append(' ');
        }
        return sb.toString().trim();
    }

    static Md list(Rec r, Map<String, Object> a) throws IOException {
        List<String> names = customTypes(r);
        Md md = new Md();
        md.p("**Custom event types in recording:** " + names.size() + " (anything not `jdk.*`/`java.*`/`sun.*`, plus async-profiler user events)");
        if (names.isEmpty()) {
            md.p("\nNone found. If you expected some, verify the app's event classes are annotated with `@Name`, enabled in the recording settings (`<name>#enabled=true`), and committed.");
            return md;
        }
        String only = Profile.strArg(a, "name", null);
        Map<String, List<Long>> durs = new HashMap<>();
        Map<String, Instant[]> span = new HashMap<>();
        Rec.Filter f = r.filter(a);
        f.types = new HashSet<>(names);
        r.each(f, e -> {
            String n = e.getEventType().getName();
            durs.computeIfAbsent(n, k -> new ArrayList<>()).add(e.getDuration().toNanos());
            Instant[] s = span.computeIfAbsent(n, k -> new Instant[]{e.getStartTime(), e.getEndTime()});
            if (e.getStartTime().isBefore(s[0])) s[0] = e.getStartTime();
            if (e.getEndTime().isAfter(s[1])) s[1] = e.getEndTime();
        });
        List<List<String>> rows = new ArrayList<>();
        for (String n : names) {
            List<Long> d = durs.getOrDefault(n, new ArrayList<>());
            Collections.sort(d);
            long tot = d.stream().mapToLong(Long::longValue).sum();
            Instant[] s = span.get(n);
            rows.add(List.of(n, String.valueOf(d.size()), d.isEmpty() ? "-" : Md.dur(tot), d.isEmpty() ? "-" : Md.ms(tot / d.size()),
                    d.isEmpty() ? "-" : Md.ms(d.get(Math.min(d.size() - 1, (int) Math.ceil(d.size() * 0.95) - 1))),
                    d.isEmpty() ? "-" : Md.ms(d.get(d.size() - 1)), s == null ? "-" : r.rel(s[0]) + " .. " + r.rel(s[1]), fieldSummary(r.types.get(n))));
        }
        md.table(List.of("Event", "Count", "Total", "Avg", "p95", "Max", "Active between", "Fields"), rows);
        if (only != null) md.sb.append(occurrences(r, a, only));
        else md.p("Pass `name` to list the slowest occurrences, or use `jfr_zoom_custom_event` to see what the JDK did during them.");
        return md;
    }

    /**
     * Light copy of the fields we need from a custom event. Holding RecordedEvent objects would pin every chunk they came from
     * (each event references its chunk's constant pools, ~8x the chunk size), which is what exhausted the heap.
     */
    record Occ(Instant start, Instant end, java.time.Duration duration, boolean hasThread, long tid, String label, boolean virtual, String v8, String v20) {
        Occ(RecordedEvent e) {
            this(e.getStartTime(), e.getEndTime(), e.getDuration(), Frames.thread(e) != null, Frames.ti(e).javaId, Frames.ti(e).label, Frames.ti(e).virtual, values(e, 8), values(e, 20));
        }
        Instant getStartTime() { return start; }
        Instant getEndTime() { return end; }
        java.time.Duration getDuration() { return duration; }
    }

    static List<Occ> collect(Rec r, Map<String, Object> a, String name) throws IOException {
        Rec.Filter f = r.filter(a);
        f.types = Set.of(name);
        String match = Profile.strArg(a, "match", null);
        if (match != null) f.extra = e -> values(e, 50).toLowerCase().contains(match.toLowerCase());
        List<Occ> l = new ArrayList<>();
        r.each(f, e -> l.add(new Occ(e)));
        return l;
    }

    static String occurrences(Rec r, Map<String, Object> a, String name) throws IOException {
        if (!r.types.containsKey(name)) return "\nUnknown event type `" + name + "`. Known custom types: " + customTypes(r) + "\n";
        List<Occ> l = collect(r, a, name);
        String order = Profile.strArg(a, "order", "slowest");
        if (order.equals("slowest")) l.sort((x, y) -> y.getDuration().compareTo(x.getDuration()));
        else l.sort(Comparator.comparing(Occ::getStartTime));
        Md md = new Md().h(3, "Occurrences of " + name + " (" + order + " first, " + l.size() + " total)");
        List<List<String>> rows = new ArrayList<>();
        int i = 1;
        for (Occ e : l.stream().limit(Profile.intArg(a, "top", 15)).toList()) {
            rows.add(List.of(String.valueOf(i++), r.rel(e.start()), Md.dur(e.duration().toNanos()), e.hasThread() ? e.label() : "<unknown>", e.v8()));
        }
        return md.table(List.of("#", "Start", "Duration", "Thread", "Fields"), rows).toString();
    }

    /** Aggregate or single-occurrence zoom: what ran on the thread while the custom event was active. */
    static Md zoom(Rec r, Map<String, Object> a) throws IOException {
        String name = Profile.strArg(a, "name", null);
        if (name == null) return new Md().p("`name` of a custom event is required. Known: " + customTypes(r));
        if (!r.types.containsKey(name)) return new Md().p("Unknown event type `" + name + "`. Known custom types: " + customTypes(r));
        List<Occ> l = collect(r, a, name);
        if (l.isEmpty()) return new Md().p("No `" + name + "` events matched.");
        boolean allThreads = Profile.boolArg(a, "all_threads", false);
        long pad = (long) (Double.parseDouble(Profile.strArg(a, "pad_ms", "0")) * 1e6);
        String mode = Profile.strArg(a, "mode", "occurrence");
        Md md = new Md();
        Rec.Filter base = new Rec.Filter();
        Map<String, Object> pass = new HashMap<>(a);
        if (mode.equals("aggregate")) {
            Map<Long, List<long[]>> iv = new HashMap<>();
            long totalDur = 0;
            for (Occ e : l) {
                long tid = allThreads || !e.hasThread() ? -1 : e.tid();
                iv.computeIfAbsent(tid, k -> new ArrayList<>()).add(new long[]{nanos(e.start()) - pad, nanos(e.end()) + pad});
                totalDur += e.duration().toNanos();
            }
            iv.values().forEach(x -> x.sort(Comparator.comparingLong(y -> y[0])));
            final boolean any = allThreads;
            base.extra = e -> {
                long ts = nanos(e.getStartTime());
                List<long[]> list = any ? iv.get(-1L) : iv.get((Frames.thread(e) == null ? -2L : Frames.ti(e).javaId));
                if (list == null) return false;
                int lo = 0, hi = list.size() - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    if (list.get(mid)[1] < ts) lo = mid + 1; else if (list.get(mid)[0] > ts) hi = mid - 1; else return true;
                }
                // overlapping intervals: check neighbours
                for (int k = Math.max(0, lo - 3); k < Math.min(list.size(), lo + 3); k++) if (list.get(k)[0] <= ts && ts <= list.get(k)[1]) return true;
                return false;
            };
            if (allThreads) {
                // all-threads aggregate: we only know interval union; handled by key -1 above
            }
            md.p("**Mode:** aggregate over " + l.size() + " `" + name + "` occurrences (total event time " + Md.dur(totalDur) + "), " + (allThreads ? "all threads" : "only the thread that ran each occurrence"));
        } else {
            int rank = Math.max(1, Profile.intArg(a, "rank", 1));
            if (Profile.strArg(a, "order", "slowest").equals("slowest")) l.sort((x, y) -> y.getDuration().compareTo(x.getDuration()));
            else l.sort(Comparator.comparing(Occ::getStartTime));
            if (rank > l.size()) return new Md().p("rank " + rank + " > number of events " + l.size());
            Occ e = l.get(rank - 1);
            base.from = e.start().minusNanos(pad);
            base.to = e.end().plusNanos(pad);
            if (!allThreads && e.hasThread()) {
                long tid = e.tid();
                base.extra = x -> { RecordedThread t = Frames.thread(x); return t != null && t.getJavaThreadId() == tid; };
            }
            md.p("**Zoomed to** `" + name + "` #" + rank + " (" + Profile.strArg(a, "order", "slowest") + "): start " + r.rel(e.start()) + ", duration " + Md.dur(e.duration().toNanos())
                    + ", thread " + (e.hasThread() ? e.label() : "<unknown>") + (e.virtual() ? " (virtual)" : ""));
            md.p("**Fields:** " + e.v20());
            md.p("Re-use window in other tools: `from=" + r.rel(e.start()) + " to=" + r.rel(e.end()) + "`" + (allThreads ? "" : " thread='" + (e.hasThread() ? e.label() : "<unknown>") + "'"));
            if (e.duration().toMillis() < 40) md.p("_Note: this occurrence is shorter than ~2 sampling intervals, so stack samples are sparse. Blocking/GC/JIT events are exact; for CPU attribution use `mode=aggregate` over many occurrences._");
        }
        // pass-through thread filter from user is applied via base.extra only if user gave thread: combine
        Rec.Filter user = r.filter(a);
        if (user.threadContains != null) { base.threadContains = user.threadContains; }
        base.virtualOnly = user.virtualOnly;
        Map<String, Object> inner = new HashMap<>(a);
        inner.remove("from"); inner.remove("to");
        if (!mode.equals("aggregate")) {
            Rec.Filter all = base.copy();
            Map<String, long[]> evs = new TreeMap<>();
            r.each(all, e -> {
                String t = e.getEventType().getName();
                if (Rec.isSample(t) || t.equals(name) || t.equals("jdk.CPULoad")) return;
                long[] x = evs.computeIfAbsent(t, k -> new long[2]);
                x[0]++; x[1] += e.getDuration().toNanos();
            });
            md.h(2, "All other JFR events in the window" + (allThreads ? "" : " on that thread (or global events)"));
            List<List<String>> rows = new ArrayList<>();
            evs.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(25)
                    .forEach(en -> rows.add(List.of(en.getKey(), String.valueOf(en.getValue()[0]), Md.dur(en.getValue()[1]))));
            md.table(List.of("Event", "Count", "Total duration"), rows);
        }
        md.h(2, "What ran during the event(s)");
        md.sb.append(Profile.hot(r, base, inner));
        md.h(2, "How the JDK/runtime spent that time");
        md.sb.append(Profile.jdk(r, base, inner));
        return md;
    }

    static long nanos(Instant i) { return i.getEpochSecond() * 1_000_000_000L + i.getNano(); }
}
