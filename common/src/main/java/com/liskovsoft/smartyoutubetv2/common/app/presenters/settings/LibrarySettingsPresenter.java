package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.LibrarySettings;

import java.util.ArrayList;
import java.util.List;

public class LibrarySettingsPresenter extends BasePresenter<Void> {
    private final LibrarySettings mSettings;

    private LibrarySettingsPresenter(Context context) {
        super(context);
        mSettings = LibrarySettings.instance(context);
    }

    public static LibrarySettingsPresenter instance(Context context) {
        return new LibrarySettingsPresenter(context);
    }

    public void show() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());

        // 1) Which shelves to show
        List<OptionItem> shelves = new ArrayList<>();
        shelves.add(UiOptionItem.from(str(R.string.library_opt_saved_playlists), o -> mSettings.setSavedPlaylistsShown(o.isSelected()), mSettings.isSavedPlaylistsShown()));
        shelves.add(UiOptionItem.from(str(R.string.library_opt_saved_channels), o -> mSettings.setSavedChannelsShown(o.isSelected()), mSettings.isSavedChannelsShown()));
        shelves.add(UiOptionItem.from(str(R.string.library_opt_mine), o -> mSettings.setMyPlaylistsShown(o.isSelected()), mSettings.isMyPlaylistsShown()));
        shelves.add(UiOptionItem.from(str(R.string.library_opt_saved_channel_shelves), o -> mSettings.setSavedChannelShelvesShown(o.isSelected()), mSettings.isSavedChannelShelvesShown()));
        shelves.add(UiOptionItem.from(str(R.string.library_opt_subscribed_shelves), o -> mSettings.setSubscribedShelvesShown(o.isSelected()), mSettings.isSubscribedShelvesShown()));
        dialog.appendCheckedCategory(str(R.string.library_cat_shelves), shelves);

        // 2) Details
        List<OptionItem> details = new ArrayList<>();
        details.add(UiOptionItem.from(str(R.string.library_opt_hide_system), o -> mSettings.setSystemListsHidden(o.isSelected()), mSettings.isSystemListsHidden()));
        details.add(UiOptionItem.from(str(R.string.library_opt_show_count), o -> mSettings.setCountShown(o.isSelected()), mSettings.isCountShown()));
        dialog.appendCheckedCategory(str(R.string.library_cat_details), details);

        // 3) Order
        List<OptionItem> order = new ArrayList<>();
        order.add(UiOptionItem.from(str(R.string.library_order_saved_first), o -> mSettings.setOrder(LibrarySettings.ORDER_SAVED_FIRST), mSettings.getOrder() == LibrarySettings.ORDER_SAVED_FIRST));
        order.add(UiOptionItem.from(str(R.string.library_order_account_first), o -> mSettings.setOrder(LibrarySettings.ORDER_ACCOUNT_FIRST), mSettings.getOrder() == LibrarySettings.ORDER_ACCOUNT_FIRST));
        dialog.appendRadioCategory(str(R.string.library_cat_order), order);

        // 4) Channel shelf content
        List<OptionItem> content = new ArrayList<>();
        content.add(UiOptionItem.from(str(R.string.library_content_playlists), o -> mSettings.setChannelContent(LibrarySettings.CHANNEL_CONTENT_PLAYLISTS), mSettings.getChannelContent() == LibrarySettings.CHANNEL_CONTENT_PLAYLISTS));
        content.add(UiOptionItem.from(str(R.string.library_content_playlists_or_videos), o -> mSettings.setChannelContent(LibrarySettings.CHANNEL_CONTENT_PLAYLISTS_OR_VIDEOS), mSettings.getChannelContent() == LibrarySettings.CHANNEL_CONTENT_PLAYLISTS_OR_VIDEOS));
        content.add(UiOptionItem.from(str(R.string.library_content_videos), o -> mSettings.setChannelContent(LibrarySettings.CHANNEL_CONTENT_VIDEOS), mSettings.getChannelContent() == LibrarySettings.CHANNEL_CONTENT_VIDEOS));
        dialog.appendRadioCategory(str(R.string.library_cat_channel_content), content);

        // 5) Numbers
        List<OptionItem> channels = new ArrayList<>();
        for (int n : new int[] {3, 5, 10, 15, 25}) {
            channels.add(UiOptionItem.from(String.valueOf(n), o -> mSettings.setMaxSubscribedChannels(n), mSettings.getMaxSubscribedChannels() == n));
        }
        dialog.appendRadioCategory(str(R.string.library_cat_max_channels), channels);

        List<OptionItem> items = new ArrayList<>();
        for (int n : new int[] {10, 20, 30, 50}) {
            items.add(UiOptionItem.from(String.valueOf(n), o -> mSettings.setItemsPerShelf(n), mSettings.getItemsPerShelf() == n));
        }
        dialog.appendRadioCategory(str(R.string.library_cat_items_per_shelf), items);

        // 6) Reset
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.library_reset), o -> {
            mSettings.reset();
            MessageHelpers.showMessage(getContext(), str(R.string.library_reset_done));
            dialog.closeDialog();
        }));

        dialog.showDialog(str(R.string.library_settings_title), () -> MessageHelpers.showMessage(getContext(), str(R.string.library_reopen_hint)));
    }

    private String str(int id) {
        return getContext().getString(id);
    }
}
