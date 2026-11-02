package io.github.zendeveloper7.picks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PicksTest {

    private static final long NOW = 1_800_000_000_000L;

    private static Picks.Item movie(String id, int minutes, String... genres) {
        return new Picks.Item(id, "Film " + id, "Movie", Arrays.asList(genres), Collections.emptyList(), 2020, minutes, 0, 7f, 0);
    }

    private static Picks.Item series(String id, String... genres) {
        return new Picks.Item(id, "Show " + id, "Series", Arrays.asList(genres), Collections.emptyList(), 2021, 0, 2, 7f, 0);
    }

    // Wednesday 2026-11-04 20:00 and Saturday 2026-11-07 20:00
    private static final LocalDateTime WEEKNIGHT = LocalDateTime.of(2026, 11, 4, 20, 0);
    private static final LocalDateTime SATURDAY = LocalDateTime.of(2026, 11, 7, 20, 0);

    @Test
    public void tasteWinsOverEverythingElseBeingEqual() {
        Picks.Profile p = Picks.Profile.of(Arrays.asList(movie("h1", 100, "Crime"), movie("h2", 100, "Crime", "Drama")));
        Picks.Moment m = new Picks.Moment(SATURDAY, "", true);
        List<Picks.Pick> picks = Picks.choose(Arrays.asList(movie("a", 100, "Romance"), movie("b", 100, "Crime")), p, m, NOW);
        assertEquals("b", picks.get(0).item.id);
    }

    @Test
    public void similarTitlesSayWhy() {
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        p.similarTo.put("b", "Lupin");
        Picks.Pick pick = Picks.score(movie("b", 100, "Documentary"), p, new Picks.Moment(WEEKNIGHT, "", true), NOW);
        assertEquals("Because you watched Lupin", pick.reason);
    }

    @Test
    public void rainLiftsCosyGenres() {
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        Picks.Moment rain = new Picks.Moment(WEEKNIGHT, "RAIN", false);
        Picks.Pick mystery = Picks.score(movie("m", 100, "Mystery"), p, rain, NOW);
        Picks.Pick comedy = Picks.score(movie("c", 100, "Comedy"), p, rain, NOW);
        assertTrue(mystery.score - 0.5f > comedy.score - 0.5f - 0.5f);   // jitter is at most 0.5
        assertEquals("Rainy evening pick", mystery.reason);
    }

    @Test
    public void weekendEveningsFavourFilmsAndWeeknightsSeries() {
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        Picks.Pick film = Picks.score(movie("f", 120, "Documentary"), p, new Picks.Moment(SATURDAY, "", true), NOW);
        assertEquals("Weekend movie night", film.reason);
        float seriesNight = Picks.score(series("s", "Documentary"), p, new Picks.Moment(WEEKNIGHT, "", true), NOW).score;
        float longFilmNight = Picks.score(movie("l", 170, "Documentary"), p, new Picks.Moment(WEEKNIGHT, "", true), NOW).score;
        assertTrue(seriesNight > longFilmNight - 0.5f);
    }

    @Test
    public void atMostFivePicksAndNoDuplicates() {
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        List<Picks.Item> many = Arrays.asList(movie("1", 90), movie("2", 90), movie("3", 90), movie("4", 90),
                movie("5", 90), movie("6", 90), movie("1", 90));
        List<Picks.Pick> picks = Picks.choose(many, p, new Picks.Moment(WEEKNIGHT, "", true), NOW);
        assertEquals(Picks.COUNT, picks.size());
    }

    @Test
    public void labelsReadNaturally() {
        assertEquals("Rainy Wednesday evening", new Picks.Moment(WEEKNIGHT, "RAIN", false).label());
        assertEquals("Saturday evening", new Picks.Moment(SATURDAY, "", true).label());
    }

    @Test
    public void idsAreDashedForTheJellyfinApp() {
        assertEquals("0123abcd-4567-89ab-cdef-0123456789ab", Refresh.dashed("0123abcd456789abcdef0123456789ab"));
        assertEquals("already-dashed", Refresh.dashed("already-dashed"));
    }

    @Test
    public void subtitles() {
        assertEquals("2020 · 1 h 52 min", Refresh.subtitle(movie("x", 112)));
        assertEquals("Series · 2021 · 2 seasons", Refresh.subtitle(series("y")));
    }

    @Test
    public void weatherCodesMatchTheLauncher() {
        assertEquals("CLEAR", Refresh.kind(1));
        assertEquals("RAIN", Refresh.kind(81));
        assertEquals("STORM", Refresh.kind(95));
    }
}
