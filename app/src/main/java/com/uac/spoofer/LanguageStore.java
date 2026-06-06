package com.uac.spoofer;

import android.content.Context;
import android.content.SharedPreferences;

public final class LanguageStore {
    private static final String PREFS = "uac_spoofer_language";
    private static final String KEY_PERSIAN = "persian";

    private LanguageStore() {
    }

    public static boolean isPersian(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PERSIAN, false);
    }

    public static void setPersian(Context context, boolean persian) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putBoolean(KEY_PERSIAN, persian);
        editor.apply();
    }
}
