package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import android.content.SharedPreferences;

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

    public void reset() {
        String local = mPrefs.getString("local_items_v2", "[]"); // keep the saved items, reset only the settings
        boolean added = mPrefs.getBoolean("added", false);
        mPrefs.edit().clear().putString("local_items_v2", local).putBoolean("added", added).apply();
    }
}
