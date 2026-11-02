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
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Picks.Pick pick : picks) assertTrue(ids.add(pick.item.id));
    }

    @Test
    public void weekendMorningDoesNotGetMovieNightBonus() {
        for (int day : new int[]{7, 8}) {
            assertTrue(!new Picks.Moment(LocalDateTime.of(2026, 11, day, 9, 0), "", true).weekendEvening());
        }
        assertTrue(new Picks.Moment(SATURDAY, "", false).weekendEvening());
        assertTrue(!new Picks.Moment(LocalDateTime.of(2026, 11, 8, 21, 0), "", false).weekendEvening());
    }

    @Test
    public void nearTiesPreferADifferentGenre() {
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        // All candidates have identical context and quality scores; jitter is smaller than the diversity penalty.
        Picks.Moment m = new Picks.Moment(WEEKNIGHT, "", false);
        List<Picks.Pick> picks = Picks.choose(Arrays.asList(movie("a", 90, "Romance"),
                movie("b", 90, "Romance"), movie("c", 90, "Romance"),
                movie("d", 90, "Documentary")), p, m, NOW);
        assertTrue(!picks.get(0).item.genres.equals(picks.get(1).item.genres));
    }

    @Test
    public void recentSuggestionsLoseNearTiesButStrongTasteStillWins() {
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        Picks.Moment m = new Picks.Moment(WEEKNIGHT, "", false);
        java.util.Map<String, Long> recent = new java.util.HashMap<>();
        recent.put("a", NOW);
        List<Picks.Item> candidates = Arrays.asList(movie("a", 90, "Romance"), movie("b", 90, "Documentary"));
        assertEquals("b", Picks.choose(candidates, p, m, NOW, recent).get(0).item.id);
        p.genres.put("Romance", 1f);
        assertEquals("a", Picks.choose(candidates, p, m, NOW, recent).get(0).item.id);
        p.genres.clear();
        assertEquals(Picks.choose(candidates, p, m, NOW).get(0).item.id,
                Picks.choose(candidates, p, m, NOW + 4L * 86400000, recent).get(0).item.id);
    }

    @Test
    public void cooldownDoesNotDriftDuringHourlyRefreshes() {
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        Picks.Moment m = new Picks.Moment(WEEKNIGHT, "", false);
        java.util.Map<String, Long> recent = Collections.singletonMap("a", NOW - 3600000);
        List<Picks.Item> candidates = Arrays.asList(movie("a", 90, "Romance"), movie("b", 90, "Documentary"));
        List<Picks.Pick> first = Picks.choose(candidates, p, m, NOW, recent, NOW);
        List<Picks.Pick> next = Picks.choose(candidates, p, m, NOW + 3600000, recent, NOW);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).item.id, next.get(i).item.id);
            assertEquals(first.get(i).score, next.get(i).score, 0.00001f);
        }
    }

    @Test
    public void inputOrderDoesNotChangeRecommendations() {
        List<Picks.Item> candidates = new java.util.ArrayList<>(Arrays.asList(movie("a", 90, "Romance"),
                movie("b", 90, "Romance"), movie("c", 90, "Documentary")));
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        Picks.Moment m = new Picks.Moment(WEEKNIGHT, "", false);
        List<Picks.Pick> first = Picks.choose(candidates, p, m, NOW);
        Collections.reverse(candidates);
        List<Picks.Pick> second = Picks.choose(candidates, p, m, NOW);
        for (int i = 0; i < first.size(); i++) assertEquals(first.get(i).item.id, second.get(i).item.id);
    }

    @Test
    public void futureLibraryDatesDoNotReceiveNewAdditionBonus() {
        Picks.Item base = movie("same", 90, "Documentary");
        Picks.Item future = new Picks.Item(base.id, base.title, base.type, base.genres, base.people,
                base.year, base.runtimeMin, base.childCount, base.rating, NOW + 86400000);
        Picks.Profile p = Picks.Profile.of(Collections.emptyList());
        Picks.Moment m = new Picks.Moment(WEEKNIGHT, "", false);
        assertEquals(Picks.score(base, p, m, NOW).score, Picks.score(future, p, m, NOW).score, 0.00001f);
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
