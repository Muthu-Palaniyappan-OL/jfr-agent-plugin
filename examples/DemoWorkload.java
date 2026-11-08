import jdk.jfr.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demo workload for the JFR agent plugin. Run with JDK 21+ (25 recommended):
 *   java -XX:StartFlightRecording:filename=demo.jfr,settings=profile,jdk.VirtualThreadPinned#threshold=5ms examples/DemoWorkload.java
 * Contains: a custom event (RequestHandled), a CPU hot method, regex abuse, synchronized contention,
 * virtual threads pinned in synchronized blocking (JDK 21-23 pin; JDK 24+ mostly do not), and allocation churn.
 */
public class DemoWorkload {
    @Name("demo.RequestHandled") @Label("Request handled") @Category("Demo")
    static class RequestHandled extends Event {
        @Label("Endpoint") String endpoint;
        @Label("User") int user;
    }

    static final Object LOCK = new Object();
    static final ReentrantLock RL = new ReentrantLock();

    static long fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

    static int slowRegex(String s) {
        int c = 0;
        for (int i = 0; i < 200; i++) if (s.matches("(a+)+b")) c++;
        return c;
    }

    static void contended() {
        synchronized (LOCK) { try { Thread.sleep(2); } catch (InterruptedException ignored) {} }
    }

    static List<byte[]> churn() {
        List<byte[]> l = new ArrayList<>();
        for (int i = 0; i < 200; i++) l.add(new byte[16 * 1024]);
        return l;
    }

    static void handle(String endpoint, int user) {
        RequestHandled e = new RequestHandled();
        e.endpoint = endpoint; e.user = user;
        e.begin();
        try {
            switch (endpoint) {
                case "/cpu" -> fib(27 + user % 3);
                case "/regex" -> slowRegex("aaaaaaaaaaaaaaaaaaaaaaaa!");
                case "/lock" -> contended();
                case "/alloc" -> churn();
                default -> { try { Thread.sleep(5); } catch (InterruptedException ignored) {} }
            }
        } finally { e.commit(); }
    }

    public static void main(String[] args) throws Exception {
        String[] eps = {"/cpu", "/regex", "/lock", "/alloc", "/io"};
        long end = System.currentTimeMillis() + Long.getLong("demo.seconds", 20) * 1000;
        try (ExecutorService platform = Executors.newFixedThreadPool(4, r -> new Thread(r, "demo-worker"));
             ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            int i = 0;
            while (System.currentTimeMillis() < end) {
                final int u = i++;
                final String ep = eps[u % eps.length];
                (u % 2 == 0 ? platform : virtual).submit(() -> handle(ep, u));
                // pinned virtual thread: blocking inside synchronized
                if (u % 10 == 0) virtual.submit(() -> { synchronized (LOCK) { try { Thread.sleep(8); } catch (InterruptedException ignored) {} } });
                Thread.sleep(2);
            }
        }
        System.out.println("done");
    }
}
