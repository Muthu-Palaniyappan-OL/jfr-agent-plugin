package com.muthupalaniyappan.jfragent;

import java.util.List;

/** Tiny markdown builder. */
final class Md {
    final StringBuilder sb = new StringBuilder();

    Md h(int level, String t) { sb.append("\n").append("#".repeat(level)).append(' ').append(t).append("\n\n"); return this; }
    Md p(String t) { sb.append(t).append("\n"); return this; }
    Md li(String t) { sb.append("- ").append(t).append("\n"); return this; }
    Md nl() { sb.append("\n"); return this; }

    Md table(List<String> head, List<List<String>> rows) {
        if (rows.isEmpty()) { p("_(none)_").nl(); return this; }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n' && !(sb.length() > 1 && sb.charAt(sb.length() - 2) == '\n')) sb.append("\n");
        sb.append("| ").append(String.join(" | ", head)).append(" |\n");
        sb.append("|").append(" --- |".repeat(head.size())).append("\n");
        for (List<String> r : rows) {
            sb.append("| ");
            for (int i = 0; i < r.size(); i++) sb.append(r.get(i).replace("|", "\\|").replace("\n", " ")).append(i + 1 < r.size() ? " | " : " |");
            sb.append("\n");
        }
        return nl();
    }

    static String pct(double part, double whole) { return whole <= 0 ? "0.0%" : String.format("%.1f%%", 100.0 * part / whole); }
    static String ms(long nanos) { return String.format("%.2f ms", nanos / 1e6); }
    static String dur(long nanos) {
        if (nanos >= 1_000_000_000L) return String.format("%.2f s", nanos / 1e9);
        return ms(nanos);
    }
    @Override public String toString() { return sb.toString(); }
}
