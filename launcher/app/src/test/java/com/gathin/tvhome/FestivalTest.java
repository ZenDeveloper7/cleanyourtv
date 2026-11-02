package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;

import org.junit.Test;

public class FestivalTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Test
    public void activeThroughTheLastDayInclusive() {
        Festival f = Festival.parse("Dussehra", "2026-10-21", "", "");
        assertTrue(f.active(DAY));
        assertTrue(f.active(DAY.plusDays(1)));
        assertFalse(f.active(DAY.plusDays(2)));
    }

    @Test
    public void noEndDateStaysOn() {
        assertTrue(Festival.parse("diwali", "", "", "").active(DAY.plusYears(1)));
    }

    @Test
    public void malformedValuesTurnTheSkinOff() {
        assertSame(Festival.NONE, Festival.parse("", "2026-10-21", "", ""));
        assertSame(Festival.NONE, Festival.parse("dussehra", "21/10/2026", "", ""));
        assertFalse(Festival.NONE.active(DAY));
    }

    @Test
    public void customGreetingIsSanitizedAndOverridesTheFestivalDefault() {
        assertEquals("Shubho Bijoya",
                Festival.parse("dussehra", "", "  Shubho\u202E Bijoya ", "").greetingText(null));
    }

    @Test
    public void unknownFestivalKeepsTheTimeOfDayGreeting() {
        assertEquals("", Festival.parse("onam", "", "", "").greetingText(null));
    }

    @Test
    public void accentFallsBackToDiyaGold() {
        assertEquals(Festival.DEFAULT_ACCENT, Festival.parse("dussehra", "", "", "").accentColor());
        assertEquals(0xFF102030, Festival.parse("dussehra", "", "", "#102030").accentColor());
        assertEquals(Festival.DEFAULT_ACCENT, Festival.parse("dussehra", "", "", "gold").accentColor());
    }

    @Test
    public void halloweenHasItsOwnSkinAndPumpkinAccent() {
        Festival f = Festival.parse("Halloween", "2026-11-01", "", "");
        assertTrue(f.isHalloween());
        assertTrue(f.active(LocalDate.of(2026, 10, 31)));
        assertTrue(f.active(LocalDate.of(2026, 11, 1)));
        assertFalse(f.active(LocalDate.of(2026, 11, 2)));
        assertEquals(Festival.PUMPKIN, f.accentColor());
        assertEquals(0xFFAA5500, Festival.parse("halloween", "", "", "#AA5500").accentColor());
    }

    @Test
    public void startDateHoldsTheSkinBackUntilThatDay() {
        Festival f = Festival.parse("birthday", "2026-06-01", "2026-06-01", "Happy Birthday", "");
        assertFalse(f.active(LocalDate.of(2026, 5, 31)));
        assertTrue(f.active(LocalDate.of(2026, 6, 1)));
        assertFalse(f.active(LocalDate.of(2026, 6, 2)));
        assertSame(Festival.NONE, Festival.parse("birthday", "13/11/2026", "", "", ""));
        assertTrue(Festival.parse("diwali", "", "", "", "").active(DAY));
    }

    @Test
    public void birthdayKeepsNormalIconsWithARoseAccent() {
        Festival f = Festival.parse("Birthday", "", "", "");
        assertTrue(f.isBirthday());
        assertFalse(f.isHalloween());
        assertEquals(Festival.ROSE, f.accentColor());
        assertEquals(0xFF112233, Festival.parse("birthday", "", "", "#112233").accentColor());
        assertFalse(Festival.parse("diwali", "", "", "").isBirthday());
    }

    @Test
    public void otherFestivalsKeepTheDiyaSkin() {
        assertFalse(Festival.parse("dussehra", "", "", "").isHalloween());
        assertFalse(Festival.parse("onam", "", "", "").isHalloween());
        assertFalse(Festival.NONE.isHalloween());
    }
}
