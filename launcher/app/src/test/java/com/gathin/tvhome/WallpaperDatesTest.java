package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Plain JVM tests for wallpaper_dates: a picture only on its day, and never in the rotation on other days. */
public class WallpaperDatesTest {
    private static final File NORMAL = new File("wallpapers/01-normal.jpg");
    private static final File OTHER = new File("wallpapers/02-other.jpg");
    private static final File BIRTHDAY = new File("wallpapers/birthday.jpg");
    private static final File XMAS = new File("wallpapers/xmas.jpg");
    private static final File BIRTHDAY_2 = new File("wallpapers/birthday-2.jpg");
    private static final List<File> ALL = Arrays.asList(NORMAL, OTHER, BIRTHDAY, XMAS);

    private static Map<String, List<String>> dates() {
        Map<String, List<String>> d = new HashMap<>();
        d.put("06-01", Collections.singletonList("birthday.jpg"));
        d.put("2026-12-25", Collections.singletonList("xmas.jpg"));
        return d;
    }

    @Test
    public void keysAreYearlyOrFullDates() {
        assertTrue(WallpaperDates.validKey("06-01"));
        assertTrue(WallpaperDates.validKey("2026-06-01"));
        assertFalse(WallpaperDates.validKey("13/11"));
        assertFalse(WallpaperDates.validKey("2026-11"));
        assertFalse(WallpaperDates.validKey(null));
    }

    @Test
    public void theDayShowsOnlyItsPicture() {
        assertEquals(Collections.singletonList(BIRTHDAY),
                WallpaperDates.select(ALL, dates(), LocalDate.of(2026, 6, 1)));
        assertEquals(Collections.singletonList(BIRTHDAY),
                WallpaperDates.select(ALL, dates(), LocalDate.of(2027, 6, 1)));
        assertEquals(Collections.singletonList(XMAS),
                WallpaperDates.select(ALL, dates(), LocalDate.of(2026, 12, 25)));
    }

    @Test
    public void otherDaysLeaveDatedPicturesOut() {
        assertEquals(Arrays.asList(NORMAL, OTHER), WallpaperDates.select(ALL, dates(), LocalDate.of(2026, 5, 31)));
        assertEquals(Arrays.asList(NORMAL, OTHER), WallpaperDates.select(ALL, dates(), LocalDate.of(2026, 6, 2)));
        // a one-off date does not come back the next year
        assertEquals(Arrays.asList(NORMAL, OTHER), WallpaperDates.select(ALL, dates(), LocalDate.of(2027, 12, 25)));
    }

    @Test
    public void aDayCanHoldASlideshowThatStaysHiddenOnOtherDays() {
        List<File> all = Arrays.asList(NORMAL, BIRTHDAY, BIRTHDAY_2, OTHER);
        Map<String, List<String>> d = new HashMap<>();
        d.put("06-01", Arrays.asList("birthday-2.jpg", "birthday.jpg", "missing.jpg"));
        // the collection's order (filenames), not the list's; a missing name is skipped
        assertEquals(Arrays.asList(BIRTHDAY, BIRTHDAY_2), WallpaperDates.select(all, d, LocalDate.of(2026, 6, 1)));
        assertEquals(Arrays.asList(NORMAL, OTHER), WallpaperDates.select(all, d, LocalDate.of(2026, 5, 31)));
    }

    @Test
    public void aDatedDayKeepsItsOwnPaceOtherDaysTheMinuteRotation() {
        long minute = 60_000L;
        assertEquals(15_000L, WallpaperDates.intervalMs(dates(), LocalDate.of(2026, 6, 1), minute, 15));
        assertEquals(minute, WallpaperDates.intervalMs(dates(), LocalDate.of(2026, 5, 31), minute, 15));
        assertEquals(minute, WallpaperDates.intervalMs(dates(), LocalDate.of(2026, 6, 1), minute, 0));
        assertEquals(minute, WallpaperDates.intervalMs(Collections.emptyMap(), LocalDate.of(2026, 6, 1), minute, 15));
    }

    @Test
    public void aFullDateWinsOverAYearlyOne() {
        Map<String, List<String>> d = dates();
        d.put("2026-06-01", Collections.singletonList("xmas.jpg"));
        assertEquals(Collections.singletonList("xmas.jpg"), WallpaperDates.forDay(d, LocalDate.of(2026, 6, 1)));
        assertEquals(Collections.singletonList("birthday.jpg"), WallpaperDates.forDay(d, LocalDate.of(2027, 6, 1)));
        assertNull(WallpaperDates.forDay(d, LocalDate.of(2026, 1, 1)));
    }

    @Test
    public void aMissingPictureOrNoDatesChangeNothing() {
        Map<String, List<String>> d = new HashMap<>();
        d.put("06-01", Collections.singletonList("gone.jpg"));
        assertEquals(ALL, WallpaperDates.select(ALL, d, LocalDate.of(2026, 6, 1)));
        assertEquals(ALL, WallpaperDates.select(ALL, Collections.emptyMap(), LocalDate.of(2026, 6, 1)));
        assertEquals(Collections.emptyList(), WallpaperDates.select(Collections.singletonList(BIRTHDAY),
                dates(), LocalDate.of(2026, 5, 31)));
    }
}
