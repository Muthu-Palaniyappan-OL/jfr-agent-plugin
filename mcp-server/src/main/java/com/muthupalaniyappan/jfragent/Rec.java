package com.muthupalaniyappan.jfragent;

import jdk.jfr.EventType;
import jdk.jfr.consumer.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Recording metadata (cached) + streaming event iteration + filter parsing. */
final class Rec {
    static final Set<String> CPU_SAMPLES = Set.of("jdk.ExecutionSample", "jdk.CPUTimeSample", "profiler.CPUSample");
    static final Set<String> WALL_SAMPLES = Set.of("profiler.WallClockSample");
    static final Set<String> NATIVE_SAMPLES = Set.of("jdk.NativeMethodSample");
    /** Events that represent a thread waiting/blocking; weighted by duration. */
    static final Set<String> WAIT_EVENTS = Set.of("jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark",
            "jdk.ThreadSleep", "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite",
            "jdk.VirtualThreadPinned", "jdk.VirtualThreadSubmitFailed", "profiler.NativeLock");

    static boolean isSample(String t) { return CPU_SAMPLES.contains(t) || WALL_SAMPLES.contains(t) || NATIVE_SAMPLES.contains(t); }
    static boolean isCustomName(String n) { return !(n.startsWith("jdk.") || n.startsWith("profiler.") || n.startsWith("java.") || n.startsWith("sun.")); }

    final Path path;
    Instant start = Instant.MAX, end = Instant.MIN;
    long total;
    final Map<String, Long> counts = new TreeMap<>();
    final Map<String, EventType> types = new TreeMap<>();
    /** Chunk layout read from the file headers (no parsing). Each chunk is self-contained (own metadata + constant pools). */
    final List<Chunk> chunks = new ArrayList<>();
    String warning = "";
    /** Events starting before / ending after their chunk's time range by up to this much are still found when chunks are skipped for a window. */
    static final long SLACK_NS = 10_000_000_000L;
    /** The JDK parser needs roughly this many bytes of heap per byte of chunk. */
    static final int HEAP_PER_CHUNK_BYTE = 10;

    record Chunk(long offset, long size, long startNs, long endNs) {
        boolean overlaps(Instant from, Instant to) {
            return (from == null || endNs + SLACK_NS >= nanos(from)) && (to == null || startNs - SLACK_NS <= nanos(to));
        }
        boolean inside(Instant from, Instant to) {
            return (from == null || startNs - SLACK_NS >= nanos(from)) && (to == null || endNs + SLACK_NS <= nanos(to));
        }
    }

    static long nanos(Instant i) { return i.getEpochSecond() * 1_000_000_000L + i.getNano(); }

    private Rec(Path p) { path = p; }

    private static final Map<String, Rec> CACHE = new ConcurrentHashMap<>();

    static Rec open(String file) throws IOException {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("'file' (path to .jfr) is required");
        Path p = Path.of(file.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("JFR file not found: " + p);
        String key = p + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
        Rec r = CACHE.get(key);
        if (r != null) return r;
        r = new Rec(p);
        r.scan();
        CACHE.put(key, r);
        return r;
    }

    /** Reads the 68-byte chunk headers only; cost is O(chunks), not O(bytes). */
    private void readChunks() throws IOException {
        long len = Files.size(path);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(48);
            long off = 0;
            while (off + 48 <= len) {
                b.clear();
                if (ch.read(b, off) < 48) break;
                b.flip();
                if (b.get(0) != 'F' || b.get(1) != 'L' || b.get(2) != 'R' || b.get(3) != 0) break;
                long size = b.getLong(8), startNs = b.getLong(32), dur = b.getLong(40);
                if (size <= 0 || off + size > len) break;
                chunks.add(new Chunk(off, size, startNs, startNs + dur));
                off += size;
            }
            if (off != len) chunks.clear(); // unexpected layout: fall back to whole-file reads
        }
    }

    /** Files to read for a chunk: the original when it is the only chunk, else a temporary byte-range copy. */
    Path chunkFile(Chunk c) throws IOException {
        if (chunks.size() <= 1) return path;
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "jfr-agent");
        Files.createDirectories(dir);
        Path t = Files.createTempFile(dir, "chunk-", ".jfr");
        try (FileChannel in = FileChannel.open(path, StandardOpenOption.READ); FileChannel out = FileChannel.open(t, StandardOpenOption.WRITE)) {
            long pos = c.offset, left = c.size;
            while (left > 0) { long n = in.transferTo(pos, left, out); pos += n; left -= n; }
        }
        return t;
    }

    private static final class Part { long total; Instant start = Instant.MAX, end = Instant.MIN; final Map<String, Long> counts = new HashMap<>(); }

    private Part scanFile(Path p) throws IOException {
        Part r = new Part();
        try (RecordingFile f = new RecordingFile(p)) {
            while (f.hasMoreEvents()) {
                RecordedEvent e = f.readEvent();
                r.total++;
                r.counts.merge(e.getEventType().getName(), 1L, Long::sum);
                Instant s = e.getStartTime();
                if (s.isBefore(r.start)) r.start = s;
                Instant en = e.getEndTime();
                if (en.isAfter(r.end)) r.end = en;
            }
        }
        return r;
    }

    /** Number of chunks that can be parsed concurrently without exceeding the heap. */
    private int parallelism(long maxChunk) {
        long budget = (long) (Runtime.getRuntime().maxMemory() * 0.6);
        int byHeap = (int) Math.max(1, budget / Math.max(1, maxChunk * HEAP_PER_CHUNK_BYTE));
        return Math.max(1, Math.min(byHeap, Math.min(Runtime.getRuntime().availableProcessors(), chunks.size())));
    }

    private void scan() throws IOException {
        readChunks();
        long maxChunk = chunks.stream().mapToLong(Chunk::size).max().orElse(Files.size(path));
        if (maxChunk * HEAP_PER_CHUNK_BYTE > Runtime.getRuntime().maxMemory() * 0.8)
            warning = "Largest chunk is " + (maxChunk >> 20) + " MiB and needs about " + (maxChunk * HEAP_PER_CHUNK_BYTE >> 20) + " MiB of heap to parse; raise JFR_AGENT_XMX (now " + (Runtime.getRuntime().maxMemory() >> 20) + " MiB).";
        // event type metadata is identical in every chunk: read it from the smallest one (a full RecordingFile.readEventTypes parses constant pools)
        Chunk smallest = chunks.stream().min(Comparator.comparingLong(Chunk::size)).orElse(null);
        Path tf = smallest == null ? path : chunkFile(smallest);
        try (RecordingFile f = new RecordingFile(tf)) {
            for (EventType t : f.readEventTypes()) types.put(t.getName(), t);
        } finally { if (!tf.equals(path)) Files.deleteIfExists(tf); }

        List<Part> parts = new ArrayList<>();
        if (chunks.size() <= 1) parts.add(scanFile(path));
        else {
            int par = parallelism(maxChunk);
            ExecutorService ex = Executors.newFixedThreadPool(par, r -> { Thread t = new Thread(r, "jfr-scan"); t.setDaemon(true); return t; });
            try {
                List<Future<Part>> fs = new ArrayList<>();
                for (Chunk c : chunks) fs.add(ex.submit(() -> {
                    Path cf = chunkFile(c);
                    try { Part pt = scanFile(cf); chunkEvents.put(c, pt.total); return pt; } finally { Files.deleteIfExists(cf); }
                }));
                for (Future<Part> fu : fs) parts.add(fu.get());
            } catch (InterruptedException | ExecutionException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                if (c instanceof IOException io) throw io;
                if (c instanceof Error er) throw er;
                throw new IOException(c);
            } finally { ex.shutdownNow(); }
        }
        for (Part pt : parts) {
            total += pt.total;
            pt.counts.forEach((k, v) -> counts.merge(k, v, Long::sum));
            if (pt.start.isBefore(start)) start = pt.start;
            if (pt.end.isAfter(end)) end = pt.end;
        }
        if (total == 0) { start = Instant.EPOCH; end = Instant.EPOCH; }
    }

    final Map<Chunk, Long> chunkEvents = new ConcurrentHashMap<>();

    long count(String type) { return counts.getOrDefault(type, 0L); }
    Duration length() { return Duration.between(start, end); }

    /**
     * Stream all events matching the filter.
     *
     * Multi-chunk recordings are read one chunk at a time, from a byte-range copy of just that chunk, so a chunk's constant pools
     * (about 8x its size on the heap) are released before the next one is loaded. With a time window, chunks that cannot hold
     * matching events are never parsed. Several chunks are parsed concurrently when the heap allows it; only parsing runs in
     * parallel, the filter and consumer are called one event at a time, so consumers need no synchronisation (event order across
     * chunks is then not guaranteed).
     */
    void each(Filter flt, Consumer<RecordedEvent> c) throws IOException { each(flt, c, false); }

    /** Same as {@link #each} but events are delivered in file (time) order, one chunk at a time. */
    void eachOrdered(Filter flt, Consumer<RecordedEvent> c) throws IOException { each(flt, c, true); }

    private void each(Filter flt, Consumer<RecordedEvent> c, boolean ordered) throws IOException {
        Frames.clearCaches();
        if (chunks.size() <= 1) { eachIn(path, flt, c, null); return; }
        List<Chunk> sel = new ArrayList<>();
        boolean windowed = flt.from != null || flt.to != null;
        for (Chunk ch : chunks) if (!windowed || ch.overlaps(flt.from, flt.to)) sel.add(ch);
        if (sel.isEmpty()) return;
        int par = ordered ? 1 : Math.min(sel.size(), parallelism(sel.stream().mapToLong(Chunk::size).max().orElse(0)));
        if (par <= 1) {
            for (Chunk ch : sel) eachChunk(ch, flt, c, null);
            return;
        }
        Object lock = new Object();
        ExecutorService ex = Executors.newFixedThreadPool(par, r -> { Thread t = new Thread(r, "jfr-read"); t.setDaemon(true); return t; });
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Chunk ch : sel) fs.add(ex.submit(() -> { eachChunk(ch, flt, c, lock); return null; }));
            for (Future<?> fu : fs) fu.get();
        } catch (InterruptedException | ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof Error er) throw er;
            if (cause instanceof RuntimeException re) throw re;
            throw new IOException(cause);
        } finally { ex.shutdownNow(); }
    }

    private void eachChunk(Chunk ch, Filter flt, Consumer<RecordedEvent> c, Object lock) throws IOException {
        Path cf = chunkFile(ch);
        try { eachIn(cf, flt, c, lock); } finally { if (!cf.equals(path)) Files.deleteIfExists(cf); Frames.clearCaches(); }
    }

    private static void eachIn(Path p, Filter flt, Consumer<RecordedEvent> c, Object lock) throws IOException {
        try (RecordingFile f = new RecordingFile(p)) {
            while (f.hasMoreEvents()) {
                RecordedEvent e = f.readEvent();
                if (lock == null) { if (flt.accept(e)) c.accept(e); }
                else synchronized (lock) { if (flt.accept(e)) c.accept(e); }
            }
        }
    }

    /** Time parse: ISO instant, or offset from recording start: "12.5s", "250ms", "2m", "+3s", or plain number = seconds. */
    Instant parseTime(String s) {
        s = s.trim();
        if (s.matches("[+]?[0-9]*\\.?[0-9]+(ms|s|m|us|ns)?")) {
            String u = s.replaceAll("[0-9.+]", "");
            double v = Double.parseDouble(s.replaceAll("[a-z]", "").replace("+", ""));
            long nanos = switch (u) { case "ms" -> (long) (v * 1e6); case "m" -> (long) (v * 60e9); case "us" -> (long) (v * 1e3); case "ns" -> (long) v; default -> (long) (v * 1e9); };
            return start.plusNanos(nanos);
        }
        return Instant.parse(s);
    }

    String rel(Instant t) { return String.format("+%.3fs", Duration.between(start, t).toNanos() / 1e9); }

    // ---- Filter ----
    static final class Filter {
        Set<String> types;                 // null = all
        Instant from, to;                  // null = open
        String threadContains;             // case-insensitive substring or regex "re:..."
        Boolean virtualOnly;               // null = any
        Predicate<RecordedEvent> extra;

        boolean accept(RecordedEvent e) {
            if (types != null && !types.contains(e.getEventType().getName())) return false;
            if (from != null && e.getEndTime().isBefore(from)) return false;
            if (to != null && e.getStartTime().isAfter(to)) return false;
            if (threadContains != null || virtualOnly != null) {
                RecordedThread t = Frames.thread(e);
                if (t == null) return false;
                if (virtualOnly != null && t.isVirtual() != virtualOnly) return false;
                if (threadContains != null && !threadOk(t)) return false;
            }
            return extra == null || extra.test(e);
        }

        private Pattern re;
        private String lower;
        private final IdentityHashMap<RecordedThread, Boolean> threadMemo = new IdentityHashMap<>();

        /** Thread match, memoised per thread object (a chunk has thousands of threads but millions of events). */
        private boolean threadOk(RecordedThread t) {
            Boolean b = threadMemo.get(t);
            if (b != null) return b;
            if (threadMemo.size() > 200_000) threadMemo.clear();
            String label = Frames.threadLabel(t);
            boolean ok;
            if (threadContains.startsWith("re:")) { if (re == null) re = Pattern.compile(threadContains.substring(3)); ok = re.matcher(label).find(); }
            else if (threadContains.equals("virtual")) ok = t.isVirtual();
            else { if (lower == null) lower = threadContains.toLowerCase(); ok = label.toLowerCase().contains(lower); }
            threadMemo.put(t, ok);
            return ok;
        }

        Filter types(Set<String> t) { types = t; return this; }
        Filter copy() { Filter f = new Filter(); f.types = types; f.from = from; f.to = to; f.threadContains = threadContains; f.virtualOnly = virtualOnly; f.extra = extra; return f; }
    }

    /** Parse common args: from, to, thread, virtual_threads_only, pad. */
    Filter filter(Map<String, Object> a) {
        Filter f = new Filter();
        Object v;
        if ((v = a.get("from")) != null && !v.toString().isBlank()) f.from = parseTime(v.toString());
        if ((v = a.get("to")) != null && !v.toString().isBlank()) f.to = parseTime(v.toString());
        if ((v = a.get("thread")) != null && !v.toString().isBlank()) f.threadContains = v.toString();
        if ((v = a.get("virtual_threads")) != null) {
            String s = v.toString();
            if (s.equals("only")) f.virtualOnly = true; else if (s.equals("exclude")) f.virtualOnly = false;
        }
        return f;
    }
}
