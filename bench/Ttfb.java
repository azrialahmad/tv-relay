import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

// Sequential bounded Range requests over one keep-alive connection, like one relay worker.
// Prints time-to-first-byte and transfer time per request. Usage: Ttfb <urlfile> <requests> <chunkMB> <startGB>
public class Ttfb {
    public static void main(String[] a) throws Exception {
        String u0 = new String(Files.readAllBytes(Paths.get(a[0]))).trim();
        HttpURLConnection r = (HttpURLConnection) new URL(u0).openConnection();
        r.setRequestProperty("Range", "bytes=0-0");
        r.getResponseCode();
        String url = r.getURL().toString();
        r.getInputStream().read(); r.getInputStream().close();
        int n = Integer.parseInt(a[1]);
        long size = Long.parseLong(a[2]) << 20, off = (long) (Double.parseDouble(a[3]) * (1L << 30));
        byte[] buf = new byte[256 * 1024];
        long totalBytes = 0, totalMs = 0, ttfbSum = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Range", "bytes=" + off + "-" + (off + size - 1));
            c.getResponseCode();
            InputStream in = c.getInputStream();
            int k = in.read(buf);
            long t1 = System.nanoTime();
            long got = k;
            while ((k = in.read(buf)) > 0) got += k;
            in.close();
            long t2 = System.nanoTime();
            long ttfb = (t1 - t0) / 1_000_000, ms = (t2 - t0) / 1_000_000;
            System.out.printf("req %d: ttfb %d ms, %d MB in %d ms = %.0f Mbps%n", i, ttfb, got >> 20, ms, got * 8.0 / ms / 1000);
            totalBytes += got; totalMs += ms; ttfbSum += ttfb;
            off += size;
        }
        System.out.printf("avg ttfb %d ms, overall %.0f Mbps%n", ttfbSum / n, totalBytes * 8.0 / totalMs / 1000);
        System.exit(0);
    }
}
