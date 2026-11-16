package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.ViewGroup;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The theatre Home (home_style theatre): what to watch is the whole screen, and the apps wait in an arc under it.
 *
 * <p>The titles are Continue watching's rows (watch_next_packages) and then the picks (picks_package), the same
 * two sources as the spotlight. This class reads them off the main thread, makes each title's pictures once, and
 * hands the list to {@link TheatreView}, which draws it and takes the keys. Home keeps its duties (the top bar,
 * the inputs, the key services, the wallpaper) and gives this what {@link Host} names.
 */
final class TheatreHome implements TheatreView.Listener {

    /** What the theatre needs from Home. */
    interface Host {
        /** Home is in front (between onResume and onPause). */
        boolean isResumed();

        /** Home's provider-reader session: a read started in an older one drops its result. */
        int readerSession();

        /** The dock's apps, in order. */
        int appCount();

        String appLabel(int index);

        Drawable appArt(int index);

        int appColor(int index);

        void openApp(int index);

        /** Focus the top bar's first button; false when there is none. */
        boolean focusTopBar();
    }

    /** Continue watching rows shown before the picks, and titles in all. */
    private static final int MAX_CONTINUE = 4, MAX_TITLES = 8;
    /** Pictures: the screen's (picks are decoded at this size), the wall's poster, and the glow's few pixels. */
    private static final int ART_W = 960, ART_H = 540, POSTER_W = 192, POSTER_H = 280, GLOW_W = 32, GLOW_H = 18;

    private final Activity activity;
    private final Handler handler;
    private final Host host;
    private final TheatreView view;
    private final Set<String> watchPackages;
    private final Uri picksUri;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ContentObserver picksObserver;
    private boolean picksObserving, loading;
    /** The keys of the titles on screen, to leave the view alone when a read brings nothing new. */
    private List<String> shownKeys = new ArrayList<>();

    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            showClock();
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L);
        }
    };

    /** Adds the theatre to root, under the view at {@code below} (the top bar) so that stays on top of it. */
    TheatreHome(Activity activity, ViewGroup root, int below, Handler handler, Set<String> watchPackages, Uri picksUri,
                boolean animate, Host host) {
        this.activity = activity;
        this.handler = handler;
        this.host = host;
        this.watchPackages = watchPackages;
        this.picksUri = picksUri;
        view = new TheatreView(activity, animate, this);
        root.addView(view, below < 0 ? root.getChildCount() : below,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        picksObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (host.isResumed()) refresh();
            }
        };
    }

    // ---- lifecycle ----------------------------------------------------------------------------------

    void onResume() {
        if (picksUri != null && !picksObserving) {
            try {
                activity.getContentResolver().registerContentObserver(picksUri, false, picksObserver);
                picksObserving = true;
            } catch (Exception ignored) {
                // a picks app without notifications: read on each return to Home
            }
        }
        view.reset();
        focus();
        handler.removeCallbacks(minute);
        minute.run();
        refresh();
    }

    void onPause() {
        handler.removeCallbacks(minute);
        if (picksObserving) {
            activity.getContentResolver().unregisterContentObserver(picksObserver);
            picksObserving = false;
        }
    }

    void onDestroy() {
        loader.shutdownNow();
        handler.removeCallbacks(minute);
    }

    /** Gives the theatre the remote. */
    void focus() {
        view.requestFocus();
    }

    /** The dock's apps changed. */
    void onAppsChanged() {
        view.onAppsChanged();
    }

    /** The weather add-on's reading, or null for none: "23° Clear". */
    void setWeather(Weather.Now now) {
        if (now == null) {
            view.setWeather("");
            return;
        }
        String condition = now.condition.isEmpty() ? Weather.adjective(now.kind, now.day) : now.condition;
        view.setWeather(Math.round(now.tempC) + "° " + condition);
    }

    private void showClock() {
        view.setClock(DateFormat.getTimeFormat(activity).format(new Date()));
    }

    // ---- titles -------------------------------------------------------------------------------------

    /**
     * Reads the titles off the main thread: first with the pictures already cached, so Home has something at once,
     * then again when a missing backdrop has arrived.
     */
    private void refresh() {
        if (loading || activity.isDestroyed() || !host.isResumed()) return;
        loading = true;
        final Context app = activity.getApplicationContext();
        final int session = host.readerSession();
        try {
            loader.execute(() -> {
                try {
                    if (session != host.readerSession()) return;
                    List<WatchNext.Row> rows = watchPackages.isEmpty() ? new ArrayList<>()
                            : WatchNext.read(app, watchPackages, MAX_CONTINUE);
                    List<Picks.Pick> picks = picksUri == null ? new ArrayList<>()
                            : Picks.read(app, picksUri, ART_W, ART_H);
                    show(session, titles(app, rows, picks));
                    boolean arrived = false;
                    for (WatchNext.Row row : rows) arrived |= WatchNext.fetchBackdrop(app, row);
                    if (arrived) show(session, titles(app, rows, picks));
                } finally {
                    activity.runOnUiThread(() -> loading = false);
                }
            });
        } catch (RejectedExecutionException e) {
            loading = false;
        }
    }

    private void show(int session, List<TheatreView.Title> titles) {
        activity.runOnUiThread(() -> {
            if (session != host.readerSession() || activity.isDestroyed()) return;
            List<String> keys = new ArrayList<>();
            for (TheatreView.Title t : titles) keys.add(t.key);
            if (keys.equals(shownKeys)) return;
            shownKeys = keys;
            view.setTitles(titles);
        });
    }

    /** Continue watching first, then the picks, each with its pictures made. Off the main thread. */
    private static List<TheatreView.Title> titles(Context app, List<WatchNext.Row> rows, List<Picks.Pick> picks) {
        List<TheatreView.Title> out = new ArrayList<>();
        for (WatchNext.Row row : rows) {
            if (out.size() == MAX_TITLES) break;
            if (WatchNext.intent(row) == null) continue;
            Bitmap art = WatchNext.backdrop(app, row);
            String name = Spotlight.appLabel(app, row);
            out.add(title(row.key() + "/" + Math.round(row.progress() * 50) + "/" + (art != null),
                    name.isEmpty() ? app.getString(R.string.spotlight_up_next) : app.getString(R.string.spotlight_kicker, name),
                    row.title, join(row.episode, Spotlight.left(app, row)), app.getString(R.string.spotlight_resume),
                    row.progress(), WatchNext.intent(row), art));
        }
        for (Picks.Pick pick : picks) {
            if (out.size() == MAX_TITLES) break;
            if (pick.intent == null) continue;
            out.add(title("pick/" + pick.id + "/" + pick.updated + "/" + (pick.art != null),
                    pick.reason.isEmpty() ? app.getString(R.string.spotlight_for_you_plain) : pick.reason,
                    pick.title, pick.subtitle, app.getString(R.string.spotlight_open), 0f, pick.intent, pick.art));
        }
        return out;
    }

    private static TheatreView.Title title(String key, String kicker, String name, String meta, String button,
                                           float progress, android.content.Intent intent, Bitmap art) {
        Bitmap screen = art != null ? art : blank();
        return new TheatreView.Title(key, kicker, name, meta, button, progress, intent, screen, poster(screen),
                Bitmap.createScaledBitmap(screen, GLOW_W, GLOW_H, true));
    }

    private static String join(String first, String second) {
        if (first.isEmpty()) return second;
        return second.isEmpty() ? first : first + " · " + second;
    }

    /** A title without a picture: the room's own dark, so its name and the wall carry it. */
    private static Bitmap blank() {
        Bitmap b = Bitmap.createBitmap(GLOW_W, GLOW_H, Bitmap.Config.RGB_565);
        b.eraseColor(TheatreView.room());
        return b;
    }

    /** The centre of the picture as a portrait with rounded corners, made once so the wall only copies pixels. */
    private static Bitmap poster(Bitmap art) {
        Bitmap out = Bitmap.createBitmap(POSTER_W, POSTER_H, Bitmap.Config.ARGB_8888);
        float scale = Math.max(POSTER_W / (float) art.getWidth(), POSTER_H / (float) art.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((POSTER_W - art.getWidth() * scale) / 2f, (POSTER_H - art.getHeight() * scale) / 2f);
        BitmapShader shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(shader);
        new Canvas(out).drawRoundRect(new RectF(0, 0, POSTER_W, POSTER_H), 12f, 12f, p);
        return out;
    }

    // ---- TheatreView.Listener -----------------------------------------------------------------------

    @Override
    public int appCount() {
        return host.appCount();
    }

    @Override
    public String appLabel(int index) {
        return host.appLabel(index);
    }

    @Override
    public Drawable appArt(int index) {
        return host.appArt(index);
    }

    @Override
    public int appColor(int index) {
        return host.appColor(index);
    }

    @Override
    public void onOpenApp(int index) {
        host.openApp(index);
    }

    @Override
    public void onOpenTitle(TheatreView.Title title) {
        try {
            activity.startActivity(title.intent);
        } catch (Exception e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, title.name), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onLeaveUp() {
        host.focusTopBar();
    }
}
