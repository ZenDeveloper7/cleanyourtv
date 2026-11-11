package com.gathin.tvhome;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * Home's header block, top left: the greeting, the clock and the date lines (Shamsi, lunar), or in the quiet look
 * (home_style quiet) the weather as the big text with "greeting · time · date" on one small line under it.
 *
 * <p>The quiet look's big text, in order: the festival's greeting on a festival day, else the temperature and the
 * condition from a fresh weather reading, else the greeting.
 */
final class HomeHeader {

    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;

    /** Day parts the greeting names. */
    static final int MORNING = 0, FORENOON = 1, AFTERNOON = 2, EVENING = 3, NIGHT = 4;

    /** What the quiet look's big text shows. */
    static final int QUIET_FESTIVAL = 0, QUIET_WEATHER = 1, QUIET_GREETING = 2;

    private final Activity activity;
    private final Handler handler;
    private final float density;
    private final Festival festival;
    private final boolean festive;
    private final String greetingName;
    private final boolean shamsi, lunar;
    private final int lunarOffset;

    /** The whole block: hidden until the wall clock is valid. */
    private final View block;
    private final TextView greeting, shamsiDate, lunarDate;
    private final int greetingColor;

    /** The quiet look, or null for the classic header. */
    private final TextView quietBig, quietCondition, quietLead;

    private Weather.Now weather;
    /** The quiet big text's full size for what it shows now; fitBig shrinks it from there to fit its row. */
    private float bigSp;
    private final Runnable clockCheck = this::updateClockVisibility;

    /** The quiet look's views are in this layout (the Hebrew layout-iw copy has none). */
    static boolean quietAvailable(Activity a) {
        return a.findViewById(R.id.quietHeader) != null;
    }

    HomeHeader(Activity activity, Handler handler, Config cfg, Festival festival, boolean festive, boolean quiet) {
        this.activity = activity;
        this.handler = handler;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.festival = festival;
        this.festive = festive;
        this.greetingName = cfg.greetingName;
        this.shamsi = cfg.shamsiDate;
        this.lunar = cfg.lunarDate;
        this.lunarOffset = Lunar.offsetFor(cfg.lunarUtcOffset, Locale.getDefault());
        block = activity.findViewById(R.id.leftBlock);
        greeting = activity.findViewById(R.id.greeting);
        shamsiDate = activity.findViewById(R.id.shamsiDate);
        lunarDate = activity.findViewById(R.id.lunarDate);
        greetingColor = greeting.getCurrentTextColor();
        if (quiet) {
            quietBig = activity.findViewById(R.id.quietBig);
            quietCondition = activity.findViewById(R.id.quietCondition);
            quietLead = activity.findViewById(R.id.quietLead);
            // The row is as wide as the header leaves beside the buttons: fit the big text again when that changes.
            ((View) quietBig.getParent()).addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol) fitBig(); });
            setUpQuiet();
        } else {
            quietBig = quietCondition = quietLead = null;
            setUpClassic(cfg);
        }
    }

    /** The header's block, for the panels laid out against it. */
    View block() {
        return block;
    }

    // ---- set-up ---------------------------------------------------------------------------------------

    /** The quiet look: the classic greeting, clock row and extra dates give way to the quiet block. */
    private void setUpQuiet() {
        greeting.setVisibility(View.GONE);
        ((View) activity.findViewById(R.id.clock).getParent()).setVisibility(View.GONE);
        activity.findViewById(R.id.quietHeader).setVisibility(View.VISIBLE);
        // The top buttons sit level with the big text's middle, not on the block's bottom line.
        LinearLayout buttons = activity.findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.removeRule(RelativeLayout.ALIGN_BOTTOM);
        lp.topMargin = Math.round(18 * density);
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
    }

    private void setUpClassic(Config cfg) {
        Resources res = activity.getResources();
        boolean rtlMenuBar = res.getBoolean(R.bool.rtl_menu_bar);
        if (!cfg.showGreeting) greeting.setVisibility(View.GONE);
        // Keep the other header layouts as designed: their extra lines change the block's centre.
        if (!cfg.showGreeting && !cfg.dateBelowClock && !cfg.shamsiDate && !cfg.lunarDate
                && !cfg.topMenu && !rtlMenuBar) {
            centerCompactHeader();
        }
        if (cfg.headerWhite) {
            // The clock's own white for the date and the line before it, instead of their dimmed whites.
            ((TextView) activity.findViewById(R.id.date)).setTextColor(Color.WHITE);
            activity.findViewById(R.id.clockDivider).setBackgroundColor(Color.WHITE);
        }
        if (rtlMenuBar) {
            // Center Hebrew greetings over the numeric time, independent of a visible AM/PM label. The clock's row
            // is watched too: date_below_clock can move the whole row right under a wider date.
            View clock = activity.findViewById(R.id.clock);
            View clockRow = (View) clock.getParent();
            View.OnLayoutChangeListener centerGreeting = (v, l, t, right, bottom, ol, ot, or, ob) -> {
                ViewGroup.LayoutParams params = greeting.getLayoutParams();
                if (params.width != clock.getWidth()) {
                    params.width = clock.getWidth();
                    greeting.setLayoutParams(params);
                }
                greeting.setTranslationX(clockRow.getLeft() + clock.getLeft());
            };
            clock.addOnLayoutChangeListener(centerGreeting);
            clockRow.addOnLayoutChangeListener(centerGreeting);
        }
        if (lunar) lunarDate.setVisibility(View.VISIBLE);
        if (cfg.dateBelowClock) moveDateBelowClock(rtlMenuBar);
        if (cfg.dateBelowClock && cfg.iconOnlyButtons && !cfg.topMenu && !rtlMenuBar) {
            alignDateBelowIconsWithClock();
        }
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = activity.findViewById(R.id.date);
            date.setTextSize(11f);
            lunarDate.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
    }

    /**
     * date_below_clock: the date column moves to its own line under the clock, in the vertical LinearLayout that holds
     * the clock's row, and the line between them goes. In Hebrew (rtl_menu_bar) the date and the clock's row sit right.
     */
    private void moveDateBelowClock(boolean rtlMenuBar) {
        View column = (View) activity.findViewById(R.id.date).getParent();
        View clockRow = (View) activity.findViewById(R.id.clock).getParent();
        LinearLayout clockColumn = (LinearLayout) clockRow.getParent();
        ((ViewGroup) column.getParent()).removeView(column);
        activity.findViewById(R.id.clockDivider).setVisibility(View.GONE);
        // Its own width, not the column's: in Hebrew the column is only as wide as the clock.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(4 * density);
        if (rtlMenuBar) {
            // Date and clock flush right; the greeting stays left and follows the clock by translation.
            lp.gravity = Gravity.RIGHT;
            ((LinearLayout.LayoutParams) clockRow.getLayoutParams()).gravity = Gravity.RIGHT;
        }
        clockColumn.addView(column, clockColumn.indexOfChild(clockRow) + 1, lp);
        // The column now wraps its texts, so they must wrap too: a match_parent TextView does not ask for a new
        // layout when its text gets longer, and the column kept the width of the day before ("TUESDAY 27 OCTOB…").
        ViewGroup texts = (ViewGroup) column;
        for (int i = 0; i < texts.getChildCount(); i++) {
            View text = texts.getChildAt(i);
            LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) text.getLayoutParams();
            textLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            // Keep each line where its own gravity put it (right in Hebrew) now that it no longer fills the column.
            textLp.gravity = ((TextView) text).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
            text.setLayoutParams(textLp);
        }
    }

    /** Centre the one-row header when the greeting and extra date lines are absent (#2265). */
    private void centerCompactHeader() {
        LinearLayout clockRow = (LinearLayout) activity.findViewById(R.id.clock).getParent();
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        clockRow.setBaselineAligned(false);
        View[] columns = {activity.findViewById(R.id.clockDivider), (View) activity.findViewById(R.id.date).getParent()};
        for (View column : columns) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) column.getLayoutParams();
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.bottomMargin = 0;
            column.setLayoutParams(lp);
        }
        LinearLayout buttons = activity.findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
    }

    /** With the date below, keep the icons beside the clock rather than beside the last date line. */
    private void alignDateBelowIconsWithClock() {
        View clock = activity.findViewById(R.id.clock);
        View clockRow = (View) clock.getParent();
        LinearLayout buttons = activity.findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.removeRule(RelativeLayout.ALIGN_BOTTOM);
        lp.topMargin = 0;
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        // Either side can be laid out first; update after both without requesting another layout mid-pass.
        Runnable align = () -> buttons.setTranslationY(clockRow.getTop() + clock.getTop()
                + (clock.getHeight() - buttons.getHeight()) / 2f);
        clockRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
        buttons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
    }

    // ---- updates --------------------------------------------------------------------------------------

    /** The weather's latest fresh reading, or null without one. */
    void setWeather(Weather.Now now) {
        weather = now;
        update();
    }

    /** Shows the block once the wall clock is valid, checking again every second until then. */
    void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        block.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            update();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    void stop() {
        handler.removeCallbacks(clockCheck);
    }

    /** The greeting and the date lines for this minute. */
    void update() {
        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        String lang = locale.getLanguage();
        int part = dayPart(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), lang, locale.getCountry());
        String plain = activity.getString(greetingRes(part));
        String named = greetingName.isEmpty() ? plain
                : activity.getString(R.string.greeting_with_name, plain, greetingName);
        String festiveGreeting = festive ? festival.greetingText(activity.getResources()) : "";
        if (quietBig != null) {
            updateQuiet(festiveGreeting, named);
            return;
        }
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            String weatherText = weatherGreeting(part, plain, lang);
            greeting.setText(weatherText != null ? weatherText : named);
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today(activity.getResources()));
        if (lunar) lunarDate.setText(Lunar.today(activity.getResources(), lunarOffset));
    }

    private void updateQuiet(String festiveGreeting, String named) {
        int state = quietState(!festiveGreeting.isEmpty(), weather != null);
        switch (state) {
            case QUIET_FESTIVAL:
                quietBig.setText(festiveGreeting);
                quietBig.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
                quietCondition.setVisibility(View.GONE);
                quietLead.setVisibility(View.GONE);
                break;
            case QUIET_WEATHER:
                quietBig.setText(Math.round(weather.tempC) + "°");
                quietBig.setTextColor(Color.WHITE);
                quietCondition.setText(weather.condition.isEmpty()
                        ? Weather.adjective(weather.kind, weather.day) : weather.condition);
                quietCondition.setVisibility(View.VISIBLE);
                quietLead.setText(named + "  " + activity.getString(R.string.quiet_separator));
                quietLead.setVisibility(View.VISIBLE);
                break;
            default:
                quietBig.setText(named);
                quietBig.setTextColor(Color.WHITE);
                quietCondition.setVisibility(View.GONE);
                quietLead.setVisibility(View.GONE);
                break;
        }
        // A greeting is wider than a temperature: smaller, and a long one (a birthday's) shrinks to fit its row.
        bigSp = state == QUIET_WEATHER ? 88f : 60f;
        fitBig();
    }

    /**
     * The big text at bigSp, or for a greeting alone on its row smaller (down to 28sp) so it fits on one line. A
     * temperature is short and keeps its size: the condition beside it takes the rest of the row and ellipsizes.
     */
    private void fitBig() {
        if (quietBig == null || bigSp == 0f) return;
        int room = ((View) quietBig.getParent()).getWidth();
        if (quietCondition.getVisibility() == View.VISIBLE || room <= 0) {
            quietBig.setTextSize(bigSp);
            return;
        }
        float fullPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, bigSp,
                activity.getResources().getDisplayMetrics());
        quietBig.getPaint().setTextSize(fullPx);
        float wide = quietBig.getPaint().measureText(quietBig.getText().toString());
        float sp = wide <= room ? bigSp : Math.max(28f, bigSp * room / wide * 0.98f);
        quietBig.setTextSize(sp);
    }

    /** The quiet look's big text: the festival's greeting, else the weather, else the greeting. */
    static int quietState(boolean festiveGreeting, boolean freshWeather) {
        if (festiveGreeting) return QUIET_FESTIVAL;
        return freshWeather ? QUIET_WEATHER : QUIET_GREETING;
    }

    /**
     * The greeting's day part for this hour and locale (app language on Android 13+).
     * Indonesian: Pagi 00-10, Siang 10-15, Sore 15-18, Malam 18-24.
     * Spain (es-ES) and Catalan: "Buenas tardes" into the evening; "Buenas noches" from 21.
     * Latin American Spanish (es-MX, es-AR, …) keeps the default hours: evening strings are also
     * «Buenas noches», and there «Buenas noches» from ~17 matches local use better than Spain's 21.
     */
    static int dayPart(int hour, String lang, String country) {
        if ("in".equals(lang) || "id".equals(lang)) {
            if (hour < 10) return MORNING;
            if (hour < 15) return AFTERNOON;
            if (hour < 18) return EVENING;
            return NIGHT;
        }
        if (("es".equals(lang) && "ES".equals(country)) || "ca".equals(lang)) {
            if (hour < 6) return NIGHT;
            if (hour < 12) return MORNING;
            if (hour < 21) return AFTERNOON;
            return NIGHT;
        }
        if ("da".equals(lang)) {
            // Danish day parts: "God morgen" until 11, then "God formiddag" to noon.
            if (hour < 5) return NIGHT;
            if (hour < 11) return MORNING;
            if (hour < 12) return FORENOON;
            if (hour < 18) return AFTERNOON;
            if (hour < 22) return EVENING;
            return NIGHT;
        }
        if (hour < 5) return NIGHT;
        if (hour < 12) return MORNING;
        if (hour < 17) return AFTERNOON;
        if (hour < 22) return EVENING;
        return NIGHT;
    }

    private static int greetingRes(int part) {
        switch (part) {
            case MORNING: return R.string.good_morning;
            case FORENOON: return R.string.good_forenoon;
            case AFTERNOON: return R.string.good_afternoon;
            case EVENING: return R.string.good_evening;
            default: return R.string.good_night;
        }
    }

    /** "Rainy afternoon · 24°" in English, "<the usual greeting> · 24°" in other languages; null without a reading. */
    private String weatherGreeting(int part, String plain, String lang) {
        if (weather == null) return null;
        String temp = Math.round(weather.tempC) + "°";
        if (!"en".equals(lang)) return plain + " · " + temp;
        String word = part == MORNING ? "morning" : part == AFTERNOON ? "afternoon"
                : part == EVENING ? "evening" : "night";
        return Weather.adjective(weather.kind, weather.day) + " " + word + " · " + temp;
    }

    static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
