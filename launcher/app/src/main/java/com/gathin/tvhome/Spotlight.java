package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Continue watching's newest row and the picks (continue_spotlight, picks_package) above the dock, as a carousel:
 * the slides take turns every {@link #SLIDE_MS}, Left / Right on the button steps through them.
 *
 * <p>Two looks share the slides and the carousel. The full spotlight shows the item's artwork as a backdrop with a
 * big title and a Resume / Open button. The quiet line (home_style quiet) is one focusable line of text: an icon,
 * the title, the episode or the pick's reason, a short progress bar and the time left, with no artwork.
 */
final class Spotlight {

    /** What the spotlight needs from Home. */
    interface Host {
        /** Home is in front (between onResume and onPause). */
        boolean isResumed();

        /** Home's provider-reader session: a read started in an older one drops its result. */
        int readerSession();

        /** A festival is on today: its own look, no picks. */
        boolean festivalToday();

        /** The spotlight's button took focus: hide the tiles' highlight and the dock's title. */
        void onSpotlightFocused();

        /** Focus a dock tile: the first one, or the one last focused. */
        void focusDock(boolean first);

        /** Up from the quiet line: focus the top bar's first button; false when there is none. */
        boolean focusTopBar();

        /** Extra room kept between the dock and the spotlight's text (the dock's title, when it shows). */
        float titleRoomPx();

        /** The weather's rain and snow keep out of this area (null: no hole). */
        void setEffectsHole(Rect hole);
    }

    static final long SLIDE_MS = 20_000;

    /** One slide: Continue watching's newest row, or a pick. */
    private static final class Slide {
        final String key, kicker, title, meta, left, button;
        final float progress;   // below 0: no progress bar
        final Intent intent;
        final Bitmap art;
        final boolean pick;

        Slide(String key, String kicker, String title, String meta, String left, String button, float progress,
              Intent intent, Bitmap art, boolean pick) {
            this.key = key;
            this.kicker = kicker;
            this.title = title;
            this.meta = meta;
            this.left = left;
            this.button = button;
            this.progress = progress;
            this.intent = intent;
            this.art = art;
            this.pick = pick;
        }
    }

    private final Activity activity;
    private final Handler handler;
    private final View root;
    private final RecyclerView grid;
    private final Host host;
    private final float density;
    private final boolean animate;
    /** The quiet line instead of the full spotlight. */
    private final boolean line;

    // The full spotlight's backdrop (null in the quiet line).
    private final View backdrop;
    private final ImageView art;
    private final View scrim;
    /** The text block placed above the dock: the full spotlight's panel, or the quiet line. */
    private final View panel;
    /** The focusable button: Resume / Open, or the whole quiet line. */
    private final View button;
    private final TextView kicker, title, meta, left, buttonText;
    private final ImageView lineIcon;
    private final View track, progress;
    private final int trackWidthPx;
    private final LinearLayout dots;
    private final int dotPx, dotOnPx;

    private final Uri picksUri;
    private final ExecutorService picksLoader = Executors.newSingleThreadExecutor();
    private final android.database.ContentObserver picksObserver;
    private boolean picksObserving;

    private Slide continueSlide;
    private List<Slide> pickSlides = Collections.emptyList();
    private int slideIndex;
    /** -1 or 1 while a slide stepped to with Left / Right is shown (its text slides in from that side), else 0. */
    private int slideStep;
    /** The slide on screen and what its button opens; null while the spotlight is hidden. */
    private String shownKey;
    private Intent shownIntent;

    private final Runnable nextSlide = new Runnable() {
        @Override
        public void run() {
            if (!host.isResumed()) return;
            slideIndex++;
            showSlides();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    /** The views for the look asked for are in this layout (the Hebrew layout-iw copy has neither). */
    static boolean available(Activity a, boolean quiet) {
        return quiet ? a.findViewById(R.id.quietResume) != null
                : a.findViewById(R.id.spotlight) != null && a.findViewById(R.id.spotlightPanel) != null;
    }

    /** @param scrimShade the full spotlight's shade over its artwork; unused by the quiet line. */
    Spotlight(Activity activity, Handler handler, View root, RecyclerView grid, Uri picksUri, boolean quiet,
              boolean animate, Drawable scrimShade, Host host) {
        this.activity = activity;
        this.handler = handler;
        this.root = root;
        this.grid = grid;
        this.picksUri = picksUri;
        this.line = quiet;
        this.animate = animate;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
        if (quiet) {
            backdrop = null;
            art = null;
            scrim = null;
            panel = activity.findViewById(R.id.quietResume);
            button = panel;
            kicker = null;
            buttonText = null;
            lineIcon = activity.findViewById(R.id.quietResumeIcon);
            title = activity.findViewById(R.id.quietResumeTitle);
            meta = activity.findViewById(R.id.quietResumeMeta);
            left = activity.findViewById(R.id.quietResumeLeft);
            track = activity.findViewById(R.id.quietResumeTrack);
            progress = activity.findViewById(R.id.quietResumeProgress);
            dots = activity.findViewById(R.id.quietResumeDots);
            trackWidthPx = Math.round(72 * density);
            dotPx = Math.round(4 * density);
            dotOnPx = Math.round(12 * density);
        } else {
            backdrop = activity.findViewById(R.id.spotlight);
            art = activity.findViewById(R.id.spotlightArt);
            panel = activity.findViewById(R.id.spotlightPanel);
            progress = activity.findViewById(R.id.spotlightProgress);
            track = (View) progress.getParent();
            kicker = activity.findViewById(R.id.spotlightKicker);
            title = activity.findViewById(R.id.spotlightTitle);
            meta = activity.findViewById(R.id.spotlightMeta);
            left = activity.findViewById(R.id.spotlightLeft);
            buttonText = activity.findViewById(R.id.spotlightResume);
            button = buttonText;
            lineIcon = null;
            // The same scrim as the cinematic shelf: dark on the text's side and towards the dock.
            scrim = activity.findViewById(R.id.spotlightScrim);
            scrim.setBackground(scrimShade);
            art.forceHasOverlappingRendering(false);
            dots = activity.findViewById(R.id.spotlightDots);
            trackWidthPx = Math.round(220 * density);
            dotPx = Math.round(6 * density);
            dotOnPx = Math.round(22 * density);
        }
        picksObserver = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (host.isResumed()) refreshPicks();
            }
        };
        button.setOnClickListener(v -> {
            if (shownIntent == null) return;
            try {
                activity.startActivity(shownIntent);
            } catch (Exception e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, title.getText()),
                        Toast.LENGTH_SHORT).show();
            }
        });
        button.setOnFocusChangeListener((v, has) -> {
            if (has) host.onSpotlightFocused();
        });
        button.setOnKeyListener((v, code, event) -> {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (slideCount() < 2) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                    stepSlide((code == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl ? 1 : -1);
                }
                return true;
            }
            // The quiet line runs under the header, away from the buttons on the right: the focus finder finds
            // nothing straight above it, so Up is sent to the top bar here.
            if (line && code == KeyEvent.KEYCODE_DPAD_UP) {
                return event.getAction() != KeyEvent.ACTION_DOWN || host.focusTopBar();
            }
            if (code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) host.focusDock(false);
            return true;
        });
        // The panel sits above the dock, wherever the grid's padding puts it.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());
        // Below the dock the grid scrolls up: the panel goes with it and fades, so the dock never covers it.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                place();
            }
        });
    }

    // ---- lifecycle ----------------------------------------------------------------------------------

    void onResume() {
        if (picksUri != null) {
            if (!picksObserving) {
                try {
                    activity.getContentResolver().registerContentObserver(picksUri, false, picksObserver);
                    picksObserving = true;
                } catch (Exception ignored) {
                    // a picks app without notifications: read on each return to Home
                }
            }
            refreshPicks();
        }
        handler.removeCallbacks(nextSlide);
        handler.postDelayed(nextSlide, SLIDE_MS);
    }

    void onPause() {
        handler.removeCallbacks(nextSlide);
        if (picksObserving) {
            activity.getContentResolver().unregisterContentObserver(picksObserver);
            picksObserving = false;
        }
    }

    void onDestroy() {
        picksLoader.shutdownNow();
        handler.removeCallbacks(nextSlide);
    }

    // ---- focus from the dock ------------------------------------------------------------------------

    /** Up from the dock row: the spotlight's button takes focus when a slide is on screen. */
    boolean takeFocusFromDock() {
        if (shownIntent == null || panel.getVisibility() != View.VISIBLE) return false;
        button.requestFocus();
        return true;
    }

    // ---- slides -------------------------------------------------------------------------------------

    /** The app's name for the kicker line, read off the main thread with the rows. */
    static String appLabel(Context app, WatchNext.Row row) {
        if (row == null) return "";
        try {
            android.content.pm.PackageManager pm = app.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(row.pkg, 0)).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Continue watching's newest row (art may be null), or none for null. Any thread. */
    void setContinue(WatchNext.Row row, Bitmap rowArt, String appName) {
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed()) return;
            Intent intent = row == null ? null : WatchNext.intent(row);
            continueSlide = intent == null ? null : new Slide(
                    row.key() + "/" + Math.round(row.progress() * 50) + "/" + (rowArt != null),
                    appName.isEmpty() ? activity.getString(R.string.spotlight_up_next)
                            : activity.getString(R.string.spotlight_kicker, appName),
                    row.title, row.episode, left(row), activity.getString(R.string.spotlight_resume),
                    row.progress(), intent, rowArt, false);
            showSlides();
        });
    }

    /** The picks for the spotlight, read off the main thread. Not on festival days. */
    private void refreshPicks() {
        final Uri uri = picksUri;
        if (uri == null || host.festivalToday()) {
            pickSlides = Collections.emptyList();
            showSlides();
            return;
        }
        final Context app = activity.getApplicationContext();
        if (!host.isResumed()) return;
        final int session = host.readerSession();
        try {
            picksLoader.execute(() -> {
                if (session != host.readerSession()) return;
                List<Picks.Pick> picks = Picks.read(app, uri, 1280, 720);
                activity.runOnUiThread(() -> {
                    if (session != host.readerSession() || activity.isDestroyed()) return;
                    List<Slide> slides = new ArrayList<>();
                    for (Picks.Pick p : picks) {
                        slides.add(new Slide("pick/" + p.id + "/" + p.label + "/" + p.updated + "/" + (p.art != null),
                                p.label.isEmpty() ? activity.getString(R.string.spotlight_for_you_plain)
                                        : activity.getString(R.string.spotlight_for_you, p.label),
                                p.title, p.reason, p.subtitle, activity.getString(R.string.spotlight_open), -1f,
                                p.intent, p.art, true));
                    }
                    pickSlides = slides;
                    showSlides();
                });
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private int slideCount() {
        return (continueSlide != null ? 1 : 0) + pickSlides.size();
    }

    /** Left / Right: the previous or next slide, and a full SLIDE_MS before it moves on by itself. */
    private void stepSlide(int by) {
        if (slideCount() < 2) return;
        slideIndex += by;
        slideStep = by;
        showSlides();
        slideStep = 0;
        handler.removeCallbacks(nextSlide);
        if (host.isResumed()) handler.postDelayed(nextSlide, SLIDE_MS);
    }

    /** Shows the current slide, or hides the spotlight when there is none. */
    private void showSlides() {
        if (activity.isDestroyed()) return;
        List<Slide> slides = new ArrayList<>();
        if (continueSlide != null) slides.add(continueSlide);
        slides.addAll(pickSlides);
        showDots(slides.size(), slides.isEmpty() ? 0 : Math.floorMod(slideIndex, slides.size()));
        if (slides.isEmpty()) {
            boolean had = button.hasFocus();
            shownKey = null;
            shownIntent = null;
            if (backdrop != null) backdrop.setVisibility(View.GONE);
            panel.setVisibility(View.GONE);
            if (art != null) art.setImageDrawable(null);
            host.setEffectsHole(null);
            if (had) host.focusDock(true);
            return;
        }
        Slide slide = slides.get(Math.floorMod(slideIndex, slides.size()));
        if (slide.key.equals(shownKey)) return;
        boolean fresh = !visible();
        shownKey = slide.key;
        shownIntent = slide.intent;
        if (kicker != null) kicker.setText(slide.kicker);
        title.setText(slide.title);
        meta.setText(slide.meta);
        meta.setVisibility(slide.meta.isEmpty() ? View.GONE : View.VISIBLE);
        left.setText(slide.left);
        left.setVisibility(line && slide.left.isEmpty() ? View.GONE : View.VISIBLE);
        if (buttonText != null) buttonText.setText(slide.button);
        if (lineIcon != null) {
            lineIcon.setImageResource(slide.pick ? R.drawable.ic_sparkle : R.drawable.ic_play);
            // The line is its own button: say what OK does, with the title.
            button.setContentDescription(slide.button + " " + slide.title);
        }
        if (slide.progress >= 0f) {
            ViewGroup.LayoutParams lp = progress.getLayoutParams();
            lp.width = Math.round(trackWidthPx * Math.max(0.03f, slide.progress));
            progress.setLayoutParams(lp);
        }
        track.setVisibility(slide.progress > 0f ? View.VISIBLE : View.GONE);
        if (art != null) {
            art.setImageBitmap(slide.art);
            art.setVisibility(slide.art != null ? View.VISIBLE : View.GONE);
            scrim.setVisibility(slide.art != null ? View.VISIBLE : View.GONE);   // no art: the wallpaper as it is
            backdrop.setVisibility(View.VISIBLE);
        } else {
            panel.setVisibility(View.VISIBLE);
        }
        place();   // the text is its own view: show it where the scroll allows
        if (!animate) return;
        View fadeIn = backdrop != null ? backdrop : panel;
        if (fresh) {
            fadeIn.setAlpha(0f);
            fadeIn.animate().alpha(1f).setDuration(420).start();
            return;
        }
        // The next slide: the picture fades in over the last one's place, the text slides in (from the side
        // Left / Right came from, else from the end side, as a carousel moves on).
        if (art != null) {
            art.setAlpha(0f);
            art.animate().alpha(1f).setDuration(600).start();
        }
        int from = slideStep != 0 ? slideStep : 1;
        if (panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) from = -from;
        View[] texts = kicker != null ? new View[]{kicker, title, meta} : new View[]{title, meta, left};
        for (View t : texts) {
            t.animate().cancel();
            t.setTranslationX(from * (line ? 24 : 48) * density);
            t.setAlpha(0f);
            t.animate().translationX(0f).alpha(1f).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    /** A slide is on screen. */
    private boolean visible() {
        return backdrop != null ? backdrop.getVisibility() == View.VISIBLE : panel.getVisibility() == View.VISIBLE;
    }

    /** One dot per slide, the current one filled and wider; hidden for a single slide. */
    private void showDots(int count, int current) {
        if (dots == null) return;
        if (count < 2) {
            dots.setVisibility(View.GONE);
            return;
        }
        while (dots.getChildCount() > count) dots.removeViewAt(dots.getChildCount() - 1);
        while (dots.getChildCount() < count) {
            View dot = new View(activity);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dotPx / 2f);
            dot.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dotPx, dotPx);
            lp.setMarginEnd(Math.round((line ? 5 : 8) * density));
            dots.addView(dot, lp);
        }
        for (int i = 0; i < count; i++) {
            View dot = dots.getChildAt(i);
            boolean on = i == current;
            Drawable bg = dot.getBackground();
            ((GradientDrawable) bg).setColor(on ? 0xFFFFFFFF : 0x66FFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int w = on ? dotOnPx : dotPx;
            if (lp.width != w) {
                lp.width = w;
                dot.setLayoutParams(lp);
            }
        }
        dots.setVisibility(View.VISIBLE);
    }

    /** "42 min left", "1 h 12 min left", or "Up next" for a next-episode row; "" when the length is unknown. */
    private String left(WatchNext.Row row) {
        if (row.type == WatchNext.TYPE_NEXT) return activity.getString(R.string.spotlight_up_next);
        if (row.duration > 0 && row.position > 0) {
            long min = Math.max(1, (row.duration - row.position + 59_999) / 60_000);
            return min >= 60 ? activity.getString(R.string.spotlight_left_hours, (int) (min / 60), (int) (min % 60))
                    : activity.getString(R.string.spotlight_left_min, (int) min);
        }
        return "";
    }

    // ---- placement ----------------------------------------------------------------------------------

    /** Bottom of the text 32dp (the quiet line: 24dp) above the dock's top, plus the dock title's room. */
    private void place() {
        if (grid.getHeight() == 0) return;
        Rect r = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(grid, r);
        float dockTop = r.top + grid.getPaddingTop();
        float gap = (line ? 24 : 32) * density + host.titleRoomPx();
        int scrolled = grid.computeVerticalScrollOffset();
        float y = Math.max(0f, dockTop - gap - panel.getHeight()) - scrolled;
        if (panel.getY() != y) panel.setY(y);
        float alpha = Math.max(0f, 1f - scrolled / (120 * density));
        panel.setAlpha(alpha);
        boolean shown = shownKey != null;
        panel.setVisibility(!shown ? View.GONE : alpha > 0f ? View.VISIBLE : View.INVISIBLE);
        // The weather's rain and snow leave the text's area (with a margin) alone, so nothing falls through the letters.
        if (shown && alpha > 0f) {
            int m = Math.round(24 * density);
            host.setEffectsHole(new Rect(Math.round(panel.getX()) - m, Math.round(y) - m,
                    Math.round(panel.getX() + panel.getWidth()) + m, Math.round(y + panel.getHeight()) + m));
        } else {
            host.setEffectsHole(null);
        }
    }
}
