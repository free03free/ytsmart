package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;

import org.json.JSONArray;
import org.json.JSONObject;

/** User settings of the "Playlist library" section. Read on every refresh, so changes apply next time the section opens. */
public class LibrarySettings {
    public static final int CHANNEL_CONTENT_PLAYLISTS = 0;       // playlists only
    public static final int CHANNEL_CONTENT_PLAYLISTS_OR_VIDEOS = 1; // playlists, else latest videos
    public static final int CHANNEL_CONTENT_VIDEOS = 2;          // latest videos only

    public static final int ORDER_SAVED_FIRST = 0;
    public static final int ORDER_ACCOUNT_FIRST = 1;

    private static final String PREFS = "playlist_library";
    private final SharedPreferences mPrefs;

    private LibrarySettings(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static LibrarySettings instance(Context context) {
        return new LibrarySettings(context);
    }

    public boolean isSavedPlaylistsShown() { return mPrefs.getBoolean("show_saved_playlists", true); }
    public void setSavedPlaylistsShown(boolean v) { mPrefs.edit().putBoolean("show_saved_playlists", v).apply(); }

    public boolean isSavedChannelsShown() { return mPrefs.getBoolean("show_saved_channels", true); }
    public void setSavedChannelsShown(boolean v) { mPrefs.edit().putBoolean("show_saved_channels", v).apply(); }

    /** "My playlists" (account). Hidden by default: most users only need the saved ones. */
    public boolean isMyPlaylistsShown() { return mPrefs.getBoolean("show_mine", false); }
    public void setMyPlaylistsShown(boolean v) { mPrefs.edit().putBoolean("show_mine", v).apply(); }

    public boolean isSystemListsHidden() { return mPrefs.getBoolean("hide_system", true); }
    public void setSystemListsHidden(boolean v) { mPrefs.edit().putBoolean("hide_system", v).apply(); }

    public boolean isSavedChannelShelvesShown() { return mPrefs.getBoolean("show_saved_channel_shelves", true); }
    public void setSavedChannelShelvesShown(boolean v) { mPrefs.edit().putBoolean("show_saved_channel_shelves", v).apply(); }

    public boolean isSubscribedShelvesShown() { return mPrefs.getBoolean("show_subscribed_shelves", true); }
    public void setSubscribedShelvesShown(boolean v) { mPrefs.edit().putBoolean("show_subscribed_shelves", v).apply(); }

    public int getMaxSubscribedChannels() { return mPrefs.getInt("max_channels", 10); }
    public void setMaxSubscribedChannels(int v) { mPrefs.edit().putInt("max_channels", v).apply(); }

    public int getItemsPerShelf() { return mPrefs.getInt("items_per_shelf", 20); }
    public void setItemsPerShelf(int v) { mPrefs.edit().putInt("items_per_shelf", v).apply(); }

    public int getChannelContent() { return mPrefs.getInt("channel_content", CHANNEL_CONTENT_PLAYLISTS_OR_VIDEOS); }
    public void setChannelContent(int v) { mPrefs.edit().putInt("channel_content", v).apply(); }

    public int getOrder() { return mPrefs.getInt("order", ORDER_SAVED_FIRST); }
    public void setOrder(int v) { mPrefs.edit().putInt("order", v).apply(); }

    public boolean isCountShown() { return mPrefs.getBoolean("show_count", true); }
    public void setCountShown(boolean v) { mPrefs.edit().putBoolean("show_count", v).apply(); }

    /** Combined shelf with the newest videos of every saved/subscribed source. */
    public boolean isLatestShown() { return mPrefs.getBoolean("show_latest", true); }
    public void setLatestShown(boolean v) { mPrefs.edit().putBoolean("show_latest", v).apply(); }

    public int getLatestTotal() { return mPrefs.getInt("latest_total", 30); }
    public void setLatestTotal(int v) { mPrefs.edit().putInt("latest_total", v).apply(); }

    public int getLatestPerSource() { return mPrefs.getInt("latest_per_source", 5); }
    public void setLatestPerSource(int v) { mPrefs.edit().putInt("latest_per_source", v).apply(); }

    public boolean isIconsShown() { return mPrefs.getBoolean("show_icons", true); }
    public void setIconsShown(boolean v) { mPrefs.edit().putBoolean("show_icons", v).apply(); }

    /** Stable key of a channel/playlist, used for hiding rows and remembering names. */
    public static String rowKey(MediaItem item) {
        if (item == null) {
            return null;
        }
        if (item.getPlaylistId() != null) {
            return "p:" + item.getPlaylistId();
        }
        if (item.getChannelId() != null) {
            return "c:" + item.getChannelId();
        }
        return item.getTitle() != null ? "t:" + item.getTitle() : null;
    }

    public static String collectionKey(String name) {
        return "col:" + name;
    }

    // ----- hidden rows -----
    public boolean isRowHidden(String key) {
        if (key == null) {
            return false;
        }
        try {
            JSONArray array = new JSONArray(mPrefs.getString("hidden_rows", "[]"));
            for (int i = 0; i < array.length(); i++) {
                if (key.equals(array.getString(i))) {
                    return true;
                }
            }
        } catch (Exception e) {
            // NOP
        }
        return false;
    }

    public void setRowHidden(String key, boolean hidden) {
        if (key == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray(mPrefs.getString("hidden_rows", "[]"));
            JSONArray result = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                if (!key.equals(array.getString(i))) {
                    result.put(array.getString(i));
                }
            }
            if (hidden) {
                result.put(key);
            }
            mPrefs.edit().putString("hidden_rows", result.toString()).apply();
        } catch (Exception e) {
            // NOP
        }
    }

    // ----- remembered names (subscription items often come without a title) -----
    public void cacheName(String key, String name) {
        if (key == null || name == null || name.isEmpty()) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(mPrefs.getString("names_v1", "{}"));
            if (!name.equals(obj.optString(key))) {
                obj.put(key, name);
                mPrefs.edit().putString("names_v1", obj.toString()).apply();
            }
        } catch (Exception e) {
            // NOP
        }
    }

    public String getCachedName(String key) {
        try {
            String name = new JSONObject(mPrefs.getString("names_v1", "{}")).optString(key);
            return name == null || name.isEmpty() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    // ----- "new" badge: newest video id seen the last time the library was opened -----
    public String getSeenTop(String key) {
        try {
            String id = new JSONObject(mPrefs.getString("seen_top_v1", "{}")).optString(key);
            return id == null || id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    public void setSeenTop(String key, String videoId) {
        if (key == null || videoId == null) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(mPrefs.getString("seen_top_v1", "{}"));
            if (!videoId.equals(obj.optString(key))) {
                obj.put(key, videoId);
                mPrefs.edit().putString("seen_top_v1", obj.toString()).apply();
            }
        } catch (Exception e) {
            // NOP
        }
    }

    // ----- continue watching -----
    public boolean isContinueShown() { return mPrefs.getBoolean("show_continue", true); }
    public void setContinueShown(boolean v) { mPrefs.edit().putBoolean("show_continue", v).apply(); }

    // ----- kids mode (PIN protected) -----
    public boolean isKidsMode() { return mPrefs.getBoolean("kids_mode", false); }
    public void setKidsMode(boolean v) { mPrefs.edit().putBoolean("kids_mode", v).apply(); }

    public String getPin() { return mPrefs.getString("pin", null); }
    public void setPin(String pin) { mPrefs.edit().putString("pin", pin).apply(); }

    public boolean isCollectionsShown() { return mPrefs.getBoolean("show_collections", true); }
    public void setCollectionsShown(boolean v) { mPrefs.edit().putBoolean("show_collections", v).apply(); }

    /** Order of the sections on the library settings screen (ids; unknown/missing ids are merged by the caller). */
    public String getSettingsOrder() { return mPrefs.getString("settings_order", ""); }
    public void setSettingsOrder(String csv) { mPrefs.edit().putString("settings_order", csv).apply(); }

    public void reset() {
        String local = mPrefs.getString("local_items_v2", "[]"); // keep the saved items, reset only the settings
        boolean added = mPrefs.getBoolean("added", false);
        String collections = mPrefs.getString("collections_v1", "[]");
        String names = mPrefs.getString("names_v1", "{}");
        String seenTop = mPrefs.getString("seen_top_v1", "{}");
        boolean kids = mPrefs.getBoolean("kids_mode", false); // a reset must not switch kids mode off
        String pin = mPrefs.getString("pin", null);
        mPrefs.edit().clear().putString("local_items_v2", local).putString("collections_v1", collections)
                .putString("names_v1", names).putString("seen_top_v1", seenTop).putBoolean("kids_mode", kids).putString("pin", pin).putBoolean("added", added).apply();
    }
}
