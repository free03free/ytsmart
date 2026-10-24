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
    public static final String MARK_OPEN = "lib_collection:";
    public static final String MARK_BACK = "lib_collection_back";

    private static String sOpenCollection; // collection currently opened inside the library row (memory only)

    public static synchronized String getOpen() {
        return sOpenCollection;
    }

    public static synchronized void setOpen(String name) {
        sOpenCollection = name;
        sOpenSearch = null;
    }

    private static String sOpenSearch; // search query shown inside the library row (memory only)

    public static final String MARK_SEARCH = "lib_search";
    public static final String MARK_REFRESH = "lib_refresh";
    public static final int SORT_MANUAL = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_NEWEST = 2;

    public static synchronized String getOpenSearch() {
        return sOpenSearch;
    }

    public static synchronized void setOpenSearch(String query) {
        sOpenSearch = query;
        sOpenCollection = null;
    }

    public static synchronized boolean hasOpen() {
        return sOpenCollection != null || sOpenSearch != null;
    }

    /** One step back: sub-collection -> parent, collection/search -> root. */
    public static synchronized void back(Context context) {
        if (sOpenCollection != null) {
            String parent = getParent(context, sOpenCollection);
            sOpenCollection = parent;
        }
        sOpenSearch = null;
    }

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
                    for (int k = 0; k < array.length(); k++) {
                        JSONObject child = array.getJSONObject(k);
                        if (oldName.equals(child.optString("parent", null))) {
                            child.put("parent", newName);
                        }
                    }
                    write(context, array);
                    if (oldName.equals(sOpenCollection)) {
                        sOpenCollection = newName;
                    }
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
                JSONObject o = array.getJSONObject(i);
                if (!name.equals(o.getString("name"))) {
                    if (name.equals(o.optString("parent", null))) {
                        o.remove("parent"); // children move to the top level
                    }
                    result.put(o);
                }
            }
            write(context, result);
            if (name.equals(sOpenCollection)) {
                sOpenCollection = null;
            }
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

    /** Items of one collection in the collection's sort mode (manual / by name / newest first). */
    public static synchronized List<MediaItem> items(Context context, String name) {
        return itemsInternal(context, name, true);
    }

    private static List<MediaItem> itemsInternal(Context context, String name, boolean sorted) {
        List<MediaItem> result = new ArrayList<>();

        try {
            JSONArray array = read(context);
            JSONObject obj = find(array, name);
            if (obj != null) {
                JSONArray items = obj.getJSONArray("items");
                for (int j = 0; j < items.length(); j++) {
                    Video video = Video.fromString(items.getString(j));
                    if (video != null) {
                        result.add(SimpleMediaItem.from(video));
                    }
                }
                if (sorted) {
                    int sort = obj.optInt("sort", SORT_MANUAL);
                    if (sort == SORT_NAME) {
                        java.util.Collections.sort(result, (x, y) -> {
                            String a1 = x.getTitle() != null ? x.getTitle() : "";
                            String b1 = y.getTitle() != null ? y.getTitle() : "";
                            return a1.compareToIgnoreCase(b1);
                        });
                    } else if (sort == SORT_NEWEST) {
                        java.util.Collections.reverse(result);
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


    // ---------- extra per-collection properties ----------

    private static JSONObject find(JSONArray array, String name) throws Exception {
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (name.equals(obj.getString("name"))) {
                return obj;
            }
        }
        return null;
    }

    private static void putProp(Context context, String name, String key, Object value) {
        try {
            JSONArray array = read(context);
            JSONObject obj = find(array, name);
            if (obj != null) {
                if (value == null) {
                    obj.remove(key);
                } else {
                    obj.put(key, value);
                }
                write(context, array);
            }
        } catch (Exception e) {
            // NOP
        }
    }

    private static JSONObject propsOf(Context context, String name) {
        try {
            JSONObject obj = find(read(context), name);
            return obj != null ? obj : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static synchronized boolean exists(Context context, String name) {
        return names(context).contains(name);
    }

    public static synchronized String getParent(Context context, String name) {
        String parent = propsOf(context, name).optString("parent", null);
        return parent != null && !parent.isEmpty() && names(context).contains(parent) ? parent : null;
    }

    /** Collections directly inside parent (null = top level). */
    public static synchronized List<String> children(Context context, String parent) {
        List<String> result = new ArrayList<>();
        for (String name : names(context)) {
            String p = getParent(context, name);
            if (parent == null ? p == null : parent.equals(p)) {
                result.add(name);
            }
        }
        return result;
    }

    /** True if candidate is name itself or lies somewhere inside it. */
    public static synchronized boolean isInside(Context context, String candidate, String name) {
        String current = candidate;
        int guard = 0;
        while (current != null && guard++ < 50) {
            if (current.equals(name)) {
                return true;
            }
            current = getParent(context, current);
        }
        return false;
    }

    public static synchronized boolean setParent(Context context, String name, String parent) {
        if (parent != null && isInside(context, parent, name)) {
            return false; // would create a cycle
        }
        putProp(context, name, "parent", parent);
        return true;
    }

    public static synchronized int getSort(Context context, String name) {
        return propsOf(context, name).optInt("sort", SORT_MANUAL);
    }

    public static synchronized void setSort(Context context, String name, int sort) {
        putProp(context, name, "sort", sort);
    }

    public static synchronized boolean isPinned(Context context, String name) {
        return propsOf(context, name).optBoolean("pinned", false);
    }

    public static synchronized void setPinned(Context context, String name, boolean pinned) {
        putProp(context, name, "pinned", pinned);
    }

    public static synchronized String getIcon(Context context, String name) {
        String icon = propsOf(context, name).optString("icon", "");
        return icon.isEmpty() ? "\uD83D\uDCC2" : icon;
    }

    public static synchronized void setIcon(Context context, String name, String icon) {
        putProp(context, name, "icon", icon == null || icon.trim().isEmpty() ? null : icon.trim());
    }

    public static synchronized String getCover(Context context, String name) {
        String cover = propsOf(context, name).optString("cover", "");
        return cover.isEmpty() ? null : cover;
    }

    public static synchronized void setCover(Context context, String name, String url) {
        putProp(context, name, "cover", url);
    }

    // ---------- items: order, move, remove ----------

    /** Items in the order they are stored (manual order), ignoring the sort mode. */
    public static synchronized List<MediaItem> itemsRaw(Context context, String name) {
        return itemsInternal(context, name, false);
    }

    public static synchronized void moveItem(Context context, String name, int index, int delta) {
        try {
            JSONArray array = read(context);
            JSONObject obj = find(array, name);
            if (obj == null) {
                return;
            }
            JSONArray items = obj.getJSONArray("items");
            int target = index + delta;
            if (index < 0 || index >= items.length() || target < 0 || target >= items.length()) {
                return;
            }
            List<String> list = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                list.add(items.getString(i));
            }
            String moved = list.remove(index);
            list.add(target, moved);
            obj.put("items", new JSONArray(list));
            write(context, array);
        } catch (Exception e) {
            // NOP
        }
    }

    public static synchronized void removeAt(Context context, String name, int index) {
        try {
            JSONArray array = read(context);
            JSONObject obj = find(array, name);
            if (obj == null) {
                return;
            }
            JSONArray items = obj.getJSONArray("items");
            List<String> list = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                if (i != index) {
                    list.add(items.getString(i));
                }
            }
            obj.put("items", new JSONArray(list));
            write(context, array);
        } catch (Exception e) {
            // NOP
        }
    }

    /** Move one stored item from one collection to another (duplicates are dropped). */
    public static synchronized void moveItemTo(Context context, String from, int index, String to) {
        try {
            JSONArray array = read(context);
            JSONObject src = find(array, from);
            JSONObject dst = find(array, to);
            if (src == null || dst == null || from.equals(to)) {
                return;
            }
            JSONArray srcItems = src.getJSONArray("items");
            if (index < 0 || index >= srcItems.length()) {
                return;
            }
            String moved = srcItems.getString(index);
            Video video = Video.fromString(moved);
            String key = video != null ? LocalPlaylistStore.keyOf(video) : null;

            boolean duplicate = false;
            JSONArray dstItems = dst.getJSONArray("items");
            for (int i = 0; key != null && i < dstItems.length(); i++) {
                Video old = Video.fromString(dstItems.getString(i));
                if (old != null && key.equals(LocalPlaylistStore.keyOf(old))) {
                    duplicate = true;
                }
            }
            if (!duplicate) {
                dstItems.put(moved);
            }

            List<String> rest = new ArrayList<>();
            for (int i = 0; i < srcItems.length(); i++) {
                if (i != index) {
                    rest.add(srcItems.getString(i));
                }
            }
            src.put("items", new JSONArray(rest));
            write(context, array);
        } catch (Exception e) {
            // NOP
        }
    }

    /** Case-insensitive title search over every collection and the saved playlists/channels. */
    public static synchronized List<MediaItem> search(Context context, String query) {
        List<MediaItem> result = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return result;
        }
        String q = query.trim().toLowerCase();
        java.util.Set<String> seen = new java.util.HashSet<>();

        List<MediaItem> all = new ArrayList<>();
        for (String name : names(context)) {
            all.addAll(itemsInternal(context, name, false));
        }
        all.addAll(LocalPlaylistStore.getAll(context));
        all.addAll(LocalPlaylistStore.getPinned(context));

        for (MediaItem item : all) {
            String title = item.getTitle() != null ? item.getTitle() : "";
            String author = item.getAuthor() != null ? item.getAuthor() : "";
            if (title.toLowerCase().contains(q) || author.toLowerCase().contains(q)) {
                String key = item.getPlaylistId() != null ? "p:" + item.getPlaylistId() :
                        item.getChannelId() != null ? "c:" + item.getChannelId() : "t:" + title;
                if (seen.add(key)) {
                    result.add(item);
                }
            }
        }
        return result;
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
