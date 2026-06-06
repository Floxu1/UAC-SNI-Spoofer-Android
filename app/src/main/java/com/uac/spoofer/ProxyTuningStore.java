package com.uac.spoofer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public final class ProxyTuningStore {
    private static final String PREFS = "uac_spoofer_proxy_tuning";
    private static final String KEY_MODE = "mode";
    private static final String KEY_FAKE_PROBE_ENABLED = "fakeProbeEnabled";
    private static final String KEY_FAKE_PROBE_COUNT = "fakeProbeCount";
    private static final String KEY_FAKE_PROBE_DELAY_MS = "fakeProbeDelayMs";
    private static final String KEY_MULTI_FRAGMENT_SIZE = "multiFragmentSize";
    private static final String KEY_SNI_SPLIT_DELAY_MS = "sniSplitDelayMs";
    private static final String KEY_TLS_RECORD_DELAY_MS = "tlsRecordDelayMs";
    private static final String KEY_MULTI_DELAY_MS = "multiDelayMs";
    private static final String KEY_HALF_DELAY_MS = "halfDelayMs";
    private static final String KEY_ROUTE_PROBE_TIMEOUT_MS = "routeProbeTimeoutMs";
    private static final String KEY_STRATEGY_CACHE_ENABLED = "strategyCacheEnabled";
    private static final String KEY_STRATEGY_CACHE_TTL_MS = "strategyCacheTtlMs";
    private static final String KEY_LOG_LEVEL = "logLevel";

    private ProxyTuningStore() {
    }

    public static ProxyTuning load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ProxyTuning defaults = ProxyTuning.balanced();
        ProxyTuning tuning = new ProxyTuning();
        tuning.mode = prefs.getString(KEY_MODE, defaults.mode);
        tuning.fakeProbeEnabled = prefs.getBoolean(KEY_FAKE_PROBE_ENABLED, defaults.fakeProbeEnabled);
        tuning.fakeProbeCount = prefs.getInt(KEY_FAKE_PROBE_COUNT, defaults.fakeProbeCount);
        tuning.fakeProbeDelayMs = prefs.getInt(KEY_FAKE_PROBE_DELAY_MS, defaults.fakeProbeDelayMs);
        tuning.multiFragmentSize = prefs.getInt(KEY_MULTI_FRAGMENT_SIZE, defaults.multiFragmentSize);
        tuning.sniSplitDelayMs = prefs.getInt(KEY_SNI_SPLIT_DELAY_MS, defaults.sniSplitDelayMs);
        tuning.tlsRecordDelayMs = prefs.getInt(KEY_TLS_RECORD_DELAY_MS, defaults.tlsRecordDelayMs);
        tuning.multiDelayMs = prefs.getInt(KEY_MULTI_DELAY_MS, defaults.multiDelayMs);
        tuning.halfDelayMs = prefs.getInt(KEY_HALF_DELAY_MS, defaults.halfDelayMs);
        tuning.routeProbeTimeoutMs = prefs.getInt(KEY_ROUTE_PROBE_TIMEOUT_MS, defaults.routeProbeTimeoutMs);
        tuning.strategyCacheEnabled = prefs.getBoolean(KEY_STRATEGY_CACHE_ENABLED, defaults.strategyCacheEnabled);
        tuning.strategyCacheTtlMs = prefs.getInt(KEY_STRATEGY_CACHE_TTL_MS, defaults.strategyCacheTtlMs);
        tuning.logLevel = prefs.getString(KEY_LOG_LEVEL, defaults.logLevel);
        return tuning.sanitize();
    }

    public static void save(Context context, ProxyTuning tuning) {
        ProxyTuning clean = tuning.copy().sanitize();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, clean.mode)
                .putBoolean(KEY_FAKE_PROBE_ENABLED, clean.fakeProbeEnabled)
                .putInt(KEY_FAKE_PROBE_COUNT, clean.fakeProbeCount)
                .putInt(KEY_FAKE_PROBE_DELAY_MS, clean.fakeProbeDelayMs)
                .putInt(KEY_MULTI_FRAGMENT_SIZE, clean.multiFragmentSize)
                .putInt(KEY_SNI_SPLIT_DELAY_MS, clean.sniSplitDelayMs)
                .putInt(KEY_TLS_RECORD_DELAY_MS, clean.tlsRecordDelayMs)
                .putInt(KEY_MULTI_DELAY_MS, clean.multiDelayMs)
                .putInt(KEY_HALF_DELAY_MS, clean.halfDelayMs)
                .putInt(KEY_ROUTE_PROBE_TIMEOUT_MS, clean.routeProbeTimeoutMs)
                .putBoolean(KEY_STRATEGY_CACHE_ENABLED, clean.strategyCacheEnabled)
                .putInt(KEY_STRATEGY_CACHE_TTL_MS, clean.strategyCacheTtlMs)
                .putString(KEY_LOG_LEVEL, clean.logLevel)
                .apply();
    }

    public static void applyToIntent(Context context, Intent intent) {
        load(context).putToIntent(intent);
    }
}
