package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.LibraryCollections;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.LocalPlaylistStore;
import com.liskovsoft.smartyoutubetv2.common.prefs.LibrarySettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/** Tick the rows you want to see in the library; untick to hide a row without deleting anything. */
public class LibraryRowsPresenter extends BasePresenter<Void> {
    private final LibrarySettings mSettings;
    /** key -> display name of every channel row we know about (filled while the dialog is built) */
    private final java.util.Map<String, String> mRowNames = new java.util.LinkedHashMap<>();

    private LibraryRowsPresenter(Context context) {
        super(context);
        mSettings = LibrarySettings.instance(context);
    }

    public static LibraryRowsPresenter instance(Context context) {
        return new LibraryRowsPresenter(context);
    }

    public void show() {
        MessageHelpers.showMessage(getContext(), str(R.string.library_rows_loading));

        final List<MediaItem> subscribed = new ArrayList<>();
        ContentService contentService = getContentService();

        if (contentService == null) {
            showDialog(subscribed);
            return;
        }

        contentService.getSubscribedChannelsByLastViewedObserve()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        group -> {
                            if (group != null && group.getMediaItems() != null) {
                                subscribed.addAll(group.getMediaItems());
                            }
                        },
                        error -> showDialog(subscribed),
                        () -> showDialog(subscribed));
    }

    private void showDialog(List<MediaItem> subscribed) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        dialog.appendSingleButton(UiOptionItem.from("\uD83D\uDD00 " + str(R.string.library_rows_reorder), o -> {
            dialog.closeDialog();
            showReorder();
        }));

        // my collections
        List<OptionItem> collections = new ArrayList<>();
        for (String name : LibraryCollections.names(getContext())) {
            collections.add(rowOption("\uD83D\uDCC2 " + name, LibrarySettings.collectionKey(name)));
        }
        if (!collections.isEmpty()) {
            dialog.appendCheckedCategory(str(R.string.library_rows_collections), collections);
        }

        Set<String> seen = new HashSet<>();

        // saved channels
        List<OptionItem> saved = new ArrayList<>();
        for (MediaItem item : LocalPlaylistStore.getAll(getContext())) {
            if (item.getPlaylistId() == null && item.getChannelId() != null) {
                addChannelOption(saved, item, seen);
            }
        }
        if (!saved.isEmpty()) {
            dialog.appendCheckedCategory(str(R.string.library_rows_saved_channels), saved);
        }

        // subscribed channels
        List<OptionItem> subs = new ArrayList<>();
        for (MediaItem item : subscribed) {
            addChannelOption(subs, item, seen);
        }
        if (!subs.isEmpty()) {
            dialog.appendCheckedCategory(str(R.string.library_rows_subscribed), subs);
        }

        LibraryRefresh.cancel();
        dialog.showDialog(str(R.string.library_rows_manage), () -> LibraryRefresh.schedule(getContext()));
    }

    private void addChannelOption(List<OptionItem> options, MediaItem item, Set<String> seen) {
        String key = LibrarySettings.rowKey(item);
        if (key == null || !seen.add(key)) {
            return;
        }

        String name = item.getTitle();
        if (name == null || name.trim().isEmpty()) {
            name = item.getAuthor();
        }
        if (name == null || name.trim().isEmpty()) {
            name = mSettings.getCachedName(key); // learned the last time the library was opened
        }
        if (name == null || name.trim().isEmpty()) {
            return; // no way to label it yet; it appears after the library is opened once
        }

        mRowNames.put(key, name);
        options.add(rowOption(name, key));
    }

    private static final String[] SYSTEM_KEYS = {"sys:saved", "sys:channels", "sys:collections", "sys:continue", "sys:mine", "sys:latest"};

    private String systemTitle(String key) {
        switch (key) {
            case "sys:saved": return "\u2B50 " + str(R.string.playlist_library_saved);
            case "sys:channels": return "\uD83D\uDCFA " + str(R.string.playlist_library_channels);
            case "sys:collections": return "\uD83D\uDCC2 " + str(R.string.collection_my);
            case "sys:continue": return "\u25B6\uFE0F " + str(R.string.playlist_library_continue);
            case "sys:mine": return "\uD83D\uDCC1 " + str(R.string.playlist_library_mine);
            case "sys:latest": return "\uD83D\uDD25 " + str(R.string.playlist_library_latest);
            default: return key;
        }
    }

    private String rowTitle(String key) {
        return key.startsWith("sys:") ? systemTitle(key) : "\uD83C\uDFAC " + mRowNames.get(key);
    }

    /** Saved custom order merged with every known row (new rows go to the end). */
    private List<String> currentOrder() {
        List<String> known = new ArrayList<>();
        for (String key : SYSTEM_KEYS) {
            known.add(key);
        }
        known.addAll(mRowNames.keySet());

        List<String> result = new ArrayList<>();
        for (String key : mSettings.getShelfOrderList()) {
            if (known.contains(key) && !result.contains(key)) {
                result.add(key);
            }
        }
        for (String key : known) {
            if (!result.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    private void showReorder() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        List<String> order = currentOrder();
        for (int i = 0; i < order.size(); i++) {
            final String key = order.get(i);
            dialog.appendSingleButton(UiOptionItem.from((i + 1) + ". " + rowTitle(key), o -> showMoveRow(key)));
        }
        dialog.appendSingleButton(UiOptionItem.from("\u267B\uFE0F " + str(R.string.library_rows_reorder_reset), o -> {
            mSettings.setShelfOrderList(null);
            showReorder();
        }));
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.library_reorder_done), o -> {
            dialog.closeDialog();
        }));

        LibraryRefresh.cancel();
        dialog.showDialog("\uD83D\uDD00 " + str(R.string.library_rows_reorder), () -> LibraryRefresh.schedule(getContext()));
    }

    private void showMoveRow(String key) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        dialog.appendSingleButton(UiOptionItem.from("\u2B06\uFE0F " + str(R.string.collection_move_up), o -> {
            moveRow(key, -1);
            showReorder();
        }));
        dialog.appendSingleButton(UiOptionItem.from("\u2B07\uFE0F " + str(R.string.collection_move_down), o -> {
            moveRow(key, 1);
            showReorder();
        }));

        LibraryRefresh.cancel();
        dialog.showDialog(rowTitle(key), () -> LibraryRefresh.schedule(getContext()));
    }

    private void moveRow(String key, int delta) {
        List<String> order = currentOrder();
        int index = order.indexOf(key);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= order.size()) {
            return;
        }
        order.remove(index);
        order.add(target, key);
        mSettings.setShelfOrderList(order);
    }

    private OptionItem rowOption(String title, String key) {
        return UiOptionItem.from(title, o -> mSettings.setRowHidden(key, !o.isSelected()), !mSettings.isRowHidden(key));
    }

    private String str(int id) {
        return getContext().getString(id);
    }
}
