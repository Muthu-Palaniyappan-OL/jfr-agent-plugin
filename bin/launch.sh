#!/usr/bin/env bash
# Launches the JFR MCP server over stdio. stdout is reserved for the MCP protocol; all diagnostics go to stderr.
# JDK selection (first match wins, but the HIGHEST version among discovered JDKs is used unless pinned):
#   1. JFR_AGENT_JAVA_HOME env var or "javaHome" in ~/.jfr-agent/config.json  (pinned)
#   2. highest-version JDK among: JAVA_HOME, OS install dirs, SDKMAN/asdf/jenv/.jdks, PATH
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/mcp-server/target/jfr-agent-mcp.jar"
CONFIG="$HOME/.jfr-agent/config.json"

major_of() { # $1 = jdk home -> prints major version or nothing
  local rel="$1/release" v
  [ -f "$rel" ] || return 0
  v=$(sed -n 's/^JAVA_VERSION="\{0,1\}\([0-9]*\)\.\{0,1\}\([0-9]*\).*/\1 \2/p' "$rel" | head -1)
  set -- $v
  if [ "${1:-}" = "1" ]; then echo "${2:-8}"; else echo "${1:-}"; fi
}

candidates() {
  [ -n "${JAVA_HOME:-}" ] && echo "$JAVA_HOME"
  if [ "$(uname)" = "Darwin" ]; then
    for d in /Library/Java/JavaVirtualMachines/* "$HOME"/Library/Java/JavaVirtualMachines/* /opt/homebrew/opt/openjdk* /usr/local/opt/openjdk*; do
      [ -d "$d/Contents/Home" ] && echo "$d/Contents/Home" || echo "$d"
    done
  fi
  for d in /usr/lib/jvm/* /usr/java/* /opt/java/* /opt/jdk* /usr/lib64/jvm/* "$HOME"/.sdkman/candidates/java/* "$HOME"/.jdks/* "$HOME"/.asdf/installs/java/* "$HOME"/.gradle/jdks/* "$HOME"/.jenv/versions/*; do
    [ -d "$d" ] && echo "$d"
  done
  command -v java >/dev/null 2>&1 && {
    p=$(command -v java); p=$(readlink -f "$p" 2>/dev/null || echo "$p"); echo "$(dirname "$(dirname "$p")")"
  }
}

pinned="${JFR_AGENT_JAVA_HOME:-}"
if [ -z "$pinned" ] && [ -f "$CONFIG" ]; then
  pinned=$(sed -n 's/.*"javaHome"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CONFIG" | head -1)
fi

JAVA=""
if [ -n "$pinned" ] && [ -x "$pinned/bin/java" ]; then
  JAVA="$pinned/bin/java"
else
  [ -n "$pinned" ] && echo "[jfr-agent] WARNING: configured javaHome '$pinned' is not a JDK; auto-detecting." >&2
  best=0
  while IFS= read -r home; do
    [ -x "$home/bin/java" ] || continue
    m=$(major_of "$home"); [ -n "$m" ] || continue
    if [ "$m" -gt "$best" ] 2>/dev/null; then best=$m; JAVA="$home/bin/java"; fi
  done < <(candidates)
  if [ "$best" -lt 25 ] && [ -n "$JAVA" ]; then
    echo "[jfr-agent] NOTE: highest JDK found is $best; the server is built for Java 25. Install JDK 25 or run jfr_configure/JFR_AGENT_JAVA_HOME." >&2
  fi
fi
if [ -z "$JAVA" ]; then
  echo "[jfr-agent] ERROR: no JDK found. Install JDK 25 (https://adoptium.net) and set JAVA_HOME or JFR_AGENT_JAVA_HOME, or add {\"javaHome\":\"/path/to/jdk\"} to $CONFIG" >&2
  exit 1
fi

if [ ! -f "$JAR" ]; then
  echo "[jfr-agent] Server jar missing, building with Maven (one-time)..." >&2
  JH="$(dirname "$(dirname "$JAVA")")"
  if [ -x "$ROOT/mcp-server/mvnw" ]; then MVN="$ROOT/mcp-server/mvnw"
  elif command -v mvn >/dev/null 2>&1; then MVN=mvn
  else echo "[jfr-agent] ERROR: Maven not found and no wrapper. Install Maven or run: cd mcp-server && mvn package" >&2; exit 1; fi
  ( cd "$ROOT/mcp-server" && JAVA_HOME="$JH" "$MVN" -q -DskipTests package >&2 ) || { echo "[jfr-agent] ERROR: build failed" >&2; exit 1; }
fi
exec "$JAVA" -Xss4m -XX:+UseG1GC -Xmx${JFR_AGENT_XMX:-4g} --add-exports=jdk.jfr/jdk.jfr.internal.consumer=ALL-UNNAMED --add-exports=jdk.jfr/jdk.jfr.internal.consumer.filter=ALL-UNNAMED -jar "$JAR"
