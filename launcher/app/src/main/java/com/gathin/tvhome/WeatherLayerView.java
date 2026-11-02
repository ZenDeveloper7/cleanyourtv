package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * A faint weather layer for Home, drawn over the wallpaper and under the app tiles. It shows a colour tint for
 * the current weather, plus slow rain, snow, fog, light rays or stars. Everything is low alpha on purpose, so the
 * tiles and text stay the focus. It is drawn in code only: no images, no blur, no network.
 *
 * <p>Call {@link #setWeather} with the current reading, {@link #start} while Home is visible and {@link #stop}
 * when it is not. Partly cloudy and cloudy skies are static: they draw once and never loop.
 */
public final class WeatherLayerView extends View {

    private static final long SEED = 0x5EA7E4L;
    private static final long FRAME_MS = 33L;                 // about 30 fps: skip any frame sooner than this
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final long LIGHTNING_PERIOD_MS = 12000L;
    private static final long LIGHTNING_FLASH_MS = 300L;
    private static final int RAIN_FAR = 80;
    private static final int RAIN_NEAR = 60;
    private static final int DRIZZLE_COUNT = 70;
    private static final int SNOW_COUNT = 110;
    private static final int STAR_COUNT = 120;
    private static final int RAY_COUNT = 3;
    private static final int FOG_BANDS = 3;
    private static final int RAIN_RGB = 0xB8D4F0;

    private final float density;
    private final long originMs;
    private final Runnable frame;

    private Weather.Kind kind;
    private boolean day = true;
    private boolean running;
    private boolean attached;
    private boolean loopPosted;   // true while a frame callback is queued with the Choreographer
    private long lastDrawMs;
    private int width;
    private int height;

    // Rain and drizzle streaks: the first rainFarCount use the thin paint, the rest the thick one.
    private int rainCount;
    private int rainFarCount;
    private float[] rx, ry0, rLen, rSpeed, rWrap;
    private int[] rAlpha;
    private Paint rainThin;
    private Paint rainThick;

    // Snow.
    private float[] sx, sy0, sr, sWrap, sSpeed, sAmp, sFreq, sPhase;
    private int[] sAlpha;
    private Paint snowPaint;

    // Fog bands.
    private float[] fogTop, fogSpeed;
    private float fogPeriod;
    private float fogBandH;
    private Paint fogPaint;

    // Clear day rays.
    private Path[] rayPath;
    private Paint[] rayPaint;

    // Clear night stars.
    private float[] tx, ty, tr, tPeriod, tPhase;
    private int[] tAlpha;
    private Paint starPaint;

    public WeatherLayerView(Context context) {
        this(context, null);
    }

    public WeatherLayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        originMs = SystemClock.uptimeMillis();
        frame = new Runnable() {
            @Override
            public void run() {
                onFrame();
            }
        };
    }

    /** Sets the weather to draw. A null kind draws nothing. Rebuilds the particles when the size is known. */
    void setWeather(Weather.Kind newKind, boolean isDay) {
        kind = newKind;
        day = isDay;
        if (width > 0 && height > 0) {
            build();
        }
        invalidate();
        if (running && attached && isAnimated() && !loopPosted) {
            postLoop();
        }
    }

    /** Starts the animation loop. Does nothing extra for static skies. */
    void start() {
        if (running) {
            return;
        }
        running = true;
        lastDrawMs = 0L;
        if (attached && isAnimated() && !loopPosted) {
            postLoop();
        }
    }

    /** Ends the loop and drops the frame already queued, so the next start() queues a fresh one. */
    void stop() {
        running = false;
        removeCallbacks(frame);
        loopPosted = false;   // the queued frame is gone: without this, start() after a pause never queues a new one
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (running && isAnimated() && !loopPosted) {
            postLoop();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(frame);
        loopPosted = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        width = w;
        height = h;
        build();
    }

    private void onFrame() {
        loopPosted = false;
        if (!running || !attached || !isAnimated()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastDrawMs >= FRAME_MS) {
            lastDrawMs = now;
            invalidate();
        }
        postLoop();
    }

    private void postLoop() {
        loopPosted = true;
        postOnAnimation(frame);
    }

    private boolean isAnimated() {
        return kind != null && kind != Weather.Kind.PARTLY_CLOUDY && kind != Weather.Kind.CLOUDY;
    }

    private float dp(float v) {
        return v * density;
    }

    private static double wrap(double v, double period) {
        double r = v % period;
        return r < 0 ? r + period : r;
    }

    private static int tintFor(Weather.Kind k, boolean isDay) {
        switch (k) {
            case CLEAR: return isDay ? 0x1AFFC266 : 0x26102050;
            case PARTLY_CLOUDY: return isDay ? 0x10FFE0A0 : 0x1A102040;
            case CLOUDY: return 0x22404A58;
            case FOG: return 0x2E8A9099;
            case DRIZZLE:
            case RAIN: return 0x26304050;
            case SNOW: return 0x1AE6EEF8;
            default: return 0x33202838;   // STORM
        }
    }

    /** Builds every size- and kind-dependent array and Paint. Uses a fixed seed, so the layout is always the same. */
    private void build() {
        if (kind == null || width <= 0 || height <= 0) {
            return;
        }
        Random rnd = new Random(SEED);
        switch (kind) {
            case RAIN:
            case STORM:
                buildRain(rnd, RAIN_FAR, RAIN_NEAR, false);
                break;
            case DRIZZLE:
                buildRain(rnd, DRIZZLE_COUNT, 0, true);
                break;
            case SNOW:
                buildSnow(rnd);
                break;
            case FOG:
                buildFog();
                break;
            case CLEAR:
                if (day) {
                    buildRays();
                } else {
                    buildStars(rnd);
                }
                break;
            default:
                break;
        }
    }

    private void buildRain(Random rnd, int farCount, int nearCount, boolean drizzle) {
        rainCount = farCount + nearCount;
        rainFarCount = farCount;
        rx = new float[rainCount];
        ry0 = new float[rainCount];
        rLen = new float[rainCount];
        rSpeed = new float[rainCount];
        rWrap = new float[rainCount];
        rAlpha = new int[rainCount];
        for (int i = 0; i < rainCount; i++) {
            boolean near = i >= farCount;
            float lenDp;
            float speedDp;
            int aMin;
            int aMax;
            if (drizzle) {
                lenDp = 8f + 4f * rnd.nextFloat();
                speedDp = 180f + 60f * rnd.nextFloat();
                aMin = 25;
                aMax = 45;
            } else if (near) {
                lenDp = 22f + 8f * rnd.nextFloat();
                speedDp = 420f + 100f * rnd.nextFloat();
                aMin = 56;
                aMax = 70;
            } else {
                lenDp = 14f + 4f * rnd.nextFloat();
                speedDp = 260f + 80f * rnd.nextFloat();
                aMin = 40;
                aMax = 55;
            }
            float len = dp(lenDp);
            rLen[i] = len;
            rSpeed[i] = dp(speedDp);
            rWrap[i] = height + len;
            ry0[i] = rnd.nextFloat() * rWrap[i];
            rx[i] = -len * 0.2f + rnd.nextFloat() * (width + len * 0.2f);
            rAlpha[i] = aMin + rnd.nextInt(aMax - aMin + 1);
        }
        rainThin = streakPaint(dp(drizzle ? 0.7f : 0.8f));
        rainThick = streakPaint(dp(1.2f));
    }

    private Paint streakPaint(float stroke) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(0xFF000000 | RAIN_RGB);
        return p;
    }

    private void buildSnow(Random rnd) {
        sx = new float[SNOW_COUNT];
        sy0 = new float[SNOW_COUNT];
        sr = new float[SNOW_COUNT];
        sWrap = new float[SNOW_COUNT];
        sSpeed = new float[SNOW_COUNT];
        sAmp = new float[SNOW_COUNT];
        sFreq = new float[SNOW_COUNT];
        sPhase = new float[SNOW_COUNT];
        sAlpha = new int[SNOW_COUNT];
        for (int i = 0; i < SNOW_COUNT; i++) {
            float r = dp(1.5f + 2.5f * rnd.nextFloat());
            sr[i] = r;
            sWrap[i] = height + 2f * r;
            sy0[i] = rnd.nextFloat() * sWrap[i];
            sx[i] = rnd.nextFloat() * width;
            sSpeed[i] = dp(22f + 18f * rnd.nextFloat());
            sAmp[i] = dp(6f + 10f * rnd.nextFloat());
            sFreq[i] = 0.08f + 0.12f * rnd.nextFloat();
            sPhase[i] = (float) (TWO_PI * rnd.nextDouble());
            sAlpha[i] = 90 + rnd.nextInt(71);
        }
        snowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        snowPaint.setStyle(Paint.Style.FILL);
        snowPaint.setColor(0xFFFFFFFF);
    }

    private void buildFog() {
        fogPeriod = width * 1.2f;
        fogBandH = height * 0.32f;
        fogTop = new float[FOG_BANDS];
        fogTop[0] = height * 0.08f;
        fogTop[1] = height * 0.38f;
        fogTop[2] = height * 0.66f;
        fogSpeed = new float[FOG_BANDS];
        fogSpeed[0] = dp(7f);
        fogSpeed[1] = dp(-10f);
        fogSpeed[2] = dp(5f);

        // Soft top and bottom edges (vertical), and irregular soft patches across the width (horizontal, repeating).
        // DST_IN multiplies the two, so a band is only as bright as its patch and fades out at its edges.
        LinearGradient vertical = new LinearGradient(0f, 0f, 0f, fogBandH,
                new int[]{0x00DDE3EA, 0xFFDDE3EA, 0x00DDE3EA}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        LinearGradient horizontal = new LinearGradient(0f, 0f, fogPeriod, 0f,
                new int[]{0x00DDE3EA, 0x19DDE3EA, 0x00DDE3EA, 0x00DDE3EA}, new float[]{0f, 0.35f, 0.7f, 1f},
                Shader.TileMode.REPEAT);
        fogPaint = new Paint();
        fogPaint.setShader(new ComposeShader(vertical, horizontal, PorterDuff.Mode.DST_IN));
    }

    private void buildRays() {
        rayPath = new Path[RAY_COUNT];
        rayPaint = new Paint[RAY_COUNT];
        double[] degrees = {30.0, 48.0, 66.0};   // angle below the horizontal, leftwards from the top-right corner
        double half = Math.toRadians(2.5);
        float len = (float) Math.hypot(width, height) * 1.05f;
        for (int i = 0; i < RAY_COUNT; i++) {
            double a = Math.toRadians(degrees[i]);
            Path p = new Path();
            p.moveTo(width, 0f);
            p.lineTo((float) (width - len * Math.cos(a - half)), (float) (len * Math.sin(a - half)));
            p.lineTo((float) (width - len * Math.cos(a + half)), (float) (len * Math.sin(a + half)));
            p.close();
            rayPath[i] = p;
            LinearGradient g = new LinearGradient(width, 0f,
                    (float) (width - 0.8 * len * Math.cos(a)), (float) (0.8 * len * Math.sin(a)),
                    0xFFFFE8B0, 0x00FFE8B0, Shader.TileMode.CLAMP);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(g);
            rayPaint[i] = paint;
        }
    }

    private void buildStars(Random rnd) {
        tx = new float[STAR_COUNT];
        ty = new float[STAR_COUNT];
        tr = new float[STAR_COUNT];
        tAlpha = new int[STAR_COUNT];
        tPeriod = new float[STAR_COUNT];
        tPhase = new float[STAR_COUNT];
        for (int i = 0; i < STAR_COUNT; i++) {
            tx[i] = rnd.nextFloat() * width;
            ty[i] = rnd.nextFloat() * height * 0.45f;
            tr[i] = dp(0.6f + 0.6f * rnd.nextFloat());
            tAlpha[i] = 60 + rnd.nextInt(91);
            if (i % 10 == 0) {   // about a dozen of them twinkle
                tPeriod[i] = 2.5f + 2f * rnd.nextFloat();
                tPhase[i] = (float) (TWO_PI * rnd.nextDouble());
            }
        }
        starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        starPaint.setStyle(Paint.Style.FILL);
        starPaint.setColor(0xFFEAF2FF);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (kind == null || width <= 0 || height <= 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        double t = (now - originMs) / 1000.0;

        c.drawColor(tintFor(kind, day));
        // The animated part stays in the open sky above the dock (setEffectsBottom); the tint covers everything.
        c.save();
        if (effectsBottom > 0) c.clipRect(0, 0, width, effectsBottom);
        if (!hole.isEmpty()) c.clipOutRect(hole);
        drawEffects(c, t, now);
        c.restore();
    }

    /** Where the animated effects end, in this view's pixels (the dock's top); 0 lets them use the whole screen. */
    void setEffectsBottom(int y) {
        if (y == effectsBottom) return;
        effectsBottom = y;
        invalidate();
    }

    private int effectsBottom;
    /** An area the effects leave out (the spotlight's text, so nothing falls through its letters); empty for none. */
    private final android.graphics.Rect hole = new android.graphics.Rect();

    /** Leaves this area (in this view's pixels) out of the effects; null or empty clears it. */
    void setEffectsHole(android.graphics.Rect r) {
        if (r == null || r.isEmpty()) {
            if (hole.isEmpty()) return;
            hole.setEmpty();
        } else {
            if (hole.equals(r)) return;
            hole.set(r);
        }
        invalidate();
    }

    private void drawEffects(Canvas c, double t, long now) {
        switch (kind) {
            case CLEAR:
                if (day) {
                    drawRays(c, t);
                } else {
                    drawStars(c, t);
                }
                break;
            case STORM:
                drawLightning(c, now);
                drawRain(c, t);
                break;
            case RAIN:
            case DRIZZLE:
                drawRain(c, t);
                break;
            case SNOW:
                drawSnow(c, t);
                break;
            case FOG:
                drawFog(c, t);
                break;
            default:
                break;   // PARTLY_CLOUDY and CLOUDY: tint only
        }
    }

    private void drawLightning(Canvas c, long now) {
        long into = (now - originMs) % LIGHTNING_PERIOD_MS;
        if (into < LIGHTNING_FLASH_MS) {
            int a = (int) (0x22 * (1f - into / (float) LIGHTNING_FLASH_MS));
            if (a > 0) {
                c.drawColor((a << 24) | 0xFFFFFF);
            }
        }
    }

    private void drawRain(Canvas c, double t) {
        for (int i = 0; i < rainCount; i++) {
            Paint p = i < rainFarCount ? rainThin : rainThick;
            p.setAlpha(rAlpha[i]);
            float len = rLen[i];
            float y = (float) ((ry0[i] + rSpeed[i] * t) % rWrap[i]);
            float x = rx[i];
            c.drawLine(x, y - len, x + len * 0.2f, y, p);
        }
    }

    private void drawSnow(Canvas c, double t) {
        for (int i = 0; i < SNOW_COUNT; i++) {
            float r = sr[i];
            float y = (float) ((sy0[i] + sSpeed[i] * t) % sWrap[i]) - r;
            float x = sx[i] + sAmp[i] * (float) Math.sin(TWO_PI * sFreq[i] * t + sPhase[i]);
            snowPaint.setAlpha(sAlpha[i]);
            c.drawCircle(x, y, r, snowPaint);
        }
    }

    private void drawFog(Canvas c, double t) {
        for (int b = 0; b < FOG_BANDS; b++) {
            float dx = (float) wrap(fogSpeed[b] * t, fogPeriod);
            c.save();
            c.translate(dx, fogTop[b]);
            c.drawRect(-fogPeriod, 0f, width + fogPeriod, fogBandH, fogPaint);
            c.restore();
        }
    }

    private void drawRays(Canvas c, double t) {
        for (int i = 0; i < RAY_COUNT; i++) {
            double breath = 0.5 + 0.5 * Math.sin(TWO_PI * t / 11.0 + i * 2.1);
            rayPaint[i].setAlpha((int) (14 + 8 * breath));
            c.drawPath(rayPath[i], rayPaint[i]);
        }
    }

    private void drawStars(Canvas c, double t) {
        for (int i = 0; i < STAR_COUNT; i++) {
            int a = tAlpha[i];
            if (tPeriod[i] > 0f) {
                double s = 0.5 + 0.5 * Math.sin(TWO_PI * t / tPeriod[i] + tPhase[i]);
                a = (int) (a * (0.3 + 0.7 * s));
            }
            starPaint.setAlpha(a);
            c.drawCircle(tx[i], ty[i], tr[i], starPaint);
        }
    }
}
