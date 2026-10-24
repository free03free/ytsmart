package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.PlaylistLibraryService;

/**
 * Reloads the library after its settings dialogs are closed.
 * Dialogs call cancel() when they open and schedule() when they close, so moving between
 * settings screens does not reload anything; only the final close does.
 */
final class LibraryRefresh {
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable sPending;

    private LibraryRefresh() {
    }

    static void cancel() {
        if (sPending != null) {
            HANDLER.removeCallbacks(sPending);
            sPending = null;
        }
    }

    static void schedule(Context context) {
        cancel();
        final Context appContext = context.getApplicationContext();
        sPending = () -> {
            sPending = null;
            BrowsePresenter presenter = BrowsePresenter.instance(appContext);
            BrowseSection section = presenter.getCurrentSection();
            if (section != null && section.getId() == PlaylistLibraryService.SECTION_ID) {
                presenter.refresh(false);
            }
        };
        HANDLER.postDelayed(sPending, 700);
    }
}
