package com.muthupalaniyappan.jfragent;

import jdk.jfr.consumer.*;

import java.util.*;
import java.util.regex.Pattern;

/** Stack/thread helpers and JDK-vs-app classification. */
final class Frames {
    private static final Pattern TRAIL = Pattern.compile("[-_# ]?\\d+$"), DIGITS = Pattern.compile("\\d+");

    /** Which optional fields an event type has. RecordedObject.hasField/getValue(String) are linear scans plus string work, so decide once per type. */
    private static final class Meta {
        String threadField; boolean state, samples, stack;
    }
    private static final ThreadLocal<IdentityHashMap<Object, Meta>> METAS = ThreadLocal.withInitial(IdentityHashMap::new);

    private static Meta meta(RecordedEvent e) {
        var et = e.getEventType();
        IdentityHashMap<Object, Meta> m = METAS.get();
        Meta x = m.get(et);
        if (x != null) return x;
        if (m.size() > 10_000) m.clear();
        x = new Meta();
        for (String f : new String[]{"sampledThread", "eventThread", "thread"}) {
            if (e.hasField(f)) { x.threadField = f; break; }
        }
        x.state = e.hasField("state");
        x.samples = e.hasField("samples");
        x.stack = e.hasField("stackTrace");
        m.put(et, x);
        return x;
    }

    static RecordedThread thread(RecordedEvent e) {
        Meta m = meta(e);
        if (m.threadField != null) {
            Object o = e.getValue(m.threadField);
            if (o instanceof RecordedThread t) return t;
        }
        return e.getThread();
    }

    // ---- per-stack / per-thread derived data, cached by object identity ----
    // JFR hands out one RecordedStackTrace / RecordedThread object per constant-pool entry, so a chunk with 500k samples
    // typically has ~60k distinct stacks. Deriving names, categories and classification once per stack (not per event)
    // removes most string building and regex work. Caches are per-thread (scans may run in parallel) and bounded.

    /** Everything the analyses need to know about one stack trace. */
    static final class SI {
        static final SI EMPTY = new SI();
        boolean empty = true;
        String leaf = "<no stack>";        // name of frame 0
        String leafCls = "";
        boolean leafNative, leafJdk, leafFramework;
        String app = "<no stack>";         // name of first application frame, else leaf
        String appLine;                    // "name:line" of that frame when a line number exists
        boolean hasApp;                    // a real application frame exists
        String appOnly;                    // name of first application frame, or null
        String appOnlyLine;                // name:line of first application frame, or null
        String[] names = new String[0];    // frame names, leaf first
        String[] incl = new String[0];     // distinct non-JDK frame names, leaf first
        String jdkCat = "No stack";
        String jdkPkg;                     // package of leaf when JDK non-native
        List<String> text12 = List.of();   // nameLine of the first 12 frames
        List<String> text10 = List.of();   // ... first 10 frames
        String[] lines = new String[0];    // nameLine per frame, leaf first (first 12 only used)
    }

    /** Cached thread facts. */
    static final class TI {
        static final TI NONE = new TI("<unknown>", "<unknown>", false, -1);
        final String label, group; final boolean virtual; final long javaId;
        TI(String l, String g, boolean v, long id) { label = l; group = g; virtual = v; javaId = id; }
    }

    private static final int CACHE_MAX = 400_000;
    private static final ThreadLocal<IdentityHashMap<Object, SI>> STACKS = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<IdentityHashMap<Object, TI>> THREADS = ThreadLocal.withInitial(IdentityHashMap::new);

    /** Drop cached entries so parsed chunks can be garbage collected. Call between chunks / passes. */
    static void clearCaches() { STACKS.get().clear(); THREADS.get().clear(); METHODS.get().clear(); }

    static SI si(RecordedEvent e) {
        if (!meta(e).stack) return SI.EMPTY;
        RecordedStackTrace st = e.getStackTrace();
        if (st == null) return SI.EMPTY;
        IdentityHashMap<Object, SI> m = STACKS.get();
        SI s = m.get(st);
        if (s != null) return s;
        if (m.size() >= CACHE_MAX) m.clear();
        s = build(st.getFrames());
        m.put(st, s);
        return s;
    }

    private static SI build(List<RecordedFrame> fr) {
        if (fr.isEmpty()) return SI.EMPTY;
        SI s = new SI();
        s.empty = false;
        int n = fr.size();
        s.names = new String[n];
        s.lines = new String[Math.min(n, 12)];
        LinkedHashSet<String> incl = new LinkedHashSet<>();
        RecordedFrame leaf = fr.get(0);
        s.leaf = name(leaf);
        s.leafCls = cls(leaf);
        s.leafNative = isNative(leaf);
        s.leafJdk = isJdk(leaf);
        s.leafFramework = isFramework(leaf);
        RecordedFrame app = null;
        for (int i = 0; i < n; i++) {
            RecordedFrame f = fr.get(i);
            String nm = name(f);
            s.names[i] = nm;
            if (i < 12) s.lines[i] = nameLine(f);
            boolean jdk = isJdk(f);
            if (!jdk) incl.add(nm);
            if (app == null && !jdk && !isFramework(f)) app = f;
        }
        s.incl = incl.toArray(new String[0]);
        s.text12 = List.of(s.lines);
        s.text10 = s.text12.subList(0, Math.min(10, s.text12.size()));
        RecordedFrame at = app != null ? app : leaf;
        s.hasApp = app != null;
        s.app = name(at);
        s.appLine = at.getLineNumber() > 0 ? nameLine(at) : null;
        if (app != null) { s.appOnly = s.app; s.appOnlyLine = nameLine(app); }
        s.jdkCat = jdkCategory(fr);
        if (s.leafJdk && !s.leafNative) s.jdkPkg = pkg(s.leafCls, 3);
        return s;
    }

    static TI ti(RecordedEvent e) {
        RecordedThread t = thread(e);
        if (t == null) return TI.NONE;
        IdentityHashMap<Object, TI> m = THREADS.get();
        TI x = m.get(t);
        if (x != null) return x;
        if (m.size() >= CACHE_MAX) m.clear();
        x = new TI(threadLabel(t), threadGroup(t), t.isVirtual(), t.getJavaThreadId());
        m.put(t, x);
        return x;
    }

    /** Stable human label; virtual threads are usually unnamed so use id. */
    static String threadLabel(RecordedThread t) {
        if (t == null) return "<unknown>";
        String n = t.getJavaName();
        if (n == null || n.isEmpty()) n = t.getOSName();
        if (t.isVirtual()) return (n == null || n.isEmpty() ? "virtual" : n) + " [virtual#" + t.getJavaThreadId() + "]";
        return n == null ? "<thread " + t.getId() + ">" : n;
    }

    /** Group key: pools collapse trailing digits so 200 workers aggregate ("http-nio-8080-exec-*"). Virtual -> "<virtual threads>". */
    static String threadGroup(RecordedThread t) {
        if (t == null) return "<unknown>";
        if (t.isVirtual()) return "<virtual threads>";
        String n = threadLabel(t);
        return DIGITS.matcher(TRAIL.matcher(n).replaceAll("-*")).replaceAll("#");
    }

    static boolean threadMatches(RecordedThread t, String pat) {
        String label = threadLabel(t);
        if (pat.startsWith("re:")) return Pattern.compile(pat.substring(3)).matcher(label).find();
        if (pat.equals("virtual")) return t.isVirtual();
        return label.toLowerCase().contains(pat.toLowerCase());
    }

    static List<RecordedFrame> stack(RecordedEvent e) {
        if (!e.hasField("stackTrace")) return List.of();
        RecordedStackTrace st = e.getStackTrace();
        return st == null ? List.of() : st.getFrames();
    }

    /** Per-method facts, computed once per RecordedMethod object (class lookup resolves constant pools lazily and is costly). */
    private static final class MI {
        String cls, name;
        boolean nativeCls, jdkPrefix, framework;
    }
    private static final MI NO_METHOD = new MI();
    static { NO_METHOD.cls = "?"; NO_METHOD.name = "?"; }
    private static final ThreadLocal<IdentityHashMap<Object, MI>> METHODS = ThreadLocal.withInitial(IdentityHashMap::new);

    private static MI mi(RecordedFrame f) {
        RecordedMethod m = f.getMethod();
        if (m == null) return NO_METHOD;
        IdentityHashMap<Object, MI> map = METHODS.get();
        MI x = map.get(m);
        if (x != null) return x;
        if (map.size() >= CACHE_MAX) map.clear();
        x = new MI();
        x.cls = m.getType() == null ? "?" : m.getType().getName();
        x.name = x.cls.isEmpty() ? m.getName() : x.cls + "." + m.getName();
        String c = x.cls;
        x.nativeCls = c.endsWith(".dylib") || c.endsWith(".so") || c.endsWith(".dll") || c.startsWith("lib");
        x.jdkPrefix = c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("com.sun.");
        for (String p : FRAMEWORK) if (c.startsWith(p)) { x.framework = true; break; }
        map.put(m, x);
        return x;
    }

    static String cls(RecordedFrame f) { return mi(f).cls; }

    static String name(RecordedFrame f) { return mi(f).name; }

    static String nameLine(RecordedFrame f) {
        int l = f.getLineNumber();
        return l > 0 ? name(f) + ":" + l : name(f);
    }

    static boolean isNative(RecordedFrame f) {
        String t = f.getType();
        return t != null && (t.equals("Native") || t.equals("C++")) || mi(f).nativeCls;
    }

    static boolean isJdk(RecordedFrame f) { return mi(f).jdkPrefix || isNative(f); }

    private static final String[] FRAMEWORK = {"org.springframework.", "org.apache.", "org.hibernate.", "com.zaxxer.", "io.netty.", "reactor.",
            "io.micrometer.", "org.eclipse.jetty.", "io.undertow.", "com.fasterxml.", "org.jboss.", "org.slf4j.", "ch.qos.", "kotlin.", "kotlinx.", "scala.", "org.junit.", "org.gradle.", "com.intellij."};

    static boolean isFramework(RecordedFrame f) { return mi(f).framework; }

    static boolean isApp(RecordedFrame f) { return !isJdk(f) && !isFramework(f); }

    /** First application frame from the top (or null). */
    static RecordedFrame firstApp(List<RecordedFrame> s) {
        for (RecordedFrame f : s) if (isApp(f)) return f;
        return null;
    }

    /** Wall-clock sample state: true if thread was doing work (runnable). Others: unknown => true. */
    static boolean isRunnable(RecordedEvent e) {
        if (!meta(e).state) return true;
        Object so = e.getValue("state"); String s = String.valueOf(so);
        return s.contains("RUNNABLE");
    }

    static int weight(RecordedEvent e) {
        if (meta(e).samples) { try { int s = e.getInt("samples"); return Math.max(s, 1); } catch (Exception ignored) {} }
        return 1;
    }

    /** Where a sample is spending time at the JDK level. Based on leaf-most JDK-ish frames. */
    static String jdkCategory(List<RecordedFrame> s) {
        if (s.isEmpty()) return "No stack";
        RecordedFrame leaf = s.get(0);
        String c = cls(leaf), n = leaf.getMethod() == null ? "" : leaf.getMethod().getName();
        if (isNative(leaf)) return nativeCategory(s);
        if (c.startsWith("java.util.concurrent.locks") || c.equals("jdk.internal.misc.Unsafe") && n.startsWith("park") || c.equals("java.lang.Object") && n.startsWith("wait")
                || c.equals("java.util.concurrent.locks.LockSupport")) return "Locks / parking / waiting";
        if (c.startsWith("java.util.concurrent")) return "java.util.concurrent";
        if (c.equals("java.lang.Thread") && (n.equals("sleep") || n.startsWith("sleep"))) return "Thread.sleep";
        if (c.startsWith("sun.nio.ch") || c.startsWith("java.net") || c.startsWith("sun.net") || c.startsWith("jdk.internal.net") || c.startsWith("sun.security.ssl") || c.startsWith("javax.net")) return "Network I/O";
        if (c.startsWith("java.io") || c.startsWith("sun.nio.fs") || c.startsWith("java.nio") || c.startsWith("sun.nio.cs")) return "File / stream I/O";
        if (c.startsWith("java.util.regex")) return "Regex";
        if (c.startsWith("java.lang.reflect") || c.startsWith("jdk.internal.reflect") || c.startsWith("java.lang.invoke") || c.startsWith("sun.reflect")) return "Reflection / method handles";
        if (c.startsWith("java.lang.ClassLoader") || c.startsWith("jdk.internal.loader") || c.startsWith("java.lang.Class")) return "Class loading / Class ops";
        if (c.startsWith("java.security") || c.startsWith("javax.crypto") || c.startsWith("sun.security") || c.startsWith("com.sun.crypto")) return "Crypto / security";
        if (c.startsWith("java.util.zip")) return "Compression";
        if (c.startsWith("java.lang.String") || c.startsWith("java.text")) return "Strings / text";
        if (c.startsWith("java.util.") || c.startsWith("java.lang.Integer") || c.startsWith("java.lang.Long") || c.startsWith("java.lang.Math")) return "Collections / utils";
        if (c.startsWith("java.time")) return "Date / time";
        if (c.equals("java.io.ObjectInputStream") || c.equals("java.io.ObjectOutputStream")) return "Serialization";
        if (c.startsWith("jdk.internal.vm") || c.startsWith("java.lang.VirtualThread") || c.startsWith("java.lang.Continuation")) return "Virtual thread scheduling";
        if (isJdk(leaf)) return "Other JDK (" + pkg(c, 2) + ")";
        return isFramework(leaf) ? "Framework code" : "Application code";
    }

    private static String nativeCategory(List<RecordedFrame> s) {
        for (RecordedFrame f : s) {
            String c = cls(f), m = f.getMethod() == null ? "" : f.getMethod().getName();
            String full = c + "." + m;
            if (full.contains("park") || full.contains("cvwait") || full.contains("pthread_cond") || full.contains("futex")) return "Native: thread parking / wait";
            if (full.contains("kevent") || full.contains("epoll") || full.contains("poll") || full.contains("select")) return "Native: I/O polling";
            if (full.contains("read") || full.contains("write") || full.contains("recv") || full.contains("send")) return "Native: I/O";
            if (full.contains("mach_msg") || full.contains("psynch")) return "Native: OS wait";
            if (full.contains("GC") || full.contains("G1") || full.contains("Gang") || full.contains("Zgc") || full.contains("ZGC")) return "Native: GC";
            if (full.contains("Compile") || full.contains("C2") || full.contains("C1")) return "Native: JIT compiler";
        }
        return "Native / JVM internal";
    }

    static String pkg(String cls, int parts) {
        String[] p = cls.split("\\.");
        return String.join(".", Arrays.copyOf(p, Math.min(parts, Math.max(1, p.length - 1))));
    }
}
