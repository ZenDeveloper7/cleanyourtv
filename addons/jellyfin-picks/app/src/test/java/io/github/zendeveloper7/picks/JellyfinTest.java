package io.github.zendeveloper7.picks;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class JellyfinTest {
    private ServerSocket http;
    private Thread worker;
    private Jellyfin client;
    private interface Reply { JSONArray apply(String query) throws Exception; }
    private volatile Reply response;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    @Before public void start() throws Exception {
        http = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        worker = new Thread(() -> {
            while (!http.isClosed()) {
                try (Socket socket = http.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String request = in.readLine();
                    String query = java.net.URI.create(request.split(" ")[1]).getQuery();
                    while (!in.readLine().isEmpty()) { /* consume headers */ }
                    requests.add(query);
                    byte[] data = new JSONObject().put("Items", response.apply(query)).toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                            + data.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(data);
                    out.flush();
                } catch (Exception e) {
                    if (!http.isClosed()) throw new RuntimeException(e);
                }
            }
        }, "fake-jellyfin");
        worker.setDaemon(true);
        worker.start();
        client = new Jellyfin("http://127.0.0.1:" + http.getLocalPort(), "test-key");
    }
    @After public void stop() throws Exception { http.close(); worker.join(5000); }

    private static JSONObject item(String id, String type) throws org.json.JSONException {
        return new JSONObject().put("Id", id).put("Name", id).put("Type", type);
    }
    private static int offset(String query) {
        for (String part : query.split("&")) if (part.startsWith("StartIndex=")) return Integer.parseInt(part.substring(11));
        return 0;
    }

    @Test public void requestsOnlySupportedOptionalFields() throws Exception {
        response = q -> new JSONArray();
        client.candidates("user");
        client.history("user", new HashMap<>());
        Set<String> valid = new HashSet<>(Arrays.asList("Genres", "People", "DateCreated", "ChildCount", "ItemCounts", "RecursiveItemCount"));
        for (String q : requests) for (String part : q.split("&")) {
            if (part.startsWith("Fields=")) for (String field : part.substring(7).split(",")) assertTrue(field, valid.contains(field));
        }
    }

    @Test public void readsBeyondOldTwoThousandLimit() throws Exception {
        response = q -> {
            JSONArray out = new JSONArray();
            if (!q.contains("IncludeItemTypes=Movie&")) return out;
            int start = offset(q);
            for (int i = start; i < Math.min(2001, start + 200); i++) out.put(item("m" + i, "Movie"));
            return out;
        };
        List<Picks.Item> candidates = client.candidates("user");
        assertEquals(2001, candidates.size());
        assertEquals("m2000", candidates.get(2000).id);
    }

    @Test public void excludesPartialMoviesAndSeriesWithAResumableFirstEpisode() throws Exception {
        response = q -> {
            if (q.contains("IsResumable")) return new JSONArray().put(item("episode", "Episode").put("SeriesId", "startedShow"));
            if (q.contains("IncludeItemTypes=Movie&")) return new JSONArray()
                    .put(item("partialFilm", "Movie").put("UserData", new JSONObject().put("Played", false).put("PlaybackPositionTicks", 100)))
                    .put(item("newFilm", "Movie"));
            return new JSONArray().put(item("startedShow", "Series"))
                    .put(item("completedEpisodes", "Series").put("UserData", new JSONObject().put("PlayedPercentage", 10)))
                    .put(item("newShow", "Series"));
        };
        List<Picks.Item> candidates = client.candidates("user");
        assertEquals(2, candidates.size());
        assertEquals("newFilm", candidates.get(0).id);
        assertEquals("newShow", candidates.get(1).id);
    }

    @Test public void historyStopsAtSixtyDistinctTitlesInRecencyOrder() throws Exception {
        response = q -> {
            JSONArray out = new JSONArray();
            for (int i = 0; i < 200; i++) out.put(item("m" + i, "Movie"));
            return out;
        };
        List<Picks.Item> history = client.history("user", new HashMap<>());
        assertEquals(60, history.size());
        assertEquals("m0", history.get(0).id);
        assertEquals("m59", history.get(59).id);
        assertEquals(1, requests.size());
    }

    @Test public void bingeDoesNotHideOlderDistinctTitles() throws Exception {
        response = q -> {
            if (q.contains("Ids=")) return new JSONArray().put(item("show", "Series"));
            JSONArray out = new JSONArray();
            if (offset(q) == 0) {
                for (int i = 0; i < 200; i++) out.put(item("e" + i, "Episode").put("SeriesId", "show").put("SeriesName", "A show"));
            } else out.put(item("film", "Movie"));
            return out;
        };
        List<Picks.Item> history = client.history("user", new HashMap<>());
        assertEquals(2, history.size());
        assertEquals("show", history.get(0).id);
        assertEquals("film", history.get(1).id);
        assertTrue(requests.stream().anyMatch(q -> q.contains("StartIndex=200")));
    }
}
