package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class WeatherTest {

    @Test
    public void wmoCodesMapToKinds() {
        assertEquals(Weather.Kind.CLEAR, Weather.kind(0));
        assertEquals(Weather.Kind.CLEAR, Weather.kind(1));
        assertEquals(Weather.Kind.PARTLY_CLOUDY, Weather.kind(2));
        assertEquals(Weather.Kind.CLOUDY, Weather.kind(3));
        assertEquals(Weather.Kind.FOG, Weather.kind(48));
        assertEquals(Weather.Kind.DRIZZLE, Weather.kind(53));
        assertEquals(Weather.Kind.RAIN, Weather.kind(63));
        assertEquals(Weather.Kind.RAIN, Weather.kind(81));
        assertEquals(Weather.Kind.SNOW, Weather.kind(73));
        assertEquals(Weather.Kind.SNOW, Weather.kind(86));
        assertEquals(Weather.Kind.STORM, Weather.kind(95));
        assertEquals(Weather.Kind.CLOUDY, Weather.kind(42));   // unknown
    }

    @Test
    public void staleReadingsAreNotFresh() {
        long now = 10L * 60 * 60 * 1000;
        assertTrue(new Weather.Now(0, 20f, true, "Clear", now - 60_000).fresh(now));
        assertFalse(new Weather.Now(0, 20f, true, "Clear", now - Weather.MAX_AGE_MS - 1).fresh(now));
        assertFalse(new Weather.Now(0, 20f, true, "Clear", 0).fresh(now));
    }

    private static List<File> files(String... names) {
        File[] out = new File[names.length];
        for (int i = 0; i < names.length; i++) out[i] = new File("/w/" + names[i]);
        return Arrays.asList(out);
    }

    @Test
    public void wallpapersForTheWeatherWin() {
        List<File> all = files("rain-street.jpg", "sun-beach.jpg", "city.jpg");
        assertEquals(files("rain-street.jpg"), Weather.forWeather(all, Weather.Kind.RAIN));
        assertEquals(files("rain-street.jpg"), Weather.forWeather(all, Weather.Kind.DRIZZLE));
        assertEquals(files("sun-beach.jpg"), Weather.forWeather(all, Weather.Kind.CLEAR));
    }

    @Test
    public void otherWeatherWaitsAndPlainPicturesFillIn() {
        List<File> all = files("rain-street.jpg", "sun-beach.jpg", "city.jpg");
        assertEquals(files("city.jpg"), Weather.forWeather(all, Weather.Kind.SNOW));
        List<File> tagged = files("rain-street.jpg", "sun-beach.jpg");
        assertEquals(tagged, Weather.forWeather(tagged, Weather.Kind.FOG));   // nothing fits: all of them
        assertEquals(all, Weather.forWeather(all, null));                     // no reading: untouched
    }
}
