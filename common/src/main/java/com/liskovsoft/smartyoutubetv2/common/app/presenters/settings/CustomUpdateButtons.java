package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.CustomUpdateSettings;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

/** Settings buttons: update from the user's own modifications site. */
final class CustomUpdateButtons {
    private CustomUpdateButtons() {
    }

    static void append(AppDialogPresenter dialog, Context context) {
        dialog.appendSingleButton(UiOptionItem.from("\uD83D\uDEE0\uFE0F " + context.getString(R.string.custom_update_check),
                option -> AppUpdatePresenter.instance(context).startCustom()));

        dialog.appendSingleButton(UiOptionItem.from("\uD83D\uDD17 " + context.getString(R.string.custom_update_set_url),
                option -> {
                    dialog.closeDialog();
                    CustomUpdateSettings settings = CustomUpdateSettings.instance(context);
                    SimpleEditDialog.show(context, context.getString(R.string.custom_update_set_url),
                            context.getString(R.string.custom_update_url_hint), settings.getUrl(), value -> {
                                settings.setUrl(value);
                                return true;
                            });
                }));
    }
}
