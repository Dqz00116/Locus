package dev.locus.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Stores numbered split arrangements and their opened directories. */
final class FavoriteLayouts {
    private static final String KEY = "layouts";
    private final SharedPreferences preferences;
    private final List<Entry> entries = new ArrayList<>();
    private String stored;
    private int nextId;

    static final class Entry {
        final int id;
        final String snapshot;
        final int paneCount;
        final List<Directory> directories;

        private Entry(int id, String snapshot, int paneCount, List<Directory> directories) {
            this.id = id;
            this.snapshot = snapshot;
            this.paneCount = paneCount;
            this.directories = directories;
        }
    }

    static final class Directory {
        final String endpoint;
        final String username;
        final String shell;
        final String browser;

        Directory(String endpoint, String username, String shell, String browser) {
            this.endpoint = endpoint;
            this.username = username;
            this.shell = shell;
            this.browser = browser;
        }

        boolean matchesConnection(String endpoint, String username) {
            return this.endpoint.equals(endpoint) && this.username.equals(username);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Directory)) return false;
            Directory folder = (Directory) other;
            return matchesConnection(folder.endpoint, folder.username)
                    && shell.equals(folder.shell) && Objects.equals(browser, folder.browser);
        }

        @Override
        public int hashCode() {
            return Objects.hash(endpoint, username, shell, browser);
        }
    }

    FavoriteLayouts(Context context) {
        preferences = context.getSharedPreferences("favorite_layouts", Context.MODE_PRIVATE);
        refresh();
    }

    List<Entry> entries() {
        refresh();
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    boolean add(String snapshot, List<Directory> directories) {
        refresh();
        PaneDock.Layout layout = PaneDock.parseLayout(snapshot);
        if (layout == null || layout.paneCount() < 2 || directories.size() != layout.paneCount()) return false;
        List<Directory> state = normalizeDirectories(directories);
        if (contains(snapshot, state)) return false;
        entries.add(new Entry(nextId++, snapshot, layout.paneCount(), state));
        save();
        return true;
    }

    void remove(Entry entry) {
        refresh();
        if (entries.removeIf(saved -> saved.id == entry.id && saved.snapshot.equals(entry.snapshot))) save();
    }

    private void refresh() {
        String current = preferences.getString(KEY, "{}");
        if (current.equals(stored)) return;
        stored = current;
        entries.clear();
        nextId = 1;
        try {
            JSONObject root = new JSONObject(current);
            nextId = Math.max(1, root.optInt("nextId", 1));
            JSONArray saved = root.optJSONArray("entries");
            if (saved == null) return;
            for (int index = 0; index < saved.length(); index++) {
                JSONObject item = saved.optJSONObject(index);
                if (item == null) continue;
                int id = item.optInt("id");
                String snapshot = item.optString("snapshot");
                PaneDock.Layout layout = PaneDock.parseLayout(snapshot);
                if (id <= 0 || layout == null || layout.paneCount() < 2) continue;
                List<Directory> directories = readDirectories(item.optJSONArray("directories"), layout.paneCount());
                if (contains(snapshot, directories)) continue;
                entries.add(new Entry(id, snapshot, layout.paneCount(), directories));
                nextId = Math.max(nextId, id + 1);
            }
        } catch (JSONException ignored) {
            // A malformed value should not prevent the workspace from opening.
        }
    }

    private static List<Directory> readDirectories(JSONArray stored, int paneCount) {
        if (stored == null || stored.length() != paneCount) return Collections.emptyList();
        List<Directory> directories = new ArrayList<>(paneCount);
        for (int index = 0; index < paneCount; index++) {
            JSONObject folder = stored.optJSONObject(index);
            if (folder == null) {
                directories.add(null);
                continue;
            }
            String endpoint = folder.optString("endpoint");
            String username = folder.optString("username");
            String shell = folder.optString("shell");
            String browser = folder.optString("browser");
            directories.add(endpoint.isEmpty() || username.isEmpty() || shell.isEmpty() ? null
                    : new Directory(endpoint, username, shell, browser.isEmpty() ? null : browser));
        }
        return normalizeDirectories(directories);
    }

    private static List<Directory> normalizeDirectories(List<Directory> directories) {
        for (Directory directory : directories) {
            if (directory != null) return Collections.unmodifiableList(new ArrayList<>(directories));
        }
        return Collections.emptyList();
    }

    private boolean contains(String snapshot, List<Directory> directories) {
        for (Entry entry : entries) {
            if (entry.snapshot.equals(snapshot) && entry.directories.equals(directories)) return true;
        }
        return false;
    }

    private void save() {
        try {
            JSONArray saved = new JSONArray();
            for (Entry entry : entries) {
                JSONObject item = new JSONObject().put("id", entry.id).put("snapshot", entry.snapshot);
                if (!entry.directories.isEmpty()) {
                    JSONArray directories = new JSONArray();
                    for (Directory folder : entry.directories) {
                        directories.put(folder == null ? JSONObject.NULL : new JSONObject()
                                .put("endpoint", folder.endpoint).put("username", folder.username)
                                .put("shell", folder.shell).put("browser", folder.browser));
                    }
                    item.put("directories", directories);
                }
                saved.put(item);
            }
            stored = new JSONObject().put("nextId", nextId).put("entries", saved).toString();
            preferences.edit().putString(KEY, stored).apply();
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
