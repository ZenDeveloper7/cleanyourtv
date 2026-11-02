package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * weather_package: the current weather, as a weather app on the TV publishes it (the launcher itself has no network).
 * The app names a read-only content provider in a {@code meta-data} on its {@code <application>}:
 * <pre>
 *   &lt;meta-data android:name="com.gathin.tvhome.weather" android:value="&lt;authority&gt;" /&gt;
 * </pre>
 * and {@code content://<authority>/now} returns one row (or none, before the first reading) with the columns
 * {@link #COL_CODE} (WMO weather code, as Open-Meteo gives it), {@link #COL_TEMP_C}, {@link #COL_IS_DAY} (1 or 0),
 * {@link #COL_CONDITION} (a short English description) and {@link #COL_UPDATED} (epoch milliseconds).
 */
final class Weather {

    static final String META_DATA = "com.gathin.tvhome.weather";
    static final String PATH = "now";
    static final String COL_CODE = "code", COL_TEMP_C = "temp_c", COL_IS_DAY = "is_day",
            COL_CONDITION = "condition", COL_UPDATED = "updated";
    /** A reading older than this is ignored: Home goes back to its usual look rather than show stale weather. */
    static final long MAX_AGE_MS = 3L * 60 * 60 * 1000;

    /** What Home draws for the weather (WeatherLayerView) and which wallpapers it prefers. */
    enum Kind {
        CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM;

        /** The wallpaper name prefix that marks a picture for this weather, e.g. "rain-street.jpg". */
        String wallpaperPrefix() {
            switch (this) {
                case CLEAR: return "sun-";
                case PARTLY_CLOUDY:
                case CLOUDY: return "cloud-";
                case FOG: return "fog-";
                case DRIZZLE:
                case RAIN: return "rain-";
                case SNOW: return "snow-";
                default: return "storm-";
            }
        }
    }

    /** The kind for a WMO weather code (0-1 clear ... 99 thunderstorm with hail); unknown codes count as cloudy. */
    static Kind kind(int wmo) {
        if (wmo == 0 || wmo == 1) return Kind.CLEAR;   // 1 is "mainly clear"
        if (wmo == 2) return Kind.PARTLY_CLOUDY;
        if (wmo == 3) return Kind.CLOUDY;
        if (wmo == 45 || wmo == 48) return Kind.FOG;
        if (wmo >= 51 && wmo <= 57) return Kind.DRIZZLE;
        if (wmo >= 61 && wmo <= 67 || wmo >= 80 && wmo <= 82) return Kind.RAIN;
        if (wmo >= 71 && wmo <= 77 || wmo == 85 || wmo == 86) return Kind.SNOW;
        if (wmo >= 95 && wmo <= 99) return Kind.STORM;
        return Kind.CLOUDY;
    }

    /** One reading. */
    static final class Now {
        final Kind kind;
        final int code;
        final float tempC;
        final boolean day;
        final String condition;
        final long updated;

        Now(int code, float tempC, boolean day, String condition, long updated) {
            this.kind = kind(code);
            this.code = code;
            this.tempC = tempC;
            this.day = day;
            this.condition = condition == null ? "" : condition;
            this.updated = updated;
        }

        boolean fresh(long now) {
            return updated > 0 && now - updated <= MAX_AGE_MS;
        }
    }

    /**
     * The provider of the named package (weather_package), as game_day finds its own: null when the key is empty,
     * the package is not installed, or it names no provider of its own. Naming the package is the owner's opt-in.
     */
    static Uri uri(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META_DATA);
            if (authority == null || authority.trim().isEmpty()) return null;
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            if (provider == null || !pkg.trim().equals(provider.packageName)) return null;
            return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority.trim()).appendPath(PATH).build();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Reads the current reading; null when there is none or anything fails. Off the main thread: it is IPC. */
    static Now read(Context ctx, Uri uri) {
        if (uri == null) return null;
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{COL_CODE, COL_TEMP_C, COL_IS_DAY, COL_CONDITION, COL_UPDATED}, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            // By name: a provider may ignore the projection and answer with its own columns, in its own order.
            int condition = c.getColumnIndex(COL_CONDITION);
            return new Now(c.getInt(c.getColumnIndexOrThrow(COL_CODE)), c.getFloat(c.getColumnIndexOrThrow(COL_TEMP_C)),
                    c.getInt(c.getColumnIndexOrThrow(COL_IS_DAY)) == 1,
                    condition < 0 || c.isNull(condition) ? "" : c.getString(condition),
                    c.getLong(c.getColumnIndexOrThrow(COL_UPDATED)));
        } catch (Exception e) {
            return null;
        }
    }

    /** True for a wallpaper named for some weather ("rain-street.jpg"), which only plays in that weather. */
    static boolean tagged(String name) {
        for (Kind k : Kind.values()) if (name.startsWith(k.wallpaperPrefix())) return true;
        return false;
    }

    /**
     * The wallpapers for this weather: the ones named for it when there are any, else the untagged ones (pictures
     * tagged for other weather wait their turn), else all of them. A null kind (no reading) leaves the list alone.
     */
    static List<File> forWeather(List<File> files, Kind kind) {
        if (kind == null || files.isEmpty()) return files;
        List<File> match = new ArrayList<>(), plain = new ArrayList<>();
        String prefix = kind.wallpaperPrefix();
        for (File f : files) {
            String name = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith(prefix)) match.add(f);
            else if (!tagged(name)) plain.add(f);
        }
        if (!match.isEmpty()) return match;
        return plain.isEmpty() ? files : plain;
    }

    /** "Rainy", "Sunny", ... for the greeting ("Rainy afternoon"); clear nights are "Clear". */
    static String adjective(Kind kind, boolean day) {
        switch (kind) {
            case CLEAR: return day ? "Sunny" : "Clear";
            case PARTLY_CLOUDY:
            case CLOUDY: return "Cloudy";
            case FOG: return "Foggy";
            case DRIZZLE: return "Drizzly";
            case RAIN: return "Rainy";
            case SNOW: return "Snowy";
            default: return "Stormy";
        }
    }

    private Weather() {
    }
}
