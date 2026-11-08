package com.muthupalaniyappan.jfragent;

import jdk.jfr.consumer.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Flamegraph generation: built-in (HTML + folded) or async-profiler jfrconv. */
final class Flame {
    static final class Node {
        final String name; long value, self; final Map<String, Node> kids = new LinkedHashMap<>();
        Node(String n) { name = n; }
    }

    static Md run(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException, InterruptedException {
        String engine = Profile.strArg(a, "engine", "builtin");
        if (engine.equals("async-profiler")) return viaJfrconv(r, base, a);
        String kind = Profile.strArg(a, "kind", "cpu"); // cpu | wall | wait | alloc
        boolean splitThreads = Profile.boolArg(a, "split_threads", false);
        boolean reverse = Profile.boolArg(a, "reverse", false);
        double minPct = Double.parseDouble(Profile.strArg(a, "min_pct", "0.02"));
        int maxDepth = Profile.intArg(a, "max_depth", 200);
        Rec.Filter f = base.copy();
        String weightField = null;
        switch (kind) {
            case "wait" -> { f.types = new HashSet<>(Rec.WAIT_EVENTS); f.types.removeIf(t -> r.count(t) == 0); }
            case "alloc" -> {
                f.types = new HashSet<>(List.of("jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB"));
                f.types.removeIf(t -> r.count(t) == 0);
            }
            case "wall" -> f = Profile.sampleFilter(r, merge(a, "source", "wall"), base);
            default -> f = Profile.sampleFilter(r, a, base);
        }
        if (f.types == null || f.types.isEmpty()) return new Md().p("No events for kind `" + kind + "` in this recording.");
        Node root = new Node("all");
        long[] cnt = {0};
        r.each(f, e -> {
            Frames.SI st = Frames.si(e);
            if (st.empty) return;
            long w = switch (kind) {
                case "wait" -> Math.max(1, e.getDuration().toNanos() / 1000); // microseconds
                case "alloc" -> allocWeight(e);
                default -> Frames.weight(e);
            };
            Node cur = root;
            cur.value += w;
            if (splitThreads) cur = child(cur, Frames.ti(e).label, w);
            int sz = st.names.length, n = Math.min(sz, maxDepth);
            if (reverse) for (int i = 0; i < n; i++) cur = child(cur, st.names[i], w);
            else for (int i = sz - 1; i >= sz - n; i--) cur = child(cur, st.names[i], w);
            cur.self += w;
            cnt[0]++;
        });
        String unit = switch (kind) { case "wait" -> "microseconds blocked"; case "alloc" -> "bytes allocated"; default -> "samples"; };
        if (root.value == 0) return new Md().p("No events matched the window/thread filter.");

        Path out = outPath(r, a, "html", kind);
        Files.createDirectories(out.toAbsolutePath().getParent());
        String title = r.path.getFileName() + " - " + kind + " - " + Profile.windowText(r, base);
        Files.writeString(out, html(title, unit, root, root.value * minPct / 100.0), StandardCharsets.UTF_8);
        Path folded = Path.of(out.toString().replaceFirst("\\.html$", "") + ".folded");
        // stream to disk: folded output can reach hundreds of MB (reversed / per-thread graphs), never build it as one String
        try (var w = Files.newBufferedWriter(folded, StandardCharsets.UTF_8)) {
            folded(root, new ArrayDeque<>(), w, true);
        }

        Md md = new Md();
        md.p("**Flamegraph written:** `" + out + "`  (open in any browser; click a frame to zoom, Ctrl+F style search box on top)");
        md.p("**Folded stacks:** `" + folded + "` (feed to FlameGraph/speedscope/`flamegraph.pl`)");
        md.p("**Kind:** " + kind + (reverse ? " (icicle/reversed: leaf first)" : "") + "; **events:** " + cnt[0] + "; **total weight:** " + root.value + " " + unit + "; window " + Profile.windowText(r, base));
        md.h(3, "Widest leaf frames (self weight)");
        Map<String, Long> selfByName = new HashMap<>();
        collectSelf(root, selfByName);
        List<List<String>> rows = new ArrayList<>();
        selfByName.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(12)
                .forEach(en -> rows.add(List.of(en.getKey(), String.valueOf(en.getValue()), Md.pct(en.getValue(), root.value))));
        md.table(List.of("Frame", unit, "Share"), rows);
        md.p("Also available: `engine=async-profiler` to render with async-profiler's own flamegraph UI.");
        return md;
    }

    static Map<String, Object> merge(Map<String, Object> a, String k, Object v) { Map<String, Object> m = new HashMap<>(a); m.put(k, v); return m; }

    static long allocWeight(RecordedEvent e) {
        for (String f : new String[]{"weight", "allocationSize", "tlabSize"}) if (e.hasField(f)) { try { return Math.max(1, e.getLong(f)); } catch (Exception ignored) {} }
        return 1;
    }

    static Node child(Node p, String name, long w) {
        Node c = p.kids.computeIfAbsent(name, Node::new);
        c.value += w;
        return c;
    }

    static void collectSelf(Node n, Map<String, Long> m) {
        if (n.self > 0) m.merge(n.name, n.self, Long::sum);
        n.kids.values().forEach(k -> collectSelf(k, m));
    }

    static void folded(Node n, Deque<String> path, java.io.Writer w, boolean isRoot) throws IOException {
        if (!isRoot) path.addLast(n.name.replace(';', ':'));
        if (n.self > 0) {
            boolean first = true;
            for (String p : path) { if (!first) w.write(';'); w.write(p); first = false; }
            w.write(' '); w.write(Long.toString(n.self)); w.write('\n');
        }
        for (Node k : n.kids.values()) folded(k, path, w, false);
        if (!isRoot) path.removeLast();
    }

    static Path outPath(Rec r, Map<String, Object> a, String ext, String kind) {
        String out = Profile.strArg(a, "output", null);
        if (out != null) return Path.of(out.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath();
        String base = r.path.getFileName().toString().replaceFirst("\\.jfr$", "");
        String suffix = "";
        if (a.get("from") != null || a.get("to") != null) suffix += "-" + Objects.toString(a.get("from"), "").replaceAll("[^A-Za-z0-9.]", "") + "_" + Objects.toString(a.get("to"), "").replaceAll("[^A-Za-z0-9.]", "");
        if (a.get("thread") != null) suffix += "-" + a.get("thread").toString().replaceAll("[^A-Za-z0-9]", "_");
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "jfr-agent");
        return dir.resolve(base + "-" + kind + suffix + "." + ext);
    }

    // ---------- async-profiler jfrconv ----------
    static Md viaJfrconv(Rec r, Rec.Filter base, Map<String, Object> a) throws IOException, InterruptedException {
        var conv = Env.jfrconv();
        if (conv.isEmpty()) return new Md().p("**WARNING: async-profiler `jfrconv` not found.** Install async-profiler (https://github.com/async-profiler/async-profiler/releases) and either put `jfrconv` on PATH, set `ASYNC_PROFILER_HOME`, or call `jfr_configure` with `asyncProfilerHome` / `jfrconvPath`. Falling back is possible with `engine=builtin`.");
        String kind = Profile.strArg(a, "kind", "cpu");
        String fmt = Profile.strArg(a, "format", "html");
        Path out = outPath(r, a, fmt.equals("collapsed") ? "collapsed" : fmt, kind + "-ap");
        Files.createDirectories(out.getParent());
        List<String> cmd = Proc.cmd(conv.get().toString(), "-o", fmt);
        switch (kind) {
            case "wall" -> cmd.add("--wall");
            case "alloc" -> cmd.add("--alloc");
            case "wait" -> cmd.add("--lock");
            case "cpu-time" -> cmd.add("--cpu-time");
            default -> cmd.add("--cpu");
        }
        // jfrconv keeps only runnable samples by default. async-profiler JFR files are often
        // almost entirely STATE_SLEEPING, which makes the default output empty. "runnable" is
        // the sensible default for CPU and wall flamegraphs (on-CPU work). Pass state=sleeping
        // to see waiting threads; state=all passes no filter, which jfrconv treats as runnable.
        String state = Profile.strArg(a, "state", "runnable");
        if (!state.equals("all")) { cmd.add("-s"); cmd.add(state); }
        if (base.from != null) { cmd.add("--from"); cmd.add(String.valueOf(Duration2.ms(r.start, base.from))); }
        if (base.to != null) { cmd.add("--to"); cmd.add(String.valueOf(Duration2.ms(r.start, base.to))); }
        if (Profile.boolArg(a, "split_threads", false)) cmd.add("--threads");
        if (Profile.boolArg(a, "reverse", false)) cmd.add("--reverse");
        if (Profile.boolArg(a, "lines", false)) cmd.add("--lines");
        if (base.threadContains != null) { if (!cmd.contains("--threads")) cmd.add("--threads"); cmd.add("--include"); cmd.add(java.util.regex.Pattern.quote(base.threadContains).replace("\\Q", "").replace("\\E", "")); }
        cmd.add(r.path.toString());
        cmd.add(out.toString());
        var res = Proc.run(cmd, null, 300, 20000);
        Md md = new Md();
        md.p("Command: `" + String.join(" ", cmd) + "`");
        if (res.exit() != 0 || !Files.exists(out) || Files.size(out) == 0 || hasNoFrames(out, fmt)) {
            md.p("**jfrconv failed or produced no frames** (exit " + res.exit() + "):\n```\n" + res.out() + "\n```");
            md.p("Hint: the recording may have no samples matching `state` (try `state=all`); `kind=wall` needs wall-clock samples, `kind=cpu` needs jdk.ExecutionSample, `kind=wait` needs lock events; or try `engine=builtin`.");
        } else {
            md.p("**Flamegraph written:** `" + out + "` (" + Files.size(out) + " bytes)");
            if (!res.out().isBlank()) md.p("```\n" + res.out().trim() + "\n```");
            md.p("Note: `thread` is applied as a stack regex (--include) by jfrconv; times are relative ms from recording start (from/to).");
        }
        return md;
    }

    /** True when jfrconv wrote no stacks: an empty collapsed file, or HTML whose cpool holds only the root frame. */
    static boolean hasNoFrames(Path out, String fmt) throws IOException {
        String text = Files.readString(out, StandardCharsets.UTF_8);
        if (fmt.equals("html")) return text.matches("(?s).*const cpool = \\[\\s*'all'\\s*\\];.*");
        if (fmt.equals("collapsed")) return text.isBlank();
        return false;
    }

    static final class Duration2 {
        static long ms(java.time.Instant start, java.time.Instant t) { return java.time.Duration.between(start, t).toMillis(); }
    }

    // ---------- html ----------
    static String json(Node n, double minValue) {
        StringBuilder sb = new StringBuilder();
        write(n, minValue, sb);
        return sb.toString().replace("</", "<\\/");
    }

    static void write(Node n, double min, StringBuilder sb) {
        sb.append("{\"n\":\"").append(esc(n.name)).append("\",\"v\":").append(n.value);
        List<Node> kids = n.kids.values().stream().filter(k -> k.value >= min).sorted((x, y) -> Long.compare(y.value, x.value)).toList();
        if (!kids.isEmpty()) {
            sb.append(",\"c\":[");
            for (int i = 0; i < kids.size(); i++) { if (i > 0) sb.append(','); write(kids.get(i), min, sb); }
            sb.append(']');
        }
        sb.append('}');
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) { case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append(' '); default -> { if (c < 32) b.append(' '); else b.append(c); } }
        }
        return b.toString();
    }

    static String html(String title, String unit, Node root, double min) {
        return TEMPLATE.replace("%%TITLE%%", title.replace("<", "&lt;")).replace("%%UNIT%%", unit).replace("%%DATA%%", json(root, min));
    }

    static final String TEMPLATE = """
<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>%%TITLE%%</title>
<style>
:root{--bg:#0f1115;--panel:#171a21;--line:#262b36;--txt:#e6e8ee;--mut:#8b93a7;--acc:#7aa2f7}
@media (prefers-color-scheme: light){:root{--bg:#f7f8fa;--panel:#ffffff;--line:#e2e5ec;--txt:#1d2230;--mut:#5f6880;--acc:#2f6fed}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);font:13px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Inter,Roboto,sans-serif}
header{position:sticky;top:0;z-index:2;background:var(--panel);border-bottom:1px solid var(--line);padding:12px 16px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
h1{font-size:15px;font-weight:600;margin:0 8px 0 0;max-width:60vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.search{flex:1;min-width:220px;max-width:420px;position:relative}
.search input{width:100%;padding:7px 10px 7px 30px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--txt);font:inherit;outline:none}
.search input:focus{border-color:var(--acc)}
.search::before{content:"\\2315";position:absolute;left:10px;top:6px;color:var(--mut)}
button{padding:7px 12px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--txt);font:inherit;cursor:pointer}
button:hover{border-color:var(--acc)}
.meta{color:var(--mut);font-size:12px}
.crumbs{padding:10px 16px;color:var(--mut);font-size:12px;white-space:nowrap;overflow-x:auto}
.crumbs a{color:var(--acc);cursor:pointer;text-decoration:none}
.crumbs a:hover{text-decoration:underline}
main{padding:0 16px 16px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:8px;overflow:hidden}
svg{display:block;width:100%}
.f{cursor:pointer;stroke:var(--bg);stroke-width:.6}
.f:hover{stroke:var(--txt);stroke-width:1}
.f.hit{stroke:#ff4fd8;stroke-width:1.5}
.lbl{font-size:11px;fill:#111;pointer-events:none}
#tip{position:fixed;pointer-events:none;background:#000d;color:#fff;padding:6px 9px;border-radius:6px;font-size:12px;max-width:70vw;display:none;white-space:pre-wrap;z-index:5}
footer{padding:8px 16px 16px;color:var(--mut);font-size:12px}
</style></head>
<body>
<header>
  <h1 title="%%TITLE%%">%%TITLE%%</h1>
  <div class="search"><input id="q" type="search" placeholder="Search frames (regex)…" aria-label="search"></div>
  <button id="reset" type="button">Reset zoom</button>
  <span class="meta">unit: %%UNIT%%</span>
</header>
<div class="crumbs" id="crumbs"></div>
<main><div class="card"><svg id="svg" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="flamegraph"></svg></div></main>
<footer>Click a frame to zoom in. Click "all" in the path above to zoom out. Hover for exact values.</footer>
<div id="tip"></div>
<script>
const DATA=%%DATA%%;
const SVG=document.getElementById('svg'),TIP=document.getElementById('tip'),CRUMBS=document.getElementById('crumbs');
const ROW=20,PAD=2,NS='http://www.w3.org/2000/svg';
let root=DATA,path=[DATA],re=null;
function hash(s){let h=0;for(let i=0;i<s.length;i++)h=(h*31+s.charCodeAt(i))|0;return h;}
function fill(n){const h=Math.abs(hash(n.n))%360;
  if(/^(java|javax|jdk|sun|com\\.sun)\\./.test(n.n))return `hsl(${35+(h%30)} 85% 62%)`;
  if(/::|\\.(so|dylib|dll)/.test(n.n))return `hsl(${200+(h%30)} 55% 62%)`;
  return `hsl(${(h%25)+4} 80% 60%)`;}
function depth(n){let d=1;(n.c||[]).forEach(k=>{d=Math.max(d,1+depth(k));});return d;}
function esc(s){return s.replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
function crumbs(){CRUMBS.innerHTML=path.map((n,i)=>`<a data-i="${i}">${esc(n.n==='all'?'all':n.n.length>60?n.n.slice(0,59)+'…':n.n)}</a>`).join(' › ');
  CRUMBS.querySelectorAll('a').forEach(a=>a.onclick=()=>{const i=+a.dataset.i;root=path[i];path=path.slice(0,i+1);draw();});}
function draw(){
  const W=Math.max(320,SVG.parentElement.clientWidth-18),H=depth(root)*ROW+PAD*2;
  SVG.setAttribute('viewBox',`0 0 ${W} ${H}`);SVG.setAttribute('height',H);SVG.innerHTML='';
  const total=root.v;
  (function rec(n,x,w,d){
    if(w<0.4)return;
    const y=H-(d+1)*ROW-PAD;
    const g=document.createElementNS(NS,'g');
    const r=document.createElementNS(NS,'rect');
    r.setAttribute('x',x);r.setAttribute('y',y);r.setAttribute('width',Math.max(0,w-0.5));r.setAttribute('height',ROW-2);r.setAttribute('rx',2);
    r.setAttribute('fill',fill(n));r.setAttribute('class','f'+(re&&re.test(n.n)?' hit':''));
    g.appendChild(r);
    if(w>36){const t=document.createElementNS(NS,'text');t.setAttribute('class','lbl');t.setAttribute('x',x+4);t.setAttribute('y',y+13);
      const m=Math.floor((w-8)/6.4);t.textContent=n.n.length>m?n.n.slice(0,Math.max(1,m-1))+'…':n.n;g.appendChild(t);}
    g.addEventListener('mousemove',e=>{TIP.style.display='block';TIP.style.left=(e.clientX+12)+'px';TIP.style.top=(e.clientY+12)+'px';
      TIP.textContent=`${n.n}\\n${n.v.toLocaleString()} (${(100*n.v/total).toFixed(2)}% of view, ${(100*n.v/DATA.v).toFixed(2)}% of total)`;});
    g.addEventListener('mouseleave',()=>TIP.style.display='none');
    g.addEventListener('click',()=>{if(n.c&&n.c.length){root=n;path.push(n);draw();}});
    SVG.appendChild(g);
    let cx=x;(n.c||[]).forEach(k=>{const kw=w*k.v/n.v;rec(k,cx,kw,d+1);cx+=kw;});
  })(root,0,W,0);
  crumbs();
}
document.getElementById('reset').onclick=()=>{root=DATA;path=[DATA];draw();};
document.getElementById('q').oninput=e=>{try{re=e.target.value?new RegExp(e.target.value,'i'):null}catch(x){re=null}draw();};
window.addEventListener('resize',draw);draw();
</script></body></html>
""";

}
