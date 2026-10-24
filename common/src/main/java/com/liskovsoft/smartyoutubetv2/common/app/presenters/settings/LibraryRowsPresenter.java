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

        dialog.showDialog(str(R.string.library_rows_manage), () -> MessageHelpers.showMessage(getContext(), str(R.string.library_rows_hint)));
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

        options.add(rowOption(name, key));
    }

    private OptionItem rowOption(String title, String key) {
        return UiOptionItem.from(title, o -> mSettings.setRowHidden(key, !o.isSelected()), !mSettings.isRowHidden(key));
    }

    private String str(int id) {
        return getContext().getString(id);
    }
}
