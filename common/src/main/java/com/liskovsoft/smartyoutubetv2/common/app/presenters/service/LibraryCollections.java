package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * User-made collections of playlists/channels, e.g. "Cartoons", "Movies", "Series".
 * Stored locally. Every item is the card itself (Video.toString), so it opens like the original card.
 */
public class LibraryCollections {
    private static final String PREFS = "playlist_library";
    private static final String KEY = "collections_v1";

    public static synchronized List<String> names(Context context) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                result.add(array.getJSONObject(i).getString("name"));
            }
        } catch (Exception e) {
            // NOP
        }
        return result;
    }

    public static synchronized boolean create(Context context, String name) {
        name = clean(name);
        if (name.isEmpty()) {
            return false;
        }

        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                if (name.equalsIgnoreCase(array.getJSONObject(i).getString("name"))) {
                    return true; // already exists
                }
            }
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("items", new JSONArray());
            array.put(obj);
            write(context, array);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized boolean rename(Context context, String oldName, String newName) {
        newName = clean(newName);
        if (newName.isEmpty()) {
            return false;
        }

        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (!oldName.equals(obj.getString("name")) && newName.equalsIgnoreCase(obj.getString("name"))) {
                    return false; // name taken
                }
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (oldName.equals(obj.getString("name"))) {
                    obj.put("name", newName);
                    write(context, array);
                    return true;
                }
            }
        } catch (Exception e) {
            // NOP
        }
        return false;
    }

    public static synchronized void delete(Context context, String name) {
        try {
            JSONArray array = read(context);
            JSONArray result = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                if (!name.equals(array.getJSONObject(i).getString("name"))) {
                    result.put(array.getJSONObject(i));
                }
            }
            write(context, result);
        } catch (Exception e) {
            // NOP
        }
    }

    public static synchronized boolean contains(Context context, String name, Video video) {
        String key = LocalPlaylistStore.keyOf(video);
        if (key == null) {
            return false;
        }

        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (name.equals(obj.getString("name"))) {
                    JSONArray items = obj.getJSONArray("items");
                    for (int j = 0; j < items.length(); j++) {
                        Video old = Video.fromString(items.getString(j));
                        if (old != null && key.equals(LocalPlaylistStore.keyOf(old))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // NOP
        }
        return false;
    }

    public static synchronized void add(Context context, String name, Video video) {
        if (video == null || contains(context, name, video)) {
            return;
        }

        try {
            Video copy = Video.from(video);
            if (copy.playlistId == null && video.mediaItem != null) {
                copy.playlistId = video.mediaItem.getPlaylistId();
            }

            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (name.equals(obj.getString("name"))) {
                    obj.getJSONArray("items").put(copy.toString());
                    write(context, array);
                    return;
                }
            }
        } catch (Exception e) {
            // NOP
        }
    }

    public static synchronized void remove(Context context, String name, Video video) {
        String key = LocalPlaylistStore.keyOf(video);
        if (key == null) {
            return;
        }

        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (name.equals(obj.getString("name"))) {
                    JSONArray items = obj.getJSONArray("items");
                    JSONArray result = new JSONArray();
                    for (int j = 0; j < items.length(); j++) {
                        Video old = Video.fromString(items.getString(j));
                        if (old != null && !key.equals(LocalPlaylistStore.keyOf(old))) {
                            result.put(items.getString(j));
                        }
                    }
                    obj.put("items", result);
                    write(context, array);
                    return;
                }
            }
        } catch (Exception e) {
            // NOP
        }
    }

    /** Items of one collection, oldest first (the order the user added them). */
    public static synchronized List<MediaItem> items(Context context, String name) {
        List<MediaItem> result = new ArrayList<>();

        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (name.equals(obj.getString("name"))) {
                    JSONArray items = obj.getJSONArray("items");
                    for (int j = 0; j < items.length(); j++) {
                        Video video = Video.fromString(items.getString(j));
                        if (video != null) {
                            result.add(SimpleMediaItem.from(video));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // NOP
        }

        return result;
    }

    public static synchronized boolean isKids(Context context, String name) {
        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (name.equals(obj.getString("name"))) {
                    return obj.optBoolean("kids", false);
                }
            }
        } catch (Exception e) {
            // NOP
        }
        return false;
    }

    public static synchronized void setKids(Context context, String name, boolean kids) {
        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (name.equals(obj.getString("name"))) {
                    obj.put("kids", kids);
                    write(context, array);
                    return;
                }
            }
        } catch (Exception e) {
            // NOP
        }
    }

    /** Move a collection up (delta = -1) or down (delta = 1) in the library. */
    public static synchronized void move(Context context, String name, int delta) {
        try {
            JSONArray array = read(context);
            List<JSONObject> list = new ArrayList<>();
            int index = -1;
            for (int i = 0; i < array.length(); i++) {
                list.add(array.getJSONObject(i));
                if (name.equals(array.getJSONObject(i).getString("name"))) {
                    index = i;
                }
            }
            int target = index + delta;
            if (index < 0 || target < 0 || target >= list.size()) {
                return;
            }
            JSONObject moved = list.remove(index);
            list.add(target, moved);
            JSONArray result = new JSONArray();
            for (JSONObject obj : list) {
                result.put(obj);
            }
            write(context, result);
        } catch (Exception e) {
            // NOP
        }
    }

    private static String clean(String name) {
        return name == null ? "" : name.trim();
    }

    private static JSONArray read(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(Context context, JSONArray array) {
        prefs(context).edit().putString(KEY, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
