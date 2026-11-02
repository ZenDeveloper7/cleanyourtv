package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class ScreensaverVideoTest {

    private static final List<File> VIDEOS = Arrays.asList(new File("/s/a.mp4"), new File("/s/b.mp4"), new File("/s/c.mp4"));

    @Test
    public void neverRepeatsTheLastVideoWhenThereIsAChoice() {
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            assertNotEquals("b.mp4", ScreensaverVideo.pickOther(VIDEOS, "b.mp4", random).getName());
        }
    }

    @Test
    public void everyOtherVideoGetsPicked() {
        Random random = new Random(7);
        boolean a = false, c = false;
        for (int i = 0; i < 200; i++) {
            String n = ScreensaverVideo.pickOther(VIDEOS, "b.mp4", random).getName();
            a |= n.equals("a.mp4");
            c |= n.equals("c.mp4");
        }
        assertEquals(true, a && c);
    }

    @Test
    public void aSingleVideoPlaysEveryTime() {
        List<File> one = Collections.singletonList(new File("/s/a.mp4"));
        assertEquals("a.mp4", ScreensaverVideo.pickOther(one, "a.mp4", new Random(3)).getName());
    }
}
