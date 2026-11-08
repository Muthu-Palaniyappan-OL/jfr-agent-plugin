#!/usr/bin/env bash
# Usage: scripts/call-tool.sh <tool_name> '<json arguments>'   (runs the MCP server over stdio, prints the tool text)
# Smoke-test helper that needs no MCP client. WAIT=<seconds> to give big recordings more time (default 8).
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL="$1"; ARGS="${2:-}"; [ -n "$ARGS" ] || ARGS='{}'
OUT="$(mktemp)"; FIFO="$(mktemp -u)"; mkfifo "$FIFO"
"$HERE/bin/launch.sh" <"$FIFO" >"$OUT" 2>"${ERRLOG:-/dev/null}" &
PID=$!
exec 3>"$FIFO"
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' >&3
echo '{"jsonrpc":"2.0","method":"notifications/initialized"}' >&3
echo "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"$TOOL\",\"arguments\":$ARGS}}" >&3
for _ in $(seq 1 $(( ${WAIT:-8} * 4 ))); do grep -q '"id":2' "$OUT" 2>/dev/null && break; sleep 0.25; done
exec 3>&-; kill "$PID" 2>/dev/null; pkill -P "$PID" 2>/dev/null; rm -f "$FIFO"
grep '"id":2' "$OUT" | sed -e 's/^.*"text":"//' -e 's/","type":"text".*$//' -e 's/"}\],"isError.*$//' -e 's/\\n/\
/g' -e 's/\\"/"/g' -e 's/\\\\/\\/g'
rm -f "$OUT"
