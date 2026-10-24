package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.Observable;

/**
 * "Playlist library" section: a Netflix-like set of horizontal shelves.
 * Shelf 1: playlists of the signed account (created + saved).
 * Next shelves: one shelf per subscribed channel that publishes playlists.
 */
public class PlaylistLibraryService {
    /** Section id. Must be less than SidebarService.RESERVED_ID (100) and not clash with MediaGroup.TYPE_*. */
    public static final int SECTION_ID = 50;
    private static final int MAX_CHANNELS = 15;

    public static Observable<List<MediaGroup>> getLibraryObserve(Context context, ContentService contentService) {
        // defer: every subscription (refresh) gets its own "seen" set, otherwise a refresh would filter everything out
        return Observable.defer(() -> buildLibrary(context, contentService));
    }

    private static Observable<List<MediaGroup>> buildLibrary(Context context, ContentService contentService) {
        final String mineTitle = context.getString(R.string.playlist_library_mine);
        final Set<String> seen = new HashSet<>();

        // 1) Locally saved + sidebar-pinned playlists: instant, no network, never empty because of a server error
        List<MediaItem> localItems = new ArrayList<>();
        for (MediaItem item : LocalPlaylistStore.getAll(context)) { // saved cards: any type (playlist or channel)
            if (seen.add(item.getPlaylistId() != null ? item.getPlaylistId() : item.getChannelId() + "|" + item.getTitle())) {
                localItems.add(item);
            }
        }
        for (MediaItem item : LocalPlaylistStore.getPinned(context)) {
            if (seen.add(item.getPlaylistId())) {
                localItems.add(item);
            }
        }
        Observable<List<MediaGroup>> local = localItems.isEmpty() ? Observable.<List<MediaGroup>>empty() :
                Observable.<List<MediaGroup>>just(Collections.<MediaGroup>singletonList(
                        new LibraryGroup(null, context.getString(R.string.playlist_library_saved) + " (" + localItems.size() + ")", localItems)));

        // 2) Account playlists (created + saved on YouTube). Errors must not kill the other shelves.
        Observable<List<MediaGroup>> mine = contentService.getPlaylistsObserve()
                .map(group -> {
                    List<MediaItem> items = playlistsOnly(group.getMediaItems(), seen);
                    if (items.isEmpty()) {
                        return Collections.<MediaGroup>emptyList();
                    }
                    return Collections.<MediaGroup>singletonList(new LibraryGroup(group, mineTitle + " (" + items.size() + ")", items));
                })
                .onErrorResumeNext(Observable.<List<MediaGroup>>empty());

        // 3) Playlists published by subscribed channels
        Observable<List<MediaGroup>> fromChannels = contentService.getSubscribedChannelsByLastViewedObserve()
                .flatMap(channels -> {
                    List<MediaItem> list = channels.getMediaItems();
                    List<MediaItem> limited = new ArrayList<MediaItem>();
                    if (list != null) {
                        limited.addAll(list.subList(0, Math.min(MAX_CHANNELS, list.size())));
                    }
                    return Observable.<MediaItem>fromIterable(limited);
                })
                .concatMap(channel -> contentService.getChannelObserve(channel)
                        .map(groups -> channelShelf(channel, groups, seen))
                        .onErrorResumeNext(Observable.<List<MediaGroup>>empty()))
                .onErrorResumeNext(Observable.<List<MediaGroup>>empty());

        return Observable.concat(local, mine, fromChannels);
    }

    private static List<MediaGroup> channelShelf(MediaItem channel, List<MediaGroup> groups, Set<String> seen) {
        List<MediaItem> all = new ArrayList<>();
        MediaGroup first = null;

        if (groups != null) {
            for (MediaGroup group : groups) {
                if (group == null || group.getMediaItems() == null) {
                    continue;
                }
                List<MediaItem> items = playlistsOnly(group.getMediaItems(), seen);
                if (!items.isEmpty()) {
                    all.addAll(items);
                    if (first == null) {
                        first = group;
                    }
                }
            }
        }

        if (all.isEmpty() || first == null) {
            return Collections.emptyList();
        }

        return Collections.<MediaGroup>singletonList(new LibraryGroup(first, channel.getTitle() + " (" + all.size() + ")", all));
    }

    private static List<MediaItem> playlistsOnly(List<MediaItem> items, Set<String> seen) {
        List<MediaItem> result = new ArrayList<>();

        if (items == null) {
            return result;
        }

        for (MediaItem item : items) {
            if (item == null || item.getType() != MediaItem.TYPE_PLAYLIST) {
                continue;
            }

            String key = item.getPlaylistId() != null ? item.getPlaylistId() : item.getParams() + "|" + item.getTitle();

            if (seen.add(key)) { // skip duplicates between shelves
                result.add(item);
            }
        }

        return result;
    }

    /** Read-only view of a group with a custom title and a filtered item list. No paging. */
    private static class LibraryGroup implements MediaGroup {
        private final MediaGroup mSource;
        private final String mTitle;
        private final List<MediaItem> mItems;

        LibraryGroup(MediaGroup source, String title, List<MediaItem> items) {
            mSource = source;
            mTitle = title;
            mItems = items;
        }

        @Override public int getType() { return mSource != null ? mSource.getType() : SECTION_ID; }
        @Override public List<MediaItem> getMediaItems() { return mItems; }
        @Override public String getTitle() { return mTitle; }
        @Override public String getChannelId() { return null; }
        @Override public String getParams() { return null; }
        @Override public String getReloadPageKey() { return null; }
        @Override public String getNextPageKey() { return null; }
        @Override public String getChannelUrl() { return null; }
        @Override public boolean isEmpty() { return mItems == null || mItems.isEmpty(); }
    }
}
