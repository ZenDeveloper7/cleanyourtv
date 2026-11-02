package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Continue watching (watch_next_packages): the Watch Next rows the named apps publish to the TV provider
 * (docs/watch-next.md), shown as a row of tiles under the dock. Only half-watched (continue) and next-episode rows,
 * engaged with in the last {@link #MAX_AGE_MS}, under {@link #DONE} watched, newest first. Needs READ_TV_LISTINGS;
 * without it, or when the apps publish nothing, the row is simply not there. Posters are read only from
 * {@code content://} and {@code android.resource://}: the launcher has no network access. Everything here runs off the
 * main thread (MainActivity): tiles come from a poster cache at once, and missing posters are fetched afterwards, each
 * within {@link #POSTER_TIMEOUT_MS}. An app's image provider may fetch the picture from its server first; on a Sony
 * BRAVIA BF1 (Jellyfin 0.19) that blocked Home until Android reported it not responding.
 */
final class WatchNext {

    private static final String TAG = "TvHome.WatchNext";
    static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    /** A row at or past this share of its length counts as finished. */
    static final float DONE = 0.95f;
    /** TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE and _NEXT. */
    static final int TYPE_CONTINUE = 0, TYPE_NEXT = 1;
    /** Poster tile size: 16:9, enough for a tile focused at 1080p. */
    private static final int W = 400, H = 225;
    /** Spotlight backdrop size (continue_spotlight): 720p, stretched over the screen behind a scrim. */
    private static final int BW = 1280, BH = 720;
    /** How long one poster may take to arrive; until then, and when it never does, the tile shows the app's icon. */
    static final long POSTER_TIMEOUT_MS = 20_000;
    /** A poster bigger than this is not read: it is meant for a tile. */
    private static final int MAX_POSTER_BYTES = 8 * 1024 * 1024;
    /** Two daemon threads for posters: a provider that never answers holds a thread, never Home. */
    private static final ExecutorService POSTERS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "watch-next-poster");
        t.setDaemon(true);
        return t;
    });

    /** One Watch Next row, as read from the provider. */
    static final class Row {
        final long id;
        final String pkg, title, episode, posterUri, intentUri;
        final int type;
        final long engaged, position, duration;

        Row(long id, String pkg, String title, String episode, String posterUri, String intentUri,
            int type, long engaged, long position, long duration) {
            this.id = id;
            this.pkg = pkg;
            this.title = title;
            this.episode = episode;
            this.posterUri = posterUri;
            this.intentUri = intentUri;
            this.type = type;
            this.engaged = engaged;
            this.position = position;
            this.duration = duration;
        }

        /** Share watched, 0 when unknown. */
        float progress() {
            return duration > 0 && position > 0 ? Math.min(1f, position / (float) duration) : 0f;
        }

        /** Stable identity of the tile: changes when the row is replaced or moves on to another episode. */
        String key() {
            return pkg + "/" + id + "/" + title + "/" + episode;
        }

        /** The name under the tile: the title, and the episode when there is one. */
        String name() {
            return episode.isEmpty() ? title : title + " · " + episode;
        }
    }

    private WatchNext() {
    }

    /**
     * The rows to show: from the named packages, continue or next only, engaged with in the last 30 days, not yet
     * finished, with a way to open them; newest first, at most {@code max}.
     */
    static List<Row> pick(List<Row> rows, Set<String> packages, long now, int max) {
        List<Row> keep = new ArrayList<>();
        for (Row r : rows) {
            if (!packages.contains(r.pkg) || r.title.isEmpty() || r.intentUri.isEmpty()) continue;
            if (r.type != TYPE_CONTINUE && r.type != TYPE_NEXT) continue;
            if (r.engaged <= 0 || now - r.engaged > MAX_AGE_MS) continue;
            if (r.progress() >= DONE) continue;
            keep.add(r);
        }
        Collections.sort(keep, (a, b) -> Long.compare(b.engaged, a.engaged));
        return keep.size() > max ? new ArrayList<>(keep.subList(0, max)) : keep;
    }

    /** "S2E5" from the display numbers, plus the episode title when there is one; "" for a film. */
    static String episodeLabel(String season, String episode, String episodeTitle) {
        StringBuilder s = new StringBuilder();
        if (!season.isEmpty()) s.append('S').append(season);
        if (!episode.isEmpty()) s.append('E').append(episode);
        if (!episodeTitle.isEmpty()) s.append(s.length() > 0 ? " " : "").append(episodeTitle);
        return s.toString().trim();
    }

    /** Reads the provider; empty without READ_TV_LISTINGS or on any failure, never an exception. */
    static List<Row> read(Context context, Set<String> packages, int max) {
        if (packages.isEmpty() || max <= 0) return Collections.emptyList();
        if (context.checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) {
            return Collections.emptyList();
        }
        List<Row> rows = new ArrayList<>();
        // No selection: a caller without ACCESS_ALL_EPG_DATA gets a SecurityException for one (docs/watch-next.md).
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return Collections.emptyList();
            while (c.moveToNext()) {
                rows.add(new Row(longCol(c, TvContract.WatchNextPrograms._ID),
                        text(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME),
                        Cards.cleanLabel(text(c, TvContract.WatchNextPrograms.COLUMN_TITLE), ""),
                        Cards.cleanLabel(episodeLabel(text(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                                text(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                                text(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE)), ""),
                        poster(text(c, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI),
                                text(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI)),
                        text(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI),
                        (int) longCol(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE),
                        longCol(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
                        longCol(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
                        longCol(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Watch Next not readable: " + e);
            return Collections.emptyList();
        }
        return pick(rows, packages, System.currentTimeMillis(), max);
    }

    /** The 16:9 thumbnail when there is one (it fits the tile), else the poster. */
    private static String poster(String thumbnail, String poster) {
        return !thumbnail.isEmpty() ? thumbnail : poster;
    }

    /**
     * The row's intent, held to its own app: an explicit package, a new task and no selector, so a published row can
     * only open the app that published it. Null when it does not parse.
     */
    static Intent intent(Row r) {
        try {
            Intent i = Intent.parseUri(r.intentUri, Intent.URI_INTENT_SCHEME);
            i.setSelector(null);
            if (i.getComponent() != null && !r.pkg.equals(i.getComponent().getPackageName())) i.setComponent(null);
            i.setPackage(r.pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * The tile picture from the poster cache: the poster cropped to 16:9 with a progress bar along the bottom; null
     * while the poster has not been fetched yet (fetchMissing). Never waits on the publishing app.
     */
    static Drawable tile(Context context, Row r, int accent) {
        Bitmap cached = BitmapFactory.decodeFile(cacheFile(context, r.posterUri).getPath());
        if (cached == null) return null;
        Bitmap out = cached.copy(Bitmap.Config.ARGB_8888, true);
        cached.recycle();
        if (out == null) return null;
        float p = r.progress();
        if (p > 0f) {
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            float bar = H * 0.035f, pad = W * 0.04f, y = H - pad * 0.6f - bar;
            paint.setColor(0x99000000);
            canvas.drawRoundRect(new RectF(pad, y, W - pad, y + bar), bar / 2, bar / 2, paint);
            paint.setColor(accent);
            canvas.drawRoundRect(new RectF(pad, y, pad + (W - 2 * pad) * Math.max(0.04f, p), y + bar), bar / 2, bar / 2, paint);
        }
        return new BitmapDrawable(context.getResources(), out);
    }

    /**
     * Fetches the posters not in the cache yet, one at a time, each within {@link #POSTER_TIMEOUT_MS}, and keeps a
     * 16:9 copy; true when at least one arrived. Slow by design: the publishing app may fetch the picture from its server.
     */
    static boolean fetchMissing(Context context, List<Row> rows) {
        ContentResolver resolver = context.getContentResolver();
        boolean any = false;
        for (Row r : rows) {
            if (r.posterUri.isEmpty()) continue;
            File file = cacheFile(context, r.posterUri);
            if (file.isFile()) continue;
            any |= fetchTo(resolver, r.posterUri, file, W, H);
        }
        return any;
    }

    /**
     * The spotlight's backdrop (continue_spotlight) from its own cache, decoded at 16 bits a pixel; null until
     * fetchBackdrop has kept one. Never waits on the publishing app.
     */
    static Bitmap backdrop(Context context, Row r) {
        if (r.posterUri.isEmpty()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(backdropFile(context, r.posterUri).getPath(), o);
    }

    /** Fetches the spotlight row's backdrop when it is not cached yet; true when one arrived. Slow, like fetchMissing. */
    static boolean fetchBackdrop(Context context, Row r) {
        if (r.posterUri.isEmpty()) return false;
        File file = backdropFile(context, r.posterUri);
        return !file.isFile() && fetchTo(context.getContentResolver(), r.posterUri, file, BW, BH);
    }

    /** Reads one poster within POSTER_TIMEOUT_MS and keeps a w x h centre crop of it in file; true when kept. */
    private static boolean fetchTo(ContentResolver resolver, String uri, File file, int w, int h) {
        long start = System.currentTimeMillis();
        Future<Bitmap> pending = POSTERS.submit(() -> fetch(resolver, uri, w, h));
        Bitmap src;
        try {
            src = pending.get(POSTER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.cancel(true);
            Log.w(TAG, "Poster not fetched after " + (System.currentTimeMillis() - start) + " ms: " + e);
            return false;
        }
        if (src == null) return false;
        Log.i(TAG, "Poster fetched in " + (System.currentTimeMillis() - start) + " ms");
        Bitmap crop = crop(src, w, h);
        src.recycle();
        boolean kept = save(crop, file);
        crop.recycle();
        return kept;
    }

    /** The poster centre-cropped to w x h (16:9). */
    private static Bitmap crop(Bitmap src, int W, int H) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        float scale = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
        float sw = W / scale, sh = H / scale;
        float sx = (src.getWidth() - sw) / 2f, sy = (src.getHeight() - sh) / 2f;
        new Canvas(out).drawBitmap(src, new Rect(Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh)),
                new RectF(0, 0, W, H), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    private static boolean save(Bitmap bitmap, File file) {
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(file);
    }

    /** Where a poster's 16:9 copy lives: the launcher's cache, named by the poster's address. */
    static File cacheFile(Context context, String posterUri) {
        File dir = new File(context.getCacheDir(), "watch_next");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, Integer.toHexString(posterUri.hashCode()) + "-" + posterUri.length() + ".jpg");
    }

    /** Where a poster's backdrop copy lives, next to its tile copy. */
    static File backdropFile(Context context, String posterUri) {
        File tile = cacheFile(context, posterUri);
        return new File(tile.getParentFile(), tile.getName().replace(".jpg", "-bd.jpg"));
    }

    /** Reads the poster once (an app's provider may download it for every open) and decodes it at about W x H. */
    private static Bitmap fetch(ContentResolver resolver, String uri, int W, int H) {
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals(ContentResolver.SCHEME_CONTENT) && !scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)) {
            return null;   // https posters need the network, which the launcher does not have
        }
        try (InputStream in = resolver.openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            for (int n; (n = in.read(chunk)) > 0; ) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_POSTER_BYTES) return null;
            }
            byte[] data = buf.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "No poster: " + e);
            return null;
        }
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        String s = i >= 0 && !c.isNull(i) ? c.getString(i) : null;
        return s == null ? "" : s.trim();
    }

    private static long longCol(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i >= 0 && !c.isNull(i) ? c.getLong(i) : 0L;
    }
}
