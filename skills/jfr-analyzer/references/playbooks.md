# Playbooks

Each playbook: tool sequence, what to read, typical causes, typical fixes.

## 1. High CPU / slow compute
1. `jfr_timeline` -> pick the hot bucket.
2. `jfr_hot_methods from= to= thread=<pool>` -> top "application" method; check hot lines and the representative stack.
3. `jfr_jdk_breakdown` -> if a JDK category dominates (Regex, Reflection, Crypto, Strings, Collections, Serialization), the fix is at the call site.
4. `jfr_flamegraph kind=cpu` for the picture.
Causes: catastrophic regex backtracking, O(n^2) loops, repeated JSON/XML (de)serialisation, reflection in hot paths, `String.format`/logging in loops, unbounded `stream()` chains, hashing/crypto per request.
Fix: precompile/limit regex, cache, batch, index, move work out of the loop, async/batch logging.

## 2. Slow but CPU idle (waiting)
1. `jfr_diagnose` -> low CPU + blocking.
2. `jfr_hot_methods` blocking section: `jdk.JavaMonitorEnter`, `jdk.ThreadPark`, `jdk.SocketRead`, `jdk.FileRead`, `jdk.ThreadSleep` by app method.
3. For async-profiler wall recordings: `state=sleeping` shows where threads wait.
4. `jfr_threads` blocked time per group.
Causes: slow downstream (DB/HTTP) without timeouts, connection-pool exhaustion (parking on pool), lock convoy, `Thread.sleep` polling, synchronous calls in a small pool.
Fix: timeouts + bulkheads, size pools from measured concurrency, batch calls, shorten critical sections.

## 3. Lock contention
`jfr_hot_methods` (blocking) -> monitor class and enter sites; `jfr_view contention-by-site`.
Fix: narrower `synchronized`, `ConcurrentHashMap.compute`, lock striping, `LongAdder`, read-write/stamped locks, remove shared mutable state.

## 4. GC
`jfr_diagnose` GC finding -> `jfr_view gc-pauses`, `gc-configuration`, `jfr_flamegraph kind=alloc`.
Causes: high allocation rate (boxing, large temporary arrays, string building), small heap, humongous allocations (G1), memory leak (heap growing), `System.gc()`.
Fix: reduce allocation at hot sites, right-size heap/regions, consider ZGC/Generational for latency, fix retention.

## 5. Virtual threads
1. `jfr_threads` -> "Virtual threads" section: pinned count and sites.
2. `jfr_hot_methods thread=virtual` and `virtual_threads=exclude` to compare with platform threads.
3. Carrier starvation: few carriers busy with pinned or CPU-bound tasks; look at `ForkJoinPool-*-worker-*` samples.
Causes: blocking inside `synchronized` (JDK 21-23 pin; JDK 24+ mostly fixed by JEP 491), native frames, `Object.wait`, CPU-bound tasks monopolising carriers, thread-local heavy use, unbounded task creation hitting a downstream limit.
Fix: `ReentrantLock`, upgrade JDK, limit concurrency with `Semaphore` (not pool size), keep CPU work off virtual threads.
Note: `jdk.VirtualThreadStart/End` are disabled by default; `jdk.VirtualThreadPinned` is on (threshold 20 ms).

## 6. Custom events
`jfr_custom_events` -> pick the event -> `name=<event>` for slowest occurrences -> `jfr_zoom_custom_event rank=1` (exact window, same thread) or `mode=aggregate` (all occurrences, statistical). Use `match=<text>` to isolate one endpoint/tenant. The "All other JFR events in the window" table shows GC, safepoints, parks, I/O that overlapped.
Short events (< 40 ms) will have few CPU samples: rely on aggregate mode, or lower `jdk.ExecutionSample#period=10ms`.

## 7. Startup / warm-up
Timeline shows JIT/class-loading early. `jfr_jdk_breakdown` shows class loading and JIT categories. Discard warm-up with `from=`.

## 8. Recording hygiene
Good recording: `java -XX:StartFlightRecording:filename=app.jfr,settings=profile,duration=120s ...` or `jcmd <pid> JFR.start settings=profile duration=120s filename=app.jfr`. Add `jdk.VirtualThreadPinned#threshold=5ms`, `jdk.ExecutionSample#period=10ms` for detail. async-profiler: `asprof -e cpu -d 60 -o jfr -f out.jfr <pid>` (add `--wall 10ms` / `-e wall` for waiting, `--alloc 512k` for allocation, `--lock 10ms` for locks).
