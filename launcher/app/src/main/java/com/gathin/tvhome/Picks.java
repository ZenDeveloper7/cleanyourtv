package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * picks_package: suggestions a picks app on the TV publishes (addons/jellyfin-picks: films and series from the owner's
 * Jellyfin, chosen for the day, the weather and the watch history), shown in the spotlight in turn with Continue
 * watching. The launcher itself has no network. The app names a read-only provider in a {@code meta-data}
 * {@value #META_DATA} on its {@code <application>}; {@code content://<authority>/now} returns the picks best first, with
 * the columns id, title, subtitle, reason, label, intent (an intent: URI) and poster (a content:// picture or "").
 */
final class Picks {

    static final String META_DATA = "com.gathin.tvhome.picks";
    /** At most this many picks take turns in the spotlight. */
    static final int MAX = 3;

    /** One pick, with its picture already decoded (or null). */
    static final class Pick {
        final String id, title, subtitle, reason, label;
        final Intent intent;
        final Bitmap art;
        /** When the picks app made this set (0 when it does not say): a new set may bring a new picture. */
        long updated;

        Pick(String id, String title, String subtitle, String reason, String label, Intent intent, Bitmap art) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.reason = reason;
            this.label = label;
            this.intent = intent;
            this.art = art;
        }
    }

    /** The provider of the named package, as Weather.uri finds its own; null when off or not installed. */
    static Uri uri(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META_DATA);
            if (authority == null || authority.trim().isEmpty()) return null;
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            if (provider == null || !pkg.trim().equals(provider.packageName)) return null;
            return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority.trim()).appendPath("now").build();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The first MAX picks with their pictures, off the main thread (IPC and decoding); empty on any failure. */
    static List<Pick> read(Context ctx, Uri uri, int width, int height) {
        List<Pick> out = new ArrayList<>();
        if (uri == null) return out;
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(uri, new String[]{"id", "title", "subtitle", "reason", "label", "intent", "poster"},
                null, null, null)) {
            if (c == null) return out;
            // By name: a provider may ignore the projection and answer with its own columns, in its own order.
            int id = c.getColumnIndexOrThrow("id"), title = c.getColumnIndexOrThrow("title"),
                    subtitle = c.getColumnIndexOrThrow("subtitle"), reason = c.getColumnIndexOrThrow("reason"),
                    label = c.getColumnIndexOrThrow("label"), link = c.getColumnIndexOrThrow("intent"),
                    picture = c.getColumnIndexOrThrow("poster"), updated = c.getColumnIndex("updated");
            while (c.moveToNext() && out.size() < MAX) {
                Intent intent = intent(c.getString(link));
                if (intent == null) continue;
                String poster = c.isNull(picture) ? "" : c.getString(picture);
                Pick p = new Pick(c.getString(id), c.getString(title), c.getString(subtitle), c.getString(reason),
                        c.getString(label), intent, poster.isEmpty() ? null : decode(cr, Uri.parse(poster), width, height));
                if (updated >= 0 && !c.isNull(updated)) p.updated = c.getLong(updated);
                out.add(p);
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** The pick's intent, held to one explicit activity: a new task and no selector. Null when it does not parse. */
    static Intent intent(String uri) {
        try {
            Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            i.setSelector(null);
            if (i.getComponent() == null) return null;
            i.setPackage(i.getComponent().getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** The picture at about the screen's size, 16 bits a pixel; null when it cannot be read. */
    private static Bitmap decode(ContentResolver cr, Uri uri, int width, int height) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = cr.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private Picks() {
    }
}
