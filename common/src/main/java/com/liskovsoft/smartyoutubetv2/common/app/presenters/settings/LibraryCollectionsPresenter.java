package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.LibraryCollections;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.LocalPlaylistStore;
import com.liskovsoft.smartyoutubetv2.common.prefs.LibrarySettings;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;

/** Create / fill / rename / delete the user's own library collections. */
public class LibraryCollectionsPresenter extends BasePresenter<Void> {
    private LibraryCollectionsPresenter(Context context) {
        super(context);
    }

    public static LibraryCollectionsPresenter instance(Context context) {
        return new LibraryCollectionsPresenter(context);
    }

    /** "Add to collection": tick the collections the card should belong to, or make a new one. */
    public void showAddTo(Video video) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        List<String> names = LibraryCollections.names(getContext());

        if (!names.isEmpty()) {
            List<OptionItem> options = new ArrayList<>();
            for (String name : names) {
                options.add(UiOptionItem.from(name, o -> {
                    if (o.isSelected()) {
                        LibraryCollections.add(getContext(), name, video);
                    } else {
                        LibraryCollections.remove(getContext(), name, video);
                    }
                }, LibraryCollections.contains(getContext(), name, video)));
            }
            dialog.appendCheckedCategory(str(R.string.collection_pick), options);
        }

        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_new), o -> {
            dialog.closeDialog();
            askName(str(R.string.collection_new), "", name -> {
                LibraryCollections.create(getContext(), name);
                LibraryCollections.add(getContext(), name.trim(), video);
                MessageHelpers.showMessage(getContext(), str(R.string.collection_added) + ": " + name.trim());
            });
        }));

        dialog.showDialog(str(R.string.collection_add_to), () -> {
            if (!names.isEmpty()) {
                MessageHelpers.showMessage(getContext(), str(R.string.library_reopen_hint));
            }
        });
    }

    /** Manage: create, rename, delete. */
    public void showManage() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_new), o -> {
            dialog.closeDialog();
            askName(str(R.string.collection_new), "", name -> LibraryCollections.create(getContext(), name));
        }));

        for (String name : LibraryCollections.names(getContext())) {
            int count = LibraryCollections.items(getContext(), name).size();
            dialog.appendSingleButton(UiOptionItem.from(name + "  \u2022  " + count, o -> showOne(name)));
        }

        dialog.showDialog(str(R.string.collection_manage));
    }

    private void showOne(String name) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        LibrarySettings settings = LibrarySettings.instance(getContext());
        List<OptionItem> flags = new ArrayList<>();
        flags.add(UiOptionItem.from(str(R.string.collection_show_in_library),
                o -> settings.setRowHidden(LibrarySettings.collectionKey(name), !o.isSelected()),
                !settings.isRowHidden(LibrarySettings.collectionKey(name))));
        flags.add(UiOptionItem.from(str(R.string.collection_pin),
                o -> LibraryCollections.setPinned(getContext(), name, o.isSelected()),
                LibraryCollections.isPinned(getContext(), name)));
        flags.add(UiOptionItem.from(str(R.string.collection_for_kids),
                o -> LibraryCollections.setKids(getContext(), name, o.isSelected()),
                LibraryCollections.isKids(getContext(), name)));
        dialog.appendCheckedCategory(name, flags);

        List<OptionItem> sorts = new ArrayList<>();
        int sort = LibraryCollections.getSort(getContext(), name);
        sorts.add(UiOptionItem.from(str(R.string.collection_sort_manual), o -> LibraryCollections.setSort(getContext(), name, LibraryCollections.SORT_MANUAL), sort == LibraryCollections.SORT_MANUAL));
        sorts.add(UiOptionItem.from(str(R.string.collection_sort_name), o -> LibraryCollections.setSort(getContext(), name, LibraryCollections.SORT_NAME), sort == LibraryCollections.SORT_NAME));
        sorts.add(UiOptionItem.from(str(R.string.collection_sort_newest), o -> LibraryCollections.setSort(getContext(), name, LibraryCollections.SORT_NEWEST), sort == LibraryCollections.SORT_NEWEST));
        dialog.appendRadioCategory(str(R.string.collection_sort), sorts);

        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_items), o -> showItems(name)));
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_bulk_add), o -> showBulkAdd(name)));
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_parent), o -> showParentPicker(name)));
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_icon), o -> {
            dialog.closeDialog();
            SimpleEditDialog.show(getContext(), str(R.string.collection_icon), str(R.string.collection_icon_prompt),
                    LibraryCollections.getIcon(getContext(), name), value -> {
                        LibraryCollections.setIcon(getContext(), name, value);
                        showOne(name);
                        return true;
                    });
        }));
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_cover), o -> showCoverPicker(name)));

        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_move_up), o -> {
            LibraryCollections.move(getContext(), name, -1);
            showOne(name);
        }));

        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_move_down), o -> {
            LibraryCollections.move(getContext(), name, 1);
            showOne(name);
        }));

        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_rename), o -> {
            dialog.closeDialog();
            askName(str(R.string.collection_rename), name, newName -> {
                if (!LibraryCollections.rename(getContext(), name, newName)) {
                    MessageHelpers.showMessage(getContext(), str(R.string.collection_name_taken));
                }
            });
        }));

        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_delete), o -> {
            LibraryCollections.delete(getContext(), name);
            MessageHelpers.showMessage(getContext(), str(R.string.collection_deleted));
            showManage();
        }));

        dialog.showDialog(name);
    }

    /** Items of one collection in stored order: reorder, move to another collection, remove. */
    private void showItems(String name) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        List<MediaItem> items = LibraryCollections.itemsRaw(getContext(), name);
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            String title = items.get(i).getTitle() != null ? items.get(i).getTitle() : "?";
            dialog.appendSingleButton(UiOptionItem.from((i + 1) + ". " + title, o -> showItem(name, index)));
        }

        dialog.showDialog(name + "  \u2022  " + str(R.string.collection_items));
    }

    private void showItem(String name, int index) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_move_up), o -> {
            LibraryCollections.moveItem(getContext(), name, index, -1);
            showItems(name);
        }));
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_move_down), o -> {
            LibraryCollections.moveItem(getContext(), name, index, 1);
            showItems(name);
        }));
        for (String other : LibraryCollections.names(getContext())) {
            if (!other.equals(name)) {
                dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_item_move_to) + " " + other, o -> {
                    LibraryCollections.moveItemTo(getContext(), name, index, other);
                    showItems(name);
                }));
            }
        }
        dialog.appendSingleButton(UiOptionItem.from(str(R.string.collection_item_remove), o -> {
            LibraryCollections.removeAt(getContext(), name, index);
            showItems(name);
        }));

        dialog.showDialog(name);
    }

    /** Tick many saved playlists/channels at once. */
    private void showBulkAdd(String name) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        List<MediaItem> candidates = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<MediaItem> source = new ArrayList<>(LocalPlaylistStore.getAll(getContext()));
        source.addAll(LocalPlaylistStore.getPinned(getContext()));
        for (MediaItem item : source) {
            String key = item.getPlaylistId() != null ? "p:" + item.getPlaylistId() : "c:" + item.getChannelId();
            if (seen.add(key)) {
                candidates.add(item);
            }
        }

        if (candidates.isEmpty()) {
            MessageHelpers.showMessage(getContext(), str(R.string.collection_bulk_empty));
            showOne(name);
            return;
        }

        List<OptionItem> options = new ArrayList<>();
        for (MediaItem item : candidates) {
            Video video = Video.from(item);
            String title = item.getTitle() != null ? item.getTitle() : "?";
            options.add(UiOptionItem.from(title, o -> {
                if (o.isSelected()) {
                    LibraryCollections.add(getContext(), name, video);
                } else {
                    LibraryCollections.remove(getContext(), name, video);
                }
            }, LibraryCollections.contains(getContext(), name, video)));
        }
        dialog.appendCheckedCategory(name + "  \u2022  " + str(R.string.collection_bulk_add), options);
        dialog.showDialog(name, () -> showOne(name));
    }

    /** Put the collection inside another one (or back to the top level). */
    private void showParentPicker(String name) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        String current = LibraryCollections.getParent(getContext(), name);
        List<OptionItem> options = new ArrayList<>();
        options.add(UiOptionItem.from(str(R.string.collection_parent_none), o -> LibraryCollections.setParent(getContext(), name, null), current == null));
        for (String other : LibraryCollections.names(getContext())) {
            if (!LibraryCollections.isInside(getContext(), other, name)) { // not itself, not its own sub-collections
                options.add(UiOptionItem.from(other, o -> LibraryCollections.setParent(getContext(), name, other), other.equals(current)));
            }
        }
        dialog.appendRadioCategory(str(R.string.collection_parent), options);
        dialog.showDialog(name, () -> showOne(name));
    }

    /** Choose the cover picture: automatic (first item) or the picture of a chosen item. */
    private void showCoverPicker(String name) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.closeDialog();

        String current = LibraryCollections.getCover(getContext(), name);
        List<OptionItem> options = new ArrayList<>();
        options.add(UiOptionItem.from(str(R.string.collection_cover_auto), o -> LibraryCollections.setCover(getContext(), name, null), current == null));
        for (MediaItem item : LibraryCollections.itemsRaw(getContext(), name)) {
            String url = item.getCardImageUrl();
            if (url != null) {
                String title = item.getTitle() != null ? item.getTitle() : "?";
                options.add(UiOptionItem.from(title, o -> LibraryCollections.setCover(getContext(), name, url), url.equals(current)));
            }
        }
        dialog.appendRadioCategory(str(R.string.collection_cover), options);
        dialog.showDialog(name, () -> showOne(name));
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void askName(String title, String defaultValue, NameCallback callback) {
        SimpleEditDialog.show(getContext(), title, defaultValue, newValue -> {
            if (newValue == null || newValue.trim().isEmpty()) {
                return false;
            }
            callback.onName(newValue.trim());
            return true;
        });
    }

    private String str(int id) {
        return getContext().getString(id);
    }
}
