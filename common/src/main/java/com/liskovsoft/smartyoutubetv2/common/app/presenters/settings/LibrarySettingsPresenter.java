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

    private void appendNumberCategory(AppDialogPresenter dialog, int titleResId, String icon, int[] presets, int current, IntSetter setter) {
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

        dialog.appendRadioCategory(icon + str(titleResId), options);
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

    private static final String ICON_SHELVES = "\uD83D\uDDC2\uFE0F ";
    private static final String ICON_DETAILS = "\u2699\uFE0F ";
    private static final String ICON_ORDER = "\u2195\uFE0F ";
    private static final String ICON_CONTENT = "\uD83C\uDFAC ";
    private static final String ICON_MAX_CHANNELS = "\uD83D\uDCFA ";
    private static final String ICON_ITEMS = "\uD83D\uDD22 ";
    private static final String ICON_LATEST_TOTAL = "\uD83C\uDD95 ";
    private static final String ICON_LATEST_PER = "\uD83D\uDC64 ";
    private static final String ICON_COLLECTIONS = "\uD83D\uDCC2 ";
    private static final String ICON_ROWS = "\uD83D\uDC41\uFE0F ";
    private static final String ICON_KIDS = "\uD83E\uDDD2 ";
    private static final String ICON_RESET = "\u267B\uFE0F ";
    private static final String ICON_REORDER = "\uD83D\uDD00 ";

    private static final String[] DEFAULT_ORDER = {"shelves", "details", "order", "content", "max_channels",
            "items", "latest_total", "latest_per", "collections", "rows", "kids", "reset"};

    /** Title (with its icon) of a settings section, used by the reorder screen. */
    private String sectionTitle(String id) {
        switch (id) {
            case "shelves": return ICON_SHELVES + str(R.string.library_cat_shelves);
            case "details": return ICON_DETAILS + str(R.string.library_cat_details);
            case "order": return ICON_ORDER + str(R.string.library_cat_order);
            case "content": return ICON_CONTENT + str(R.string.library_cat_channel_content);
            case "max_channels": return ICON_MAX_CHANNELS + str(R.string.library_cat_max_channels);
            case "items": return ICON_ITEMS + str(R.string.library_cat_items_per_shelf);
            case "latest_total": return ICON_LATEST_TOTAL + str(R.string.library_cat_latest_total);
            case "latest_per": return ICON_LATEST_PER + str(R.string.library_cat_latest_per_source);
            case "collections": return ICON_COLLECTIONS + str(R.string.collection_manage);
            case "rows": return ICON_ROWS + str(R.string.library_rows_manage);
            case "kids": return ICON_KIDS + str(R.string.library_kids_title);
            case "reset": return ICON_RESET + str(R.string.library_reset);
            default: return id;
        }
    }

    /** Saved order merged with the defaults (new sections go to the end). */
    private List<String> sectionOrder() {
        List<String> result = new ArrayList<>();
        String saved = mSettings.getSettingsOrder();
        if (saved != null && !saved.isEmpty()) {
            for (String id : saved.split(",")) {
                if (java.util.Arrays.asList(DEFAULT_ORDER).contains(id) && !result.contains(id)) {
                    result.add(id);
                }
            }
        }
        for (String id : DEFAULT_ORDER) {
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private void moveSection(String id, int delta) {
        List<String> order = sectionOrder();
        int index = order.indexOf(id);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= order.size()) {
            return;
        }
        order.remove(index);
        order.add(target, id);
        mSettings.setSettingsOrder(android.text.TextUtils.join(",", order));
    }

    /** Reorder screen: tap a section, then move it up or down. */
    private void showReorder() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        List<String> order = sectionOrder();
        for (int i = 0; i < order.size(); i++) {
            final String id = order.get(i);
            dialog.appendSingleButton(UiOptionItem.from((i + 1) + ". " + sectionTitle(id), o -> showMoveSection(id)));
        }
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.library_reorder_done), o -> {
            dialog.closeDialog();
            showSettings();
        }));

        dialog.showDialog(ICON_REORDER + str(R.string.library_reorder));
    }

    private void showMoveSection(String id) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        dialog.appendSingleButton(UiOptionItem.from("\u2B06\uFE0F " + str(R.string.collection_move_up), o -> {
            moveSection(id, -1);
            showReorder();
        }));
        dialog.appendSingleButton(UiOptionItem.from("\u2B07\uFE0F " + str(R.string.collection_move_down), o -> {
            moveSection(id, 1);
            showReorder();
        }));

        dialog.showDialog(sectionTitle(id));
    }

    private void showSettings() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        java.util.Map<String, Runnable> sections = new java.util.HashMap<>();

        sections.put("shelves", () -> {
        List<OptionItem> shelves = new ArrayList<>();
        shelves.add(UiOptionItem.from("\u2B50 " + str(R.string.library_opt_saved_playlists), o -> mSettings.setSavedPlaylistsShown(o.isSelected()), mSettings.isSavedPlaylistsShown()));
        shelves.add(UiOptionItem.from("\uD83D\uDCFA " + str(R.string.library_opt_saved_channels), o -> mSettings.setSavedChannelsShown(o.isSelected()), mSettings.isSavedChannelsShown()));
        shelves.add(UiOptionItem.from("\uD83D\uDCC1 " + str(R.string.library_opt_mine), o -> mSettings.setMyPlaylistsShown(o.isSelected()), mSettings.isMyPlaylistsShown()));
        shelves.add(UiOptionItem.from("\uD83C\uDF9E\uFE0F " + str(R.string.library_opt_saved_channel_shelves), o -> mSettings.setSavedChannelShelvesShown(o.isSelected()), mSettings.isSavedChannelShelvesShown()));
        shelves.add(UiOptionItem.from("\uD83D\uDD14 " + str(R.string.library_opt_subscribed_shelves), o -> mSettings.setSubscribedShelvesShown(o.isSelected()), mSettings.isSubscribedShelvesShown()));
        shelves.add(0, UiOptionItem.from("\u25B6\uFE0F " + str(R.string.library_opt_continue), o -> mSettings.setContinueShown(o.isSelected()), mSettings.isContinueShown()));
        shelves.add(0, UiOptionItem.from("\uD83D\uDCC2 " + str(R.string.library_opt_collections), o -> mSettings.setCollectionsShown(o.isSelected()), mSettings.isCollectionsShown()));
        shelves.add(0, UiOptionItem.from("\uD83C\uDD95 " + str(R.string.library_opt_latest), o -> mSettings.setLatestShown(o.isSelected()), mSettings.isLatestShown()));
        dialog.appendCheckedCategory(ICON_SHELVES + str(R.string.library_cat_shelves), shelves);

        });

        sections.put("details", () -> {
        List<OptionItem> details = new ArrayList<>();
        details.add(UiOptionItem.from("\uD83D\uDE48 " + str(R.string.library_opt_hide_system), o -> mSettings.setSystemListsHidden(o.isSelected()), mSettings.isSystemListsHidden()));
        details.add(UiOptionItem.from("\uD83D\uDD22 " + str(R.string.library_opt_show_count), o -> mSettings.setCountShown(o.isSelected()), mSettings.isCountShown()));
        details.add(UiOptionItem.from("\uD83D\uDDBC\uFE0F " + str(R.string.library_opt_icons), o -> mSettings.setIconsShown(o.isSelected()), mSettings.isIconsShown()));
        SidebarService sidebar = SidebarService.instance(getContext());
        details.add(UiOptionItem.from("\uD83D\uDE80 " + str(R.string.library_opt_boot),
                o -> sidebar.setBootSectionId(o.isSelected() ? PlaylistLibraryService.SECTION_ID : MediaGroup.TYPE_HOME),
                sidebar.getBootSectionId() == PlaylistLibraryService.SECTION_ID));
        dialog.appendCheckedCategory(ICON_DETAILS + str(R.string.library_cat_details), details);

        });

        sections.put("order", () -> {
        List<OptionItem> order = new ArrayList<>();
        order.add(UiOptionItem.from("\u2B50 " + str(R.string.library_order_saved_first), o -> mSettings.setOrder(LibrarySettings.ORDER_SAVED_FIRST), mSettings.getOrder() == LibrarySettings.ORDER_SAVED_FIRST));
        order.add(UiOptionItem.from("\uD83D\uDC64 " + str(R.string.library_order_account_first), o -> mSettings.setOrder(LibrarySettings.ORDER_ACCOUNT_FIRST), mSettings.getOrder() == LibrarySettings.ORDER_ACCOUNT_FIRST));
        dialog.appendRadioCategory(ICON_ORDER + str(R.string.library_cat_order), order);

        });

        sections.put("content", () -> {
        List<OptionItem> content = new ArrayList<>();
        content.add(UiOptionItem.from("\uD83D\uDCC3 " + str(R.string.library_content_playlists), o -> mSettings.setChannelContent(LibrarySettings.CHANNEL_CONTENT_PLAYLISTS), mSettings.getChannelContent() == LibrarySettings.CHANNEL_CONTENT_PLAYLISTS));
        content.add(UiOptionItem.from("\uD83D\uDD00 " + str(R.string.library_content_playlists_or_videos), o -> mSettings.setChannelContent(LibrarySettings.CHANNEL_CONTENT_PLAYLISTS_OR_VIDEOS), mSettings.getChannelContent() == LibrarySettings.CHANNEL_CONTENT_PLAYLISTS_OR_VIDEOS));
        content.add(UiOptionItem.from("\uD83C\uDFAC " + str(R.string.library_content_videos), o -> mSettings.setChannelContent(LibrarySettings.CHANNEL_CONTENT_VIDEOS), mSettings.getChannelContent() == LibrarySettings.CHANNEL_CONTENT_VIDEOS));
        dialog.appendRadioCategory(ICON_CONTENT + str(R.string.library_cat_channel_content), content);

        });

        sections.put("max_channels", () -> {
            appendNumberCategory(dialog, R.string.library_cat_max_channels, ICON_MAX_CHANNELS, new int[] {3, 5, 10, 15, 25},
                    mSettings.getMaxSubscribedChannels(), mSettings::setMaxSubscribedChannels);
        });

        sections.put("items", () -> {
            appendNumberCategory(dialog, R.string.library_cat_items_per_shelf, ICON_ITEMS, new int[] {10, 20, 30, 50},
                    mSettings.getItemsPerShelf(), mSettings::setItemsPerShelf);
        });

        sections.put("latest_total", () -> {
            appendNumberCategory(dialog, R.string.library_cat_latest_total, ICON_LATEST_TOTAL, new int[] {15, 30, 50, 80},
                    mSettings.getLatestTotal(), mSettings::setLatestTotal);
        });

        sections.put("latest_per", () -> {
            appendNumberCategory(dialog, R.string.library_cat_latest_per_source, ICON_LATEST_PER, new int[] {2, 3, 5, 8},
                    mSettings.getLatestPerSource(), mSettings::setLatestPerSource);
        });

        sections.put("collections", () -> {
        dialog.appendSingleButton(UiOptionItem.from(ICON_COLLECTIONS + str(R.string.collection_manage), o -> {
            dialog.closeDialog();
            LibraryCollectionsPresenter.instance(getContext()).showManage();
        }));

        });

        sections.put("rows", () -> {
        dialog.appendSingleButton(UiOptionItem.from(ICON_ROWS + str(R.string.library_rows_manage), o -> {
            dialog.closeDialog();
            LibraryRowsPresenter.instance(getContext()).show();
        }));

        });

        sections.put("kids", () -> {
        dialog.appendSingleButton(UiOptionItem.from(
                ICON_KIDS + str(mSettings.isKidsMode() ? R.string.library_kids_disable : R.string.library_kids_enable),
                o -> {
                    dialog.closeDialog();
                    toggleKidsMode();
                }));

        });

        sections.put("reset", () -> {
        dialog.appendSingleButton(UiOptionItem.from(ICON_RESET + str(R.string.library_reset), o -> {
            mSettings.reset();
            MessageHelpers.showMessage(getContext(), str(R.string.library_reset_done));
            dialog.closeDialog();
        }));

        });

        for (String id : sectionOrder()) {
            Runnable section = sections.get(id);
            if (section != null) {
                section.run();
            }
        }

        // Always last: reorder the settings sections
        dialog.appendSingleButton(UiOptionItem.from(ICON_REORDER + str(R.string.library_reorder), o -> {
            dialog.closeDialog();
            showReorder();
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
