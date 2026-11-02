package io.github.zendeveloper7.picks;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * The one-time setup, with the remote: find the Jellyfin server on the home network (or type its address), type an
 * API key made in the Jellyfin dashboard, choose whose history counts. Afterwards: what is picked now, and why.
 */
public final class SetupActivity extends Activity {

    private LinearLayout page;
    private String address = "";
    private String apiKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(64);
        page.setPadding(pad, dp(48), pad, dp(48));
        scroll.addView(page);
        setContentView(scroll);
        if (Refresh.configured(this)) showStatus();
        else findServer();
    }

    // ---- step 1: the server ---------------------------------------------------------------------

    private void findServer() {
        clear("Find your Jellyfin server", "Looking on your home network…");
        new Thread(() -> {
            List<Jellyfin.Server> servers = Jellyfin.discover(3000);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                clear("Find your Jellyfin server", servers.isEmpty()
                        ? "No server answered. Type its address, e.g. http://192.168.1.10:8096."
                        : "Choose your server:");
                Button first = null;
                for (Jellyfin.Server s : servers) {
                    Button b = button(s.name + "   " + s.address, v -> askKey(s.address));
                    if (first == null) first = b;
                }
                Button type = button("Type the address", v -> typeAddress());
                (first != null ? first : type).requestFocus();
            });
        }).start();
    }

    private void typeAddress() {
        clear("Server address", "Its address with the port, as you open Jellyfin in a browser.");
        EditText field = field("http://", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        Button next = button("Next", v -> {
            String a = field.getText().toString().trim();
            if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
            askKey(a);
        });
        field.setOnEditorActionListener((v, id, e) -> next.performClick());
        field.requestFocus();
    }

    // ---- step 2: the API key ----------------------------------------------------------------------

    private void askKey(String serverAddress) {
        address = serverAddress;
        clear("API key", "In Jellyfin on a phone or computer: Dashboard → API Keys → +, name it \"TV picks\", "
                + "then type the key here. It stays on this TV.");
        EditText field = field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        TextView error = text("", 18, 0xFFFF8A80);
        Button connect = button("Connect", v -> {
            String key = field.getText().toString().trim();
            if (key.isEmpty()) {
                error.setText("Type the key first.");
                return;
            }
            error.setText("Connecting…");
            new Thread(() -> {
                try {
                    List<Jellyfin.User> users = new Jellyfin(address, key).users();
                    runOnUiThread(() -> {
                        apiKey = key;
                        chooseUser(users);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> error.setText(e instanceof Jellyfin.HttpError && ((Jellyfin.HttpError) e).code == 401
                            ? "The server did not accept that key. Check it and try again."
                            : "Could not reach " + address + ". Check the address and that the server is on."));
                }
            }).start();
        });
        field.setOnEditorActionListener((v, id, e) -> id == EditorInfo.IME_ACTION_DONE && connect.performClick());
        button("Back", v -> findServer());
        field.requestFocus();
    }

    // ---- step 3: whose history ------------------------------------------------------------------

    private void chooseUser(List<Jellyfin.User> users) {
        if (users.size() == 1) {
            save(users.get(0));
            return;
        }
        clear("Whose picks?", "The picks follow this person's watch history.");
        Button first = null;
        for (Jellyfin.User u : users) {
            Button b = button(u.name, v -> save(u));
            if (first == null) first = b;
        }
        if (first != null) first.requestFocus();
    }

    private void save(Jellyfin.User user) {
        getSharedPreferences(Refresh.SETUP, MODE_PRIVATE).edit()
                .putString(Refresh.KEY_ADDRESS, address)
                .putString(Refresh.KEY_API_KEY, apiKey)
                .putString(Refresh.KEY_USER_ID, user.id)
                .putString(Refresh.KEY_USER_NAME, user.name)
                .apply();
        PicksJob.schedule(this);
        clear("Picking…", "Reading " + user.name + "'s history and the library.");
        Refresh.async(this, () -> runOnUiThread(this::showStatus));
    }

    // ---- afterwards -------------------------------------------------------------------------------

    private void showStatus() {
        if (isDestroyed()) return;
        SharedPreferences s = getSharedPreferences(Refresh.SETUP, MODE_PRIVATE);
        JSONObject saved = Refresh.saved(this);
        clear("Jellyfin Picks", "For " + s.getString(Refresh.KEY_USER_NAME, "?") + " on "
                + s.getString(Refresh.KEY_ADDRESS, "?") + ". They show in Home's spotlight and refresh every hour.");
        if (saved == null) {
            text("No picks yet: the server could not be read. Try Refresh now.", 22, Color.WHITE);
        } else {
            text("Now: " + saved.optString("label") + "  ·  updated "
                    + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(saved.optLong("updated"))), 20, 0xB3FFFFFF);
            JSONArray picks = saved.optJSONArray("picks");
            for (int i = 0; picks != null && i < picks.length(); i++) {
                JSONObject p = picks.optJSONObject(i);
                text((i + 1) + ".  " + p.optString("title") + "  —  " + p.optString("reason"), 24, Color.WHITE);
            }
        }
        Button refresh = button("Refresh now", v -> {
            clear("Picking…", "Reading the history and the library.");
            Refresh.async(this, () -> runOnUiThread(this::showStatus));
        });
        button("Change server, key or person", v -> findServer());
        refresh.requestFocus();
    }

    // ---- small UI helpers ---------------------------------------------------------------------------

    private void clear(String title, String hint) {
        // The keyboard of a field on the last step would stay over the next one.
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(page.getWindowToken(), 0);
        page.removeAllViews();
        text(title, 40, Color.WHITE);
        text(hint, 20, 0xB3FFFFFF);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(8), 0, dp(8));
        page.addView(t);
        return t;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(720), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        page.addView(b, lp);
        return b;
    }

    private EditText field(String start, int type) {
        EditText e = new EditText(this);
        e.setText(start);
        e.setSelection(start.length());
        e.setInputType(type);
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        e.setTextColor(Color.WHITE);
        page.addView(e, new LinearLayout.LayoutParams(dp(720), LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
