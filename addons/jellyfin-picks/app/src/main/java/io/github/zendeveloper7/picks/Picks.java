package io.github.zendeveloper7.picks;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The scoring, apart from Android and the network: which unwatched films and series to suggest now, from the
 * owner's watch history (genres, people, titles similar to recent ones), the time and day, and the weather.
 * Plain rules, nothing learned and nothing sent anywhere.
 */
final class Picks {

    /** How many picks are kept for the launcher's spotlight. */
    static final int COUNT = 5;

    /** A library item: a film, or a series nobody has started. */
    static final class Item {
        final String id, title, type;   // type "Movie" or "Series"
        final List<String> genres, people;
        final int year, runtimeMin, childCount;
        final float rating;             // community rating 0..10, 0 when unknown
        final long addedMs;             // date added, epoch ms, 0 when unknown

        Item(String id, String title, String type, List<String> genres, List<String> people,
             int year, int runtimeMin, int childCount, float rating, long addedMs) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.genres = genres;
            this.people = people;
            this.year = year;
            this.runtimeMin = runtimeMin;
            this.childCount = childCount;
            this.rating = rating;
            this.addedMs = addedMs;
        }

        boolean isMovie() {
            return "Movie".equals(type);
        }
    }

    /** What the owner watches: genre and people weights from the history (newest counting most), and similar titles. */
    static final class Profile {
        final Map<String, Float> genres = new HashMap<>();
        final Map<String, Float> people = new HashMap<>();
        /** Item id to the watched title it is similar to ("Because you watched ..."). */
        final Map<String, String> similarTo = new HashMap<>();

        /** history: newest first; each entry's genres and people (for an episode, its series'). */
        static Profile of(List<Item> history) {
            Profile p = new Profile();
            float w = 1f;
            for (Item h : history) {
                for (String g : h.genres) p.genres.merge(g, w, Float::sum);
                for (String person : h.people) p.people.merge(person, w, Float::sum);
                w *= 0.93f;   // older history counts less
            }
            normalise(p.genres);
            normalise(p.people);
            return p;
        }

        private static void normalise(Map<String, Float> m) {
            float max = 0f;
            for (float v : m.values()) max = Math.max(max, v);
            if (max <= 0f) return;
            for (Map.Entry<String, Float> e : m.entrySet()) e.setValue(e.getValue() / max);
        }
    }

    /** When and in what weather the picks are for. weather is Weather kind's name ("RAIN") or "" for none. */
    static final class Moment {
        final LocalDateTime now;
        final String weather;
        final boolean day;

        Moment(LocalDateTime now, String weather, boolean day) {
            this.now = now;
            this.weather = weather == null ? "" : weather;
            this.day = day;
        }

        String partOfDay() {
            int h = now.getHour();
            return h < 5 ? "night" : h < 12 ? "morning" : h < 17 ? "afternoon" : h < 22 ? "evening" : "night";
        }

        boolean weekendEvening() {
            DayOfWeek d = now.getDayOfWeek();
            int h = now.getHour();
            return (d == DayOfWeek.FRIDAY && h >= 18) || (d == DayOfWeek.SATURDAY && h >= 18)
                    || (d == DayOfWeek.SUNDAY && h >= 18 && h < 21);
        }

        boolean weeknight() {
            DayOfWeek d = now.getDayOfWeek();
            return d.getValue() <= 4 && now.getHour() >= 18 || d == DayOfWeek.SUNDAY && now.getHour() >= 21;
        }

        /** "Rainy Sunday evening", "Saturday night", ... for the spotlight's label. */
        String label() {
            String day = now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
            String adj = weatherAdjective();
            return (adj.isEmpty() ? day : adj + " " + day) + " " + partOfDay();
        }

        String weatherAdjective() {
            switch (weather) {
                case "CLEAR": return day ? "Sunny" : "Clear";
                case "PARTLY_CLOUDY":
                case "CLOUDY": return "Cloudy";
                case "FOG": return "Foggy";
                case "DRIZZLE":
                case "RAIN": return "Rainy";
                case "SNOW": return "Snowy";
                case "STORM": return "Stormy";
                default: return "";
            }
        }
    }

    /** A chosen item, its score and the one line that says why. */
    static final class Pick {
        final Item item;
        final float score;
        final String reason;

        Pick(Item item, float score, String reason) {
            this.item = item;
            this.score = score;
            this.reason = reason;
        }
    }

    /** Genres that suit the weather. */
    static Set<String> weatherGenres(String weather, boolean day) {
        switch (weather) {
            case "DRIZZLE":
            case "RAIN": return set("Drama", "Mystery", "Romance", "Crime");
            case "STORM": return set("Thriller", "Horror", "Mystery", "Crime");
            case "SNOW": return set("Family", "Fantasy", "Animation", "Romance");
            case "FOG": return set("Mystery", "Thriller", "Horror");
            case "PARTLY_CLOUDY":
            case "CLOUDY": return set("Drama", "Crime", "Mystery");
            case "CLEAR": return day ? set("Adventure", "Comedy", "Action") : set("Science Fiction", "Thriller", "Mystery");
            default: return Collections.emptySet();
        }
    }

    /** Genres that suit the time of day. */
    static Set<String> timeGenres(String partOfDay) {
        switch (partOfDay) {
            case "morning": return set("Comedy", "Animation", "Family", "Documentary");
            case "afternoon": return set("Adventure", "Comedy", "Action", "Fantasy");
            case "night": return set("Thriller", "Horror", "Crime", "Mystery");
            default: return set("Drama", "Action", "Crime", "Science Fiction");
        }
    }

    /** The best COUNT picks, at most one per title, highest score first. */
    static List<Pick> choose(List<Item> candidates, Profile profile, Moment moment, long nowMs) {
        return choose(candidates, profile, moment, nowMs, Collections.emptyMap());
    }

    /** Exposure penalties are fixed for each part of the day, so hourly refreshes stay stable. */
    static List<Pick> choose(List<Item> candidates, Profile profile, Moment moment, long nowMs,
                             Map<String, Long> recentlyShown) {
        return choose(candidates, profile, moment, nowMs, recentlyShown, nowMs);
    }

    static List<Pick> choose(List<Item> candidates, Profile profile, Moment moment, long nowMs,
                             Map<String, Long> recentlyShown, long exposureTimeMs) {
        List<Pick> remaining = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item item : candidates) {
            if (!seen.add(item.id)) continue;
            Pick p = score(item, profile, moment, nowMs);
            Long shown = recentlyShown.get(item.id);
            float penalty = shown == null ? 0f : Math.max(0f, 1f - Math.max(0L, exposureTimeMs - shown) / (3f * 86400000f));
            remaining.add(new Pick(item, p.score - penalty, p.reason));
        }
        // Greedy reranking: strong taste matches still win, but near ties favour a different genre mix.
        List<Pick> chosen = new ArrayList<>();
        while (!remaining.isEmpty() && chosen.size() < COUNT) {
            Pick winner = null;
            float best = -Float.MAX_VALUE;
            for (Pick p : remaining) {
                float overlap = 0f;
                for (Pick c : chosen) overlap = Math.max(overlap, genreOverlap(p.item, c.item));
                float adjusted = p.score - overlap;
                if (winner == null || adjusted > best || (adjusted == best && p.item.id.compareTo(winner.item.id) < 0)) {
                    winner = p;
                    best = adjusted;
                }
            }
            chosen.add(new Pick(winner.item, best, winner.reason));
            remaining.remove(winner);
        }
        return chosen;
    }

    private static float genreOverlap(Item a, Item b) {
        Set<String> union = new HashSet<>(a.genres);
        union.addAll(b.genres);
        if (union.isEmpty()) return 0f;
        Set<String> common = new HashSet<>(a.genres);
        common.retainAll(b.genres);
        return (float) common.size() / union.size();
    }

    /** One item's score and reason. Each rule adds to the score; the biggest contribution names the reason. */
    static Pick score(Item item, Profile profile, Moment moment, long nowMs) {
        float score = 0f;
        float best = 0f;
        String reason = "";

        // Taste: the genres and people of what was watched.
        float genre = 0f;
        String topGenre = "";
        float topGenreWeight = 0f;
        for (String g : item.genres) {
            float w = profile.genres.getOrDefault(g, 0f);
            genre += w;
            if (w > topGenreWeight) {
                topGenreWeight = w;
                topGenre = g;
            }
        }
        if (!item.genres.isEmpty()) genre = 3f * genre / (float) Math.sqrt(item.genres.size());
        score += genre;
        if (genre > best && !topGenre.isEmpty()) {
            best = genre * 0.6f;   // taste is the fallback reason: the others win when they are close
            reason = "More " + topGenre.toLowerCase(Locale.ROOT) + ", your favourite";
        }
        float people = 0f;
        String person = "";
        for (String p : item.people) {
            float w = profile.people.getOrDefault(p, 0f);
            if (w > 0.3f) {
                people += 0.5f * w;
                if (person.isEmpty()) person = p;
            }
        }
        people = Math.min(1.5f, people);
        score += people;
        if (people > best) {
            best = people;
            reason = "With " + person;
        }

        // Similar to something watched lately.
        String like = profile.similarTo.get(item.id);
        if (like != null) {
            score += 2f;
            if (2f > best) {
                best = 2f;
                reason = "Because you watched " + like;
            }
        }

        // The weather and the time of day.
        Set<String> wg = weatherGenres(moment.weather, moment.day);
        if (overlaps(item.genres, wg)) {
            score += 1.2f;
            if (1.2f > best) {
                best = 1.2f;
                reason = moment.weatherAdjective() + " " + moment.partOfDay() + " pick";
            }
        }
        if (overlaps(item.genres, timeGenres(moment.partOfDay()))) score += 0.6f;

        // The day: films for weekend evenings, episodes on weeknights.
        if (moment.weekendEvening() && item.isMovie() && item.runtimeMin >= 90) {
            score += 0.8f;
            if (0.8f > best) {
                best = 0.8f;
                reason = "Weekend movie night";
            }
        }
        if (moment.weeknight()) {
            if (!item.isMovie()) score += 0.6f;
            else if (item.runtimeMin > 140) score -= 0.8f;
        }

        // Well liked, and new in the library.
        if (item.rating > 0f) score += Math.max(-0.5f, Math.min(0.75f, (item.rating - 6.5f) / 3f));
        if (item.addedMs > 0 && nowMs >= item.addedMs && nowMs - item.addedMs < 14L * 24 * 3600 * 1000) {
            score += 0.5f;
            if (0.5f > best) {
                reason = "New in your library";
            }
        }

        // A little variety, the same all through one part of the day.
        score += 0.5f * jitter(item.id + moment.now.toLocalDate() + moment.partOfDay());
        if (reason.isEmpty()) reason = item.isMovie() ? "A film for tonight" : "A new series to start";
        return new Pick(item, score, reason);
    }

    /** 0..1 from a string, the same every time. */
    static float jitter(String s) {
        int h = s.hashCode() * 0x9E3779B1;
        return ((h >>> 8) & 0xFFFF) / 65535f;
    }

    private static boolean overlaps(List<String> genres, Set<String> wanted) {
        for (String g : genres) if (wanted.contains(g)) return true;
        return false;
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private Picks() {
    }
}
