package com.uac.spoofer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

public final class BypassStore {
    private static final String PREFS = "uac_spoofer_bypass";
    private static final String KEY_PACKAGES = "packages";

    private BypassStore() {
    }

    public static Set<String> getExcludedPackages(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> raw = prefs.getStringSet(KEY_PACKAGES, new LinkedHashSet<>());
        return new LinkedHashSet<>(raw);
    }

    public static boolean isExcluded(Context context, String packageName) {
        return getExcludedPackages(context).contains(packageName);
    }

    public static void setExcluded(Context context, String packageName, boolean excluded) {
        Set<String> packages = getExcludedPackages(context);
        if (excluded) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
        }
        save(context, packages);
    }

    public static int count(Context context) {
        return getExcludedPackages(context).size();
    }

    private static void save(Context context, Set<String> packages) {
        TreeSet<String> sorted = new TreeSet<>(packages);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_PACKAGES, sorted)
                .apply();
    }
}
