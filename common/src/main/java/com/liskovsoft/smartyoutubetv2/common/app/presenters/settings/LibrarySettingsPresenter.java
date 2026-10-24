package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.LibraryCollections;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.PlaylistLibraryService;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.prefs.LibrarySettings;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

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
        if (mSettings.isKidsMode() && mSettings.getPin() != null) {
            SimpleEditDialog.showPassword(getContext(), str(R.string.library_kids_enter_pin), null, value -> {
                if (mSettings.getPin().equals(value)) {
                    showSettings();
                } else {
                    MessageHelpers.showMessage(getContext(), str(R.string.library_kids_wrong_pin));
                }
                return true;
            });
            return;
        }

        showSettings();
    }

    private interface IntSetter {
        void set(int value);
    }

    private void appendNumberCategory(AppDialogPresenter dialog, int titleResId, int[] presets, int current, IntSetter setter) {
        List<OptionItem> options = new ArrayList<>();
        boolean isPreset = false;
        for (int n : presets) {
            if (n == current) {
                isPreset = true;
            }
            options.add(UiOptionItem.from(String.valueOf(n), o -> setter.set(n), n == current));
        }

        String customTitle = isPreset ? str(R.string.library_custom)
                : String.format(str(R.string.library_custom_current), current);
        options.add(UiOptionItem.from(customTitle, o -> {
            dialog.closeDialog();
            SimpleEditDialog.show(getContext(), str(titleResId), str(R.string.library_custom_prompt),
                    String.valueOf(current), value -> {
                        int number = parsePositive(value);
                        if (number <= 0) {
                            MessageHelpers.showMessage(getContext(), str(R.string.library_custom_invalid));
                            return false;
                        }
                        setter.set(number);
                        showSettings();
                        return true;
                    });
        }, !isPreset));

        dialog.appendRadioCategory(str(titleResId), options);
    }

    private static int parsePositive(String value) {
        if (value == null) {
            return -1;
        }
        try {
            long n = Long.parseLong(value.trim());
            return n <= 0 ? -1 : (int) Math.min(n, 1_000_000L);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void showSettings() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());

        // 1) Which shelves to show
        List<OptionItem> shelves = new ArrayList<>();
        shelves.add(UiOptionItem.from(str(R.string.library_opt_saved_playlists), o -> mSettings.setSavedPlaylistsShown(o.isSelected()), mSettings.isSavedPlaylistsShown()));
        shelves.add(UiOptionItem.from(str(R.string.library_opt_saved_channels), o -> mSettings.setSavedChannelsShown(o.isSelected()), mSettings.isSavedChannelsShown()));
        shelves.add(UiOptionItem.from(str(R.string.library_opt_mine), o -> mSettings.setMyPlaylistsShown(o.isSelected()), mSettings.isMyPlaylistsShown()));
        shelves.add(UiOptionItem.from(str(R.string.library_opt_saved_channel_shelves), o -> mSettings.setSavedChannelShelvesShown(o.isSelected()), mSettings.isSavedChannelShelvesShown()));
        shelves.add(UiOptionItem.from(str(R.string.library_opt_subscribed_shelves), o -> mSettings.setSubscribedShelvesShown(o.isSelected()), mSettings.isSubscribedShelvesShown()));
        shelves.add(0, UiOptionItem.from(str(R.string.library_opt_continue), o -> mSettings.setContinueShown(o.isSelected()), mSettings.isContinueShown()));
        shelves.add(0, UiOptionItem.from(str(R.string.library_opt_collections), o -> mSettings.setCollectionsShown(o.isSelected()), mSettings.isCollectionsShown()));
        shelves.add(0, UiOptionItem.from(str(R.string.library_opt_latest), o -> mSettings.setLatestShown(o.isSelected()), mSettings.isLatestShown()));
        dialog.appendCheckedCategory(str(R.string.library_cat_shelves), shelves);

        // 2) Details
        List<OptionItem> details = new ArrayList<>();
        details.add(UiOptionItem.from(str(R.string.library_opt_hide_system), o -> mSettings.setSystemListsHidden(o.isSelected()), mSettings.isSystemListsHidden()));
        details.add(UiOptionItem.from(str(R.string.library_opt_show_count), o -> mSettings.setCountShown(o.isSelected()), mSettings.isCountShown()));
        details.add(UiOptionItem.from(str(R.string.library_opt_icons), o -> mSettings.setIconsShown(o.isSelected()), mSettings.isIconsShown()));
        SidebarService sidebar = SidebarService.instance(getContext());
        details.add(UiOptionItem.from(str(R.string.library_opt_boot),
                o -> sidebar.setBootSectionId(o.isSelected() ? PlaylistLibraryService.SECTION_ID : MediaGroup.TYPE_HOME),
                sidebar.getBootSectionId() == PlaylistLibraryService.SECTION_ID));
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

        // 5) Numbers (presets + custom value)
        appendNumberCategory(dialog, R.string.library_cat_max_channels, new int[] {3, 5, 10, 15, 25},
                mSettings.getMaxSubscribedChannels(), mSettings::setMaxSubscribedChannels);
        appendNumberCategory(dialog, R.string.library_cat_items_per_shelf, new int[] {10, 20, 30, 50},
                mSettings.getItemsPerShelf(), mSettings::setItemsPerShelf);
        appendNumberCategory(dialog, R.string.library_cat_latest_total, new int[] {15, 30, 50, 80},
                mSettings.getLatestTotal(), mSettings::setLatestTotal);
        appendNumberCategory(dialog, R.string.library_cat_latest_per_source, new int[] {2, 3, 5, 8},
                mSettings.getLatestPerSource(), mSettings::setLatestPerSource);

        // 6) Collections
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_manage), o -> {
            dialog.closeDialog();
            LibraryCollectionsPresenter.instance(getContext()).showManage();
        }));

        // 7) Show / hide individual rows
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.library_rows_manage), o -> {
            dialog.closeDialog();
            LibraryRowsPresenter.instance(getContext()).show();
        }));

        // 8) Kids mode
        dialog.appendSingleButton(UiOptionItem.from(
                str(mSettings.isKidsMode() ? R.string.library_kids_disable : R.string.library_kids_enable),
                o -> {
                    dialog.closeDialog();
                    toggleKidsMode();
                }));

        // 9) Reset
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.library_reset), o -> {
            mSettings.reset();
            MessageHelpers.showMessage(getContext(), str(R.string.library_reset_done));
            dialog.closeDialog();
        }));

        dialog.showDialog(str(R.string.library_settings_title), () -> MessageHelpers.showMessage(getContext(), str(R.string.library_reopen_hint)));
    }

    private void toggleKidsMode() {
        if (mSettings.isKidsMode()) {
            SimpleEditDialog.showPassword(getContext(), str(R.string.library_kids_enter_pin), null, value -> {
                if (mSettings.getPin() == null || mSettings.getPin().equals(value)) {
                    mSettings.setKidsMode(false);
                    MessageHelpers.showMessage(getContext(), str(R.string.library_kids_off));
                } else {
                    MessageHelpers.showMessage(getContext(), str(R.string.library_kids_wrong_pin));
                }
                return true;
            });
            return;
        }

        boolean hasKidsCollection = false;
        for (String name : LibraryCollections.names(getContext())) {
            if (LibraryCollections.isKids(getContext(), name)) {
                hasKidsCollection = true;
            }
        }
        if (!hasKidsCollection) {
            MessageHelpers.showLongMessage(getContext(), str(R.string.library_kids_no_collection));
            return;
        }

        SimpleEditDialog.showPassword(getContext(), str(R.string.library_kids_set_pin), null, value -> {
            if (value == null || value.trim().isEmpty()) {
                return false;
            }
            mSettings.setPin(value.trim());
            mSettings.setKidsMode(true);
            MessageHelpers.showMessage(getContext(), str(R.string.library_kids_on));
            return true;
        });
    }

    private String str(int id) {
        return getContext().getString(id);
    }
}
