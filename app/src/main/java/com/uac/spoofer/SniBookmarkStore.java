package com.uac.spoofer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SniBookmarkStore {
    private static final String PREFS = "uac_spoofer_sni_bookmarks";
    private static final String KEY_ITEMS = "items";

    private SniBookmarkStore() {
    }

    public static List<SniSpoofScanner.Result> load(Context context) {
        String raw = prefs(context).getString(KEY_ITEMS, "");
        List<SniSpoofScanner.Result> out = new ArrayList<>();
        for (String line : raw.split("\\n")) {
            SniSpoofScanner.Result result = decode(line);
            if (result != null) {
                out.add(result);
            }
        }
        out.sort((a, b) -> {
            int ping = Integer.compare(a.pingMs, b.pingMs);
            return ping != 0 ? ping : a.domain.compareToIgnoreCase(b.domain);
        });
        return out;
    }

    public static boolean contains(Context context, String domain) {
        String key = normalize(domain);
        for (SniSpoofScanner.Result result : load(context)) {
            if (normalize(result.domain).equals(key)) {
                return true;
            }
        }
        return false;
    }

    public static void toggle(Context context, SniSpoofScanner.Result result) {
        Map<String, SniSpoofScanner.Result> items = map(context);
        String key = normalize(result.domain);
        if (items.containsKey(key)) {
            items.remove(key);
        } else {
            items.put(key, result);
        }
        save(context, new ArrayList<>(items.values()));
    }

    public static void upsertAll(Context context, List<SniSpoofScanner.Result> results) {
        Map<String, SniSpoofScanner.Result> items = map(context);
        for (SniSpoofScanner.Result result : results) {
            if (result.ok()) {
                items.put(normalize(result.domain), result);
            }
        }
        save(context, new ArrayList<>(items.values()));
    }

    private static Map<String, SniSpoofScanner.Result> map(Context context) {
        Map<String, SniSpoofScanner.Result> items = new LinkedHashMap<>();
        for (SniSpoofScanner.Result result : load(context)) {
            items.put(normalize(result.domain), result);
        }
        return items;
    }

    private static void save(Context context, List<SniSpoofScanner.Result> items) {
        StringBuilder raw = new StringBuilder();
        for (SniSpoofScanner.Result result : items) {
            raw.append(encode(result)).append('\n');
        }
        prefs(context).edit().putString(KEY_ITEMS, raw.toString()).apply();
    }

    private static String encode(SniSpoofScanner.Result result) {
        return safe(result.domain) + "|" + safe(result.resolvedIp) + "|"
                + result.pingMs + "|" + result.stability + "|"
                + safe(result.cfIp) + "|" + safe(result.colo) + "|" + safe(result.country);
    }

    private static SniSpoofScanner.Result decode(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length < 1 || parts[0].trim().isEmpty()) {
            return null;
        }
        SniSpoofScanner.Result result = new SniSpoofScanner.Result(parts[0]);
        result.resolvedIp = parts.length > 1 ? parts[1] : "N/A";
        result.pingMs = parseInt(parts, 2, 9999);
        result.stability = parseInt(parts, 3, 0);
        result.cfIp = parts.length > 4 ? parts[4] : "";
        result.colo = parts.length > 5 ? parts[5] : "";
        result.country = parts.length > 6 ? parts[6] : "";
        result.success = true;
        return result;
    }

    private static int parseInt(String[] parts, int index, int fallback) {
        try {
            return Integer.parseInt(parts[index]);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String normalize(String domain) {
        return safe(domain).toLowerCase(Locale.US);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("|", "").replace("\n", "").trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
