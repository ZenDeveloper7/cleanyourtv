package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeHeaderTest {

    @Test
    public void dayPartsByDefault() {
        assertEquals(HomeHeader.NIGHT, HomeHeader.dayPart(4, "en", "GB"));
        assertEquals(HomeHeader.MORNING, HomeHeader.dayPart(5, "en", "GB"));
        assertEquals(HomeHeader.AFTERNOON, HomeHeader.dayPart(12, "en", "GB"));
        assertEquals(HomeHeader.EVENING, HomeHeader.dayPart(17, "en", "IN"));
        assertEquals(HomeHeader.NIGHT, HomeHeader.dayPart(22, "en", "IN"));
    }

    @Test
    public void dayPartsKeepTheirLocales() {
        // Indonesian: Siang from 10, Sore from 15, Malam from 18.
        assertEquals(HomeHeader.AFTERNOON, HomeHeader.dayPart(10, "in", ""));
        assertEquals(HomeHeader.EVENING, HomeHeader.dayPart(15, "id", ""));
        assertEquals(HomeHeader.NIGHT, HomeHeader.dayPart(18, "id", ""));
        // Spain and Catalan: "tardes" until 21; Latin American Spanish keeps the default evening.
        assertEquals(HomeHeader.AFTERNOON, HomeHeader.dayPart(20, "es", "ES"));
        assertEquals(HomeHeader.NIGHT, HomeHeader.dayPart(21, "ca", ""));
        assertEquals(HomeHeader.EVENING, HomeHeader.dayPart(20, "es", "MX"));
        // Danish forenoon from 11 to noon.
        assertEquals(HomeHeader.FORENOON, HomeHeader.dayPart(11, "da", "DK"));
        assertEquals(HomeHeader.EVENING, HomeHeader.dayPart(18, "da", "DK"));
    }

    @Test
    public void quietBigTextPrefersFestivalThenWeather() {
        assertEquals(HomeHeader.QUIET_FESTIVAL, HomeHeader.quietState(true, true));
        assertEquals(HomeHeader.QUIET_FESTIVAL, HomeHeader.quietState(true, false));
        assertEquals(HomeHeader.QUIET_WEATHER, HomeHeader.quietState(false, true));
        assertEquals(HomeHeader.QUIET_GREETING, HomeHeader.quietState(false, false));
    }

    @Test
    public void quietIsTheDefaultWhereTheLayoutAllowsIt() {
        assertTrue(Config.quietHome("quiet", true, false, false));
        assertTrue(Config.quietHome(null, true, false, false));
        assertTrue(Config.quietHome("", true, false, false));
        assertTrue(Config.quietHome(" Quiet ", true, false, false));
        assertFalse(Config.quietHome("classic", true, false, false));
        assertFalse(Config.quietHome(" CLASSIC", true, false, false));
        // It steps aside for the layouts that have their own header and dock.
        assertFalse(Config.quietHome("quiet", false, false, false));
        assertFalse(Config.quietHome("quiet", true, true, false));
        assertFalse(Config.quietHome("quiet", true, false, true));
    }

    @Test
    public void festiveGreetingColourIsLiftedTowardsWhite() {
        assertEquals(0xFFFFFFFF, HomeHeader.liftTowardsWhite(0xFF000000, 1f));
        assertEquals(0xFF7F7F7F, HomeHeader.liftTowardsWhite(0xFF000000, 0.5f));
    }
}
