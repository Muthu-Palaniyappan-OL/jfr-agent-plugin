package com.muthupalaniyappan.jfragent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs external processes (jfr CLI, jfrconv) with captured output. */
final class Proc {
    record Result(int exit, String out, boolean truncated) {}

    static Result run(List<String> cmd, Path dir, int timeoutSec, int maxChars) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());
        Process p = pb.start();
        p.getOutputStream().close();
        StringBuilder sb = new StringBuilder();
        boolean[] trunc = {false};
        Thread t = Thread.ofVirtual().start(() -> {
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (sb.length() < maxChars) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    else trunc[0] = true;
                }
            } catch (IOException ignored) {}
        });
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); sb.append("\n[timed out after ").append(timeoutSec).append("s]"); }
        t.join(2000);
        String s = sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
        return new Result(p.isAlive() ? -1 : p.exitValue(), s, trunc[0] || sb.length() > maxChars);
    }

    static List<String> cmd(String... a) { return new ArrayList<>(List.of(a)); }
}
