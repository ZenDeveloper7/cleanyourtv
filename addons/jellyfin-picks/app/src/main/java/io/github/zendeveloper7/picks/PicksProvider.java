package io.github.zendeveloper7.picks;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Read-only, for the launcher's spotlight (com.gathin.tvhome.Picks): content://io.github.zendeveloper7.picks.now/now
 * gives the saved picks, best first, with the columns id, title, subtitle, reason, label (the moment they are for,
 * "Rainy Sunday evening"), intent (opens the item in the Jellyfin app), poster (a content:// picture, or empty) and
 * updated. /poster/{id} is that picture. A query never waits on the network: when the picks are older than an hour
 * (the TV was off) or the weather has turned since they were made, it answers with what it has and refreshes in the
 * background, then announces the change.
 */
public final class PicksProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.zendeveloper7.picks.now";
    static final Uri NOW_URI = Uri.parse("content://" + AUTHORITY + "/now");
    private static final String[] COLUMNS = {"id", "title", "subtitle", "reason", "label", "intent", "poster", "updated"};

    @Override
    public boolean onCreate() {
        PicksJob.schedule(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (!isPath(uri, "now")) return cursor;
        JSONObject saved = Refresh.saved(getContext());
        long updated = saved == null ? 0 : saved.optLong("updated", 0);
        if (saved != null) {
            JSONArray picks = saved.optJSONArray("picks");
            String label = saved.optString("label", "");
            for (int i = 0; picks != null && i < picks.length(); i++) {
                JSONObject p = picks.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id");
                String poster = p.optBoolean("poster") ? "content://" + AUTHORITY + "/poster/" + id : "";
                cursor.addRow(new Object[]{id, p.optString("title"), p.optString("subtitle"), p.optString("reason"),
                        label, p.optString("intent"), poster, updated});
            }
        }
        // Old picks (the TV was off), or the weather has turned since: new picks in the background.
        boolean stale = System.currentTimeMillis() - updated > Refresh.STALE_MS;
        boolean weatherTurned = saved != null && !Refresh.weather(getContext())[0].equals(saved.optString("weather", ""));
        if ((stale || weatherTurned) && Refresh.configured(getContext())) {
            Refresh.async(getContext(), null);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (!"r".equals(mode) || seg.size() != 2 || !"poster".equals(seg.get(0)) || !seg.get(1).matches("[0-9a-fA-F-]{8,64}")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(Refresh.posters(getContext()), seg.get(1) + ".jpg");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static boolean isPath(Uri uri, String path) {
        return uri != null && AUTHORITY.equals(uri.getAuthority()) && uri.getPathSegments().size() == 1
                && path.equals(uri.getPathSegments().get(0));
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException("read-only"); }
}
