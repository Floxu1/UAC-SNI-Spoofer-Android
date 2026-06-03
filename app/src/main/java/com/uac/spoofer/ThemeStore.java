package com.uac.spoofer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

public final class ThemeStore {
    private static final String PREFS = "uac_spoofer_theme";
    private static final String KEY_DARK = "dark";

    private ThemeStore() {
    }

    public static boolean isDark(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DARK, true);
    }

    public static void setDark(Context context, boolean dark) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DARK, dark)
                .apply();
    }

    @SuppressWarnings("deprecation")
    public static void apply(Activity activity) {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        int nightMode = isDark(activity) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
        activity.setTheme(R.style.AppTheme);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.getWindow().setStatusBarColor(activity.getResources().getColor(R.color.accent));
            activity.getWindow().setNavigationBarColor(activity.getResources().getColor(R.color.surface));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = isDark(activity) ? 0 : android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }
}
