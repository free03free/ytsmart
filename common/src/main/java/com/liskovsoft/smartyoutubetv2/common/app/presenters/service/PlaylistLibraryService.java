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

    /** A channel/playlist whose content is loaded from the network. */
    private static class Source {
        final MediaItem item;
        final boolean showShelf;
        List<MediaGroup> groups;

        Source(MediaItem item, boolean showShelf) {
            this.item = item;
            this.showShelf = showShelf;
        }
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
        if (!settings.isKidsMode() && settings.isSavedPlaylistsShown() && !savedPlaylists.isEmpty()) {
            localShelves.add(new LibraryGroup(null, title(settings, "\u2B50 ", context.getString(R.string.playlist_library_saved), savedPlaylists.size()), savedPlaylists));
        }
        if (!settings.isKidsMode() && settings.isSavedChannelsShown() && !savedChannels.isEmpty()) {
            localShelves.add(new LibraryGroup(null, title(settings, "\uD83D\uDCFA ", context.getString(R.string.playlist_library_channels), savedChannels.size()), savedChannels));
        }
        String openName = LibraryCollections.getOpen();
        if (openName != null && !LibraryCollections.exists(context, openName)) {
            LibraryCollections.setOpen(null);
            openName = null;
        }
        String openSearch = LibraryCollections.getOpenSearch();

        if (openSearch != null && !settings.isKidsMode()) {
            // Search results inside the library row
            List<MediaItem> found = LibraryCollections.search(context, openSearch);
            List<MediaItem> row = new ArrayList<>();
            row.add(collectionCard(LibraryCollections.MARK_BACK, context.getString(R.string.collection_back), null, null));
            row.addAll(found);
            List<MediaGroup> results = new ArrayList<>();
            String label = found.isEmpty() ? openSearch + " - " + context.getString(R.string.collection_search_empty) : openSearch;
            results.add(new LibraryGroup(null, title(settings, "\uD83D\uDD0D ", label, found.size()), row));
            return Observable.<List<MediaGroup>>just(results);
        }

        if (openName != null) {
            // Opened collection: back card + sub-collections + its playlists/channels in one row
            List<MediaItem> row = new ArrayList<>();
            row.add(collectionCard(LibraryCollections.MARK_BACK, context.getString(R.string.collection_back), null, null));
            for (String child : LibraryCollections.children(context, openName)) {
                if (settings.isKidsMode() && !LibraryCollections.isKids(context, child)) {
                    continue;
                }
                row.add(collectionCardFor(context, child));
            }
            List<MediaItem> items = LibraryCollections.items(context, openName);
            row.addAll(items);
            List<MediaGroup> opened = new ArrayList<>();
            opened.add(new LibraryGroup(null, title(settings, LibraryCollections.getIcon(context, openName) + " ", openName, row.size() - 1), row));
            return Observable.<List<MediaGroup>>just(opened);
        }

        if (settings.isCollectionsShown() || settings.isKidsMode()) {
            // One row "My collections": a card per top-level collection, side by side. Pinned first.
            List<MediaItem> cards = new ArrayList<>();
            List<MediaItem> pinnedCards = new ArrayList<>();
            for (String name : LibraryCollections.children(context, null)) {
                if (settings.isKidsMode() && !LibraryCollections.isKids(context, name)) {
                    continue; // kids mode: only collections marked "for kids"
                }
                if (settings.isRowHidden(LibrarySettings.collectionKey(name))) {
                    continue;
                }
                (LibraryCollections.isPinned(context, name) ? pinnedCards : cards).add(collectionCardFor(context, name));
            }
            List<MediaItem> all = new ArrayList<>(pinnedCards);
            all.addAll(cards);
            if (!all.isEmpty()) {
                if (!settings.isKidsMode()) {
                    all.add(0, collectionCard(LibraryCollections.MARK_SEARCH, context.getString(R.string.collection_search), null, null));
                }
                LibraryGroup group = new LibraryGroup(null, title(settings, "\uD83D\uDCC2 ", context.getString(R.string.collection_my), all.size() - (settings.isKidsMode() ? 0 : 1)), all);
                if (!pinnedCards.isEmpty()) {
                    localShelves.add(0, group); // pinned collections: the row goes to the very top
                } else {
                    localShelves.add(group);
                }
            }
        }
        if (settings.isKidsMode()) {
            return localShelves.isEmpty() ? Observable.<List<MediaGroup>>empty() : Observable.<List<MediaGroup>>just(localShelves);
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
                    return Collections.<MediaGroup>singletonList(new LibraryGroup(group, title(settings, "\uD83D\uDCC1 ", mineTitle, items.size()), items));
                })
                .onErrorResumeNext(Observable.<List<MediaGroup>>empty());

        // 3) Network part: load every source ONCE, then build "latest videos" + one shelf per source from the same data
        final boolean wantLatest = settings.isLatestShown();
        final boolean wantSavedShelves = settings.isSavedChannelShelvesShown();

        List<Source> saved = new ArrayList<>();
        for (MediaItem item : savedChannels) {
            saved.add(new Source(item, wantSavedShelves));
        }
        if (wantLatest) { // saved playlists only feed the "latest" shelf
            for (MediaItem item : savedPlaylists) {
                saved.add(new Source(item, false));
            }
        }

        final boolean wantSubscribed = settings.isSubscribedShelvesShown() || wantLatest;
        Observable<Source> subscribed = !wantSubscribed ? Observable.<Source>empty() :
                contentService.getSubscribedChannelsByLastViewedObserve()
                .flatMap(channels -> {
                    List<MediaItem> list = channels.getMediaItems();
                    List<Source> limited = new ArrayList<>();
                    if (list != null) {
                        for (MediaItem item : list.subList(0, Math.min(settings.getMaxSubscribedChannels(), list.size()))) {
                            limited.add(new Source(item, settings.isSubscribedShelvesShown()));
                        }
                    }
                    return Observable.<Source>fromIterable(limited);
                })
                .onErrorResumeNext(Observable.<Source>empty());

        Observable<List<MediaGroup>> network = Observable.concat(Observable.fromIterable(saved), subscribed)
                .concatMap(source -> contentService.getChannelObserve(source.item)
                        .map(groups -> {
                            source.groups = groups;
                            return source;
                        })
                        .onErrorResumeNext(Observable.<Source>empty()))
                .toList()
                .toObservable()
                .map(sources -> networkShelves(settings, context, sources, seen));

        // "Continue watching": videos started but not finished (from the watch history)
        Observable<List<MediaGroup>> keepWatching = !settings.isContinueShown() ? Observable.<List<MediaGroup>>empty() :
                contentService.getHistoryObserve()
                .map(group -> {
                    List<MediaItem> items = new ArrayList<>();
                    if (group != null && group.getMediaItems() != null) {
                        for (MediaItem item : group.getMediaItems()) {
                            if (item != null && item.getVideoId() != null && item.getPercentWatched() >= 3 && item.getPercentWatched() < 95) {
                                items.add(item);
                                if (items.size() >= settings.getItemsPerShelf()) {
                                    break;
                                }
                            }
                        }
                    }
                    if (items.isEmpty()) {
                        return Collections.<MediaGroup>emptyList();
                    }
                    return Collections.<MediaGroup>singletonList(new LibraryGroup(group, title(settings, "\u25B6\uFE0F ", context.getString(R.string.playlist_library_continue), items.size()), items));
                })
                .onErrorResumeNext(Observable.<List<MediaGroup>>empty());

        // Fast shelves first, slow network shelves after
        if (settings.getOrder() == LibrarySettings.ORDER_ACCOUNT_FIRST) {
            return Observable.concat(mine, local, keepWatching, network);
        }
        return Observable.concat(local, keepWatching, mine, network);
    }

    private static List<MediaGroup> networkShelves(LibrarySettings settings, Context context, List<Source> sources, Set<String> seen) {
        List<MediaGroup> result = new ArrayList<>();
        Set<String> channelsDone = new HashSet<>();

        // Newest videos of everything the user follows, mixed into one shelf (round robin between sources)
        if (settings.isLatestShown()) {
            List<List<MediaItem>> perSource = new ArrayList<>();
            for (Source source : sources) {
                List<MediaItem> videos = firstVideos(source.groups, settings.getLatestPerSource());
                if (!videos.isEmpty()) {
                    perSource.add(videos);
                }
            }

            List<MediaItem> mixed = new ArrayList<>();
            Set<String> videoIds = new HashSet<>();
            for (int i = 0; i < settings.getLatestPerSource() && mixed.size() < settings.getLatestTotal(); i++) {
                for (List<MediaItem> videos : perSource) {
                    if (i < videos.size() && mixed.size() < settings.getLatestTotal() && videoIds.add(videos.get(i).getVideoId())) {
                        mixed.add(videos.get(i));
                    }
                }
            }

            if (!mixed.isEmpty()) {
                result.add(new LibraryGroup(null, title(settings, "\uD83D\uDD25 ", context.getString(R.string.playlist_library_latest), mixed.size()), mixed));
            }
        }

        // One shelf per source (Netflix-like)
        for (Source source : sources) {
            String rowKey = LibrarySettings.rowKey(source.item);
            settings.cacheName(rowKey, nameOf(context, source.item, source.groups)); // so the "rows" manager can show real names
            if (!source.showShelf || settings.isRowHidden(rowKey)) {
                continue;
            }
            String key = source.item.getChannelId() != null ? source.item.getChannelId() : source.item.getTitle();
            if (key != null && !channelsDone.add(key)) {
                continue; // same channel saved and subscribed
            }
            // "New" badge: the newest video differs from the one seen when the library was opened last time
            boolean isNew = false;
            List<MediaItem> top = firstVideos(source.groups, 1);
            if (!top.isEmpty() && top.get(0).getVideoId() != null) {
                String seenId = settings.getSeenTop(rowKey);
                isNew = seenId != null && !seenId.equals(top.get(0).getVideoId());
                settings.setSeenTop(rowKey, top.get(0).getVideoId());
            }

            result.addAll(channelShelf(settings, context, source.item, source.groups, seen, isNew));
        }

        return result;
    }

    /** Videos (not playlists/channels) of the first non-empty shelf. */
    private static List<MediaItem> firstVideos(List<MediaGroup> groups, int limit) {
        List<MediaItem> result = new ArrayList<>();

        if (groups == null) {
            return result;
        }

        for (MediaGroup group : groups) {
            if (group == null || group.getMediaItems() == null) {
                continue;
            }
            for (MediaItem item : group.getMediaItems()) {
                if (item != null && item.getVideoId() != null && item.getType() != MediaItem.TYPE_PLAYLIST) {
                    result.add(item);
                    if (result.size() >= limit) {
                        return result;
                    }
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        return result;
    }

    private static MediaItem collectionCard(String marker, String title, String subtitle, String cover) {
        com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video = new com.liskovsoft.smartyoutubetv2.common.app.models.data.Video();
        video.title = title;
        video.secondTitle = subtitle;
        video.cardImageUrl = cover;
        video.reloadPageKey = marker;
        return com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem.from(video);
    }

    private static MediaItem collectionCardFor(Context context, String name) {
        List<MediaItem> items = LibraryCollections.items(context, name);
        String cover = LibraryCollections.getCover(context, name);
        for (MediaItem item : items) {
            if (cover == null && item.getCardImageUrl() != null) {
                cover = item.getCardImageUrl();
            }
        }
        int subs = LibraryCollections.children(context, name).size();
        String subtitle = String.format(context.getString(R.string.collection_items_count), items.size());
        if (subs > 0) {
            subtitle += "  \u2022  " + String.format(context.getString(R.string.collection_sub_count), subs);
        }
        return collectionCard(LibraryCollections.MARK_OPEN + name,
                LibraryCollections.getIcon(context, name) + " " + name, subtitle, cover);
    }

    private static String title(LibrarySettings settings, String icon, String name, int count) {
        String text = settings.isIconsShown() ? icon + name : name;
        return settings.isCountShown() ? text + "  \u2022  " + count : text;
    }

    /** Subscription items often have no title: fall back to the author, then to the first video's author. */
    private static String nameOf(Context context, MediaItem channel, List<MediaGroup> groups) {
        if (channel.getTitle() != null && !channel.getTitle().trim().isEmpty()) {
            return channel.getTitle();
        }
        if (channel.getAuthor() != null && !channel.getAuthor().trim().isEmpty()) {
            return channel.getAuthor();
        }
        if (groups != null) {
            for (MediaGroup group : groups) {
                if (group == null || group.getMediaItems() == null) {
                    continue;
                }
                for (MediaItem item : group.getMediaItems()) {
                    if (item != null && item.getAuthor() != null && !item.getAuthor().trim().isEmpty()) {
                        return item.getAuthor();
                    }
                }
            }
        }
        return context.getString(R.string.playlist_library_channel_fallback);
    }

    private static List<MediaGroup> channelShelf(LibrarySettings settings, Context context, MediaItem channel, List<MediaGroup> groups, Set<String> seen, boolean isNew) {
        final int mode = settings.getChannelContent();
        final int limit = settings.getItemsPerShelf();
        final String name = (isNew ? "\uD83C\uDD95 " : "") + nameOf(context, channel, groups);

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
                return Collections.<MediaGroup>singletonList(new LibraryGroup(first, title(settings, "\uD83C\uDFAC ", name, limited.size()), limited));
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
                return Collections.<MediaGroup>singletonList(new LibraryGroup(group, title(settings, "\uD83C\uDFAC ", name, limited.size()), limited));
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
