package com.uac.spoofer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GitHubSync {
    public static final String PROJECT_URL = "https://github.com/Floxu1/UAC-SNI-Spoofer-Android";
    public static final String RELEASES_API = "https://api.github.com/repos/Floxu1/UAC-SNI-Spoofer-Android/releases/latest";
    public static final String REMOTE_CONFIGS_URL = "https://raw.githubusercontent.com/Floxu1/UAC-SNI-Spoofer-Android/main/configs.txt";
    public static final String CURRENT_VERSION = "1.0.5";
    public static final String CURRENT_RELEASE_TAG = "1.0.5";
    public static final String FALLBACK_APK_URL = "https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/download/v1.0.5/uac-Spoofer.apk";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface ConfigCallback {
        void onDone(int added, int parsed, String error);
    }

    public interface UpdateCallback {
        void onDone(UpdateInfo info, String error);
    }

    private GitHubSync() {
    }

    public static void syncConfigs(Context context, ConfigCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                String raw = get(REMOTE_CONFIGS_URL);
                List<ProxyConfig> remote = VlessParser.parseMany(raw, true);
                int added = new ProfileStore(context).syncRemoteProfiles(remote);
                callback.onDone(added, remote.size(), "");
            } catch (Exception e) {
                callback.onDone(0, 0, safe(e));
            }
        });
    }

    public static void checkUpdate(UpdateCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                JSONObject json = new JSONObject(get(RELEASES_API));
                String tag = cleanVersion(json.optString("tag_name", ""));
                String url = assetUrl(json.optJSONArray("assets"));
                if (url.isEmpty()) {
                    url = json.optString("html_url", FALLBACK_APK_URL);
                }
                boolean newer = compareVersions(tag, CURRENT_RELEASE_TAG) > 0;
                callback.onDone(new UpdateInfo(tag, url, newer), "");
            } catch (Exception e) {
                callback.onDone(new UpdateInfo("", FALLBACK_APK_URL, false), safe(e));
            }
        });
    }

    public static void openUrl(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private static String assetUrl(JSONArray assets) {
        if (assets == null) {
            return "";
        }
        String fallback = "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "").toLowerCase(Locale.US);
            String url = asset.optString("browser_download_url", "");
            if (!name.endsWith(".apk") || url.isEmpty() || name.contains("unsigned") || name.contains("debug")) {
                continue;
            }
            if ("uac-spoofer.apk".equals(name) || "uac_spoofer.apk".equals(name)) {
                return url;
            }
            if (fallback.isEmpty()) {
                fallback = url;
            }
        }
        return fallback;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "UAC-Spoofer-Android/" + CURRENT_VERSION);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } finally {
            connection.disconnect();
        }
        return out.toString();
    }

    private static int compareVersions(String left, String right) {
        int[] a = parts(left);
        int[] b = parts(right);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int[] parts(String version) {
        String[] raw = cleanVersion(version).split("\\.");
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            try {
                out[i] = Integer.parseInt(raw[i].replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String cleanVersion(String value) {
        return value == null ? "" : value.trim().replaceFirst("^[vV]", "");
    }

    private static String safe(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    public static final class UpdateInfo {
        public final String version;
        public final String url;
        public final boolean newer;

        UpdateInfo(String version, String url, boolean newer) {
            this.version = version;
            this.url = url;
            this.newer = newer;
        }
    }
}
