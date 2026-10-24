package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.LibraryCollections;
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
        flags.add(UiOptionItem.from(str(R.string.collection_for_kids),
                o -> LibraryCollections.setKids(getContext(), name, o.isSelected()),
                LibraryCollections.isKids(getContext(), name)));
        dialog.appendCheckedCategory(name, flags);

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
