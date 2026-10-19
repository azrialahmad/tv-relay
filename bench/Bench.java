import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

// Runs on the TV via app_process. Reads the URL stored in <urlfile> (so keys never appear on a command line
// or in output) over <threads> parallel connections, each starting at a different offset, for <seconds>.
// Prints per-second Mbps and the average after a 2 s warm-up. Never prints the URL.
// Usage: Bench <seconds> <threads> <urlfile> [startOffsetGB] [spacingMB, default 512]
public class Bench {
    public static void main(String[] args) throws Exception {
        final int seconds = Integer.parseInt(args[0]);
        final int threads = Integer.parseInt(args[1]);
        String u0 = new String(Files.readAllBytes(Paths.get(args[2]))).trim();
        // Resolve redirects once (as the relay does) so the resolver is hit once, not per connection.
        long t0 = System.currentTimeMillis();
        HttpURLConnection r = (HttpURLConnection) new URL(u0).openConnection();
        r.setRequestProperty("Range", "bytes=0-0");
        int rc = r.getResponseCode();
        final String url = r.getURL().toString();
        r.disconnect();
        System.out.println("resolve " + rc + " in " + (System.currentTimeMillis() - t0) + " ms -> " + new URL(url).getHost());
        final long base = args.length > 3 ? (long) (Double.parseDouble(args[3]) * (1L << 30)) : 0;
        final long[] bytes = new long[1];
        final long end = System.currentTimeMillis() + seconds * 1000L;
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final long spacing = (args.length > 4 ? Long.parseLong(args[4]) : 512L) << 20;
            final long off = base + t * spacing; // each connection reads its own region
            ts[t] = new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("Range", "bytes=" + off + "-");
                    int code = c.getResponseCode();
                    if (code >= 400) { System.out.println("HTTP " + code); return; }
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while (System.currentTimeMillis() < end && (n = in.read(buf)) > 0) {
                        synchronized (bytes) { bytes[0] += n; }
                    }
                    c.disconnect();
                } catch (Exception e) {
                    System.out.println("error " + e.getClass().getSimpleName());
                }
            });
            ts[t].start();
        }
        long prev = 0, warm = 0;
        StringBuilder line = new StringBuilder();
        for (int s = 1; s <= seconds; s++) {
            Thread.sleep(1000);
            long now;
            synchronized (bytes) { now = bytes[0]; }
            line.append((now - prev) * 8 / 1_000_000).append(' ');
            prev = now;
            if (s == 2) warm = now;
        }
        for (Thread t : ts) t.join(1000);
        System.out.println(line);
        System.out.printf("avg %.0f Mbps (after 2 s warm-up), %d MB total%n", (prev - warm) * 8.0 / ((seconds - 2) * 1e6), prev >> 20);
        System.exit(0);
    }
}
