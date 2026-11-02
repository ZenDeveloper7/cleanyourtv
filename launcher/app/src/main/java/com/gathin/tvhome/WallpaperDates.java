package com.gathin.tvhome;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * wallpaper_dates: pictures from the rotating collection for one day, every year ({@code "06-01"}) or on one date
 * ({@code "2026-06-01"}), as one name or a list of names. On that day only they are shown, rotating as the theme's
 * collection does (a slideshow); on every other day they are left out of the rotation, so a surprise stays a
 * surprise. A full date wins over a yearly one on the same day.
 */
final class WallpaperDates {

    /** wallpaper_dates_seconds below this is raised to it: a picture needs time to be decoded and seen. */
    static final int MIN_SECONDS = 5;

    private WallpaperDates() {
    }

    /**
     * How long a picture stays today: wallpaper_dates_seconds on a day with dated pictures when it is set,
     * otherwise the collection's rotation interval.
     */
    static long intervalMs(Map<String, List<String>> dates, LocalDate today, long rotationMs, int datedSeconds) {
        return datedSeconds > 0 && forDay(dates, today) != null ? datedSeconds * 1000L : rotationMs;
    }

    /** Whether {@code key} is a yearly {@code MM-DD} or a full {@code YYYY-MM-DD} date. */
    static boolean validKey(String key) {
        return key != null && (key.matches("\\d{2}-\\d{2}") || key.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    /** The pictures named for {@code today}, or null when the day has none. */
    static List<String> forDay(Map<String, List<String>> dates, LocalDate today) {
        List<String> exact = dates.get(today.toString());
        if (exact != null) return exact;
        return dates.get(String.format(Locale.ROOT, "%02d-%02d", today.getMonthValue(), today.getDayOfMonth()));
    }

    /**
     * The pictures the rotation may show today: only the day's pictures that are in the collection, in the
     * collection's order, when it has any; otherwise the collection without any dated picture (empty when every
     * picture is dated, which leaves the plain colour).
     */
    static List<File> select(List<File> all, Map<String, List<String>> dates, LocalDate today) {
        if (dates.isEmpty()) return all;
        List<String> names = forDay(dates, today);
        if (names != null) {
            List<File> day = new ArrayList<>();
            for (File file : all) if (names.contains(file.getName())) day.add(file);
            if (!day.isEmpty()) return day;
        }
        Set<String> dated = new HashSet<>();
        for (List<String> list : dates.values()) dated.addAll(list);
        List<File> rest = new ArrayList<>();
        for (File file : all) if (!dated.contains(file.getName())) rest.add(file);
        return rest;
    }
}
