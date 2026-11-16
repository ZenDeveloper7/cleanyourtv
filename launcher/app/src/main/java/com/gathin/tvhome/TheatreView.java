package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.Collections;
import java.util.List;

/**
 * The theatre Home's picture (home_style theatre, see {@link TheatreHome}): the title in focus on a screen, in front
 * of a tilted wall of posters lit by that title's colours, and the apps in an arc that comes up from the bottom edge.
 *
 * <p>Three positions, each on a spring, are all the state it draws from: which title is in front ({@code pos}),
 * how far the screen has stepped back into the wall ({@code wall}), and how far the arc is up ({@code arc}). It
 * draws only while one of them moves; a Home left alone costs nothing.
 *
 * <p>Everything is laid out on the 680 x 382.5 grid the design was drawn on and scaled by the view's width.
 */
final class TheatreView extends View {

    /** One title: Continue watching's row or a pick, with its pictures already made (see TheatreHome). */
    static final class Title {
        final String key, kicker, name, meta, button;
        /** Share watched; 0 or less draws no bar. */
        final float progress;
        final Intent intent;
        /** The wide picture for the screen, the rounded portrait crop for the wall, and a few pixels of it for the glow. */
        final Bitmap art, poster, glow;

        Title(String key, String kicker, String name, String meta, String button, float progress, Intent intent,
              Bitmap art, Bitmap poster, Bitmap glow) {
            this.key = key;
            this.kicker = kicker;
            this.name = name;
            this.meta = meta;
            this.button = button;
            this.progress = progress;
            this.intent = intent;
            this.art = art;
            this.poster = poster;
            this.glow = glow;
        }
    }

    /** The apps for the arc, and what the keys ask for. */
    interface Listener {
        int appCount();

        String appLabel(int index);

        /** The app's banner, or its icon; null draws its name on its colour. */
        Drawable appArt(int index);

        int appColor(int index);

        void onOpenApp(int index);

        void onOpenTitle(Title title);

        /** Up from the wall: the top bar's buttons take focus. */
        void onLeaveUp();
    }

    // The wall: its centre on screen, its tilt, the posters' pitch and size, on the design grid.
    private static final float WALL_X = 400f, WALL_Y = 178f, TILT_DEG = -5.73f, SKEW = -0.22f;
    private static final float PITCH = 108f, ROW_PITCH = 156f, POSTER_W = 96f, POSTER_H = 140f, STAGGER = 10f;
    private static final float TILT_COS = (float) Math.cos(Math.toRadians(-TILT_DEG));
    private static final float TILT_SIN = (float) Math.sin(Math.toRadians(-TILT_DEG));
    // The screen in front of it: 16:9, the shape the pictures come in, from under the top bar down.
    private static final float SCREEN_X = 340f, SCREEN_Y = 195.5f, SCREEN_W = 432f, SCREEN_H = 243f;
    /** With the arc up the screen gives it room: this much smaller, its top edge staying where it is. */
    private static final float ARC_SHRINK = 0.18f;
    private static final int ROOM = 0xFF10131C;

    private final Listener listener;
    private final boolean animate;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final Paint sideScrim = new Paint();
    private final Paint screenScrim = new Paint();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular, medium;
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();

    private final FloatValueHolder pos = new FloatValueHolder(0f);
    private final FloatValueHolder wall = new FloatValueHolder(0f);
    private final FloatValueHolder arc = new FloatValueHolder(0f);
    private final FloatValueHolder arcPos = new FloatValueHolder(0f);
    private final SpringAnimation posSpring, wallSpring, arcSpring, arcPosSpring;

    private List<Title> titles = Collections.emptyList();
    private int focus, arcFocus;
    private boolean wallOn, arcOn;
    private String clock = "", weather = "";
    /** The design grid's unit in pixels. */
    private float u = 1f;

    TheatreView(Context context, boolean animate, Listener listener) {
        super(context);
        this.listener = listener;
        this.animate = animate;
        regular = context.getResources().getFont(R.font.outfit_regular);
        medium = context.getResources().getFont(R.font.outfit_medium);
        stroke.setStyle(Paint.Style.STROKE);
        // Titles move with a little overshoot; the wall and the arc settle without one.
        posSpring = spring(pos, 170f, 0.82f);
        arcPosSpring = spring(arcPos, 260f, 0.78f);
        wallSpring = spring(wall, 150f, 1f);
        arcSpring = spring(arc, 180f, 0.9f);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
    }

    private SpringAnimation spring(FloatValueHolder holder, float stiffness, float damping) {
        SpringAnimation a = new SpringAnimation(holder);
        a.setSpring(new SpringForce().setStiffness(stiffness).setDampingRatio(damping));
        a.setMinimumVisibleChange(0.002f);
        a.addUpdateListener((animation, value, velocity) -> invalidate());
        return a;
    }

    private void move(SpringAnimation spring, FloatValueHolder holder, float to) {
        if (animate && isAttachedToWindow()) {
            spring.animateToFinalPosition(to);
        } else {
            spring.cancel();
            holder.setValue(to);
            invalidate();
        }
    }

    // ---- what it shows ------------------------------------------------------------------------------

    /** The titles, in order. The one in focus stays in focus when it is still among them. */
    void setTitles(List<Title> next) {
        String key = focus < titles.size() ? titles.get(focus).key : null;
        titles = next;
        int at = 0;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).key.equals(key)) at = i;
        }
        focus = at;
        posSpring.cancel();
        pos.setValue(at);
        // Nothing to watch: the apps are what Home has, so the arc stays up.
        if (next.isEmpty()) {
            wallOn = false;
            move(wallSpring, wall, 0f);
            setArc(true);
        }
        describe();
        invalidate();
    }

    void setClock(String time) {
        if (time.equals(clock)) return;
        clock = time;
        invalidate();
    }

    void setWeather(String line) {
        if (line.equals(weather)) return;
        weather = line;
        invalidate();
    }

    /** The apps changed (installed, removed, reordered). */
    void onAppsChanged() {
        arcFocus = Math.max(0, Math.min(arcFocus, listener.appCount() - 1));
        move(arcPosSpring, arcPos, arcFocus);
        invalidate();
    }

    /** Home came back to the front: the screen, not the wall or the arc. */
    void reset() {
        if (titles.isEmpty()) return;
        wallOn = false;
        move(wallSpring, wall, 0f);
        setArc(false);
    }

    private void setArc(boolean on) {
        arcOn = on;
        move(arcSpring, arc, on ? 1f : 0f);
        describe();
    }

    private void setWall(boolean on) {
        wallOn = on;
        move(wallSpring, wall, on ? 1f : 0f);
    }

    /** What a screen reader says for the view: the title or app in focus and what OK does. */
    private void describe() {
        if (arcOn) {
            setContentDescription(listener.appLabel(arcFocus));
        } else if (!titles.isEmpty()) {
            Title t = titles.get(focus);
            setContentDescription(t.button + " " + t.name);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        u = w / 680f;
        scrim.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        sideScrim.setShader(new LinearGradient(0, 0, w * 0.55f, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        // The shade under the title's text, over the lower half of the screen's picture.
        screenScrim.setShader(new LinearGradient(0, SCREEN_Y * u, 0, (SCREEN_Y + SCREEN_H / 2) * u, 0x00000000, 0xE6000000,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        posSpring.cancel();
        wallSpring.cancel();
        arcSpring.cancel();
        arcPosSpring.cancel();
    }

    // ---- keys ---------------------------------------------------------------------------------------

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isBack(int code) {
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                step((code == KeyEvent.KEYCODE_DPAD_RIGHT) != (getLayoutDirection() == LAYOUT_DIRECTION_RTL) ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getRepeatCount() > 0) return true;
                if (arcOn && !titles.isEmpty()) setArc(false);
                else if (!wallOn && !arcOn) setWall(true);
                else listener.onLeaveUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getRepeatCount() > 0) return true;
                if (wallOn) setWall(false);
                else if (!arcOn) setArc(true);
                return true;
            default:
                if (isOk(code)) return true;   // acts on release, so a held OK opens nothing twice
                // Back closes the arc or the wall; on the screen itself it is Home's to handle.
                if (isBack(code) && (wallOn || (arcOn && !titles.isEmpty()))) return true;
                return super.onKeyDown(code, event);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent event) {
        if (isOk(code)) {
            if (event.isCanceled()) return true;
            if (arcOn) {
                if (listener.appCount() > 0) listener.onOpenApp(arcFocus);
            } else if (wallOn) {
                setWall(false);
            } else if (!titles.isEmpty()) {
                listener.onOpenTitle(titles.get(focus));
            }
            return true;
        }
        if (isBack(code) && (wallOn || (arcOn && !titles.isEmpty()))) {
            if (arcOn) setArc(false);
            else setWall(false);
            return true;
        }
        return super.onKeyUp(code, event);
    }

    private void step(int by) {
        if (arcOn) {
            int to = Math.max(0, Math.min(listener.appCount() - 1, arcFocus + by));
            if (to == arcFocus) return;
            arcFocus = to;
            move(arcPosSpring, arcPos, to);
        } else {
            int to = Math.max(0, Math.min(titles.size() - 1, focus + by));
            if (to == focus) return;
            focus = to;
            move(posSpring, pos, to);
        }
        describe();
    }

    // ---- drawing ------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth(), h = getHeight();
        final int n = titles.size();
        final float p = n == 0 ? 0f : Math.max(0f, Math.min(n - 1, pos.getValue()));
        final float v = clamp01(wall.getValue()), s = clamp01(arc.getValue());
        if (n > 0) {
            int a = (int) Math.floor(p), b = Math.min(n - 1, a + 1), front = Math.round(p);
            drawGlow(canvas, titles.get(a), 1f, w, h);
            if (p - a > 0.01f) drawGlow(canvas, titles.get(b), p - a, w, h);
            canvas.drawColor(0x8C040509);
            drawWall(canvas, p, v);
            drawGlow(canvas, titles.get(front), lerp(0.18f, 0.1f, v), w, h);
            drawScreens(canvas, p, v, s);
            scrim.setAlpha(Math.round(255 * lerp(0.75f, 0.92f, s)));
            canvas.drawRect(0, 0, w, h, scrim);
            drawInfo(canvas, titles.get(front), Math.min(1f, near(front, p) * 1.6f) * (1f - s), v, w, h);
        } else if (s > 0.01f) {
            // No titles: the wallpaper shows through, with the arc over a shade at the bottom.
            scrim.setAlpha(Math.round(255 * 0.8f * s));
            canvas.drawRect(0, 0, w, h, scrim);
        }
        drawHeader(canvas);
        if (s > 0.01f) drawArc(canvas, s, h);
    }

    /** A title's few pixels stretched past the edges: its colours as soft light, with no blur to pay for. */
    private void drawGlow(Canvas canvas, Title t, float alpha, float w, float h) {
        bitmapPaint.setAlpha(Math.round(255 * alpha));
        rect.set(-80 * u, -60 * u, w + 80 * u, h + 60 * u);
        canvas.drawBitmap(t.glow, null, rect, bitmapPaint);
    }

    private void drawWall(Canvas canvas, float p, float v) {
        final int n = titles.size();
        final float dim = lerp(0.74f, 0.5f, v), r = 6 * u;
        canvas.save();
        canvas.translate(WALL_X * u, WALL_Y * u);
        canvas.rotate(TILT_DEG);
        canvas.skew(SKEW, 0f);
        canvas.translate(-p * PITCH * u, 0f);
        bitmapPaint.setAlpha(255);
        final int first = (int) Math.floor(p) - 5, last = first + 11;
        for (int row = -1; row <= 1; row++) {
            for (int c = first; c <= last; c++) {
                boolean title = row == 0 && c >= 0 && c < n;
                float k = title ? near(c, p) : 0f;
                float left = (c * PITCH - POSTER_W / 2) * u;
                float top = (row * ROW_PITCH - POSTER_H / 2 + stagger(c)) * u;
                rect.set(left, top, left + POSTER_W * u, top + POSTER_H * u);
                if (k * (1f - v) > 0.5f) {
                    // This one is out in front as the screen: its place on the wall is an empty frame.
                    stroke.setColor(0x59FFFFFF);
                    stroke.setStrokeWidth(Math.max(1f, u * 0.5f));
                    canvas.drawRoundRect(rect, r, r, stroke);
                    continue;
                }
                if (k * v > 0.5f) {
                    fill.setColor(0xFFFFFFFF);
                    rect.inset(-3 * u, -3 * u);
                    canvas.drawRoundRect(rect, r + 2 * u, r + 2 * u, fill);
                    rect.inset(3 * u, 3 * u);
                }
                canvas.drawBitmap(titles.get(Math.floorMod(c + row * 2, n)).poster, null, rect, bitmapPaint);
                fill.setColor(Math.round(255 * dim * (1f - k * v)) << 24);
                canvas.drawRoundRect(rect, r, r, fill);
            }
        }
        canvas.restore();
    }

    /**
     * The screen. The title in focus grows from its place on the wall to the screen; the one being left flies on
     * towards the viewer and fades. In the wall view (v) the screen goes back into its place.
     */
    private void drawScreens(Canvas canvas, float p, float v, float s) {
        for (int i = titles.size() - 1; i >= 0; i--) {
            float d = i - p, k = near(i, p) * (1f - v);
            if (d < -0.98f || (k < 0.02f && d >= 0f)) continue;
            float width, height, cx, cy, alpha;
            if (d >= 0f || v > 0.02f) {
                float e = k * k * (3f - 2f * k);
                float along = d * PITCH;
                float size = 1f - ARC_SHRINK * s;
                width = lerp(POSTER_W, SCREEN_W * size, e);
                height = lerp(POSTER_H, SCREEN_H * size, e);
                cx = lerp(WALL_X + along * TILT_COS, SCREEN_X, e);
                cy = lerp(WALL_Y - along * TILT_SIN + stagger(i), SCREEN_Y - SCREEN_H * (1f - size) / 2f, e);
                alpha = Math.min(1f, k * 3f);
            } else {
                float out = -d;
                width = SCREEN_W * (1f + out * 1.1f);
                height = SCREEN_H * (1f + out * 1.1f);
                cx = SCREEN_X;
                cy = SCREEN_Y + out * 30f;
                alpha = Math.max(0f, 1f - out * 1.7f);
            }
            if (alpha <= 0f) continue;
            rect.set((cx - width / 2) * u, (cy - height / 2) * u, (cx + width / 2) * u, (cy + height / 2) * u);
            fill.setColor(Math.round(255 * 0.12f * alpha) << 24 | 0xFFFFFF);
            rect.inset(-5 * u, -5 * u);
            canvas.drawRoundRect(rect, 10 * u, 10 * u, fill);
            rect.inset(5 * u, 5 * u);
            Bitmap art = titles.get(i).art;
            cover(art, rect.width(), rect.height());
            clip.rewind();
            clip.addRoundRect(rect, 6 * u, 6 * u, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            bitmapPaint.setAlpha(Math.round(255 * alpha));
            canvas.drawBitmap(art, src, rect, bitmapPaint);
            canvas.restore();
        }
    }

    /** Sets {@link #src} to the centre part of the bitmap that fills a w x h place. */
    private void cover(Bitmap bitmap, float w, float h) {
        float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
        int sw = Math.round(w / scale), sh = Math.round(h / scale);
        int left = (bitmap.getWidth() - sw) / 2, top = (bitmap.getHeight() - sh) / 2;
        src.set(left, top, left + sw, top + sh);
    }

    /**
     * Why it is here, its name, the time left and the button: over the foot of the screen's picture, the text at its
     * start and the button at its end, or at the left of the wall.
     */
    private void drawInfo(Canvas canvas, Title t, float alpha, float v, float w, float h) {
        boolean onWall = v >= 0.5f;
        alpha *= onWall ? v * 2f - 1f : 1f - v * 2f;
        if (alpha <= 0.01f) return;
        int a = Math.round(255 * alpha);
        float x, base, nameSize, room;
        // The screen's own edges at rest, for the text laid over its picture.
        final float screenLeft = (SCREEN_X - SCREEN_W / 2) * u, screenRight = (SCREEN_X + SCREEN_W / 2) * u;
        final float screenBottom = (SCREEN_Y + SCREEN_H / 2) * u;
        // The button's text, then the three lines: smaller over the picture than beside the wall.
        final float buttonSize = (onWall ? 13 : 12) * u, pillHeight = (onWall ? 26 : 24) * u;
        final float kickerSize = (onWall ? 12 : 11) * u, metaSize = (onWall ? 13 : 12) * u;
        text.setTypeface(medium);
        text.setTextSize(buttonSize);
        final float pill = Math.max(72 * u, text.measureText(t.button) + 30 * u);
        text.setTextAlign(Paint.Align.LEFT);
        if (onWall) {
            sideScrim.setAlpha(Math.round(204 * (v * 2f - 1f)));
            canvas.drawRect(0, 0, w, h, sideScrim);
            x = 40 * u;
            base = 274 * u;
            nameSize = 22 * u;
            room = 300 * u;
        } else {
            rect.set(screenLeft, (SCREEN_Y - SCREEN_H / 2) * u, screenRight, screenBottom);
            clip.rewind();
            clip.addRoundRect(rect, 6 * u, 6 * u, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            screenScrim.setAlpha(a);
            canvas.drawRect(rect, screenScrim);
            canvas.restore();
            x = screenLeft + 18 * u;
            base = screenBottom - 32 * u;
            nameSize = 17 * u;
            room = screenRight - screenLeft - 48 * u - pill;
        }
        text.setColor(0xFFFFFF | a << 24);
        line(canvas, t.kicker, regular, kickerSize, x, base - nameSize - 5 * u, room);
        line(canvas, t.name, medium, nameSize, x, base, room);
        line(canvas, t.meta, regular, metaSize, x, base + metaSize + 5 * u, room);
        // On the wall the bar and the button follow the text; on the screen the bar is the text's last line and the
        // button sits at the picture's other end.
        float barTop = base + metaSize + 12 * u;
        if (t.progress > 0f) {
            fill.setColor(0xFFFFFF | Math.round(a * 0.35f) << 24);
            canvas.drawRect(x, barTop, x + 180 * u, barTop + 3 * u, fill);
            fill.setColor(0xFFFFFF | a << 24);
            canvas.drawRect(x, barTop, x + 180 * u * Math.max(0.03f, t.progress), barTop + 3 * u, fill);
        }
        float pillLeft = onWall ? x : screenRight - 18 * u - pill;
        float pillTop = onWall ? base + metaSize + 24 * u : screenBottom - 16 * u - pillHeight;
        rect.set(pillLeft, pillTop, pillLeft + pill, pillTop + pillHeight);
        fill.setColor(0xFFFFFF | a << 24);
        canvas.drawRoundRect(rect, pillHeight / 2, pillHeight / 2, fill);
        text.setTypeface(medium);
        text.setTextSize(buttonSize);
        text.setColor(0x111111 | a << 24);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(t.button, rect.centerX(), pillTop + pillHeight / 2 + buttonSize * 0.35f, text);
    }

    private void line(Canvas canvas, String value, Typeface face, float size, float x, float y, float room) {
        if (value.isEmpty()) return;
        text.setTypeface(face);
        text.setTextSize(size);
        canvas.drawText(TextUtils.ellipsize(value, text, room, TextUtils.TruncateAt.END).toString(), x, y, text);
    }

    /** The time and the weather, top start; the top bar's buttons keep the other corner. */
    private void drawHeader(Canvas canvas) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xE6FFFFFF);
        line(canvas, clock, medium, 15 * u, 40 * u, 38 * u, 300 * u);
        line(canvas, weather, regular, 13 * u, 40 * u, 57 * u, 300 * u);
    }

    /** The apps on a curve that rises from the bottom edge; the one in focus is the largest, at the top of the curve. */
    private void drawArc(Canvas canvas, float s, float h) {
        final int count = listener.appCount();
        final float radius = 520 * u, at = arcPos.getValue();
        final float alpha = Math.min(1f, s * 1.4f);
        for (int j = 0; j < count; j++) {
            float off = j - at;
            if (Math.abs(off) > 5f) continue;
            float angle = off * 0.19f, k = Math.max(0f, 1f - Math.abs(off));
            float cx = SCREEN_X * u + radius * (float) Math.sin(angle);
            float cy = h + 440 * u - radius * (float) Math.cos(angle) + (1f - s) * (150 + Math.abs(off) * 40) * u;
            canvas.save();
            canvas.translate(cx, cy - k * 10 * u);
            canvas.rotate((float) Math.toDegrees(angle * 0.7f));
            drawApp(canvas, j, (88 + k * 30) * u, (54 + k * 18) * u, k, alpha);
            canvas.restore();
        }
        if (count == 0) return;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFF | Math.round(255 * s) << 24);
        line(canvas, listener.appLabel(Math.max(0, Math.min(count - 1, Math.round(at)))), medium, 14 * u,
                SCREEN_X * u, h - 12 * u, 400 * u);
    }

    /** One app's tile around the origin: its banner, or its name on its colour. */
    private void drawApp(Canvas canvas, int index, float w, float h, float k, float alpha) {
        float r = 9 * u;
        rect.set(-w / 2, -h / 2, w / 2, h / 2);
        boolean layer = alpha < 0.99f;
        if (layer) canvas.saveLayerAlpha(-w / 2 - 4 * u, -h / 2 - 4 * u, w / 2 + 4 * u, h / 2 + 4 * u, Math.round(255 * alpha));
        if (k > 0.5f) {
            fill.setColor(0xFFFFFFFF);
            rect.inset(-3 * u, -3 * u);
            canvas.drawRoundRect(rect, r + 2 * u, r + 2 * u, fill);
            rect.inset(3 * u, 3 * u);
        }
        fill.setColor(listener.appColor(index) | 0xFF000000);
        canvas.drawRoundRect(rect, r, r, fill);
        Drawable art = listener.appArt(index);
        if (art != null) {
            clip.rewind();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            Rect kept = art.copyBounds();
            art.setBounds(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
            art.draw(canvas);
            art.setBounds(kept);
            canvas.restore();
        } else {
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0xFFFFFFFF);
            line(canvas, listener.appLabel(index), medium, Math.max(11 * u, w * 0.13f), 0f, 5 * u, w - 12 * u);
        }
        if (layer) canvas.restore();
    }

    // ---- small maths --------------------------------------------------------------------------------

    /** 1 for the title at p, falling to 0 one title away. */
    private static float near(int index, float p) {
        return Math.max(0f, 1f - Math.abs(index - p));
    }

    /** Posters sit a little up and down by turns, so the wall is not a grid. */
    private static float stagger(int column) {
        return Math.floorMod(column, 2) == 1 ? STAGGER : -STAGGER;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /** The colour of a room with nothing on its screen, for a title without a picture. */
    static int room() {
        return ROOM;
    }
}
