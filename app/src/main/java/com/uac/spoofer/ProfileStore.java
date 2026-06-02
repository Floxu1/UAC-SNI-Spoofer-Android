package com.uac.spoofer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ProfileStore {
    private static final String PREFS = "uac_spoofer_profiles";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_SELECTED = "selected";
    private static final String KEY_SEEDED_VERSION = "seededVersion";
    private static final int SEEDED_VERSION = 6;
    private static final String BUILTIN_CONFIGS =
            "trojan://humanity@127.0.0.1:40443?path=%2Fassignment&security=tls&insecure=0&host=www.calmlunch.com&type=ws&allowInsecure=0&sni=www.calmlunch.com#uacSpoofer%201\n"
                    + "trojan://humanity@127.0.0.1:40443?path=%2Fassignment&security=tls&insecure=0&host=www.ignitelimit.com&type=ws&allowInsecure=0&sni=www.ignitelimit.com#uacSpoofer%202\n"
                    + "trojan://humanity@127.0.0.1:40443?path=assignment&security=tls&insecure=0&type=ws&allowInsecure=0&sni=www.ignitelimit.com#uacSpoofer%203\n"
                    + "trojan://humanity@127.0.0.1:40443?path=%2Fassignment%3FTELEGRAM--KANAL--JKVPN--JKVPN--JKVPN--JKVPN--JKVPN--JKVPN&security=tls&insecure=0&fp=chrome&type=ws&allowInsecure=0&sni=www.gossipglove.com#uacSpoofer%204\n"
                    + "trojan://humanity@127.0.0.1:40443?path=%2F%2Fassignment&security=tls&insecure=0&host=www.multiplydose.com&type=ws&allowInsecure=0&sni=www.multiplydose.com#uacSpoofer%205\n"
                    + "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@127.0.0.1:40443?path=%2Fvpnhu&security=tls&encryption=none&insecure=1&host=cdn.veilvpn.fans&fp=chrome&type=httpupgrade&allowInsecure=1&sni=cdn.veilvpn.fans#uacSpoofer%206";

    private final SharedPreferences prefs;

    public ProfileStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<ProxyConfig> loadProfiles() {
        List<ProxyConfig> profiles = new ArrayList<>();
        String raw = prefs.getString(KEY_PROFILES, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    profiles.add(ProxyConfig.fromJson(array.getJSONObject(i)));
                }
            } catch (Exception ignored) {
                profiles.clear();
            }
        }
        boolean removedLegacy = removeLegacyNonV2rayProfiles(profiles);
        boolean normalized = normalizeSpoofTargets(profiles);
        boolean renamed = normalizeBuiltinProfileNames(profiles);
        if (profiles.isEmpty()) {
            profiles.addAll(defaultProfiles());
            saveProfiles(profiles);
            setSelectedId(profiles.get(0).id);
            prefs.edit().putInt(KEY_SEEDED_VERSION, SEEDED_VERSION).apply();
        } else if (prefs.getInt(KEY_SEEDED_VERSION, 0) < SEEDED_VERSION) {
            addMissingBuiltins(profiles);
            removeLegacyNonV2rayProfiles(profiles);
            normalizeSpoofTargets(profiles);
            normalizeBuiltinProfileNames(profiles);
            saveProfiles(profiles);
            prefs.edit().putInt(KEY_SEEDED_VERSION, SEEDED_VERSION).apply();
        } else if (removedLegacy || normalized || renamed) {
            saveProfiles(profiles);
        }
        return profiles;
    }

    public static List<ProxyConfig> defaultProfiles() {
        List<ProxyConfig> defaults = new ArrayList<>(VlessParser.parseMany(BUILTIN_CONFIGS));
        for (int i = 0; i < defaults.size(); i++) {
            defaults.get(i).name = "uacSpoofer " + (i + 1);
        }
        return defaults;
    }

    private boolean removeLegacyNonV2rayProfiles(List<ProxyConfig> profiles) {
        int before = profiles.size();
        profiles.removeIf(profile -> profile.id.startsWith("default-hcaptcha")
                || profile.name.toLowerCase().contains("hcaptcha")
                || !isV2rayProtocol(profile.protocol));
        return profiles.size() != before;
    }

    private boolean isV2rayProtocol(String protocol) {
        String value = protocol == null ? "" : protocol.toLowerCase();
        return value.equals("vless") || value.equals("trojan") || value.equals("vmess") || value.equals("ss");
    }

    private boolean normalizeSpoofTargets(List<ProxyConfig> profiles) {
        boolean changed = false;
        for (ProxyConfig profile : profiles) {
            if (isLocalConfig(profile)) {
                if (!ProxyConfig.DEFAULT_SPOOF_ADDRESS.equals(profile.address)
                        || !ProxyConfig.DEFAULT_SPOOF_FALLBACK.equals(profile.fallbackAddress)
                        || profile.port != 443
                        || !ProxyConfig.DEFAULT_SPOOF_SNI.equals(profile.sni)) {
                    profile.address = ProxyConfig.DEFAULT_SPOOF_ADDRESS;
                    profile.fallbackAddress = ProxyConfig.DEFAULT_SPOOF_FALLBACK;
                    profile.port = 443;
                    profile.sni = ProxyConfig.DEFAULT_SPOOF_SNI;
                    profile.method = "combined";
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean isLocalConfig(ProxyConfig profile) {
        String host = profile.configHost == null ? "" : profile.configHost.toLowerCase();
        return profile.configPort == 40443
                && (host.equals("127.0.0.1")
                || host.equals("localhost")
                || host.equals("::1")
                || host.startsWith("127."));
    }

    private void addMissingBuiltins(List<ProxyConfig> profiles) {
        for (ProxyConfig builtin : defaultProfiles()) {
            boolean exists = false;
            for (ProxyConfig profile : profiles) {
                if (!profile.sourceUri.isEmpty() && profile.sourceUri.equals(builtin.sourceUri)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                profiles.add(builtin);
            }
        }
    }

    private boolean normalizeBuiltinProfileNames(List<ProxyConfig> profiles) {
        boolean changed = false;
        List<ProxyConfig> builtins = defaultProfiles();
        for (ProxyConfig profile : profiles) {
            for (ProxyConfig builtin : builtins) {
                if (!profile.sourceUri.isEmpty() && isSameBuiltinProfile(profile, builtin)
                        && !profile.name.equals(builtin.name)) {
                    profile.name = builtin.name;
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    private boolean isSameBuiltinProfile(ProxyConfig profile, ProxyConfig builtin) {
        return stripFragment(profile.sourceUri).equals(stripFragment(builtin.sourceUri));
    }

    private String stripFragment(String uri) {
        int hash = uri == null ? -1 : uri.indexOf('#');
        return hash >= 0 ? uri.substring(0, hash) : (uri == null ? "" : uri);
    }

    public void saveProfiles(List<ProxyConfig> profiles) {
        JSONArray array = new JSONArray();
        for (ProxyConfig profile : profiles) {
            try {
                array.put(profile.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply();
    }

    public String getSelectedId(List<ProxyConfig> profiles) {
        String selected = prefs.getString(KEY_SELECTED, "");
        for (ProxyConfig profile : profiles) {
            if (profile.id.equals(selected)) {
                return selected;
            }
        }
        String fallback = profiles.isEmpty() ? "" : profiles.get(0).id;
        setSelectedId(fallback);
        return fallback;
    }

    public void setSelectedId(String id) {
        prefs.edit().putString(KEY_SELECTED, id).apply();
    }
}

