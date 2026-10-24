package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import android.content.SharedPreferences;

/** Address of the user's own update site (manifest json, same format as the official smarttube_*.json). */
public class CustomUpdateSettings {
    private static final String PREFS = "custom_update";
    private final SharedPreferences mPrefs;

    private CustomUpdateSettings(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static CustomUpdateSettings instance(Context context) {
        return new CustomUpdateSettings(context);
    }

    public String getUrl() { return mPrefs.getString("url", ""); }
    public void setUrl(String url) { mPrefs.edit().putString("url", url == null ? "" : url.trim()).apply(); }
}
