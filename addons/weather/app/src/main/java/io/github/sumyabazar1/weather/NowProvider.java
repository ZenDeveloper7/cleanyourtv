package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only: content://io.github.sumyabazar1.weather.now/now gives the latest saved reading (NowStore) as one row,
 * or none before the first one. Columns: code, temp_c, is_day (1 or 0), condition, updated (epoch milliseconds).
 * The query itself never waits on the network: when the reading is older than {@link #STALE_MS} (the TV was off),
 * it answers with what it has and starts one background fetch, which announces the new reading with notifyChange.
 */
public final class NowProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.now";
    private static final String PATH = "now";
    static final Uri NOW_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH);
    /** A reading this old is refreshed when someone asks for it; the periodic job keeps it fresher while the TV is on. */
    static final long STALE_MS = 30 * 60_000L;
    /** At most one such fetch at a time, and not again within this long after one started. */
    private static final long RETRY_MS = 2 * 60_000L;
    private static boolean fetching;
    private static long lastTry;

    @Override
    public boolean onCreate() {
        RefreshJob.schedule(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {
                "code", "temp_c", "is_day", "condition", "updated"});
        if (!isNow(uri)) return cursor;
        NowStore.Reading now = NowStore.read(getContext());
        if (now != null) {
            cursor.addRow(new Object[] {
                    now.code, now.tempC, now.day ? 1 : 0, now.condition, now.updated});
        }
        refreshIfStale(getContext().getApplicationContext(), now);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    /** Starts one background fetch when the reading is missing or older than STALE_MS and a city is chosen. */
    private static void refreshIfStale(Context ctx, NowStore.Reading now) {
        long t = System.currentTimeMillis();
        if (now != null && t - now.updated < STALE_MS) return;
        SharedPreferences place = ctx.getSharedPreferences(WeatherActivity.PREFS, Context.MODE_PRIVATE);
        if (!place.contains("name")) return;
        synchronized (NowProvider.class) {
            if (fetching || t - lastTry < RETRY_MS) return;
            fetching = true;
            lastTry = t;
        }
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        android.util.Log.i("WeatherNow", "Reading stale, fetching");
        new Thread(() -> {
            try {
                NowStore.save(ctx, Forecast.fetch(lat, lon, Forecast.MIN_DAYS));
            } catch (Exception e) {
                // the periodic job tries again; the launcher keeps its usual look meanwhile
                android.util.Log.w("WeatherNow", "Stale reading not refreshed: " + e);
            } finally {
                synchronized (NowProvider.class) {
                    fetching = false;
                }
            }
        }, "weather-now").start();
    }

    private static boolean isNow(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority()) && uri.getPathSegments().size() == 1
                && PATH.equals(uri.getPathSegments().get(0));
    }
}
