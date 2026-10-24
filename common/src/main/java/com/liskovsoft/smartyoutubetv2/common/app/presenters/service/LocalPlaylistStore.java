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
 * Local list of subscribed (saved) playlists.
 * Works without any account/network call, so "save playlist" and the library never depend on the server answer.
 */
public class LocalPlaylistStore {
    private static final String PREFS = "playlist_library";
    private static final String KEY = "local_playlists";

    public static synchronized boolean add(Context context, Video video) {
        if (context == null || video == null || video.playlistId == null) {
            return false;
        }

        try {
            JSONArray array = read(context);

            for (int i = 0; i < array.length(); i++) {
                if (video.playlistId.equals(array.getJSONObject(i).optString("id"))) {
                    return true; // already saved
                }
            }

            JSONObject obj = new JSONObject();
            obj.put("id", video.playlistId);
            obj.put("title", video.title != null ? video.title : "");
            obj.put("image", video.cardImageUrl != null ? video.cardImageUrl : "");
            obj.put("author", video.author != null ? video.author : "");
            obj.put("channelId", video.channelId != null ? video.channelId : "");
            array.put(obj);
            write(context, array);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized boolean contains(Context context, String playlistId) {
        if (context == null || playlistId == null) {
            return false;
        }

        try {
            JSONArray array = read(context);

            for (int i = 0; i < array.length(); i++) {
                if (playlistId.equals(array.getJSONObject(i).optString("id"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            // NOP
        }

        return false;
    }

    public static synchronized void remove(Context context, String playlistId) {
        if (context == null || playlistId == null) {
            return;
        }

        try {
            JSONArray array = read(context);
            JSONArray result = new JSONArray();

            for (int i = 0; i < array.length(); i++) {
                if (!playlistId.equals(array.getJSONObject(i).optString("id"))) {
                    result.put(array.getJSONObject(i));
                }
            }

            write(context, result);
        } catch (Exception e) {
            // NOP
        }
    }

    public static synchronized List<MediaItem> getAll(Context context) {
        List<MediaItem> result = new ArrayList<>();

        if (context == null) {
            return result;
        }

        try {
            JSONArray array = read(context);

            for (int i = array.length() - 1; i >= 0; i--) { // newest first
                result.add(toItem(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            // NOP
        }

        return result;
    }

    /** Playlists the user pinned to the sidebar earlier. They count as subscriptions too. */
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

    private static MediaItem toItem(JSONObject obj) {
        Video video = new Video();
        video.itemType = MediaItem.TYPE_PLAYLIST;
        video.playlistId = obj.optString("id");
        video.title = obj.optString("title");
        video.cardImageUrl = emptyToNull(obj.optString("image"));
        video.author = emptyToNull(obj.optString("author"));
        video.channelId = null; // a playlist card must open as a playlist, not as a channel
        return SimpleMediaItem.from(video);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
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
