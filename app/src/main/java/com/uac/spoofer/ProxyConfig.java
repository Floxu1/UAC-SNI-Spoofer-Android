package com.uac.spoofer;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public class ProxyConfig {
    public static final String DEFAULT_SPOOF_ADDRESS = "104.19.229.21";
    public static final String DEFAULT_SPOOF_FALLBACK = "104.19.230.21";
    public static final String DEFAULT_SPOOF_SNI = "www.hcaptcha.com";

    public String id;
    public String name;
    public String address;
    public String fallbackAddress;
    public int port;
    public String sni;
    public String method;
    public String sourceUri;
    public String protocol;
    public String configHost;
    public int configPort;
    public boolean lastPingOk;
    public double lastPingMs;
    public String origin;

    public ProxyConfig() {
        id = UUID.randomUUID().toString();
        name = "New V2Ray config";
        address = DEFAULT_SPOOF_ADDRESS;
        fallbackAddress = DEFAULT_SPOOF_FALLBACK;
        port = 443;
        sni = DEFAULT_SPOOF_SNI;
        method = "combined";
        sourceUri = "";
        protocol = "vless";
        configHost = "127.0.0.1";
        configPort = 40443;
        lastPingOk = false;
        lastPingMs = 0;
        origin = "user";
    }

    public static ProxyConfig defaultPrimary() {
        ProxyConfig config = new ProxyConfig();
        return config;
    }

    public static ProxyConfig defaultFallback() {
        ProxyConfig config = new ProxyConfig();
        return config;
    }

    public ProxyConfig copy() {
        ProxyConfig copy = new ProxyConfig();
        copy.id = id;
        copy.name = name;
        copy.address = address;
        copy.fallbackAddress = fallbackAddress;
        copy.port = port;
        copy.sni = sni;
        copy.method = method;
        copy.sourceUri = sourceUri;
        copy.protocol = protocol;
        copy.configHost = configHost;
        copy.configPort = configPort;
        copy.lastPingOk = lastPingOk;
        copy.lastPingMs = lastPingMs;
        copy.origin = origin;
        return copy;
    }

    public String targetLabel() {
        String ping = lastPingOk ? String.format(java.util.Locale.US, "  %.0f ms", lastPingMs) : "  not tested";
        return address + ":" + port + " / " + sni + ping;
    }

    public String configEndpointLabel() {
        return protocol + "://" + configHost + ":" + configPort;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("address", address);
        json.put("fallbackAddress", fallbackAddress);
        json.put("port", port);
        json.put("sni", sni);
        json.put("method", method);
        json.put("sourceUri", sourceUri);
        json.put("protocol", protocol);
        json.put("configHost", configHost);
        json.put("configPort", configPort);
        json.put("lastPingOk", lastPingOk);
        json.put("lastPingMs", lastPingMs);
        json.put("origin", origin);
        return json;
    }

    public static ProxyConfig fromJson(JSONObject json) {
        ProxyConfig config = new ProxyConfig();
        config.id = json.optString("id", UUID.randomUUID().toString());
        config.name = json.optString("name", "Imported config");
        config.address = json.optString("address", DEFAULT_SPOOF_ADDRESS);
        config.fallbackAddress = json.optString("fallbackAddress", DEFAULT_SPOOF_FALLBACK);
        config.port = json.optInt("port", 443);
        config.sni = json.optString("sni", DEFAULT_SPOOF_SNI);
        if (config.address.trim().isEmpty()) {
            config.address = DEFAULT_SPOOF_ADDRESS;
        }
        if (config.fallbackAddress.trim().isEmpty()) {
            config.fallbackAddress = DEFAULT_SPOOF_FALLBACK;
        }
        if (config.sni.trim().isEmpty()) {
            config.sni = DEFAULT_SPOOF_SNI;
        }
        config.method = json.optString("method", "combined");
        config.sourceUri = json.optString("sourceUri", "");
        config.protocol = json.optString("protocol", "vless");
        config.configHost = json.optString("configHost", "127.0.0.1");
        config.configPort = json.optInt("configPort", 40443);
        config.lastPingOk = json.optBoolean("lastPingOk", false);
        config.lastPingMs = json.optDouble("lastPingMs", 0);
        config.origin = json.optString("origin", "user");
        return config;
    }
}

