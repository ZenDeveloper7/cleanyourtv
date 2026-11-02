package io.github.zendeveloper7.picks;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One refresh: read the history and the unwatched library from the server, score them for now (Picks), keep the
 * best few and their pictures, and tell the launcher (notifyChange). One at a time; slow work, never on the main thread.
 */
final class Refresh {

    private static final String TAG = "JellyfinPicks";
    static final String SETUP = "setup";
    static final String KEY_ADDRESS = "address", KEY_API_KEY = "api_key", KEY_USER_ID = "user_id", KEY_USER_NAME = "user_name";
    /** Picks older than this are refreshed when the launcher asks (the TV was off); the hourly job keeps them fresh. */
    static final long STALE_MS = 60 * 60_000L;
    private static final String WEATHER = "content://io.github.sumyabazar1.weather.now/now";
    private static final String JELLYFIN_APP = "org.jellyfin.androidtv";
    private static final String JELLYFIN_STARTUP = "org.jellyfin.androidtv.ui.startup.StartupActivity";

    private static boolean running;

    static boolean configured(Context ctx) {
        SharedPreferences s = ctx.getSharedPreferences(SETUP, Context.MODE_PRIVATE);
        return !s.getString(KEY_ADDRESS, "").isEmpty() && !s.getString(KEY_API_KEY, "").isEmpty()
                && !s.getString(KEY_USER_ID, "").isEmpty();
    }

    /** Starts a refresh on its own thread unless one is running; then runs done (any thread), if not null. */
    static void async(Context ctx, Runnable done) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            run(app);
            if (done != null) done.run();
        }, "jellyfin-picks").start();
    }

    /** The refresh itself; false when not set up, already running, or the server could not be read. */
    static boolean run(Context ctx) {
        synchronized (Refresh.class) {
            if (running) return false;
            running = true;
        }
        try {
            if (!configured(ctx)) return false;
            SharedPreferences s = ctx.getSharedPreferences(SETUP, Context.MODE_PRIVATE);
            Jellyfin server = new Jellyfin(s.getString(KEY_ADDRESS, ""), s.getString(KEY_API_KEY, ""));
            String user = s.getString(KEY_USER_ID, "");

            Map<String, String> titles = new HashMap<>();
            List<Picks.Item> history = server.history(user, titles);
            Picks.Profile profile = Picks.Profile.of(history);
            for (int i = 0; i < Math.min(3, history.size()); i++) {
                String id = history.get(i).id;
                try {
                    for (String similar : server.similar(user, id)) profile.similarTo.putIfAbsent(similar, titles.get(id));
                } catch (Exception e) {
                    Log.w(TAG, "No similar titles for one item: " + e);
                }
            }
            Set<String> watched = new HashSet<>();
            for (Picks.Item h : history) watched.add(h.id);
            List<Picks.Item> candidates = server.candidates(user);
            candidates.removeIf(c -> watched.contains(c.id));

            String[] weather = weather(ctx);
            Picks.Moment moment = new Picks.Moment(LocalDateTime.now(), weather[0], !"0".equals(weather[1]));
            long now = System.currentTimeMillis();
            String owner = server.base + "/" + user;
            JSONObject previous = saved(ctx);
            if (previous != null && !owner.equals(previous.optString("owner"))) previous = null;
            String bucket = moment.now.toLocalDate() + "/" + moment.partOfDay();
            Map<String, Long> shown = recent(previous == null ? null : previous.optJSONObject("shown"), now);
            // Freeze the exposure history within a time bucket. Otherwise each refresh penalises its own picks.
            boolean sameBucket = previous != null && bucket.equals(previous.optString("bucket"));
            Map<String, Long> cooldown = sameBucket
                    ? recent(previous.optJSONObject("cooldown"), now) : new HashMap<>(shown);
            long cooldownAt = sameBucket ? previous.optLong("cooldownAt", now) : now;
            List<Picks.Pick> picks = Picks.choose(candidates, profile, moment, now, cooldown, cooldownAt);

            File posters = posters(ctx);
            JSONArray out = new JSONArray();
            Set<String> keep = new HashSet<>();
            for (Picks.Pick p : picks) {
                File f = new File(posters, p.item.id + ".jpg");
                if (!f.isFile()) server.poster(p.item.id, f);
                keep.add(f.getName());
                JSONObject o = new JSONObject();
                o.put("id", p.item.id);
                o.put("title", p.item.title);
                o.put("subtitle", subtitle(p.item));
                o.put("reason", p.reason);
                o.put("intent", intent(p.item.id));
                o.put("poster", f.isFile());
                out.put(o);
                shown.put(p.item.id, now);
            }
            File[] old = posters.listFiles();
            if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
            JSONObject saved = new JSONObject();
            saved.put("owner", owner);
            saved.put("bucket", bucket);
            saved.put("shown", new JSONObject(shown));
            saved.put("cooldown", new JSONObject(cooldown));
            saved.put("cooldownAt", cooldownAt);
            saved.put("label", moment.label());
            saved.put("weather", weather[0]);
            saved.put("updated", System.currentTimeMillis());
            saved.put("picks", out);
            write(new File(ctx.getFilesDir(), "picks.json"), saved.toString());
            Log.i(TAG, "Picks refreshed: " + picks.size() + " for " + moment.label());
            ctx.getContentResolver().notifyChange(PicksProvider.NOW_URI, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Picks not refreshed: " + e);
            return false;
        } finally {
            synchronized (Refresh.class) {
                running = false;
            }
        }
    }

    /** Retain only a week of exposure history; no watch history is persisted here. */
    private static Map<String, Long> recent(JSONObject json, long now) {
        Map<String, Long> out = new HashMap<>();
        if (json != null) {
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                long time = json.optLong(id, 0);
                if (time > 0 && time <= now && now - time < 7L * 86400000) out.put(id, time);
            }
        }
        return out;
    }

    /** The saved picks, or null before the first refresh. */
    static JSONObject saved(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "picks.json");
            return f.isFile() ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static File posters(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "posters");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** "2019 · 1 h 52 min" for a film, "Series · 2019 · 3 seasons" for a series. */
    static String subtitle(Picks.Item item) {
        StringBuilder s = new StringBuilder(item.isMovie() ? "" : "Series");
        if (item.year > 0) s.append(s.length() > 0 ? " · " : "").append(item.year);
        if (item.isMovie() && item.runtimeMin > 0) {
            String length = item.runtimeMin >= 60
                    ? String.format(Locale.ROOT, "%d h %d min", item.runtimeMin / 60, item.runtimeMin % 60)
                    : item.runtimeMin + " min";
            s.append(s.length() > 0 ? " · " : "").append(length);
        }
        if (!item.isMovie() && item.childCount > 0) {
            s.append(" · ").append(item.childCount).append(item.childCount == 1 ? " season" : " seasons");
        }
        return s.toString();
    }

    /** Opens the item's page in the Jellyfin app (its StartupActivity takes the id in ItemId, as a dashed UUID). */
    static String intent(String id) {
        return new Intent().setComponent(new ComponentName(JELLYFIN_APP, JELLYFIN_STARTUP))
                .putExtra("ItemId", dashed(id)).toUri(Intent.URI_INTENT_SCHEME);
    }

    /** Jellyfin's API gives ids without dashes; the app expects the usual 8-4-4-4-12 form. */
    static String dashed(String id) {
        if (id.length() != 32 || id.contains("-")) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-"
                + id.substring(16, 20) + "-" + id.substring(20);
    }

    /** The weather add-on's current kind ("RAIN", ...) and day flag ("1"/"0"); empty when it is not there or old. */
    static String[] weather(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(Uri.parse(WEATHER),
                new String[]{"code", "is_day", "updated"}, null, null, null)) {
            // By name: a provider may ignore the projection and answer with its own columns, in its own order.
            if (c != null && c.moveToFirst()
                    && System.currentTimeMillis() - c.getLong(c.getColumnIndexOrThrow("updated")) < 3L * 3600 * 1000) {
                return new String[]{kind(c.getInt(c.getColumnIndexOrThrow("code"))),
                        String.valueOf(c.getInt(c.getColumnIndexOrThrow("is_day")))};
            }
        } catch (Exception ignored) {
            // no weather add-on: picks without the weather
        }
        return new String[]{"", "1"};
    }

    /** WMO code to the launcher's weather kind names (com.gathin.tvhome.Weather.kind). */
    static String kind(int wmo) {
        if (wmo == 0 || wmo == 1) return "CLEAR";
        if (wmo == 2) return "PARTLY_CLOUDY";
        if (wmo == 3) return "CLOUDY";
        if (wmo == 45 || wmo == 48) return "FOG";
        if (wmo >= 51 && wmo <= 57) return "DRIZZLE";
        if (wmo >= 61 && wmo <= 67 || wmo >= 80 && wmo <= 82) return "RAIN";
        if (wmo >= 71 && wmo <= 77 || wmo == 85 || wmo == 86) return "SNOW";
        if (wmo >= 95 && wmo <= 99) return "STORM";
        return "CLOUDY";
    }

    private static void write(File file, String text) throws java.io.IOException {
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file)) throw new java.io.IOException("could not save " + file.getName());
    }

    private Refresh() {
    }
}
