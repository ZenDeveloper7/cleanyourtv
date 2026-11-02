package com.gathin.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The theme's screensaver videos (screensaver_loops, files in screensaver/*.mp4). Each time the screensaver starts,
 * one is picked at random, never the one played last time when there is a choice, and it loops muted until the
 * screensaver ends. A TextureView, not a SurfaceView, so it can fade in.
 */
final class ScreensaverVideo implements TextureView.SurfaceTextureListener {

    private static final long FADE_MS = 1_000;
    private static final String PREFS = "screensaver", KEY_LAST = "last_video";

    private final TextureView view;
    private MediaPlayer player;
    private Surface surface;
    /** This session's video (setUp). */
    private File file;

    /**
     * For a dream_clock layout (ClockDream, and Home's idle clock ClockActivity) with screensaver_loops: the old
     * clock-on-glow look is removed (plain black behind the clock), and this session's video is picked, with a soft
     * shadow on the clock so it stays readable. Null when screensaver_loops is off or there is no video.
     */
    static ScreensaverVideo setUp(Context ctx, View root, Config cfg) {
        if (!cfg.screensaverLoops) return null;
        View ambient = root.findViewById(R.id.dreamAmbient);
        if (ambient != null) ambient.setVisibility(View.GONE);
        TextureView surfaceView = root.findViewById(R.id.dreamVideo);
        if (surfaceView == null) return null;   // a layout without it (layout-iw): the clock alone
        File chosen = pick(ctx, list(Config.dir(ctx)), new Random());
        if (chosen == null) return null;
        float d = ctx.getResources().getDisplayMetrics().density;
        View content = root.findViewById(R.id.dreamContent);
        shadow(content, 10 * d, 2 * d);
        // The shadow reaches past each text's edges: let it, or the clock sits in grey boxes on a bright video.
        for (View v = content; v instanceof ViewGroup; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            ((ViewGroup) v).setClipChildren(false);
            ((ViewGroup) v).setClipToPadding(false);
            if (v == root) break;
        }
        ScreensaverVideo video = new ScreensaverVideo(surfaceView);
        video.file = chosen;
        return video;
    }

    /** Plays this session's video (setUp) on a loop. */
    void play() {
        if (file != null) play(file);
    }

    /** Gives every text in the clock block the same soft shadow. */
    private static void shadow(View v, float radius, float dy) {
        if (v instanceof TextView) {
            ((TextView) v).setShadowLayer(radius, 0f, dy, 0xB3000000);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            for (int i = 0; i < g.getChildCount(); i++) shadow(g.getChildAt(i), radius, dy);
        }
    }

    ScreensaverVideo(TextureView view) {
        this.view = view;
        view.setSurfaceTextureListener(this);
    }

    /** The .mp4 files in the theme's screensaver folder, by name. */
    static List<File> list(File themeDir) {
        List<File> out = new ArrayList<>();
        File[] files = themeDir == null ? null
                : new File(themeDir, ThemeFiles.SCREENSAVER).listFiles((d, n) -> ThemeFiles.screensaverVideo(n));
        if (files != null) {
            Arrays.sort(files);
            out.addAll(Arrays.asList(files));
        }
        return out;
    }

    /**
     * A random video, not the one picked last time when there are two or more; null for none. The pick is
     * remembered, so the next screensaver starts with a different one.
     */
    static File pick(Context ctx, List<File> videos, Random random) {
        if (videos.isEmpty()) return null;
        String last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, "");
        File chosen = pickOther(videos, last, random);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, chosen.getName()).apply();
        return chosen;
    }

    /** The choice itself, apart from storage: any of the videos but the last one's name, unless it is the only one. */
    static File pickOther(List<File> videos, String lastName, Random random) {
        List<File> choices = new ArrayList<>();
        for (File f : videos) if (!f.getName().equals(lastName)) choices.add(f);
        if (choices.isEmpty()) choices = videos;
        return choices.get(random.nextInt(choices.size()));
    }

    /** Plays this video on a loop, fading in, until stop(). A file that will not play leaves the view hidden. */
    void play(File file) {
        release();
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        player = new MediaPlayer();
        try {
            player.setDataSource(file.getPath());
            player.setVolume(0f, 0f);
            player.setLooping(true);
            player.setOnPreparedListener(mp -> {
                if (mp != player) return;
                mp.start();
                view.animate().alpha(1f).setDuration(FADE_MS).start();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                stop();
                return true;
            });
            if (surface != null) player.setSurface(surface);
            player.prepareAsync();
        } catch (Exception e) {
            stop();
        }
    }

    /** Stops the video and hides the view: the screensaver is ending. */
    void stop() {
        view.animate().cancel();
        release();
        view.setVisibility(View.GONE);
    }

    private void release() {
        if (player == null) return;
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (player != null) player.setSurface(surface);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (player != null) player.setSurface(null);
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
