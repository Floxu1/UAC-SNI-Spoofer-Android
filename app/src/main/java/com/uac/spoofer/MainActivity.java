package com.uac.spoofer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import hev.sockstun.TProxyService;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements ProxyService.Listener {
    private static final int VPN_REQUEST_CODE = 7001;
    private static final int CONFIG_PING_TIMEOUT_SECONDS = 8;
    private static final int SCAN_MAX_DOMAINS = 512;
    private static final int SCAN_WORKERS = 30;
    private static final int SCAN_TIMEOUT_SECONDS = 20;
    private static final int SCAN_TRIES = 3;
    private static final long BACK_EXIT_WINDOW_MS = 2500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<SniSpoofScanner.Result> scanResults = new ArrayList<>();

    private ProfileStore profileStore;
    private List<ProxyConfig> profiles = new ArrayList<>();
    private String selectedId = "";
    private SniSpoofScanner scanner;
    private ConfigPinger configPinger;
    private boolean pingingConfigs = false;
    private boolean scanning = false;
    private boolean startAfterVpnConsent = false;
    private boolean blinkOn = true;
    private long lastBackPressMs = 0;
    private int scannerRunId = 0;
    private boolean exiting = false;
    private boolean waitingForProxyBeforeVpn = false;
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            updateBlinkingButtons();
            mainHandler.postDelayed(this, 650);
        }
    };

    private TextView statusText;
    private TextView activeConfigText;
    private TextView targetText;
    private TextView connectedPingText;
    private TextView trafficText;
    private ScrollView pageScroll;
    private View statusSection;
    private View configsSection;
    private View scannerSection;
    private View logsSection;
    private LinearLayout configsContainer;
    private TextView scannerStatusText;
    private LinearLayout resultsContainer;
    private ScrollView scannerResultsScroll;
    private TextView logText;
    private ScrollView logScroll;
    private Button runButton;
    private Button stopButton;
    private Button startScanButton;
    private Button stopScanButton;
    private Button themeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeStore.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        profileStore = new ProfileStore(this);
        profiles = profileStore.loadProfiles();
        selectedId = profileStore.getSelectedId(profiles);

        runButton.setOnClickListener(v -> pingConfigsThenRun());
        stopButton.setOnClickListener(v -> stopProxy());
        findViewById(R.id.menuButton).setOnClickListener(v -> showHamburgerMenu());
        themeButton.setOnClickListener(v -> toggleTheme());
        findViewById(R.id.addConfigButton).setOnClickListener(v -> showConfigEditor(null));
        findViewById(R.id.editConfigButton).setOnClickListener(v -> showConfigEditor(selectedProfile()));
        findViewById(R.id.importClipboardButton).setOnClickListener(v -> importConfigsFromClipboard());
        startScanButton.setOnClickListener(v -> startScanner());
        stopScanButton.setOnClickListener(v -> stopScanner());
        findViewById(R.id.creditText).setOnClickListener(v -> openTelegramSupport());
        stopScanButton.setEnabled(false);
        mainHandler.post(blinkRunnable);
        enableInnerResultScrolling();
        refreshProfiles();
        refreshActiveText();
        seedScannerDefaults();
        onProxyState(ProxyService.isRunning(), ProxyService.getActiveTargetLabel(), ProxyService.getTrafficSummary());
        updateThemeButton();
        ensureStartsAtTop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ProxyService.addListener(this);
    }

    @Override
    protected void onStop() {
        ProxyService.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopScanner();
        stopConfigPinger();
        mainHandler.removeCallbacks(blinkRunnable);
        if (exiting) {
            stopProxy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressMs <= BACK_EXIT_WINDOW_MS) {
            stopEverythingAndExit();
            return;
        }
        lastBackPressMs = now;
        toast("Press BACK again to exit.");
    }

    private void bindViews() {
        pageScroll = findViewById(R.id.pageScroll);
        statusSection = findViewById(R.id.statusSection);
        configsSection = findViewById(R.id.configsSection);
        scannerSection = findViewById(R.id.scannerSection);
        logsSection = findViewById(R.id.logsSection);
        statusText = findViewById(R.id.statusText);
        activeConfigText = findViewById(R.id.activeConfigText);
        targetText = findViewById(R.id.targetText);
        connectedPingText = findViewById(R.id.connectedPingText);
        trafficText = findViewById(R.id.trafficText);
        configsContainer = findViewById(R.id.configsContainer);
        scannerStatusText = findViewById(R.id.scannerStatusText);
        scannerResultsScroll = findViewById(R.id.scannerResultsScroll);
        resultsContainer = findViewById(R.id.resultsContainer);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        runButton = findViewById(R.id.runButton);
        stopButton = findViewById(R.id.stopButton);
        startScanButton = findViewById(R.id.startScanButton);
        stopScanButton = findViewById(R.id.stopScanButton);
        themeButton = findViewById(R.id.themeButton);
    }

    private void enableInnerResultScrolling() {
        scannerResultsScroll.setVerticalScrollBarEnabled(true);
        scannerResultsScroll.setScrollbarFadingEnabled(false);
        scannerResultsScroll.setFocusable(false);
        scannerResultsScroll.setFocusableInTouchMode(false);
        resultsContainer.setFocusable(false);
        resultsContainer.setFocusableInTouchMode(false);
    }

    private void refreshProfiles() {
        sortProfilesByPing();
        configsContainer.removeAllViews();
        for (ProxyConfig profile : profiles) {
            configsContainer.addView(profileRow(profile));
        }
        refreshActiveText();
    }

    private View profileRow(ProxyConfig profile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundResource(profile.id.equals(selectedId) ? R.drawable.bg_selected_config : R.drawable.bg_chip);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);

        TextView title = new TextView(this);
        String prefix = profile.id.equals(selectedId) ? "* " : "";
        title.setText(prefix + profile.name);
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(title);

        TextView detail = new TextView(this);
        detail.setText(profile.configEndpointLabel() + "  |  " + profile.address + ":" + profile.port + "  |  " + profile.sni);
        detail.setTextColor(getResources().getColor(R.color.muted));
        detail.setTextSize(12);
        detail.setSingleLine(true);
        detail.setTextDirection(View.TEXT_DIRECTION_LTR);
        detail.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(detail);

        TextView ping = new TextView(this);
        ping.setText(profile.lastPingOk
                ? String.format(Locale.US, "PING %.0f ms", profile.lastPingMs)
                : "PING --");
        ping.setTextColor(profile.lastPingOk ? getResources().getColor(R.color.accent_green) : getResources().getColor(R.color.muted));
        ping.setTextSize(12);
        ping.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        row.addView(ping);

        row.setOnClickListener(v -> {
            selectedId = profile.id;
            profileStore.setSelectedId(selectedId);
            seedScannerDefaults();
            refreshProfiles();
            if (ProxyService.isRunning()) {
                restartProxy();
            }
        });
        row.setOnLongClickListener(v -> {
            selectedId = profile.id;
            profileStore.setSelectedId(selectedId);
            refreshProfiles();
            showConfigEditor(profile);
            return true;
        });
        return row;
    }

    private void refreshActiveText() {
        ProxyConfig selected = selectedProfile();
        activeConfigText.setText(selected.name);
        targetText.setText(selected.targetLabel());
        connectedPingText.setText(selected.lastPingOk
                ? String.format(Locale.US, "Connected ping: %.0f ms", selected.lastPingMs)
                : "Connected ping: --");
    }

    private void sortProfilesByPing() {
        boolean hasPing = false;
        for (ProxyConfig profile : profiles) {
            if (profile.lastPingOk) {
                hasPing = true;
                break;
            }
        }
        if (!hasPing) {
            return;
        }
        profiles.sort(Comparator
                .comparing((ProxyConfig p) -> !p.lastPingOk)
                .thenComparingDouble(p -> p.lastPingOk ? p.lastPingMs : Double.MAX_VALUE)
                .thenComparing(p -> p.name.toLowerCase(Locale.US)));
    }

    private void seedScannerDefaults() {
        // Keep VPS IP optional. Leaving it empty follows the Python scanner behavior and lists responsive SNI domains.
    }

    private ProxyConfig selectedProfile() {
        for (ProxyConfig profile : profiles) {
            if (profile.id.equals(selectedId)) {
                return profile;
            }
        }
        if (profiles.isEmpty()) {
            profiles.addAll(ProfileStore.defaultProfiles());
            if (profiles.isEmpty()) {
                profiles.add(new ProxyConfig());
            }
            selectedId = profiles.get(0).id;
        }
        return profiles.get(0);
    }

    private void pingConfigsThenRun() {
        stopConfigPinger();
        pingingConfigs = true;
        statusText.setText("Pinging configs...");
        runButton.setText("PINGING");
        runButton.setEnabled(false);
        connectedPingText.setText("Connected ping: measuring...");

        configPinger = new ConfigPinger();
        configPinger.start(new ArrayList<>(profiles), 8, CONFIG_PING_TIMEOUT_SECONDS, new ConfigPinger.Callback() {
            @Override
            public void onResult(ProxyConfig config, boolean ok, double latencyMs, int done, int total) {
                mainHandler.post(() -> {
                    for (ProxyConfig profile : profiles) {
                        if (profile.id.equals(config.id)) {
                            profile.lastPingOk = ok;
                            profile.lastPingMs = ok ? latencyMs : 0;
                            break;
                        }
                    }
                    scannerStatusText.setText("Config ping " + done + "/" + total + "  OK: " + countOkProfiles());
                    refreshProfiles();
                });
            }

            @Override
            public void onFinished(boolean cancelled) {
                mainHandler.post(() -> {
                    pingingConfigs = false;
                    runButton.setText(getString(R.string.run));
                    runButton.setEnabled(true);
                    if (cancelled) {
                        statusText.setText("Ready");
                        return;
                    }
                    ProxyConfig best = bestPingProfile();
                    if (best != null) {
                        selectedId = best.id;
                        toast("Best config selected: " + best.name);
                    } else {
                        toast("No ping result. Running selected config.");
                    }
                    saveProfiles();
                    refreshProfiles();
                    requestVpnAndStartSelectedProxy();
                });
            }
        });
    }

    private ProxyConfig bestPingProfile() {
        ProxyConfig best = null;
        for (ProxyConfig profile : profiles) {
            if (!profile.lastPingOk) {
                continue;
            }
            if (best == null || profile.lastPingMs < best.lastPingMs) {
                best = profile;
            }
        }
        return best;
    }

    private int countOkProfiles() {
        int count = 0;
        for (ProxyConfig profile : profiles) {
            if (profile.lastPingOk) {
                count++;
            }
        }
        return count;
    }

    private void stopConfigPinger() {
        if (configPinger != null) {
            configPinger.cancel();
            configPinger = null;
        }
        pingingConfigs = false;
    }

    private void startSelectedProxy() {
        ProxyConfig config = selectedProfile();
        waitingForProxyBeforeVpn = true;
        statusText.setText("Starting proxy...");
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_START);
        intent.putExtra(ProxyService.EXTRA_NAME, config.name);
        intent.putExtra(ProxyService.EXTRA_ADDRESS, config.address);
        intent.putExtra(ProxyService.EXTRA_FALLBACK, config.fallbackAddress);
        intent.putExtra(ProxyService.EXTRA_PORT, config.port);
        intent.putExtra(ProxyService.EXTRA_SNI, config.sni);
        intent.putExtra(ProxyService.EXTRA_METHOD, config.method);
        intent.putExtra(ProxyService.EXTRA_SOURCE_URI, config.sourceUri);
        intent.putExtra(ProxyService.EXTRA_PROTOCOL, config.protocol);
        intent.putExtra(ProxyService.EXTRA_CONFIG_HOST, config.configHost);
        intent.putExtra(ProxyService.EXTRA_CONFIG_PORT, config.configPort);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        mainHandler.postDelayed(() -> {
            if (waitingForProxyBeforeVpn && !ProxyService.isRunning()) {
                waitingForProxyBeforeVpn = false;
                String error = ProxyService.getLastError();
                statusText.setText(error.isEmpty() ? "Proxy failed" : "Proxy failed: " + error);
                runButton.setEnabled(true);
                stopButton.setEnabled(false);
                toast(error.isEmpty() ? "Proxy did not start. Check logs." : error);
            }
        }, 3000);
    }

    private void requestVpnAndStartSelectedProxy() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startAfterVpnConsent = true;
            startActivityForResult(prepare, VPN_REQUEST_CODE);
            return;
        }
        startSelectedProxy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK && startAfterVpnConsent) {
                startAfterVpnConsent = false;
                startSelectedProxy();
            } else {
                startAfterVpnConsent = false;
                statusText.setText("Ready");
                runButton.setEnabled(true);
                runButton.setText(getString(R.string.run));
                toast("VPN permission is required.");
            }
        }
    }

    private void startVpnTunnel() {
        Intent intent = new Intent(this, TProxyService.class);
        intent.setAction(TProxyService.ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void restartProxy() {
        stopProxy();
        mainHandler.postDelayed(this::startSelectedProxy, 350);
    }

    private void stopProxy() {
        startAfterVpnConsent = false;
        waitingForProxyBeforeVpn = false;
        ProxyService.logEvent("STOP requested.");
        stopConfigPinger();
        stopVpnTunnel();
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP);
        sendServiceCommand(intent);
        mainHandler.postDelayed(() -> {
            stopService(new Intent(this, ProxyService.class));
            stopService(new Intent(this, TProxyService.class));
        }, 200);
    }

    private void stopVpnTunnel() {
        Intent intent = new Intent(this, TProxyService.class);
        intent.setAction(TProxyService.ACTION_DISCONNECT);
        sendServiceCommand(intent);
    }

    private void sendServiceCommand(Intent intent) {
        try {
            startService(intent);
        } catch (IllegalStateException e) {
            stopService(intent);
        }
    }

    private void stopEverythingAndExit() {
        if (exiting) {
            return;
        }
        exiting = true;
        stopScanner();
        stopConfigPinger();
        stopProxy();
        mainHandler.removeCallbacks(blinkRunnable);
        mainHandler.postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finishAffinity();
            }
            mainHandler.postDelayed(() -> {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }, 450);
        }, 350);
    }

    private void showConfigEditor(ProxyConfig existing) {
        boolean isNew = existing == null;
        ProxyConfig draft = isNew ? new ProxyConfig() : existing.copy();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, pad / 2);

        EditText name = edit("Name", draft.name, InputType.TYPE_CLASS_TEXT);
        EditText address = edit("Address", draft.address, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText fallback = edit("Fallback address", draft.fallbackAddress, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = edit("Port", String.valueOf(draft.port), InputType.TYPE_CLASS_NUMBER);
        EditText sni = edit("SNI", draft.sni, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText source = edit("Source URI (optional)", draft.sourceUri, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name);
        form.addView(address);
        form.addView(fallback);
        form.addView(port);
        form.addView(sni);
        form.addView(source);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isNew ? "Add config" : "Edit config")
                .setView(form)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton(isNew ? null : "Delete", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String addr = address.getText().toString().trim();
                String host = sni.getText().toString().trim();
                int parsedPort = readInt(port, 443);
                if (addr.isEmpty() || host.isEmpty() || parsedPort < 1 || parsedPort > 65535) {
                    toast("Address, SNI and port are required.");
                    return;
                }
                draft.name = name.getText().toString().trim().isEmpty() ? addr : name.getText().toString().trim();
                draft.address = addr;
                draft.fallbackAddress = fallback.getText().toString().trim();
                draft.port = parsedPort;
                draft.sni = host;
                draft.method = "combined";
                draft.sourceUri = source.getText().toString().trim();
                if (isNew) {
                    draft.id = UUID.randomUUID().toString();
                    profiles.add(draft);
                    selectedId = draft.id;
                } else {
                    replaceProfile(draft);
                }
                saveProfiles();
                seedScannerDefaults();
                refreshProfiles();
                dialog.dismiss();
            });
            if (!isNew) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    if (profiles.size() <= 1) {
                        toast("At least one config is required.");
                        return;
                    }
                    profiles.removeIf(p -> p.id.equals(existing.id));
                    selectedId = profiles.get(0).id;
                    saveProfiles();
                    refreshProfiles();
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private EditText edit(String hint, String value, int inputType) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value == null ? "" : value);
        view.setInputType(inputType);
        view.setSingleLine(true);
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        params.setMargins(0, 0, 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private void replaceProfile(ProxyConfig draft) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(draft.id)) {
                profiles.set(i, draft);
                return;
            }
        }
        profiles.add(draft);
    }

    private void saveProfiles() {
        profileStore.saveProfiles(profiles);
        profileStore.setSelectedId(selectedId);
    }

    private void importConfigsFromClipboard() {
        String text = clipboardText();
        List<ProxyConfig> imported = VlessParser.parseMany(text);
        if (imported.isEmpty()) {
            toast("Clipboard does not contain a supported config.");
            return;
        }
        profiles.addAll(imported);
        selectedId = imported.get(imported.size() - 1).id;
        saveProfiles();
        seedScannerDefaults();
        refreshProfiles();
        toast("Imported " + imported.size() + " config(s).");
    }

    private String clipboardText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return "";
        }
        ClipData data = clipboard.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) {
            return "";
        }
        CharSequence text = data.getItemAt(0).coerceToText(this);
        return text == null ? "" : text.toString();
    }

    private void startScanner() {
        try {
            stopScanner();
            int runId = ++scannerRunId;
            scanResults.clear();
            resultsContainer.removeAllViews();
            scannerResultsScroll.scrollTo(0, 0);

            List<String> domains = parseDomains(readAssetText("sni-spoof/domains.txt").toString(), SCAN_MAX_DOMAINS);
            if (domains.isEmpty()) {
                scannerStatusText.setText("Scanner error: no domains.");
                return;
            }

            SniSpoofScanner.Settings settings = new SniSpoofScanner.Settings();
            settings.domains = domains;
            settings.serverIp = "";
            settings.threads = SCAN_WORKERS;
            settings.timeoutSeconds = SCAN_TIMEOUT_SECONDS;
            settings.tries = SCAN_TRIES;

            scanner = new SniSpoofScanner();
            scanning = true;
            startScanButton.setEnabled(false);
            stopScanButton.setEnabled(true);
            scannerStatusText.setText("Scanning " + domains.size() + " domain(s)");
            scanner.start(settings, new SniSpoofScanner.Callback() {
                @Override
                public void onProgress(int done, int total, SniSpoofScanner.Result result) {
                    mainHandler.post(() -> {
                        if (runId != scannerRunId || !scanning) {
                            return;
                        }
                        if (result.ok()) {
                            scanResults.add(result);
                            addScanResultRow(result);
                        }
                        scannerStatusText.setText("Scan " + done + "/" + total + "  OK: " + countOk(scanResults));
                    });
                }

                @Override
                public void onFinished(List<SniSpoofScanner.Result> results, boolean cancelled) {
                    mainHandler.post(() -> {
                        if (runId != scannerRunId) {
                            return;
                        }
                        if (scanResults.isEmpty()) {
                            scanResults.addAll(results);
                            renderResults();
                        }
                        scannerStatusText.setText((cancelled ? "Scan stopped" : "Scan finished") + "  OK: " + countOk(scanResults));
                        scanning = false;
                        scanner = null;
                        startScanButton.setEnabled(true);
                        stopScanButton.setEnabled(false);
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        if (runId != scannerRunId) {
                            return;
                        }
                        scannerStatusText.setText("Scanner error: " + message);
                        scanning = false;
                        scanner = null;
                        startScanButton.setEnabled(true);
                        stopScanButton.setEnabled(false);
                    });
                }
            });
        } catch (Exception e) {
            scannerStatusText.setText("Scanner error: " + e.getMessage());
            scanning = false;
            startScanButton.setEnabled(true);
            stopScanButton.setEnabled(false);
        }
    }

    private void stopScanner() {
        boolean wasScanning = scanning;
        scannerRunId++;
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
        }
        scanning = false;
        startScanButton.setEnabled(true);
        stopScanButton.setEnabled(false);
        if (wasScanning && scannerStatusText != null) {
            scannerStatusText.setText("Scan stopped  OK: " + countOk(scanResults));
        }
    }

    private void addScanResultRow(SniSpoofScanner.Result result) {
        resultsContainer.addView(resultRow(result, scanResults.size()));
    }

    private void renderResults() {
        resultsContainer.removeAllViews();
        List<SniSpoofScanner.Result> sorted = new ArrayList<>(scanResults);
        sorted.removeIf(result -> !result.ok());
        sorted.sort(Comparator
                .comparing((SniSpoofScanner.Result r) -> !r.ok())
                .thenComparingInt(r -> r.pingMs)
                .thenComparing(r -> r.domain));
        if (sorted.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No working SNI result found.");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(getResources().getColor(R.color.muted));
            empty.setTextSize(12);
            resultsContainer.addView(empty);
            return;
        }
        int limit = Math.min(120, sorted.size());
        for (int i = 0; i < limit; i++) {
            resultsContainer.addView(resultRow(sorted.get(i), i + 1));
        }
        if (sorted.size() > limit) {
            TextView more = new TextView(this);
            more.setText("Showing first " + limit + " of " + sorted.size() + " results");
            more.setGravity(Gravity.CENTER);
            more.setTextColor(getResources().getColor(R.color.muted));
            more.setTextSize(12);
            resultsContainer.addView(more);
        }
    }

    private View resultRow(SniSpoofScanner.Result result, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setFocusable(false);
        row.setFocusableInTouchMode(false);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundResource(result.ok() ? R.drawable.bg_result_ok : R.drawable.bg_result_fail);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(7));
        row.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText(String.format(Locale.US, "%02d  %s  %s", index, result.domain, result.ok() ? "OK" : "FAIL"));
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        row.addView(title);

        String detail = result.ok()
                ? String.format(Locale.US, "IP=%s  TRACE=%s  %dms  stable %d%%  %s %s",
                result.resolvedIp,
                result.cfIp.isEmpty() ? "N/A" : result.cfIp,
                result.pingMs,
                result.stability,
                result.colo,
                result.country)
                : "No response";
        TextView subtitle = new TextView(this);
        subtitle.setText(detail);
        subtitle.setTextColor(getResources().getColor(R.color.muted));
        subtitle.setTextSize(12);
        subtitle.setTextDirection(View.TEXT_DIRECTION_LTR);
        row.addView(subtitle);

        row.setOnClickListener(v -> applyScanResult(result));
        row.setOnLongClickListener(v -> {
            copyScanResult(result);
            return true;
        });
        return row;
    }

    private StringBuilder readAssetText(String path) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(path)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (Exception e) {
            toast("Cannot read " + path);
        }
        return out;
    }

    private List<String> parseDomains(String raw, int maxDomains) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String token : (raw == null ? "" : raw).split("[\\s,;]+")) {
            String domain = token.trim();
            if (domain.isEmpty() || domain.startsWith("#")) {
                continue;
            }
            unique.add(domain);
            if (unique.size() >= Math.max(1, maxDomains)) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private void copyScanResult(SniSpoofScanner.Result result) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        String text = result.domain + " " + result.resolvedIp;
        clipboard.setPrimaryClip(ClipData.newPlainText("SNI result", text));
        toast("SNI result copied.");
    }

    private void applyScanResult(SniSpoofScanner.Result result) {
        if (!result.ok() || result.resolvedIp.equals("N/A")) {
            toast("This SNI has no usable address.");
            return;
        }
        for (ProxyConfig profile : profiles) {
            profile.address = result.resolvedIp;
            profile.fallbackAddress = "";
            profile.port = 443;
            profile.sni = result.domain;
            profile.method = "combined";
            profile.lastPingOk = false;
            profile.lastPingMs = 0;
        }
        saveProfiles();
        seedScannerDefaults();
        refreshProfiles();
        toast("SNI applied to configs.");
        if (ProxyService.isRunning()) {
            restartProxy();
        }
    }

    private int countOk(List<SniSpoofScanner.Result> results) {
        int count = 0;
        for (SniSpoofScanner.Result result : results) {
            if (result.ok()) {
                count++;
            }
        }
        return count;
    }

    private int readInt(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void openTelegramSupport() {
        Intent appIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=Beh50roocentzuac"));
        try {
            startActivity(appIntent);
        } catch (Exception ignored) {
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Beh50roocentzuac"));
            try {
                startActivity(webIntent);
            } catch (Exception e) {
                toast("Support link cannot be opened.");
            }
        }
    }

    private void showHamburgerMenu() {
        int drawerWidth = Math.min(dp(280), (int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(getResources().getColor(R.color.surface));
        drawer.setPadding(dp(14), dp(18), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Menu");
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.setMargins(0, 0, 0, dp(14));
        drawer.addView(title, titleParams);

        PopupWindow popup = new PopupWindow(
                drawer,
                drawerWidth,
                LinearLayout.LayoutParams.MATCH_PARENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(false);

        addDrawerItem(drawer, "Status", () -> closeDrawer(popup, drawer, () -> scrollTo(statusSection)));
        addDrawerItem(drawer, "Configs", () -> closeDrawer(popup, drawer, () -> scrollTo(configsSection)));
        addDrawerItem(drawer, "Scanner", () -> closeDrawer(popup, drawer, () -> scrollTo(scannerSection)));
        addDrawerItem(drawer, "Apps Bypass", () -> closeDrawer(popup, drawer, this::openAppsBypass));
        addDrawerItem(drawer, "Live Logs", () -> closeDrawer(popup, drawer, () -> scrollTo(logsSection)));
        addDrawerItem(drawer, "Support", () -> closeDrawer(popup, drawer, this::openTelegramSupport));

        drawer.setTranslationX(-drawerWidth);
        popup.showAtLocation(pageScroll, Gravity.LEFT | Gravity.TOP, 0, 0);
        drawer.animate().translationX(0).setDuration(180).start();
    }

    private void addDrawerItem(LinearLayout drawer, String label, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextColor(getResources().getColor(R.color.ink));
        item.setTextSize(15);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), 0, dp(12), 0);
        item.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        );
        params.setMargins(0, 0, 0, dp(8));
        drawer.addView(item, params);
        item.setOnClickListener(v -> action.run());
    }

    private void openAppsBypass() {
        startActivity(new Intent(this, AppsActivity.class));
    }

    private void toggleTheme() {
        ThemeStore.setDark(this, !ThemeStore.isDark(this));
        recreate();
    }

    private void updateThemeButton() {
        themeButton.setText(ThemeStore.isDark(this) ? "Light" : "Dark");
    }

    private void closeDrawer(PopupWindow popup, View drawer, Runnable afterClose) {
        drawer.animate()
                .translationX(-Math.max(drawer.getWidth(), dp(280)))
                .setDuration(140)
                .withEndAction(() -> {
                    popup.dismiss();
                    afterClose.run();
                })
                .start();
    }

    private void scrollTo(View target) {
        pageScroll.post(() -> pageScroll.smoothScrollTo(0, target.getTop()));
    }

    private void ensureStartsAtTop() {
        pageScroll.setFocusableInTouchMode(true);
        pageScroll.requestFocus();
        pageScroll.post(() -> pageScroll.scrollTo(0, 0));
        pageScroll.postDelayed(() -> pageScroll.scrollTo(0, 0), 120);
    }

    private void updateBlinkingButtons() {
        blinkOn = !blinkOn;
        boolean proxyWorking = ProxyService.isRunning() || pingingConfigs;
        runButton.setAlpha(proxyWorking ? (blinkOn ? 1.0f : 0.45f) : 1.0f);
        startScanButton.setAlpha(scanning ? (blinkOn ? 1.0f : 0.45f) : 1.0f);
        stopButton.setAlpha(ProxyService.isRunning() ? 1.0f : 0.55f);
        stopScanButton.setAlpha(scanning ? 1.0f : 0.55f);
    }

    @Override
    public void onLogSnapshot(List<String> lines) {
        mainHandler.post(() -> {
            StringBuilder builder = new StringBuilder();
            for (String line : lines) {
                builder.append(line).append('\n');
            }
            logText.setText(builder.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onLogLine(String line) {
        mainHandler.post(() -> {
            logText.append(line);
            logText.append("\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onProxyState(boolean running, String targetLabel, String trafficSummary) {
        mainHandler.post(() -> {
            trafficText.setText(trafficSummary);
            if (running) {
                if (waitingForProxyBeforeVpn) {
                    waitingForProxyBeforeVpn = false;
                    startVpnTunnel();
                }
                statusText.setText("Running");
                statusText.setBackgroundResource(R.drawable.bg_status_running);
                stopButton.setEnabled(true);
                runButton.setEnabled(false);
                runButton.setText("RUNNING");
                targetText.setText(targetLabel);
                ProxyConfig selected = selectedProfile();
                connectedPingText.setText(selected.lastPingOk
                        ? String.format(Locale.US, "Connected ping: %.0f ms", selected.lastPingMs)
                        : "Connected ping: --");
            } else {
                String error = ProxyService.getLastError();
                statusText.setText(error.isEmpty() ? "Ready" : "Proxy failed");
                statusText.setBackgroundResource(R.drawable.bg_status_idle);
                runButton.setEnabled(!pingingConfigs);
                stopButton.setEnabled(false);
                if (!pingingConfigs) {
                    runButton.setText(getString(R.string.run));
                }
                refreshActiveText();
            }
            updateBlinkingButtons();
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}

