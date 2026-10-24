package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.LibrarySettings;

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
        final LibrarySettings settings = LibrarySettings.instance(context);
        final String mineTitle = context.getString(R.string.playlist_library_mine);
        final Set<String> seen = new HashSet<>();

        // 1) Saved by the user (local, instant, independent of the server): playlists and channels in separate shelves
        List<MediaItem> savedPlaylists = new ArrayList<>();
        List<MediaItem> savedChannels = new ArrayList<>();
        for (MediaItem item : LocalPlaylistStore.getAll(context)) {
            if (item.getPlaylistId() != null) {
                if (seen.add(item.getPlaylistId())) {
                    savedPlaylists.add(item);
                }
            } else if (item.getChannelId() != null) {
                if (seen.add("c:" + item.getChannelId())) {
                    savedChannels.add(item);
                }
            }
        }
        for (MediaItem item : LocalPlaylistStore.getPinned(context)) {
            if (seen.add(item.getPlaylistId())) {
                savedPlaylists.add(item);
            }
        }

        List<MediaGroup> localShelves = new ArrayList<>();
        if (settings.isSavedPlaylistsShown() && !savedPlaylists.isEmpty()) {
            localShelves.add(new LibraryGroup(null, title(settings, context.getString(R.string.playlist_library_saved), savedPlaylists.size()), savedPlaylists));
        }
        if (settings.isSavedChannelsShown() && !savedChannels.isEmpty()) {
            localShelves.add(new LibraryGroup(null, title(settings, context.getString(R.string.playlist_library_channels), savedChannels.size()), savedChannels));
        }
        Observable<List<MediaGroup>> local = localShelves.isEmpty() ? Observable.<List<MediaGroup>>empty() :
                Observable.<List<MediaGroup>>just(localShelves);

        // 2) Account playlists (created + saved on YouTube). Hidden by default.
        Observable<List<MediaGroup>> mine = !settings.isMyPlaylistsShown() ? Observable.<List<MediaGroup>>empty() :
                contentService.getPlaylistsObserve()
                .map(group -> {
                    List<MediaItem> items = playlistsOnly(group.getMediaItems(), seen);
                    if (settings.isSystemListsHidden()) {
                        List<MediaItem> filtered = new ArrayList<>();
                        for (MediaItem item : items) {
                            String id = item.getPlaylistId();
                            if (id == null || !(id.equals("WL") || id.equals("LL") || id.equals("HL"))) {
                                filtered.add(item);
                            }
                        }
                        items = filtered;
                    }
                    if (items.isEmpty()) {
                        return Collections.<MediaGroup>emptyList();
                    }
                    return Collections.<MediaGroup>singletonList(new LibraryGroup(group, title(settings, mineTitle, items.size()), items));
                })
                .onErrorResumeNext(Observable.<List<MediaGroup>>empty());

        // 3) One shelf per saved channel (Netflix-like)
        Observable<List<MediaGroup>> savedChannelShelves = !settings.isSavedChannelShelvesShown() ? Observable.<List<MediaGroup>>empty() :
                Observable.fromIterable(savedChannels)
                .concatMap(channel -> channelShelfObserve(settings, contentService, channel, seen));

        // 4) One shelf per subscribed channel
        Observable<List<MediaGroup>> fromChannels = !settings.isSubscribedShelvesShown() ? Observable.<List<MediaGroup>>empty() :
                contentService.getSubscribedChannelsByLastViewedObserve()
                .flatMap(channels -> {
                    List<MediaItem> list = channels.getMediaItems();
                    List<MediaItem> limited = new ArrayList<MediaItem>();
                    if (list != null) {
                        limited.addAll(list.subList(0, Math.min(settings.getMaxSubscribedChannels(), list.size())));
                    }
                    return Observable.<MediaItem>fromIterable(limited);
                })
                .concatMap(channel -> channelShelfObserve(settings, contentService, channel, seen))
                .onErrorResumeNext(Observable.<List<MediaGroup>>empty());

        // Fast shelves first, slow network shelves after
        if (settings.getOrder() == LibrarySettings.ORDER_ACCOUNT_FIRST) {
            return Observable.concat(mine, local, savedChannelShelves, fromChannels);
        }
        return Observable.concat(local, mine, savedChannelShelves, fromChannels);
    }

    private static String title(LibrarySettings settings, String name, int count) {
        return settings.isCountShown() ? name + " (" + count + ")" : name;
    }

    private static Observable<List<MediaGroup>> channelShelfObserve(LibrarySettings settings, ContentService contentService, MediaItem channel, Set<String> seen) {
        return contentService.getChannelObserve(channel)
                .map(groups -> channelShelf(settings, channel, groups, seen))
                .onErrorResumeNext(Observable.<List<MediaGroup>>empty());
    }

    private static List<MediaGroup> channelShelf(LibrarySettings settings, MediaItem channel, List<MediaGroup> groups, Set<String> seen) {
        final int mode = settings.getChannelContent();
        final int limit = settings.getItemsPerShelf();
        final String name = channel.getTitle() != null ? channel.getTitle() : "";

        if (groups == null) {
            return Collections.emptyList();
        }

        // Playlists published by the channel
        if (mode != LibrarySettings.CHANNEL_CONTENT_VIDEOS) {
            List<MediaItem> all = new ArrayList<>();
            MediaGroup first = null;

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

            if (!all.isEmpty() && first != null) {
                List<MediaItem> limited = new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
                return Collections.<MediaGroup>singletonList(new LibraryGroup(first, title(settings, name, limited.size()), limited));
            }

            if (mode == LibrarySettings.CHANNEL_CONTENT_PLAYLISTS) {
                return Collections.emptyList();
            }
        }

        // Latest content of the channel (first non-empty shelf)
        for (MediaGroup group : groups) {
            if (group != null && group.getMediaItems() != null && !group.getMediaItems().isEmpty()) {
                List<MediaItem> items = group.getMediaItems();
                List<MediaItem> limited = new ArrayList<>(items.subList(0, Math.min(limit, items.size())));
                return Collections.<MediaGroup>singletonList(new LibraryGroup(group, title(settings, name, limited.size()), limited));
            }
        }

        return Collections.emptyList();
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
