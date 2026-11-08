package com.muthupalaniyappan.jfragent;

import java.util.*;

/** Counter map with top-N helpers. */
final class Agg {
    final Map<String, long[]> m = new HashMap<>();
    long total;

    void add(String k, long w) { m.computeIfAbsent(k, x -> new long[2])[0] += w; total += w; }
    void add(String k, long w, long extra) { long[] a = m.computeIfAbsent(k, x -> new long[2]); a[0] += w; a[1] += extra; total += w; }
    long get(String k) { long[] a = m.get(k); return a == null ? 0 : a[0]; }

    List<Map.Entry<String, long[]>> top(int n) {
        return m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(n).toList();
    }
}
