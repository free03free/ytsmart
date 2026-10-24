package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import android.annotation.SuppressLint;
import android.content.Context;
import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.appupdatechecker2.AppUpdateCheckerListener;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadingManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.List;

public class AppUpdatePresenter extends BasePresenter<Void> implements AppUpdateCheckerListener {
    @SuppressLint("StaticFieldLeak")
    private static AppUpdatePresenter sInstance;
    private final AppUpdateChecker mUpdateChecker;
    private final AppDialogPresenter mSettingsPresenter;
    private final String[] mUpdateManifestUrls;
    private boolean mIsForceCheck;
    private boolean mIsCustomSource; // update comes from the user's own site: installing is allowed

    public AppUpdatePresenter(Context context) {
        super(context);
        mUpdateChecker = new AppUpdateChecker(context, this);
        mSettingsPresenter = AppDialogPresenter.instance(context);
        mUpdateManifestUrls = context.getResources().getStringArray(R.array.update_urls);
    }

    public static AppUpdatePresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new AppUpdatePresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    /** Check the user's own update site (address set in "About"). The official check only notifies. */
    public void startCustom() {
        String url = com.liskovsoft.smartyoutubetv2.common.prefs.CustomUpdateSettings.instance(getContext()).getUrl();

        if (url == null || url.trim().isEmpty()) {
            MessageHelpers.showMessage(getContext(), R.string.custom_update_no_url);
            return;
        }

        mIsCustomSource = true;
        mIsForceCheck = true;
        LoadingManager.showLoading(getContext(), true);
        mUpdateChecker.forceCheckForUpdates(new String[] {url.trim()});
    }

    public void start(boolean forceCheck) {
        mIsCustomSource = false;
        mIsForceCheck = forceCheck;

        if (forceCheck) {
            LoadingManager.showLoading(getContext(), true);
            mUpdateChecker.forceCheckForUpdates(mUpdateManifestUrls);
        } else {
            mUpdateChecker.checkForUpdates(mUpdateManifestUrls);
        }
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        if (mIsForceCheck) {
            LoadingManager.showLoading(getContext(), false);
            showUpdateDialog(versionName, changelog, apkPath);
        } else if (GeneralData.instance(getContext()).isOldUpdateNotificationsEnabled()) {
            showUpdateDialog(versionName, changelog, apkPath);
        } else {
            pinUpdateSection(versionName, changelog, apkPath);
        }
    }

    @Override
    public void onUpdateError(Exception error) {
        if (mIsForceCheck) {
            LoadingManager.showLoading(getContext(), false);

            if (AppUpdateCheckerListener.LATEST_VERSION.equals(error.getMessage())) {
                MessageHelpers.showMessage(getContext(), R.string.update_not_found);
            } else {
                MessageHelpers.showMessage(getContext(), String.format("%s: %s", getContext().getString(R.string.update_error),
                        error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
            }
        }

        onFinish();
    }

    private void showUpdateDialog(String versionName, List<String> changelog, String apkPath) {
        // Don't show update dialog if the player opened or the app is collapsed
        if (getContext() == null || getViewManager().isPlayerInForeground() || !Utils.isAppInForegroundFixed()) {
            return;
        }

        // Official update: notification only (installing it would erase the custom changes of this build).
        // Update from the user's own site: installation allowed.
        if (mIsCustomSource) {
            mSettingsPresenter.appendSingleButton(
                    UiOptionItem.from(getContext().getString(R.string.install_update), optionItem -> {
                        GeneralData.instance(getContext()).setChangelog(changelog);
                        mUpdateChecker.installUpdate();
                    }, false));
        }
        mSettingsPresenter.appendStringsCategory(getContext().getString(R.string.update_changelog), createChangelogOptions(changelog));
        //mSettingsPresenter.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.show_again), optionItem -> {
        //    mUpdateChecker.enableUpdateCheck(optionItem.isSelected());
        //}, mUpdateChecker.isUpdateCheckEnabled()));

        //mSettingsPresenter.setOnFinish(getOnFinish());
        mSettingsPresenter.showDialog(String.format("%s %s", getContext().getString(R.string.app_name), versionName), AppUpdatePresenter::unhold);
    }

    private void pinUpdateSection(String versionName, List<String> changelog, String apkPath) {
        // Don't show update dialog if the player opened or the app is collapsed
        if (getContext() == null) {
            return;
        }

        BrowsePresenter.instance(getContext()).pinItem(getContext().getString(R.string.update_found), R.drawable.action_info, new ErrorFragmentData() {
            @Override
            public void onAction() {
                // Notification only: no installation (keeps the custom changes of this build)
            }

            @Override
            public String getMessage() {
                return String.format("%s %s", getContext().getString(R.string.app_name), versionName) + " " +
                        getContext().getString(R.string.update_changelog) + ":\n" +
                        createChangelog(changelog);
            }

            @Override
            public String getActionText() {
                return getContext().getString(R.string.update_notice_ok);
            }
        });
    }

    private List<OptionItem> createChangelogOptions(List<String> changelog) {
        List<OptionItem> options = new ArrayList<>();

        for (String change : changelog) {
            options.add(UiOptionItem.from(change));
        }

        return options;
    }

    private String createChangelog(List<String> changelog) {
        StringBuilder builder = new StringBuilder();

        int maxLines = 30;
        int lineNum = 0;

        for (String change : changelog) {
            if (lineNum > maxLines) {
                break;
            }

            builder.append("- ");
            builder.append(change);
            builder.append("\n");

            lineNum++;
        }

        return builder.toString();
    }
}
