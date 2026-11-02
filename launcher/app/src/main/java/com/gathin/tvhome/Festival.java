package com.gathin.tvhome;

import android.content.res.Resources;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * A festive skin set by a theme's config.json: {@code festival} (or {@code seasonal_skin}), {@code festival_from},
 * {@code festival_until}, {@code greeting} and {@code accent_color}. While it is active the pills get festive icons (a diya for the
 * network, a bell for Inputs, a marigold for Settings; for {@code halloween} a jack-o'-lantern, a bat and a
 * ghost), the greeting is replaced and the focus glow takes the accent colour. {@code birthday} keeps the normal
 * icons and drops hearts instead of petals. It switches itself on at {@code festival_from} and off after
 * {@code festival_until}, so a theme can be pushed days ahead and nobody has to remember to undo it.
 */
final class Festival {

    static final Festival NONE = new Festival("", null, null, "", 0);

    /** Warm diya gold, the accent when a theme names a festival but no colour. */
    static final int DEFAULT_ACCENT = 0xFFFFB23C;

    /** The festival with its own skin (jack-o'-lantern, bat, ghost, bats on the first Home) instead of the diya's. */
    static final String HALLOWEEN = "halloween";

    /** Pumpkin orange, Halloween's accent when the theme names no colour. */
    static final int PUMPKIN = 0xFFFF7A1A;

    /** The festival with the normal pill icons and a shower of hearts instead of marigold petals. */
    static final String BIRTHDAY = "birthday";

    /** Rose pink, the birthday's accent when the theme names no colour. */
    static final int ROSE = 0xFFFF5C8A;

    /** Lower-case festival name, or "" for none. */
    final String name;
    /** First day the skin shows, inclusive; null for no start date. */
    final LocalDate from;
    /** Last day the skin shows, inclusive; null for no end date. */
    final LocalDate until;
    /** Text that replaces the time-of-day greeting; "" uses the festival's own default. */
    final String greeting;
    /** Focus glow colour as 0xAARRGGBB, or 0 for {@link #DEFAULT_ACCENT}. */
    final int accent;

    private Festival(String name, LocalDate from, LocalDate until, String greeting, int accent) {
        this.name = name;
        this.from = from;
        this.until = until;
        this.greeting = greeting;
        this.accent = accent;
    }

    /**
     * Builds the skin from raw config values. Anything malformed turns the skin off rather than leaving a
     * festival on the screen forever: an unreadable start or end date or an empty name gives {@link #NONE}.
     */
    static Festival parse(String name, String until, String greeting, String accent) {
        return parse(name, "", until, greeting, accent);
    }

    static Festival parse(String name, String from, String until, String greeting, String accent) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.length() > 40) return NONE;
        LocalDate start, end;
        try {
            start = date(from);
            end = date(until);
        } catch (DateTimeParseException e) {
            return NONE;
        }
        String g = greeting == null ? "" : Cards.cleanLabel(greeting, "");
        return new Festival(n, start, end, g, parseColor(accent));
    }

    /** A YYYY-MM-DD date, or null when blank. */
    private static LocalDate date(String s) {
        return s == null || s.trim().isEmpty() ? null : LocalDate.parse(s.trim());
    }

    /** Whether the skin shows today. */
    boolean active(LocalDate today) {
        return !name.isEmpty() && (from == null || !today.isBefore(from)) && (until == null || !today.isAfter(until));
    }

    /** The greeting to show while active, or "" to keep the normal time-of-day greeting. */
    String greetingText(Resources res) {
        if (!greeting.isEmpty()) return greeting;
        switch (name) {
            case "dussehra":
                return res.getString(R.string.festival_dussehra_greeting);
            case "diwali":
                return res.getString(R.string.festival_diwali_greeting);
            case HALLOWEEN:
                return res.getString(R.string.festival_halloween_greeting);
            default:
                return "";
        }
    }

    int accentColor() {
        if (accent != 0) return accent;
        if (isBirthday()) return ROSE;
        return isHalloween() ? PUMPKIN : DEFAULT_ACCENT;
    }

    /** Whether this is the Halloween skin rather than the diya, bell and marigold. */
    boolean isHalloween() {
        return HALLOWEEN.equals(name);
    }

    /** Whether this is the birthday skin: normal icons, hearts instead of petals. */
    boolean isBirthday() {
        return BIRTHDAY.equals(name);
    }

    /** {@code #RRGGBB} to an opaque colour, anything else to 0. */
    static int parseColor(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (!t.matches("#[0-9A-Fa-f]{6}")) return 0;
        return 0xFF000000 | Integer.parseInt(t.substring(1), 16);
    }
}
