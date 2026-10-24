package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.os.Bundle;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

public class BrowseActivity extends LeanbackActivity {
    private static final String TAG = BrowseActivity.class.getSimpleName();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // User defined style of row titles
        MainUIData uiData = MainUIData.instance(this);
        androidx.leanback.widget.RowHeaderPresenter.sTitleScale = uiData.getRowTitleScale();
        androidx.leanback.widget.RowHeaderPresenter.sTitleColor = uiData.getRowTitleColor();
        androidx.leanback.widget.RowHeaderPresenter.sTitleBold = uiData.isRowTitleBold();
        try {
            setContentView(R.layout.fragment_main);
        } catch (NoClassDefFoundError e) {
            // Failed resolution of: Landroidx/lifecycle/ViewTreeLifecycleOwner;
            MessageHelpers.showMessage(this, e.getMessage());
        }
    }

    @Override
    protected void initTheme() {
        int browseThemeResId = MainUIData.instance(this).getColorScheme().browseThemeResId;
        if (browseThemeResId > 0) {
            setTheme(browseThemeResId);
        }
    }

    @Override
    public void onBackPressed() {
        // Inside an opened library collection / search: step out first
        if (com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter.instance(this).handleLibraryBack()) {
            return;
        }
        super.onBackPressed();
    }
}
