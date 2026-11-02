package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the launcher's thin clock and date. With screensaver_loops it plays one of the theme's videos, picked
 * at random each time, behind the clock, on black (ScreensaverVideo.setUp); otherwise the clock sits over a slow
 * ambient glow (ClockScene).
 */
public class ClockDream extends DreamService {

    private ClockScene scene;
    /** This screensaver's video (screensaver_loops); null leaves the screensaver as it was. */
    private ScreensaverVideo video;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        Config cfg = Config.load(this);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);
        scene = new ClockScene(getWindow().getDecorView());
        scene.useHomeWallpaper(this);
        video = ScreensaverVideo.setUp(this, getWindow().getDecorView(), cfg);
        if (video != null) setScreenBright(true);   // a video under the clock should be seen at full brightness
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
        if (video != null) video.play();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        if (video != null) video.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        if (video != null) video.stop();
        super.onDetachedFromWindow();
    }
}
