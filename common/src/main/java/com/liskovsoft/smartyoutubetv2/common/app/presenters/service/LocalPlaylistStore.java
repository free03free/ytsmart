package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Local library: playlists and channels the user added with "Add to library".
 * Each entry is the card itself (Video.toString), so it opens exactly like the original card.
 * Needs no account and no network.
 */
public class LocalPlaylistStore {
    private static final String PREFS = "playlist_library";
    private static final String KEY = "local_items_v2";

    /** Stable identity of a card. */
    public static String keyOf(Video v) {
        if (v == null) {
            return null;
        }
        if (v.playlistId != null) {
            return "p:" + v.playlistId;
        }
        if (v.mediaItem != null && v.mediaItem.getPlaylistId() != null) {
            return "p:" + v.mediaItem.getPlaylistId();
        }
        if (v.channelId != null && v.videoId == null) {
            return "c:" + v.channelId;
        }
        if (v.reloadPageKey != null) {
            return "r:" + v.reloadPageKey;
        }
        return "t:" + v.title;
    }

    public static synchronized boolean add(Context context, Video video) {
        if (context == null || video == null) {
            return false;
        }

        try {
            Video copy = Video.from(video);
            if (copy.playlistId == null && video.mediaItem != null) {
                copy.playlistId = video.mediaItem.getPlaylistId();
            }
            String key = keyOf(copy);
            JSONArray array = read(context);

            for (int i = 0; i < array.length(); i++) {
                Video old = Video.fromString(array.getString(i));
                if (old != null && key.equals(keyOf(old))) {
                    return true; // already there
                }
            }

            array.put(copy.toString());
            write(context, array);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized boolean contains(Context context, Video video) {
        String key = keyOf(video);
        if (context == null || key == null) {
            return false;
        }

        try {
            JSONArray array = read(context);
            for (int i = 0; i < array.length(); i++) {
                Video old = Video.fromString(array.getString(i));
                if (old != null && key.equals(keyOf(old))) {
                    return true;
                }
            }
        } catch (Exception e) {
            // NOP
        }

        return false;
    }

    public static synchronized void remove(Context context, Video video) {
        String key = keyOf(video);
        if (context == null || key == null) {
            return;
        }

        try {
            JSONArray array = read(context);
            JSONArray result = new JSONArray();

            for (int i = 0; i < array.length(); i++) {
                Video old = Video.fromString(array.getString(i));
                if (old != null && !key.equals(keyOf(old))) {
                    result.put(array.getString(i));
                }
            }

            write(context, result);
        } catch (Exception e) {
            // NOP
        }
    }

    /** Newest first. Unlike server shelves, these items are never filtered by type. */
    public static synchronized List<MediaItem> getAll(Context context) {
        List<MediaItem> result = new ArrayList<>();

        if (context == null) {
            return result;
        }

        try {
            JSONArray array = read(context);

            for (int i = array.length() - 1; i >= 0; i--) {
                Video video = Video.fromString(array.getString(i));
                if (video != null) {
                    result.add(SimpleMediaItem.from(video));
                }
            }
        } catch (Exception e) {
            // NOP
        }

        return result;
    }

    /** Playlists pinned to the sidebar earlier also count as saved. */
    public static List<MediaItem> getPinned(Context context) {
        List<MediaItem> result = new ArrayList<>();

        try {
            for (Video pinned : SidebarService.instance(context).getPinnedItems()) {
                if (pinned != null && pinned.playlistId != null && pinned.videoId == null) {
                    Video copy = Video.from(pinned);
                    copy.itemType = MediaItem.TYPE_PLAYLIST;
                    result.add(SimpleMediaItem.from(copy));
                }
            }
        } catch (Exception e) {
            // NOP
        }

        return result;
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
