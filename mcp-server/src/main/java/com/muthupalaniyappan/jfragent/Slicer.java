package com.muthupalaniyappan.jfragent;

import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.internal.consumer.JdkJfrConsumer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.function.Predicate;

/**
 * Lossless time slice of a JFR recording.
 *
 * Uses the same writer as `jfr scrub` (JdkJfrConsumer), so kept events are copied byte-for-byte with their
 * metadata and constant pools. Only the keep-predicate is new: an event is kept when it overlaps [from, to].
 * Every thread and every event type is kept.
 *
 * Requires the JVM flags below (set in bin/launch.sh and the plugin manifest):
 *   --add-exports=jdk.jfr/jdk.jfr.internal.consumer=ALL-UNNAMED
 *   --add-exports=jdk.jfr/jdk.jfr.internal.consumer.filter=ALL-UNNAMED
 */
final class Slicer {
    private Slicer() {}

    /**
     * Writes events overlapping [from, to] (either bound may be null = open) to {@code out}. Returns kept-event count.
     *
     * A JFR file is a sequence of self-contained chunks, so the output is built chunk by chunk:
     *  - chunks entirely outside the window are skipped without being parsed,
     *  - chunks entirely inside it are copied byte-for-byte without being parsed,
     *  - only the (usually one or two) boundary chunks are parsed and filtered, one at a time.
     * This keeps heap use at one chunk and avoids parsing the rest of the recording.
     */
    static long writeWindow(Rec r, Instant from, Instant to, Path out) throws IOException {
        Predicate<RecordedEvent> keep = e ->
                (from == null || !e.getEndTime().isBefore(from)) && (to == null || !e.getStartTime().isAfter(to));
        if (r.chunks.size() <= 1) return filterFile(r.path, out, keep);

        long kept = 0;
        boolean wrote = false;
        try (FileChannel o = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Rec.Chunk ch : r.chunks) {
                if (!ch.overlaps(from, to)) continue;
                if (ch.inside(from, to)) {
                    try (FileChannel in = FileChannel.open(r.path, StandardOpenOption.READ)) {
                        long pos = ch.offset(), left = ch.size();
                        while (left > 0) { long n = in.transferTo(pos, left, o); pos += n; left -= n; }
                    }
                    kept += r.chunkEvents.getOrDefault(ch, 0L);
                } else {
                    kept += filterChunk(r, ch, o, keep);
                }
                wrote = true;
            }
            if (!wrote) filterChunk(r, r.chunks.get(0), o, e -> false); // keep the output a valid (empty) JFR
        }
        return kept;
    }

    private static long filterChunk(Rec r, Rec.Chunk ch, FileChannel o, Predicate<RecordedEvent> keep) throws IOException {
        Path src = r.chunkFile(ch);
        Path tmp = Files.createTempFile(Path.of(System.getProperty("java.io.tmpdir")), "jfr-slice-", ".jfr");
        try {
            long n = filterFile(src, tmp, keep);
            try (FileChannel in = FileChannel.open(tmp, StandardOpenOption.READ)) {
                long pos = 0, left = in.size();
                while (left > 0) { long w = in.transferTo(pos, left, o); pos += w; left -= w; }
            }
            return n;
        } finally {
            Files.deleteIfExists(tmp);
            if (!src.equals(r.path)) Files.deleteIfExists(src);
        }
    }

    private static long filterFile(Path src, Path out, Predicate<RecordedEvent> keep) throws IOException {
        long[] kept = {0};
        // RecordingFile.write() opens its own second parser; the instance we pass only identifies the file. Its constructor
        // already parsed the first chunk's constant pools (~8x the chunk size), so release that before write() allocates its own.
        RecordingFile rf = new RecordingFile(src);
        rf.close();
        JdkJfrConsumer.instance().write(rf, out, e -> {
            boolean k = keep.test(e);
            if (k) kept[0]++;
            return k;
        });
        return kept[0];
    }
}
