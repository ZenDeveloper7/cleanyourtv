package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** Plain JVM tests for Continue watching: which Watch Next rows show, and in what order. */
public class WatchNextTest {
    private static final long NOW = 1_800_000_000_000L, DAY = 24L * 60 * 60 * 1000;
    private static final String JF = "org.jellyfin.androidtv";
    private static final Set<String> APPS = Collections.singleton(JF);

    private static WatchNext.Row row(long id, String pkg, int type, long engagedAgo, long position, long duration) {
        return new WatchNext.Row(id, pkg, "Title " + id, "S1E" + id, "content://x/" + id, "intent:#Intent;end",
                type, NOW - engagedAgo, position, duration);
    }

    private static long[] ids(List<WatchNext.Row> rows) {
        long[] out = new long[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = rows.get(i).id;
        return out;
    }

    @Test
    public void newestFirstFromTheNamedAppsOnly() {
        List<WatchNext.Row> rows = Arrays.asList(
                row(1, JF, WatchNext.TYPE_CONTINUE, 3 * DAY, 10, 100),
                row(2, JF, WatchNext.TYPE_NEXT, DAY, 0, 100),
                row(3, "com.netflix.ninja", WatchNext.TYPE_CONTINUE, 1000, 10, 100),
                row(4, JF, WatchNext.TYPE_CONTINUE, 2 * DAY, 50, 100));
        assertEquals(Arrays.toString(new long[]{2, 4, 1}), Arrays.toString(ids(WatchNext.pick(rows, APPS, NOW, 4))));
    }

    @Test
    public void finishedOldOtherTypesAndUnopenableRowsAreLeftOut() {
        List<WatchNext.Row> rows = Arrays.asList(
                row(1, JF, WatchNext.TYPE_CONTINUE, DAY, 96, 100),          // 96% watched
                row(2, JF, WatchNext.TYPE_CONTINUE, 31 * DAY, 10, 100),     // over 30 days old
                row(3, JF, 2, DAY, 0, 100),                                 // "new", not resume or next
                row(4, JF, 3, DAY, 0, 100),                                 // watch list
                new WatchNext.Row(5, JF, "No intent", "", "", "", WatchNext.TYPE_NEXT, NOW - DAY, 0, 0),
                new WatchNext.Row(6, JF, "Never engaged", "", "", "intent:#Intent;end", WatchNext.TYPE_NEXT, 0, 0, 0),
                row(7, JF, WatchNext.TYPE_CONTINUE, DAY, 94, 100));         // 94%: still shown
        assertEquals(Arrays.toString(new long[]{7}), Arrays.toString(ids(WatchNext.pick(rows, APPS, NOW, 4))));
    }

    @Test
    public void atMostOneRowOfTiles() {
        List<WatchNext.Row> rows = Arrays.asList(
                row(1, JF, WatchNext.TYPE_NEXT, 1, 0, 0), row(2, JF, WatchNext.TYPE_NEXT, 2, 0, 0),
                row(3, JF, WatchNext.TYPE_NEXT, 3, 0, 0), row(4, JF, WatchNext.TYPE_NEXT, 4, 0, 0),
                row(5, JF, WatchNext.TYPE_NEXT, 5, 0, 0));
        assertEquals(Arrays.toString(new long[]{1, 2, 3}), Arrays.toString(ids(WatchNext.pick(rows, APPS, NOW, 3))));
    }

    @Test
    public void episodeLabelAndName() {
        assertEquals("S2E5 The Rains", WatchNext.episodeLabel("2", "5", "The Rains"));
        assertEquals("E12", WatchNext.episodeLabel("", "12", ""));
        assertEquals("", WatchNext.episodeLabel("", "", ""));
        assertEquals("Film", new WatchNext.Row(1, JF, "Film", "", "", "x", 0, 1, 0, 0).name());
        assertEquals("Show · S1E2", new WatchNext.Row(1, JF, "Show", "S1E2", "", "x", 0, 1, 0, 0).name());
    }

    @Test
    public void keyChangesWhenTheRowMovesOn() {
        WatchNext.Row a = row(1, JF, WatchNext.TYPE_NEXT, DAY, 0, 0);
        WatchNext.Row b = new WatchNext.Row(1, JF, "Title 1", "S1E2", "", "x", WatchNext.TYPE_NEXT, NOW, 0, 0);
        assertNotEquals(a.key(), b.key());
        assertEquals(0.5f, row(1, JF, 0, DAY, 50, 100).progress(), 0.0001f);
        assertEquals(0f, row(1, JF, 0, DAY, 50, 0).progress(), 0.0001f);
    }
}
