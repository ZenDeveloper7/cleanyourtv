package io.github.sumyabazar1.weather;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The latest current reading, kept in the app's own preferences. WeatherActivity and RefreshJob save it,
 * NowProvider reads it for the launcher. Nothing here touches the network.
 */
final class NowStore {
    private static final String PREFS = "now";
    static final String KEY_CODE = "code", KEY_TEMP_C = "temp_c", KEY_IS_DAY = "is_day",
            KEY_CONDITION = "condition", KEY_UPDATED = "updated";

    private NowStore() { }

    /** One saved reading, as the launcher reads it. */
    static final class Reading {
        final int code;
        final float tempC;
        final boolean day;
        final String condition;
        final long updated;

        Reading(int code, float tempC, boolean day, String condition, long updated) {
            this.code = code;
            this.tempC = tempC;
            this.day = day;
            this.condition = condition;
            this.updated = updated;
        }
    }

    /** Saves the report's current reading (its code is already snow-adjusted), stamped with the time now. */
    static void save(Context context, Forecast.Report report) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_CODE, report.code)
                .putFloat(KEY_TEMP_C, (float) report.tempC)
                .putBoolean(KEY_IS_DAY, report.day)
                .putString(KEY_CONDITION, condition(report.code))
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .commit();   // written before the change is announced, so a reader sees the new reading
        // Tell a listening launcher at once (it re-reads content://...weather.now/now).
        context.getContentResolver().notifyChange(NowProvider.NOW_URI, null);
    }

    /** The saved reading, or null before the first one is saved. */
    static Reading read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long updated = prefs.getLong(KEY_UPDATED, 0);
        if (updated <= 0) return null;
        return new Reading(prefs.getInt(KEY_CODE, 0), prefs.getFloat(KEY_TEMP_C, 0),
                prefs.getBoolean(KEY_IS_DAY, true), prefs.getString(KEY_CONDITION, ""), updated);
    }

    /**
     * Short English words for a WMO weather code, for the launcher. Forecast.sky is the localized, finer
     * version for the page; this one is fixed English.
     */
    static String condition(int code) {
        switch (code) {
            case 0: case 1: return "Clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle";
            case 61: case 66: case 80: return "Light rain";
            case 63: case 81: return "Rain";
            case 65: case 67: case 82: return "Heavy rain";
            case 71: case 73: case 75: case 77: case 85: case 86: return "Snow";
            default: return code >= 95 ? "Thunderstorm" : "Overcast";
        }
    }
}
