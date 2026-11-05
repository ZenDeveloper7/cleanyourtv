package io.github.zendeveloper7.picks;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Continue watching's https posters for the launcher, which has no network (PicksProvider /web?u=...). Hotstar
 * publishes its Watch Next posters as https addresses; this fetches one, keeps it in the cache and hands the file
 * over. https only, no cookies or keys sent, at most MAX_BYTES, and only the newest KEEP pictures are kept.
 */
final class WebPosters {
    private static final String TAG = "JellyfinPicks";
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int KEEP = 60;

    /** The cached picture for an https address, fetched first when needed; null when it cannot be had. */
    static File get(Context ctx, String address) {
        if (address == null || !address.toLowerCase(Locale.ROOT).startsWith("https://")) return null;
        File dir = new File(ctx.getCacheDir(), "web");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File file = new File(dir, name(address));
        if (file.isFile() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            if (!"https".equalsIgnoreCase(c.getURL().getProtocol())) return null;   // a redirect to http
            String type = c.getContentType();
            if (type != null && !type.toLowerCase(Locale.ROOT).startsWith("image/")) return null;
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_BYTES) return null;
                    out.write(buf, 0, n);
                }
            }
            if (total == 0 || !tmp.renameTo(file)) return null;
            prune(dir);
            return file;
        } catch (Exception e) {
            Log.w(TAG, "Poster not fetched: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) c.disconnect();
            tmp.delete();
        }
    }

    private static String name(String address) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(address.getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 16; i++) s.append(String.format(Locale.ROOT, "%02x", d[i]));
            return s + ".img";
        } catch (Exception e) {
            return Integer.toHexString(address.hashCode()) + "-" + address.length() + ".img";
        }
    }

    /** Keeps the KEEP most recently used pictures. */
    private static void prune(File dir) {
        File[] all = dir.listFiles((d, n) -> n.endsWith(".img"));
        if (all == null || all.length <= KEEP) return;
        Arrays.sort(all, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = KEEP; i < all.length; i++) all[i].delete();
    }

    private WebPosters() {
    }
}
