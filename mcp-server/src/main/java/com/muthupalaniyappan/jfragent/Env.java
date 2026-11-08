package com.muthupalaniyappan.jfragent;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Locates JDKs (highest version wins, like Gradle toolchains) and external tools.
 * Precedence: tool arg/config file > env vars > JAVA_HOME > OS-specific install dirs > PATH.
 */
final class Env {
    static final boolean WIN = System.getProperty("os.name").toLowerCase().contains("win");
    static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path CONFIG = Path.of(System.getProperty("user.home"), ".jfr-agent", "config.json");

    record Jdk(Path home, int major, String source) {
        Path bin(String tool) { return home.resolve("bin").resolve(WIN ? tool + ".exe" : tool); }
        boolean hasJfr() { return Files.isExecutable(bin("jfr")); }
    }

    // ---- config ----
    static ObjectNode config() {
        try {
            if (Files.exists(CONFIG)) return (ObjectNode) JSON.readTree(CONFIG.toFile());
        } catch (Exception ignored) {}
        return JSON.createObjectNode();
    }

    static void saveConfig(ObjectNode n) throws IOException {
        Files.createDirectories(CONFIG.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(CONFIG.toFile(), n);
    }

    static String cfg(String key, String envVar) {
        String v = config().path(key).asString("");
        if (!v.isBlank()) return v;
        v = System.getenv(envVar);
        return v == null ? "" : v;
    }

    // ---- JDK discovery ----
    static List<Jdk> discoverJdks() {
        Map<Path, Jdk> found = new LinkedHashMap<>();
        BiConsumer2 add = (p, src) -> {
            if (p == null) return;
            Path home = normalize(p);
            if (home == null || found.containsKey(home)) return;
            int major = majorOf(home);
            if (major > 0 && Files.exists(home.resolve("bin").resolve(WIN ? "java.exe" : "java")))
                found.put(home, new Jdk(home, major, src));
        };
        String cfg = cfg("javaHome", "JFR_AGENT_JAVA_HOME");
        if (!cfg.isBlank()) add.accept(Path.of(cfg), "configured (javaHome / JFR_AGENT_JAVA_HOME)");
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) add.accept(Path.of(jh), "JAVA_HOME");
        add.accept(Path.of(System.getProperty("java.home")), "running MCP server JVM");

        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (MAC) {
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
            roots.add(Path.of(home, "Library/Java/JavaVirtualMachines"));
            roots.add(Path.of("/opt/homebrew/opt"));
            roots.add(Path.of("/usr/local/opt"));
        } else if (WIN) {
            for (String v : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
                String pf = System.getenv(v);
                if (pf == null) continue;
                for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Microsoft", "Amazon Corretto", "Zulu", "BellSoft", "Semeru", "Programs\\Eclipse Adoptium"})
                    roots.add(Path.of(pf, vendor));
            }
        } else {
            for (String r : new String[]{"/usr/lib/jvm", "/usr/java", "/opt/java", "/opt/jdk", "/usr/local/java", "/usr/lib64/jvm"}) roots.add(Path.of(r));
        }
        roots.add(Path.of(home, ".sdkman/candidates/java"));
        roots.add(Path.of(home, ".jdks"));
        roots.add(Path.of(home, ".asdf/installs/java"));
        roots.add(Path.of(home, ".gradle/jdks"));
        roots.add(Path.of(home, ".m2/jdks"));
        roots.add(Path.of(home, ".jenv/versions"));
        roots.add(Path.of(home, "scoop/apps"));
        for (Path r : roots) {
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> s = Files.list(r)) {
                s.forEach(c -> add.accept(c, "installed JDK dir " + r));
            } catch (IOException ignored) {}
        }
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(java.io.File.pathSeparator)) {
            Path bin = Path.of(d);
            if (Files.exists(bin.resolve(WIN ? "java.exe" : "java"))) {
                try { add.accept(bin.resolve(WIN ? "java.exe" : "java").toRealPath().getParent().getParent(), "PATH"); } catch (IOException ignored) {}
            }
        }
        List<Jdk> list = new ArrayList<>(found.values());
        // configured always first; otherwise highest major, prefer JDKs having jfr
        Jdk configured = list.stream().filter(j -> j.source.startsWith("configured")).findFirst().orElse(null);
        list.sort(Comparator.comparingInt((Jdk j) -> j.major).reversed());
        if (configured != null) { list.remove(configured); list.add(0, configured); }
        return list;
    }

    private interface BiConsumer2 { void accept(Path p, String src); }

    private static Path normalize(Path p) {
        try {
            Path q = p;
            if (Files.isDirectory(q.resolve("Contents/Home"))) q = q.resolve("Contents/Home"); // macOS bundles
            if (!Files.exists(q.resolve("release")) && Files.isDirectory(q.resolve("libexec/openjdk.jdk/Contents/Home")))
                q = q.resolve("libexec/openjdk.jdk/Contents/Home"); // homebrew
            if (!Files.exists(q.resolve("release"))) return null;
            return q.toRealPath();
        } catch (IOException e) { return null; }
    }

    private static final Pattern VER = Pattern.compile("JAVA_VERSION=\"?([0-9]+)(?:\\.([0-9]+))?");

    static int majorOf(Path home) {
        try {
            for (String line : Files.readAllLines(home.resolve("release"))) {
                Matcher m = VER.matcher(line);
                if (m.find()) {
                    int a = Integer.parseInt(m.group(1));
                    return a == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : a;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    static Optional<Jdk> bestJdkWithJfr() {
        return discoverJdks().stream().filter(Jdk::hasJfr).findFirst();
    }

    /** Path to `jfr` CLI or empty with reason. */
    static Optional<Path> jfrCli() {
        String explicit = cfg("jfrPath", "JFR_AGENT_JFR");
        if (!explicit.isBlank() && Files.isExecutable(Path.of(explicit))) return Optional.of(Path.of(explicit));
        return bestJdkWithJfr().map(j -> j.bin("jfr"));
    }

    // ---- async-profiler ----
    static Optional<Path> jfrconv() {
        String explicit = cfg("jfrconvPath", "JFR_AGENT_JFRCONV");
        if (!explicit.isBlank() && Files.isExecutable(Path.of(explicit))) return Optional.of(Path.of(explicit));
        String ap = cfg("asyncProfilerHome", "ASYNC_PROFILER_HOME");
        List<Path> cands = new ArrayList<>();
        String exe = WIN ? "jfrconv.exe" : "jfrconv";
        if (!ap.isBlank()) { cands.add(Path.of(ap, "bin", exe)); cands.add(Path.of(ap, exe)); }
        Path found = onPath(exe);
        if (found != null) cands.add(found);
        if (MAC) { cands.add(Path.of("/opt/homebrew/bin/jfrconv")); cands.add(Path.of("/usr/local/bin/jfrconv")); }
        for (Path c : cands) if (Files.isExecutable(c)) return Optional.of(c);
        return Optional.empty();
    }

    static Optional<Path> asprof() {
        String ap = cfg("asyncProfilerHome", "ASYNC_PROFILER_HOME");
        String exe = WIN ? "asprof.exe" : "asprof";
        if (!ap.isBlank() && Files.isExecutable(Path.of(ap, "bin", exe))) return Optional.of(Path.of(ap, "bin", exe));
        return Optional.ofNullable(onPath(exe));
    }

    static Path onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String d : path.split(java.io.File.pathSeparator)) {
            Path p = Path.of(d, exe);
            if (Files.isExecutable(p)) return p;
        }
        return null;
    }
}
