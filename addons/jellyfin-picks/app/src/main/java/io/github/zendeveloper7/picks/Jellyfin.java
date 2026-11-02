package io.github.zendeveloper7.picks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few Jellyfin server calls the picks need, with an API key: find the server on the home network, list its
 * users, read one user's watch history and unwatched library, titles similar to a watched one, and posters.
 * Plain HttpURLConnection and org.json; the server is the only host it talks to.
 */
final class Jellyfin {

    static final class Server {
        final String name, address;

        Server(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    static final class User {
        final String id, name;

        User(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final int TIMEOUT_MS = 15_000;
    private static final String ITEM_FIELDS = "Genres,People,ProductionYear,DateCreated,CommunityRating,ChildCount,RunTimeTicks";

    final String base, apiKey;

    Jellyfin(String address, String apiKey) {
        String a = address.trim();
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        this.base = a;
        this.apiKey = apiKey.trim();
    }

    /** Jellyfin servers on the home network: they answer "who is JellyfinServer?" on UDP 7359 (broadcast). */
    static List<Server> discover(int waitMs) {
        Map<String, Server> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(waitMs);
            byte[] ask = "who is JellyfinServer?".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(ask, ask.length, InetAddress.getByName("255.255.255.255"), 7359));
            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    break;
                }
                try {
                    JSONObject o = new JSONObject(new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8));
                    String address = o.optString("Address", "");
                    if (!address.isEmpty()) found.put(address, new Server(o.optString("Name", address), address));
                } catch (JSONException ignored) {
                    // not a Jellyfin answer
                }
            }
        } catch (IOException ignored) {
            // no network: an empty list, and the owner types the address
        }
        return new ArrayList<>(found.values());
    }

    /** The server's users (an API key may list them all). Throws when the address or the key is wrong. */
    List<User> users() throws IOException, JSONException {
        JSONArray a = new JSONArray(get("/Users"));
        List<User> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject u = a.getJSONObject(i);
            out.add(new User(u.getString("Id"), u.optString("Name", "?")));
        }
        return out;
    }

    /**
     * The user's history, newest first: films and episodes. An episode stands for its series (the series' genres and
     * people are read in a second call). titles maps the ids to show names, for "Because you watched ...".
     */
    List<Picks.Item> history(String userId, Map<String, String> titles) throws IOException, JSONException {
        JSONArray played = items(userId, "Recursive=true&IncludeItemTypes=Movie,Episode&Filters=IsPlayed"
                + "&SortBy=DatePlayed&SortOrder=Descending&Limit=60&Fields=" + ITEM_FIELDS + ",SeriesId,SeriesName");
        List<String> order = new ArrayList<>();
        Map<String, Picks.Item> movies = new LinkedHashMap<>();
        List<String> seriesIds = new ArrayList<>();
        for (int i = 0; i < played.length(); i++) {
            JSONObject o = played.getJSONObject(i);
            if ("Episode".equals(o.optString("Type"))) {
                String sid = o.optString("SeriesId", "");
                if (sid.isEmpty() || order.contains(sid)) continue;
                order.add(sid);
                seriesIds.add(sid);
                titles.put(sid, o.optString("SeriesName", ""));
            } else {
                Picks.Item m = item(o);
                if (order.contains(m.id)) continue;
                order.add(m.id);
                movies.put(m.id, m);
                titles.put(m.id, m.title);
            }
        }
        Map<String, Picks.Item> series = new LinkedHashMap<>();
        if (!seriesIds.isEmpty()) {
            JSONArray s = items(userId, "Ids=" + String.join(",", seriesIds) + "&Fields=" + ITEM_FIELDS);
            for (int i = 0; i < s.length(); i++) {
                Picks.Item it = item(s.getJSONObject(i));
                series.put(it.id, it);
            }
        }
        List<Picks.Item> out = new ArrayList<>();
        for (String id : order) {
            Picks.Item it = movies.containsKey(id) ? movies.get(id) : series.get(id);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** Unwatched films, and series not started yet: what a pick can be. */
    List<Picks.Item> candidates(String userId) throws IOException, JSONException {
        List<Picks.Item> out = new ArrayList<>();
        JSONArray movies = items(userId, "Recursive=true&IncludeItemTypes=Movie&Filters=IsUnplayed&Limit=2000&Fields=" + ITEM_FIELDS);
        for (int i = 0; i < movies.length(); i++) out.add(item(movies.getJSONObject(i)));
        JSONArray series = items(userId, "Recursive=true&IncludeItemTypes=Series&Limit=2000&Fields=" + ITEM_FIELDS);
        for (int i = 0; i < series.length(); i++) {
            JSONObject o = series.getJSONObject(i);
            JSONObject data = o.optJSONObject("UserData");
            // Started series belong to Continue watching / Next Up, not to the picks.
            if (data != null && (data.optBoolean("Played", false) || data.optDouble("PlayedPercentage", 0) > 0)) {
                continue;
            }
            out.add(item(o));
        }
        return out;
    }

    /** Ids of titles the server finds similar to this one. */
    List<String> similar(String userId, String itemId) throws IOException, JSONException {
        JSONObject o = new JSONObject(get("/Items/" + itemId + "/Similar?userId=" + userId + "&limit=12"));
        JSONArray a = o.optJSONArray("Items");
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("Id"));
        return out;
    }

    /** Saves a wide picture of the item (its backdrop, else its poster) to file; false when it has none. */
    boolean poster(String itemId, File file) {
        for (String kind : new String[]{"Backdrop", "Thumb", "Primary"}) {
            try {
                byte[] data = bytes("/Items/" + itemId + "/Images/" + kind + "?maxWidth=1280&quality=85");
                File tmp = new File(file.getPath() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (tmp.renameTo(file)) return true;
            } catch (IOException ignored) {
                // no such picture: try the next kind
            }
        }
        return false;
    }

    /** Items for the user: /Items?userId= (Jellyfin 10.9 and later), else the older /Users/{id}/Items. */
    private JSONArray items(String userId, String query) throws IOException, JSONException {
        String text;
        try {
            text = get("/Items?userId=" + userId + "&" + query);
        } catch (HttpError e) {
            if (e.code != 404 && e.code != 400) throw e;
            text = get("/Users/" + userId + "/Items?" + query);
        }
        JSONArray a = new JSONObject(text).optJSONArray("Items");
        return a == null ? new JSONArray() : a;
    }

    static Picks.Item item(JSONObject o) {
        List<String> genres = strings(o.optJSONArray("Genres"));
        List<String> people = new ArrayList<>();
        JSONArray p = o.optJSONArray("People");
        if (p != null) {
            for (int i = 0; i < p.length() && people.size() < 8; i++) {
                JSONObject person = p.optJSONObject(i);
                if (person == null) continue;
                String type = person.optString("Type", "");
                if ("Actor".equals(type) || "Director".equals(type)) people.add(person.optString("Name", ""));
            }
        }
        long ticks = o.optLong("RunTimeTicks", 0);
        long added = 0;
        String created = o.optString("DateCreated", "");
        if (!created.isEmpty()) {
            try {
                added = Instant.parse(created.length() > 20 && !created.endsWith("Z") ? created + "Z" : created).toEpochMilli();
            } catch (Exception ignored) {
                // an unexpected date format: no "new" bonus
            }
        }
        return new Picks.Item(o.optString("Id"), o.optString("Name", ""), o.optString("Type", ""), genres, people,
                o.optInt("ProductionYear", 0), (int) (ticks / 600_000_000L), o.optInt("ChildCount", 0),
                (float) o.optDouble("CommunityRating", 0), added);
    }

    private static List<String> strings(JSONArray a) {
        if (a == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String path) {
            super("HTTP " + code + " for " + path);
            this.code = code;
        }
    }

    private String get(String path) throws IOException {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    private byte[] bytes(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Authorization", "MediaBrowser Client=\"Jellyfin Picks\", Device=\"TV\", "
                + "DeviceId=\"jellyfin-picks\", Version=\"1.0\", Token=\"" + apiKey + "\"");
        c.setRequestProperty("X-Emby-Token", apiKey);   // older servers
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new HttpError(code, path.replaceAll("\\?.*", ""));
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                for (int n; (n = in.read(chunk)) > 0; ) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 16 * 1024 * 1024) throw new IOException("reply too large");
                }
                return buf.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
