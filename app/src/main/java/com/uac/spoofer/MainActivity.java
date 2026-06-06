package com.uac.spoofer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final List<SniSpoofScanner.Result> bookmarkedScanResults = new ArrayList<>();

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
    private boolean showingSavedSni = false;
    private boolean autoMode = true;
    private boolean pickBestManualMode = false;
    private boolean showingSuggestedConfigs = false;
    private boolean persianUi = false;
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
    private TextView homeVersionText;
    private LinearLayout rootLayout;
    private ScrollView pageScroll;
    private LinearLayout mainContent;
    private LinearLayout bottomNav;
    private LinearLayout statusSection;
    private View homeSpacer;
    private View configsSection;
    private View scannerSection;
    private View scanPanel;
    private View bookmarksSection;
    private View logsSection;
    private View supportSection;
    private LinearLayout configsContainer;
    private TextView scannerStatusText;
    private LinearLayout resultsContainer;
    private ScrollView scannerResultsScroll;
    private TextView logText;
    private TextView updateStatusText;
    private ScrollView logScroll;
    private ConnectionRingView connectionRingView;
    private Button runButton;
    private Button startScanButton;
    private Button pingBookmarksButton;
    private Button themeButton;
    private Button languageButton;
    private Button advancedTuningButton;
    private Button sniScanTabButton;
    private Button sniSavedTabButton;
    private Button navHomeButton;
    private Button navConfigsButton;
    private Button navScanButton;
    private Button navLogsButton;
    private Button navSupportButton;
    private Button checkUpdateButton;
    private Button githubProjectButton;
    private Switch modeSwitch;
    private Switch manualBestSwitch;
    private Button manualConfigsTabButton;
    private Button suggestedConfigsTabButton;
    private LinearLayout bookmarksContainer;
    private TextView bookmarkTitleText;
    private String latestUpdateUrl = GitHubSync.FALLBACK_APK_URL;
    private GitHubSync.UpdateInfo pendingUpdateNotification;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeStore.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        persianUi = LanguageStore.isPersian(this);

        bindViews();
        applySystemBarInsets();
        profileStore = new ProfileStore(this);
        profiles = profileStore.loadProfiles();
        autoMode = profileStore.isAutoMode();
        pickBestManualMode = profileStore.isPickBestManual();
        selectedId = autoMode ? profileStore.getSelectedSuggestedId(profiles) : profileStore.getSelectedManualId(profiles);

        runButton.setOnClickListener(v -> toggleConnection());
        findViewById(R.id.menuButton).setOnClickListener(v -> showHamburgerMenu());
        themeButton.setOnClickListener(v -> toggleTheme());
        languageButton.setOnClickListener(v -> toggleLanguage());
        findViewById(R.id.addConfigButton).setOnClickListener(v -> showConfigEditor(null));
        findViewById(R.id.editConfigButton).setOnClickListener(v -> editSelectedProfile());
        findViewById(R.id.deleteConfigButton).setOnClickListener(v -> confirmDeleteCurrentTabProfiles());
        findViewById(R.id.importClipboardButton).setOnClickListener(v -> importConfigsFromClipboard());
        findViewById(R.id.suggestedConfigButton).setOnClickListener(v -> addSuggestedConfigs());
        advancedTuningButton.setOnClickListener(v -> showAdvancedTuningDialog());
        manualConfigsTabButton.setOnClickListener(v -> showConfigsPage(false));
        suggestedConfigsTabButton.setOnClickListener(v -> showConfigsPage(true));
        manualBestSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            pickBestManualMode = isChecked;
            profileStore.setPickBestManual(pickBestManualMode);
            updateModeSwitch();
        });
        modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoMode = isChecked;
            profileStore.setAutoMode(autoMode);
            selectedId = autoMode ? profileStore.getSelectedSuggestedId(profiles) : profileStore.getSelectedManualId(profiles);
            updateModeSwitch();
            refreshProfiles();
        });
        pingBookmarksButton.setOnClickListener(v -> pingBookmarkedSni());
        findViewById(R.id.creditText).setOnClickListener(v -> openTelegramSupport());
        checkUpdateButton.setOnClickListener(v -> checkForUpdates(true));
        githubProjectButton.setOnClickListener(v -> GitHubSync.openUrl(this, GitHubSync.PROJECT_URL));
        sniScanTabButton.setOnClickListener(v -> {
            if (showingSavedSni) {
                showSniSubTab("scan");
                return;
            }
            toggleScanner();
        });
        sniSavedTabButton.setOnClickListener(v -> showSniSubTab("saved"));
        navHomeButton.setOnClickListener(v -> showTab("home"));
        navConfigsButton.setOnClickListener(v -> showTab("configs"));
        navScanButton.setOnClickListener(v -> showTab("scan"));
        navLogsButton.setOnClickListener(v -> showTab("logs"));
        navSupportButton.setOnClickListener(v -> showTab("support"));
        mainHandler.post(blinkRunnable);
        enableInnerResultScrolling();
        refreshProfiles();
        refreshBookmarks();
        refreshActiveText();
        updateModeSwitch();
        seedScannerDefaults();
        onProxyState(ProxyService.isRunning(), ProxyService.getActiveTargetLabel(), ProxyService.getTrafficSummary());
        homeVersionText.setText("UAC Spoofer v" + GitHubSync.CURRENT_VERSION);
        updateThemeButton();
        showTab("home");
        applyLanguage();
        ensureStartsAtTop();
        checkForUpdates(false);
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
        toast(persianUi ? "برای خروج دوباره دکمه برگشت را بزنید." : "Press BACK again to exit.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7101 && pendingUpdateNotification != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            GitHubSync.UpdateInfo info = pendingUpdateNotification;
            pendingUpdateNotification = null;
            showUpdateNotification(info);
        }
    }

    private void bindViews() {
        rootLayout = findViewById(R.id.rootLayout);
        pageScroll = findViewById(R.id.pageScroll);
        mainContent = findViewById(R.id.mainContent);
        bottomNav = findViewById(R.id.bottomNav);
        statusSection = findViewById(R.id.statusSection);
        homeSpacer = findViewById(R.id.homeSpacer);
        configsSection = findViewById(R.id.configsSection);
        scannerSection = findViewById(R.id.scannerSection);
        scanPanel = findViewById(R.id.scanPanel);
        bookmarksSection = findViewById(R.id.bookmarksSection);
        logsSection = findViewById(R.id.logsSection);
        supportSection = findViewById(R.id.supportSection);
        statusText = findViewById(R.id.statusText);
        activeConfigText = findViewById(R.id.activeConfigText);
        targetText = findViewById(R.id.targetText);
        connectedPingText = findViewById(R.id.connectedPingText);
        trafficText = findViewById(R.id.trafficText);
        homeVersionText = findViewById(R.id.homeVersionText);
        configsContainer = findViewById(R.id.configsContainer);
        scannerStatusText = findViewById(R.id.scannerStatusText);
        scannerResultsScroll = findViewById(R.id.scannerResultsScroll);
        resultsContainer = findViewById(R.id.resultsContainer);
        logText = findViewById(R.id.logText);
        updateStatusText = findViewById(R.id.updateStatusText);
        logScroll = findViewById(R.id.logScroll);
        connectionRingView = findViewById(R.id.connectionRingView);
        runButton = findViewById(R.id.runButton);
        startScanButton = findViewById(R.id.sniScanTabButton);
        pingBookmarksButton = findViewById(R.id.pingBookmarksButton);
        bookmarksContainer = findViewById(R.id.bookmarksContainer);
        bookmarkTitleText = findViewById(R.id.bookmarkTitleText);
        themeButton = findViewById(R.id.themeButton);
        languageButton = findViewById(R.id.languageButton);
        advancedTuningButton = findViewById(R.id.advancedTuningButton);
        modeSwitch = findViewById(R.id.modeSwitch);
        manualBestSwitch = findViewById(R.id.manualBestSwitch);
        manualConfigsTabButton = findViewById(R.id.manualConfigsTabButton);
        suggestedConfigsTabButton = findViewById(R.id.suggestedConfigsTabButton);
        sniScanTabButton = findViewById(R.id.sniScanTabButton);
        sniSavedTabButton = findViewById(R.id.sniSavedTabButton);
        navHomeButton = findViewById(R.id.navHomeButton);
        navConfigsButton = findViewById(R.id.navConfigsButton);
        navScanButton = findViewById(R.id.navScanButton);
        navLogsButton = findViewById(R.id.navLogsButton);
        navSupportButton = findViewById(R.id.navSupportButton);
        checkUpdateButton = findViewById(R.id.checkUpdateButton);
        githubProjectButton = findViewById(R.id.githubProjectButton);
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            rootLayout.setOnApplyWindowInsetsListener((view, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                view.setPadding(0, top, 0, bottom);
                return insets;
            });
            rootLayout.requestApplyInsets();
            return;
        }
        rootLayout.setPadding(0, statusBarHeight(), 0, navigationBarHeight());
    }

    private int statusBarHeight() {
        return systemDimension("status_bar_height");
    }

    private int navigationBarHeight() {
        return systemDimension("navigation_bar_height");
    }

    private int systemDimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void enableInnerResultScrolling() {
        scannerResultsScroll.setVerticalScrollBarEnabled(true);
        scannerResultsScroll.setScrollbarFadingEnabled(false);
        scannerResultsScroll.setFocusable(false);
        scannerResultsScroll.setFocusableInTouchMode(false);
        resultsContainer.setFocusable(false);
        resultsContainer.setFocusableInTouchMode(false);
    }

    private void toggleLanguage() {
        persianUi = !persianUi;
        LanguageStore.setPersian(this, persianUi);
        applyLanguage();
        toast(persianUi ? "زبان برنامه فارسی شد." : "Language changed to English.");
    }

    private void applyLanguage() {
        updateThemeButton();
        updateModeSwitch();
        refreshProfiles();
        refreshBookmarks();
        refreshActiveText();
        updatePingSavedButtonState();
        onProxyState(ProxyService.isRunning(), ProxyService.getActiveTargetLabel(), ProxyService.getTrafficSummary());
        translateTree(rootLayout);
        if (languageButton != null) {
            languageButton.setText("فارسی/English");
        }
        if (homeVersionText != null) {
            homeVersionText.setText("UAC Spoofer v" + GitHubSync.CURRENT_VERSION);
        }
        targetText.setTextDirection(View.TEXT_DIRECTION_LTR);
        connectedPingText.setTextDirection(View.TEXT_DIRECTION_LTR);
        trafficText.setTextDirection(View.TEXT_DIRECTION_LTR);
        logText.setTextDirection(View.TEXT_DIRECTION_LTR);
    }

    private void translateTree(View view) {
        if (view == null) {
            return;
        }
        if (view instanceof TextView && view != logText && view != targetText && view != homeVersionText) {
            TextView textView = (TextView) view;
            CharSequence text = textView.getText();
            if (text != null) {
                textView.setText(localizeStatic(text.toString()));
            }
            if (textView instanceof EditText) {
                CharSequence hint = ((EditText) textView).getHint();
                if (hint != null) {
                    ((EditText) textView).setHint(localizeStatic(hint.toString()));
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                translateTree(group.getChildAt(i));
            }
        }
    }

    private String localizeStatic(String text) {
        String value = text == null ? "" : text;
        String mapped = pair(value, "Menu", "منو");
        if (mapped != null) return mapped;
        mapped = pair(value, "Xray tunnel control", "کنترل تونل Xray");
        if (mapped != null) return mapped;
        mapped = pair(value, "Light", "روشن");
        if (mapped != null) return mapped;
        mapped = pair(value, "Dark", "تیره");
        if (mapped != null) return mapped;
        mapped = pair(value, "VPN is OFF", "VPN خاموش است");
        if (mapped != null) return mapped;
        mapped = pair(value, "VPN is ON", "VPN روشن است");
        if (mapped != null) return mapped;
        mapped = pair(value, "CONNECT", "اتصال");
        if (mapped != null) return mapped;
        mapped = pair(value, "DISCONNECT", "قطع اتصال");
        if (mapped != null) return mapped;
        mapped = pair(value, "CONNECTING", "در حال اتصال");
        if (mapped != null) return mapped;
        mapped = pair(value, "Active config", "کانفیگ فعال");
        if (mapped != null) return mapped;
        mapped = pair(value, "No config selected", "هیچ کانفیگی انتخاب نشده");
        if (mapped != null) return mapped;
        mapped = pair(value, "Auto Mode", "حالت خودکار");
        if (mapped != null) return mapped;
        mapped = pair(value, "Manual Mode", "حالت دستی");
        if (mapped != null) return mapped;
        mapped = pair(value, "Pick Best Manual Config", "انتخاب بهترین کانفیگ دستی");
        if (mapped != null) return mapped;
        mapped = pair(value, "Configs", "کانفیگ‌ها");
        if (mapped != null) return mapped;
        mapped = pair(value, "Add", "افزودن");
        if (mapped != null) return mapped;
        mapped = pair(value, "Edit", "ویرایش");
        if (mapped != null) return mapped;
        mapped = pair(value, "Delete", "حذف");
        if (mapped != null) return mapped;
        mapped = pair(value, "Delete All", "حذف همه");
        if (mapped != null) return mapped;
        mapped = pair(value, "Clipboard", "کلیپ‌بورد");
        if (mapped != null) return mapped;
        mapped = pair(value, "Load Suggested", "دریافت پیشنهادی‌ها");
        if (mapped != null) return mapped;
        mapped = pair(value, "Manual", "دستی");
        if (mapped != null) return mapped;
        mapped = pair(value, "Suggested", "پیشنهادی");
        if (mapped != null) return mapped;
        mapped = pair(value, "SNI Lab", "آزمایشگاه SNI");
        if (mapped != null) return mapped;
        mapped = pair(value, "Scan", "اسکن");
        if (mapped != null) return mapped;
        mapped = pair(value, "Saved", "ذخیره‌شده");
        if (mapped != null) return mapped;
        mapped = pair(value, "Scanner idle", "اسکنر آماده است");
        if (mapped != null) return mapped;
        mapped = pair(value, "Bookmarked SNI", "SNIهای ذخیره‌شده");
        if (mapped != null) return mapped;
        mapped = pair(value, "Saved SNI routes stay here and can be pinged again any time.", "SNIهای ذخیره‌شده اینجا می‌مانند و هر زمان می‌توانید دوباره پینگ بگیرید.");
        if (mapped != null) return mapped;
        mapped = pair(value, "Ping Saved SNI", "پینگ SNIهای ذخیره‌شده");
        if (mapped != null) return mapped;
        mapped = pair(value, "Live Logs", "لاگ زنده");
        if (mapped != null) return mapped;
        mapped = pair(value, "Support", "پشتیبانی");
        if (mapped != null) return mapped;
        mapped = pair(value, "Need help with configs, SNI results, or route testing?", "برای کانفیگ، نتیجه‌های SNI یا تست مسیر کمک می‌خواهید؟");
        if (mapped != null) return mapped;
        mapped = pair(value, "Checking GitHub updates...", "در حال بررسی آپدیت GitHub...");
        if (mapped != null) return mapped;
        mapped = pair(value, "Check for Update", "بررسی آپدیت");
        if (mapped != null) return mapped;
        mapped = pair(value, "Open Releases", "باز کردن نسخه‌ها");
        if (mapped != null) return mapped;
        mapped = pair(value, "Download Update", "دانلود آپدیت");
        if (mapped != null) return mapped;
        mapped = pair(value, "GitHub Project / Star", "پروژه GitHub / ستاره");
        if (mapped != null) return mapped;
        mapped = pair(value, "Support the project by giving it a star on GitHub.", "با ستاره دادن به پروژه در GitHub از آن حمایت کنید.");
        if (mapped != null) return mapped;
        mapped = pair(value, "Open Telegram Channel\nt.me/UacSniSpoofer", "باز کردن کانال تلگرام\nt.me/UacSniSpoofer");
        if (mapped != null) return mapped;
        mapped = pair(value, "credits to behroozuac", "با تشکر از behroozuac");
        if (mapped != null) return mapped;
        mapped = pair(value, "Home", "خانه");
        if (mapped != null) return mapped;
        mapped = pair(value, "SNI", "SNI");
        if (mapped != null) return mapped;
        mapped = pair(value, "Logs", "لاگ‌ها");
        if (mapped != null) return mapped;
        mapped = pair(value, "Cancel", "لغو");
        if (mapped != null) return mapped;
        mapped = pair(value, "Save", "ذخیره");
        if (mapped != null) return mapped;
        mapped = pair(value, "Stop", "توقف");
        if (mapped != null) return mapped;
        mapped = pair(value, "SAVED", "ذخیره شد");
        if (mapped != null) return mapped;
        mapped = pair(value, "SAVE", "ذخیره");
        if (mapped != null) return mapped;
        mapped = pair(value, "REMOVE", "حذف");
        if (mapped != null) return mapped;
        return value;
    }

    private String pair(String value, String english, String persian) {
        if (value.equals(english) || value.equals(persian)) {
            return persianUi ? persian : english;
        }
        return null;
    }

    private String runLabel() {
        return persianUi ? "اتصال" : "CONNECT";
    }

    private String disconnectLabel() {
        return persianUi ? "قطع اتصال" : "DISCONNECT";
    }

    private String connectingLabel() {
        return persianUi ? "در حال اتصال" : "CONNECTING";
    }

    private String vpnOffText() {
        return persianUi ? "VPN خاموش است" : "VPN is OFF";
    }

    private String vpnOnText() {
        return persianUi ? "VPN روشن است" : "VPN is ON";
    }

    private String pingEmptyText() {
        return persianUi ? "پینگ --" : "Ping --";
    }

    private String pingValueText(double latencyMs) {
        return persianUi
                ? String.format(Locale.US, "پینگ %.0f ms", latencyMs)
                : String.format(Locale.US, "Ping %.0f ms", latencyMs);
    }

    private void syncRemoteConfigs() {
        GitHubSync.syncConfigs(this, (added, parsed, error) -> mainHandler.post(() -> {
            if (!error.isEmpty()) {
                ProxyService.logEvent("GitHub config sync failed: " + error);
                scannerStatusText.setText(persianUi ? "کانفیگ‌های پیشنهادی اضافه شد. همگام‌سازی GitHub ناموفق بود." : "Suggested configs added. GitHub sync failed.");
                return;
            }
            profiles = profileStore.loadProfiles();
            selectedId = autoMode ? profileStore.getSelectedSuggestedId(profiles) : profileStore.getSelectedManualId(profiles);
            refreshProfiles();
            refreshActiveText();
            scannerStatusText.setText(persianUi
                    ? "کانفیگ‌های GitHub همگام شد: " + parsed + " خوانده شد، " + added + " فعال"
                    : "GitHub configs synced: " + parsed + " parsed, " + added + " active");
        }));
    }

    private void addSuggestedConfigs() {
        int added = profileStore.addSuggestedProfiles();
        profiles = profileStore.loadProfiles();
        showingSuggestedConfigs = true;
        if (autoMode) {
            selectedId = profileStore.getSelectedSuggestedId(profiles);
        }
        updateModeSwitch();
        refreshProfiles();
        toast(added > 0
                ? (persianUi ? "کانفیگ پیشنهادی اضافه شد: " + added : "Suggested configs added: " + added)
                : (persianUi ? "کانفیگ‌های پیشنهادی از قبل وجود دارند." : "Suggested configs already exist."));
        scannerStatusText.setText(persianUi ? "در حال افزودن کانفیگ‌های پیشنهادی از GitHub..." : "Adding suggested configs from GitHub...");
        syncRemoteConfigs();
    }

    private void editSelectedProfile() {
        ProxyConfig selected = selectedProfileOrNull();
        if (selected == null) {
            toast(persianUi ? "اول یک کانفیگ انتخاب یا اضافه کنید." : "Select or add a config first.");
            return;
        }
        showConfigEditor(selected);
    }

    private void deleteSelectedProfile() {
        ProxyConfig selected = selectedProfileOrNull();
        if (selected == null) {
            toast(persianUi ? "اول یک کانفیگ انتخاب کنید." : "Select a config first.");
            return;
        }
        confirmDeleteProfiles(singletonProfileList(selected));
    }

    private void confirmDeleteCurrentTabProfiles() {
        List<ProxyConfig> visible = showingSuggestedConfigs ? suggestedProfiles() : manualProfiles();
        if (visible.isEmpty()) {
            toast(showingSuggestedConfigs
                    ? (persianUi ? "کانفیگ پیشنهادی برای حذف وجود ندارد." : "No suggested configs to delete.")
                    : (persianUi ? "کانفیگ دستی برای حذف وجود ندارد." : "No manual configs to delete."));
            return;
        }
        String tab = showingSuggestedConfigs
                ? (persianUi ? "پیشنهادی" : "suggested")
                : (persianUi ? "دستی" : "manual");
        new AlertDialog.Builder(this)
                .setTitle(persianUi
                        ? "حذف همه کانفیگ‌های " + tab
                        : "Delete All " + (showingSuggestedConfigs ? "Suggested" : "Manual"))
                .setMessage(persianUi
                        ? "همه " + visible.size() + " کانفیگ " + tab + " حذف شوند؟"
                        : "Delete all " + visible.size() + " " + tab + " configs?")
                .setNegativeButton(persianUi ? "لغو" : "Cancel", null)
                .setPositiveButton(persianUi ? "حذف همه" : "Delete All", (dialog, which) -> deleteProfiles(visible))
                .show();
    }

    private List<ProxyConfig> singletonProfileList(ProxyConfig profile) {
        List<ProxyConfig> out = new ArrayList<>();
        out.add(profile);
        return out;
    }

    private void confirmDeleteProfiles(List<ProxyConfig> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        String message = targets.size() == 1
                ? (persianUi ? targets.get(0).name + " حذف شود؟" : "Delete " + targets.get(0).name + "?")
                : (persianUi ? targets.size() + " کانفیگ از این بخش حذف شود؟" : "Delete " + targets.size() + " configs from this tab?");
        new AlertDialog.Builder(this)
                .setTitle(targets.size() == 1
                        ? (persianUi ? "حذف کانفیگ" : "Delete Config")
                        : (persianUi ? "حذف کانفیگ‌ها" : "Delete Configs"))
                .setMessage(message)
                .setNegativeButton(persianUi ? "لغو" : "Cancel", null)
                .setPositiveButton(persianUi ? "حذف" : "Delete", (dialog, which) -> deleteProfiles(targets))
                .show();
    }

    private void deleteProfiles(List<ProxyConfig> targets) {
        Set<String> ids = new LinkedHashSet<>();
        for (ProxyConfig target : targets) {
            ids.add(target.id);
        }
        boolean removingActive = ids.contains(selectedId);
        profiles.removeIf(profile -> ids.contains(profile.id));
        if (ProxyService.isRunning() && removingActive) {
            stopProxy();
        }
        selectedId = autoMode ? profileStore.getSelectedSuggestedId(profiles) : profileStore.getSelectedManualId(profiles);
        saveProfiles();
        refreshProfiles();
        toast(ids.size() == 1
                ? (persianUi ? "کانفیگ حذف شد." : "Config deleted.")
                : (persianUi ? ids.size() + " کانفیگ حذف شد." : ids.size() + " configs deleted."));
    }

    private void checkForUpdates(boolean manual) {
        updateStatusText.setText(persianUi ? "در حال بررسی آپدیت GitHub..." : "Checking GitHub updates...");
        checkUpdateButton.setEnabled(false);
        GitHubSync.checkUpdate((info, error) -> mainHandler.post(() -> {
            checkUpdateButton.setEnabled(true);
            latestUpdateUrl = info.url == null || info.url.isEmpty() ? GitHubSync.FALLBACK_APK_URL : info.url;
            if (!error.isEmpty()) {
                updateStatusText.setText(persianUi ? "بررسی آپدیت ناموفق بود. برای باز کردن نسخه‌ها بزنید." : "Update check failed. Tap to open releases.");
                checkUpdateButton.setText(persianUi ? "باز کردن نسخه‌ها" : "Open Releases");
                checkUpdateButton.setOnClickListener(v -> GitHubSync.openUrl(this, GitHubSync.PROJECT_URL + "/releases"));
                if (manual) {
                    toast(persianUi ? "بررسی آپدیت ممکن نیست: " + error : "Cannot check update: " + error);
                }
                return;
            }
            if (info.newer) {
                updateStatusText.setText(persianUi ? "نسخه جدید v" + info.version + " آماده است. برای دانلود دکمه را بزنید." : "New version v" + info.version + " is available. Tap the button to download.");
                checkUpdateButton.setText(persianUi ? "دانلود آپدیت" : "Download Update");
                checkUpdateButton.setOnClickListener(v -> GitHubSync.openUrl(this, latestUpdateUrl));
                showUpdateNotification(info);
            } else {
                updateStatusText.setText(persianUi ? "شما آخرین نسخه را دارید: v" + GitHubSync.CURRENT_VERSION : "You are on the latest version: v" + GitHubSync.CURRENT_VERSION);
                checkUpdateButton.setText(persianUi ? "بررسی آپدیت" : "Check for Update");
                checkUpdateButton.setOnClickListener(v -> checkForUpdates(true));
            }
        }));
    }

    private void showUpdateNotification(GitHubSync.UpdateInfo info) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            String channelId = "uac_updates";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "UAC Spoofer updates",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription("New release notifications");
                manager.createNotificationChannel(channel);
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingUpdateNotification = info;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7101);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(info.url));
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    7102,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, channelId)
                    : new Notification.Builder(this);
            builder.setSmallIcon(R.drawable.ic_stat_proxy)
                    .setContentTitle("UAC Spoofer update available")
                    .setContentText("Version v" + info.version + " is ready. Tap to download.")
                    .setStyle(new Notification.BigTextStyle().bigText("Version v" + info.version + " is ready. Tap to open GitHub and download the APK."))
                    .setColor(getResources().getColor(R.color.accent))
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);
            manager.notify(7103, builder.build());
        } catch (Exception e) {
            ProxyService.logEvent("Update notification failed: " + e.getMessage());
        }
    }

    private void refreshProfiles() {
        sortProfilesByPing();
        configsContainer.removeAllViews();
        updateConfigTabs();
        List<ProxyConfig> visible = showingSuggestedConfigs ? suggestedProfiles() : manualProfiles();
        addConfigSection(showingSuggestedConfigs ? "Suggested" : "Manual", visible);
        refreshActiveText();
    }

    private void showConfigsPage(boolean suggested) {
        showingSuggestedConfigs = suggested;
        refreshProfiles();
    }

    private void updateConfigTabs() {
        if (manualConfigsTabButton == null || suggestedConfigsTabButton == null) {
            return;
        }
        manualConfigsTabButton.setBackgroundResource(showingSuggestedConfigs ? R.drawable.bg_outline_button : R.drawable.bg_run);
        manualConfigsTabButton.setTextColor(showingSuggestedConfigs ? getResources().getColor(R.color.ink) : Color.WHITE);
        suggestedConfigsTabButton.setBackgroundResource(showingSuggestedConfigs ? R.drawable.bg_run : R.drawable.bg_outline_button);
        suggestedConfigsTabButton.setTextColor(showingSuggestedConfigs ? Color.WHITE : getResources().getColor(R.color.ink));
    }

    private void addConfigSection(String title, List<ProxyConfig> items) {
        TextView heading = new TextView(this);
        heading.setText((persianUi
                ? ("Suggested".equals(title) ? "پیشنهادی" : "دستی")
                : title) + " (" + items.size() + ")");
        heading.setTextColor(getResources().getColor(R.color.ink));
        heading.setTextSize(14);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headingParams.setMargins(0, configsContainer.getChildCount() == 0 ? 0 : dp(8), 0, dp(8));
        configsContainer.addView(heading, headingParams);

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Manual".equals(title)
                    ? (persianUi ? "کانفیگ دستی ندارید. یکی اضافه یا import کنید." : "No manual configs. Add or import one.")
                    : (persianUi ? "کانفیگ پیشنهادی ندارید. دریافت پیشنهادی‌ها را بزنید." : "No suggested configs. Tap Load Suggested."));
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(getResources().getColor(R.color.muted));
            empty.setTextSize(12);
            empty.setPadding(0, dp(10), 0, dp(10));
            empty.setBackgroundResource(R.drawable.bg_chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, dp(8));
            configsContainer.addView(empty, params);
            return;
        }
        for (ProxyConfig profile : items) {
            configsContainer.addView(profileRow(profile));
        }
    }

    private View profileRow(ProxyConfig profile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        boolean selected = profile.id.equals(selectedId);
        row.setBackgroundResource(selected ? R.drawable.bg_selected_config : R.drawable.bg_chip);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        String prefix = selected ? (persianUi ? "فعال  " : "ACTIVE  ") : "";
        title.setText(prefix + profile.name);
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button quickDelete = new Button(this);
        quickDelete.setText(persianUi ? "حذف" : "Delete");
        quickDelete.setAllCaps(false);
        quickDelete.setTextSize(11);
        quickDelete.setTypeface(null, android.graphics.Typeface.BOLD);
        quickDelete.setTextColor(getResources().getColor(R.color.danger));
        quickDelete.setBackgroundResource(R.drawable.bg_outline_button);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(82), dp(36));
        deleteParams.setMargins(dp(8), 0, 0, 0);
        titleRow.addView(quickDelete, deleteParams);

        TextView detail = new TextView(this);
        detail.setText(profile.configEndpointLabel() + "  /  " + profile.address + ":" + profile.port + "  /  " + profile.sni);
        detail.setTextColor(getResources().getColor(R.color.muted));
        detail.setTextSize(12);
        detail.setSingleLine(true);
        detail.setTextDirection(View.TEXT_DIRECTION_LTR);
        detail.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(detail);

        TextView ping = new TextView(this);
        ping.setText(profile.lastPingOk
                ? String.format(Locale.US, persianUi ? "تاخیر %.0f ms" : "LATENCY %.0f ms", profile.lastPingMs)
                : (persianUi ? "تاخیر --" : "LATENCY --"));
        ping.setTextColor(profile.lastPingOk ? getResources().getColor(R.color.accent_green) : getResources().getColor(R.color.muted));
        ping.setTextSize(12);
        ping.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        row.addView(ping);

        row.setOnClickListener(v -> {
            selectedId = profile.id;
            saveSelectedId(profile);
            seedScannerDefaults();
            refreshProfiles();
            if (ProxyService.isRunning()) {
                restartProxy();
            }
        });
        row.setOnLongClickListener(v -> {
            selectedId = profile.id;
            saveSelectedId(profile);
            refreshProfiles();
            showConfigEditor(profile);
            return true;
        });
        quickDelete.setOnClickListener(v -> confirmDeleteProfiles(singletonProfileList(profile)));
        return row;
    }

    private void refreshActiveText() {
        ProxyConfig selected = selectedProfileOrNull();
        if (selected == null) {
            activeConfigText.setText(autoMode
                    ? (persianUi ? "کانفیگ پیشنهادی انتخاب نشده" : "No suggested config selected")
                    : (persianUi ? "کانفیگ دستی انتخاب نشده" : "No manual config selected"));
            targetText.setText(autoMode
                    ? (persianUi ? "کانفیگ‌های پیشنهادی را دریافت کنید" : "Load suggested configs")
                    : (persianUi ? "یک کانفیگ دستی اضافه یا import کنید" : "Add or import a manual config"));
            connectedPingText.setText(pingEmptyText());
            return;
        }
        activeConfigText.setText(selected.name);
        targetText.setText(selected.targetLabel());
        connectedPingText.setText(selected.lastPingOk
                ? pingValueText(selected.lastPingMs)
                : pingEmptyText());
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

    private List<ProxyConfig> manualProfiles() {
        List<ProxyConfig> out = new ArrayList<>();
        for (ProxyConfig profile : profiles) {
            if (ProfileStore.isUserProfile(profile)) {
                out.add(profile);
            }
        }
        return out;
    }

    private List<ProxyConfig> suggestedProfiles() {
        List<ProxyConfig> out = new ArrayList<>();
        for (ProxyConfig profile : profiles) {
            if (ProfileStore.isSuggestedProfile(profile)) {
                out.add(profile);
            }
        }
        return out;
    }

    private ProxyConfig selectedProfileOrNull() {
        ProxyConfig selected = selectedProfileById(selectedId);
        if (selected != null) {
            return selected;
        }
        ProxyConfig fallback = autoMode ? firstSuggestedProfile() : firstManualOrAnyProfile();
        if (fallback != null) {
            selectedId = fallback.id;
            saveSelectedId(fallback);
            return fallback;
        }
        selectedId = "";
        profileStore.setSelectedId("");
        return null;
    }

    private ProxyConfig selectedProfileById(String id) {
        for (ProxyConfig profile : profiles) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return null;
    }

    private ProxyConfig firstManualOrAnyProfile() {
        for (ProxyConfig profile : profiles) {
            if (ProfileStore.isUserProfile(profile)) {
                return profile;
            }
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    private ProxyConfig firstSuggestedProfile() {
        for (ProxyConfig profile : profiles) {
            if (ProfileStore.isSuggestedProfile(profile)) {
                return profile;
            }
        }
        return null;
    }

    private void saveSelectedId(ProxyConfig profile) {
        if (ProfileStore.isSuggestedProfile(profile)) {
            profileStore.setSelectedSuggestedId(profile.id);
        } else {
            profileStore.setSelectedManualId(profile.id);
        }
    }

    private void updateModeSwitch() {
        if (modeSwitch == null) {
            return;
        }
        modeSwitch.setOnCheckedChangeListener(null);
        modeSwitch.setChecked(autoMode);
        modeSwitch.setText(autoMode
                ? (persianUi ? "حالت خودکار" : "Auto Mode")
                : (persianUi ? "حالت دستی" : "Manual Mode"));
        if (manualBestSwitch != null) {
            manualBestSwitch.setOnCheckedChangeListener(null);
            manualBestSwitch.setChecked(pickBestManualMode);
            manualBestSwitch.setText(persianUi ? "انتخاب بهترین کانفیگ دستی" : "Pick Best Manual Config");
            manualBestSwitch.setVisibility(autoMode ? View.GONE : View.VISIBLE);
            manualBestSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                pickBestManualMode = isChecked;
                profileStore.setPickBestManual(pickBestManualMode);
                updateModeSwitch();
            });
        }
        modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoMode = isChecked;
            profileStore.setAutoMode(autoMode);
            selectedId = autoMode ? profileStore.getSelectedSuggestedId(profiles) : profileStore.getSelectedManualId(profiles);
            updateModeSwitch();
            refreshProfiles();
        });
    }

    private void toggleConnection() {
        if (ProxyService.isRunning() || pingingConfigs || waitingForProxyBeforeVpn || startAfterVpnConsent) {
            stopProxy();
            statusText.setText(persianUi ? "در حال قطع اتصال" : "Disconnecting");
            statusText.setTextColor(getResources().getColor(R.color.ink));
            runButton.setText(runLabel());
            runButton.setBackgroundResource(R.drawable.bg_connect_button);
            connectionRingView.setMode(ConnectionRingView.MODE_LOADING);
            return;
        }
        if (autoMode) {
            pingConfigsThenRun();
        } else if (pickBestManualMode) {
            pickBestManualConfig(true);
        } else {
            requestVpnAndStartSelectedProxy();
        }
    }

    private void showConfigPingConnectingState(String label, int total) {
        statusText.setText(persianUi ? "در حال اتصال" : "Connecting");
        statusText.setTextColor(getResources().getColor(R.color.ink));
        statusText.setBackgroundResource(R.drawable.bg_modern_panel);
        runButton.setText(connectingLabel());
        runButton.setEnabled(true);
        runButton.setBackgroundResource(R.drawable.bg_connect_button);
        connectionRingView.setMode(ConnectionRingView.MODE_LOADING);
        connectedPingText.setText(persianUi ? "در حال اندازه‌گیری پینگ..." : "Ping measuring...");
        scannerStatusText.setText(label + " 0/" + total + "  OK: 0");
    }

    private void showIdleConnectionState() {
        statusText.setText(vpnOffText());
        statusText.setTextColor(getResources().getColor(R.color.muted));
        statusText.setBackgroundResource(R.drawable.bg_modern_panel);
        runButton.setText(runLabel());
        runButton.setEnabled(true);
        runButton.setBackgroundResource(R.drawable.bg_connect_button);
        connectionRingView.setMode(ConnectionRingView.MODE_IDLE);
    }

    private void pingConfigsThenRun() {
        List<ProxyConfig> candidates = suggestedProfiles();
        if (candidates.isEmpty()) {
            toast(persianUi ? "اول کانفیگ‌های پیشنهادی را دریافت کنید یا به حالت دستی بروید." : "Load suggested configs first, or switch to Manual Mode.");
            return;
        }
        stopConfigPinger();
        for (ProxyConfig profile : candidates) {
            profile.lastPingOk = false;
            profile.lastPingMs = 0;
        }
        pingingConfigs = true;
        showConfigPingConnectingState(persianUi ? "در حال پینگ کانفیگ‌های پیشنهادی" : "Pinging suggested configs", candidates.size());
        refreshProfiles();

        configPinger = new ConfigPinger();
        configPinger.start(new ArrayList<>(candidates), 8, CONFIG_PING_TIMEOUT_SECONDS, new ConfigPinger.Callback() {
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
                    scannerStatusText.setText((persianUi ? "پینگ کانفیگ " : "Config ping ") + done + "/" + total + "  OK: " + countOkProfiles(candidates));
                    refreshProfiles();
                });
            }

            @Override
            public void onFinished(boolean cancelled) {
                mainHandler.post(() -> {
                    pingingConfigs = false;
                    if (cancelled) {
                        showIdleConnectionState();
                        return;
                    }
                    ProxyConfig best = bestPingProfile(candidates);
                    if (best != null) {
                        selectedId = best.id;
                        autoMode = true;
                        profileStore.setAutoMode(true);
                        profileStore.setSelectedSuggestedId(selectedId);
                        updateModeSwitch();
                        toast((persianUi ? "بهترین کانفیگ انتخاب شد: " : "Best config selected: ") + best.name);
                        saveProfiles();
                        refreshProfiles();
                        statusText.setText(persianUi ? "در حال اتصال" : "Connecting");
                        runButton.setText(connectingLabel());
                        runButton.setEnabled(true);
                        runButton.setBackgroundResource(R.drawable.bg_connect_button);
                        connectionRingView.setMode(ConnectionRingView.MODE_LOADING);
                        requestVpnAndStartSelectedProxy();
                    } else {
                        showIdleConnectionState();
                        connectedPingText.setText(pingEmptyText());
                        scannerStatusText.setText(persianUi ? "هیچ کانفیگ پیشنهادی پینگ موفق نداشت." : "No suggested config passed ping.");
                        toast(persianUi ? "هیچ کانفیگ پیشنهادی پینگ موفق نداشت." : "No suggested config passed ping.");
                        refreshProfiles();
                    }
                });
            }
        });
    }

    private void pickBestManualConfig(boolean connectAfterPick) {
        List<ProxyConfig> candidates = manualProfiles();
        if (candidates.isEmpty()) {
            toast(persianUi ? "کانفیگ دستی برای پینگ وجود ندارد." : "No manual configs to ping.");
            updateModeSwitch();
            return;
        }
        stopConfigPinger();
        for (ProxyConfig profile : candidates) {
            profile.lastPingOk = false;
            profile.lastPingMs = 0;
        }
        pingingConfigs = true;
        manualBestSwitch.setText(persianUi ? "در حال پینگ دستی..." : "Pinging Manual...");
        showConfigPingConnectingState(persianUi ? "در حال پینگ کانفیگ‌های دستی" : "Pinging manual configs", candidates.size());
        refreshProfiles();

        configPinger = new ConfigPinger();
        configPinger.start(new ArrayList<>(candidates), 8, CONFIG_PING_TIMEOUT_SECONDS, new ConfigPinger.Callback() {
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
                    scannerStatusText.setText((persianUi ? "پینگ دستی " : "Manual ping ") + done + "/" + total + "  OK: " + countOkProfiles(candidates));
                    refreshProfiles();
                });
            }

            @Override
            public void onFinished(boolean cancelled) {
                mainHandler.post(() -> {
                    pingingConfigs = false;
                    updateModeSwitch();
                    if (cancelled) {
                        showIdleConnectionState();
                        return;
                    }
                    ProxyConfig best = bestPingProfile(candidates);
                    if (best == null) {
                        showIdleConnectionState();
                        connectedPingText.setText(pingEmptyText());
                        scannerStatusText.setText(persianUi ? "هیچ کانفیگ دستی پینگ موفق نداشت." : "No manual config passed ping.");
                        toast(persianUi ? "هیچ کانفیگ دستی پینگ موفق نداشت." : "No manual config passed ping.");
                        refreshProfiles();
                        return;
                    }
                    autoMode = false;
                    profileStore.setAutoMode(false);
                    selectedId = best.id;
                    profileStore.setSelectedManualId(selectedId);
                    saveProfiles();
                    updateModeSwitch();
                    refreshProfiles();
                    scannerStatusText.setText((persianUi ? "بهترین کانفیگ دستی: " : "Best manual config: ") + best.name);
                    toast((persianUi ? "بهترین کانفیگ دستی انتخاب شد: " : "Best manual config selected: ") + best.name);
                    if (connectAfterPick) {
                        statusText.setText(persianUi ? "در حال اتصال" : "Connecting");
                        runButton.setText(connectingLabel());
                        runButton.setEnabled(true);
                        runButton.setBackgroundResource(R.drawable.bg_connect_button);
                        connectionRingView.setMode(ConnectionRingView.MODE_LOADING);
                        requestVpnAndStartSelectedProxy();
                    } else if (ProxyService.isRunning()) {
                        restartProxy();
                    }
                });
            }
        });
    }

    private ProxyConfig bestPingProfile(List<ProxyConfig> candidates) {
        ProxyConfig best = null;
        for (ProxyConfig profile : candidates) {
            if (!profile.lastPingOk) {
                continue;
            }
            if (best == null || profile.lastPingMs < best.lastPingMs) {
                best = profile;
            }
        }
        return best;
    }

    private int countOkProfiles(List<ProxyConfig> candidates) {
        int count = 0;
        for (ProxyConfig profile : candidates) {
            if (profile.lastPingOk) {
                count++;
            }
        }
        return count;
    }

    private void pingSelectedManualProfileForDisplay(ProxyConfig selected) {
        if (selected == null) {
            return;
        }
        stopConfigPinger();
        selected.lastPingOk = false;
        selected.lastPingMs = 0;
        connectedPingText.setText(persianUi ? "در حال اندازه‌گیری پینگ..." : "Ping measuring...");
        scannerStatusText.setText(persianUi ? "در حال پینگ کانفیگ دستی انتخاب‌شده..." : "Pinging selected manual config...");
        String selectedConfigId = selected.id;
        configPinger = new ConfigPinger();
        configPinger.start(singletonProfileList(selected), 1, CONFIG_PING_TIMEOUT_SECONDS, new ConfigPinger.Callback() {
            @Override
            public void onResult(ProxyConfig config, boolean ok, double latencyMs, int done, int total) {
                mainHandler.post(() -> {
                    for (ProxyConfig profile : profiles) {
                        if (profile.id.equals(selectedConfigId)) {
                            profile.lastPingOk = ok;
                            profile.lastPingMs = ok ? latencyMs : 0;
                            break;
                        }
                    }
                    saveProfiles();
                    refreshProfiles();
                    connectedPingText.setText(ok
                            ? pingValueText(latencyMs)
                            : pingEmptyText());
                    scannerStatusText.setText(ok
                            ? String.format(Locale.US, persianUi ? "پینگ کانفیگ دستی: %.0f ms" : "Selected manual ping: %.0f ms", latencyMs)
                            : (persianUi ? "کانفیگ دستی انتخاب‌شده پینگ موفق نداشت." : "Selected manual config did not pass ping."));
                });
            }

            @Override
            public void onFinished(boolean cancelled) {
                mainHandler.post(() -> {
                    if (configPinger != null) {
                        configPinger = null;
                    }
                    if (cancelled) {
                        return;
                    }
                    ProxyConfig selectedNow = selectedProfileById(selectedConfigId);
                    if (selectedNow != null && !selectedNow.lastPingOk) {
                        connectedPingText.setText(pingEmptyText());
                    }
                });
            }
        });
    }

    private void stopConfigPinger() {
        if (configPinger != null) {
            configPinger.cancel();
            configPinger = null;
        }
        pingingConfigs = false;
    }
    private void startSelectedProxy() {
        ProxyConfig config = selectedProfileOrNull();
        if (config == null) {
            statusText.setText(vpnOffText());
            statusText.setTextColor(getResources().getColor(R.color.muted));
            runButton.setText(runLabel());
            runButton.setEnabled(true);
            connectionRingView.setMode(ConnectionRingView.MODE_IDLE);
            toast(autoMode
                    ? (persianUi ? "اول کانفیگ‌های پیشنهادی را دریافت کنید." : "Load suggested configs first.")
                    : (persianUi ? "اول یک کانفیگ دستی اضافه یا import کنید." : "Add or import a manual config first."));
            return;
        }
        if (config.sourceUri == null || config.sourceUri.trim().isEmpty()) {
            statusText.setText(vpnOffText());
            statusText.setTextColor(getResources().getColor(R.color.muted));
            runButton.setText(runLabel());
            runButton.setEnabled(true);
            connectionRingView.setMode(ConnectionRingView.MODE_IDLE);
            toast(persianUi ? "Source URI برای کانفیگ Xray لازم است." : "Source URI is required for Xray configs.");
            return;
        }
        if (config.address == null || config.address.trim().isEmpty()
                || config.sni == null || config.sni.trim().isEmpty()) {
            statusText.setText(vpnOffText());
            statusText.setTextColor(getResources().getColor(R.color.muted));
            runButton.setText(runLabel());
            runButton.setEnabled(true);
            connectionRingView.setMode(ConnectionRingView.MODE_IDLE);
            toast(persianUi ? "آدرس و SNI لازم هستند." : "Address and SNI are required.");
            return;
        }
        waitingForProxyBeforeVpn = true;
        statusText.setText(persianUi ? "در حال اتصال" : "Connecting");
        statusText.setTextColor(getResources().getColor(R.color.ink));
        runButton.setText(connectingLabel());
        runButton.setEnabled(true);
        connectionRingView.setMode(ConnectionRingView.MODE_LOADING);
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
        ProxyTuningStore.applyToIntent(this, intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        if (!autoMode && !pickBestManualMode) {
            pingSelectedManualProfileForDisplay(config);
        }
        mainHandler.postDelayed(() -> {
            if (waitingForProxyBeforeVpn && !ProxyService.isRunning()) {
                waitingForProxyBeforeVpn = false;
                String error = ProxyService.getLastError();
                statusText.setText(error.isEmpty()
                        ? (persianUi ? "پروکسی ناموفق بود" : "Proxy failed")
                        : (persianUi ? "پروکسی ناموفق بود: " + error : "Proxy failed: " + error));
                statusText.setBackgroundResource(R.drawable.bg_modern_panel);
                statusText.setTextColor(getResources().getColor(R.color.danger));
                runButton.setEnabled(true);
                runButton.setText(runLabel());
                runButton.setBackgroundResource(R.drawable.bg_connect_button);
                connectionRingView.setMode(ConnectionRingView.MODE_IDLE);
                toast(error.isEmpty()
                        ? (persianUi ? "پروکسی اجرا نشد. لاگ‌ها را بررسی کنید." : "Proxy did not start. Check logs.")
                        : error);
            }
        }, 3000);
    }

    private void requestVpnAndStartSelectedProxy() {
        ProxyConfig config = selectedProfileOrNull();
        if (config == null) {
            toast(autoMode
                    ? (persianUi ? "اول کانفیگ‌های پیشنهادی را دریافت کنید." : "Load suggested configs first.")
                    : (persianUi ? "اول یک کانفیگ دستی اضافه یا import کنید." : "Add or import a manual config first."));
            return;
        }
        if (config.sourceUri == null || config.sourceUri.trim().isEmpty()) {
            toast(persianUi ? "Source URI برای کانفیگ Xray لازم است." : "Source URI is required for Xray configs.");
            return;
        }
        if (config.address == null || config.address.trim().isEmpty()
                || config.sni == null || config.sni.trim().isEmpty()) {
            toast(persianUi ? "آدرس و SNI لازم هستند." : "Address and SNI are required.");
            return;
        }
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
                statusText.setText(vpnOffText());
                statusText.setTextColor(getResources().getColor(R.color.muted));
                runButton.setEnabled(true);
                runButton.setText(runLabel());
                runButton.setBackgroundResource(R.drawable.bg_connect_button);
                connectionRingView.setMode(ConnectionRingView.MODE_IDLE);
                toast(persianUi ? "اجازه VPN لازم است." : "VPN permission is required.");
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
        statusText.setText(vpnOffText());
        statusText.setTextColor(getResources().getColor(R.color.muted));
        runButton.setText(runLabel());
        runButton.setEnabled(true);
        runButton.setBackgroundResource(R.drawable.bg_connect_button);
        connectionRingView.setMode(ConnectionRingView.MODE_IDLE);
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

    private void showAdvancedTuningDialog() {
        final ProxyTuning[] draft = new ProxyTuning[]{ProxyTuningStore.load(this)};

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(16), dp(18), dp(14));
        shell.setBackgroundResource(R.drawable.bg_card);

        TextView title = new TextView(this);
        title.setText("Advanced / Tuning");
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        shell.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setTextColor(getResources().getColor(R.color.muted));
        summary.setTextSize(12);
        summary.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.setMargins(0, dp(4), 0, dp(12));
        shell.addView(summary, summaryParams);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button fast = dialogButton("Fast", false);
        Button balanced = dialogButton("Balanced", false);
        Button stealth = dialogButton("Stealth", false);
        Button custom = dialogButton("Custom", false);
        modeRow.addView(fast, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams balancedParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        balancedParams.setMargins(dp(6), 0, 0, 0);
        modeRow.addView(balanced, balancedParams);
        LinearLayout.LayoutParams stealthParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        stealthParams.setMargins(dp(6), 0, 0, 0);
        modeRow.addView(stealth, stealthParams);
        LinearLayout.LayoutParams customParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        customParams.setMargins(dp(6), 0, 0, 0);
        modeRow.addView(custom, customParams);
        form.addView(modeRow);

        Switch fakeProbeEnabled = tuningSwitch("Fake SNI probe", true);
        Switch strategyCacheEnabled = tuningSwitch("Strategy cache", true);
        EditText fakeProbeCount = edit("Fake probe count 0-3", "", InputType.TYPE_CLASS_NUMBER);
        EditText fakeProbeDelay = edit("Fake probe delay ms 0-150", "", InputType.TYPE_CLASS_NUMBER);
        EditText multiFragmentSize = edit("Multi fragment size 64/96/128/192/256", "", InputType.TYPE_CLASS_NUMBER);
        EditText sniSplitDelay = edit("SNI split delay ms 0-100", "", InputType.TYPE_CLASS_NUMBER);
        EditText tlsRecordDelay = edit("TLS record delay ms 0-100", "", InputType.TYPE_CLASS_NUMBER);
        EditText multiDelay = edit("Multi delay ms 0-25", "", InputType.TYPE_CLASS_NUMBER);
        EditText halfDelay = edit("Half delay ms 0-100", "", InputType.TYPE_CLASS_NUMBER);
        EditText routeTimeout = edit("Route probe timeout ms 1000-4000", "", InputType.TYPE_CLASS_NUMBER);
        Button logLevel = dialogButton("Log: Normal", false);

        form.addView(fakeProbeEnabled);
        form.addView(labeledInput("Fake probe count", fakeProbeCount));
        form.addView(labeledInput("Fake probe delay ms", fakeProbeDelay));
        form.addView(labeledInput("Multi fragment size", multiFragmentSize));
        form.addView(labeledInput("SNI split delay ms", sniSplitDelay));
        form.addView(labeledInput("TLS record delay ms", tlsRecordDelay));
        form.addView(labeledInput("Multi delay ms", multiDelay));
        form.addView(labeledInput("Half delay ms", halfDelay));
        form.addView(labeledInput("Route probe timeout ms", routeTimeout));
        form.addView(strategyCacheEnabled);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        logParams.setMargins(0, 0, 0, dp(8));
        form.addView(logLevel, logParams);

        Runnable refresh = () -> {
            ProxyTuning value = draft[0].sanitize();
            summary.setText(value.summary());
            setModeButtonState(fast, ProxyTuning.MODE_FAST.equals(value.mode));
            setModeButtonState(balanced, ProxyTuning.MODE_BALANCED.equals(value.mode));
            setModeButtonState(stealth, ProxyTuning.MODE_STEALTH.equals(value.mode));
            setModeButtonState(custom, ProxyTuning.MODE_CUSTOM.equals(value.mode));
            fakeProbeEnabled.setChecked(value.fakeProbeEnabled);
            strategyCacheEnabled.setChecked(value.strategyCacheEnabled);
            fakeProbeCount.setText(String.valueOf(value.fakeProbeCount));
            fakeProbeDelay.setText(String.valueOf(value.fakeProbeDelayMs));
            multiFragmentSize.setText(String.valueOf(value.multiFragmentSize));
            sniSplitDelay.setText(String.valueOf(value.sniSplitDelayMs));
            tlsRecordDelay.setText(String.valueOf(value.tlsRecordDelayMs));
            multiDelay.setText(String.valueOf(value.multiDelayMs));
            halfDelay.setText(String.valueOf(value.halfDelayMs));
            routeTimeout.setText(String.valueOf(value.routeProbeTimeoutMs));
            logLevel.setText("Log: " + logLabel(value.logLevel));
        };
        refresh.run();

        fast.setOnClickListener(v -> {
            draft[0] = ProxyTuning.fast();
            refresh.run();
        });
        balanced.setOnClickListener(v -> {
            draft[0] = ProxyTuning.balanced();
            refresh.run();
        });
        stealth.setOnClickListener(v -> {
            draft[0] = ProxyTuning.stealth();
            refresh.run();
        });
        custom.setOnClickListener(v -> {
            draft[0].mode = ProxyTuning.MODE_CUSTOM;
            refresh.run();
        });
        logLevel.setOnClickListener(v -> {
            draft[0].logLevel = nextLogLevel(draft[0].logLevel);
            logLevel.setText("Log: " + logLabel(draft[0].logLevel));
        });

        ScrollView formScroll = new ScrollView(this);
        formScroll.setFillViewport(false);
        formScroll.addView(form);
        shell.addView(formScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(430)
        ));

        LinearLayout maintenance = new LinearLayout(this);
        maintenance.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams maintenanceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        );
        maintenanceParams.setMargins(0, dp(10), 0, 0);
        shell.addView(maintenance, maintenanceParams);

        Button reset = dialogButton("Reset Balanced", false);
        Button clearCache = dialogButton("Clear Cache", false);
        maintenance.addView(reset, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        clearParams.setMargins(dp(8), 0, 0, 0);
        maintenance.addView(clearCache, clearParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        actionParams.setMargins(0, dp(10), 0, 0);
        shell.addView(actions, actionParams);

        Button cancel = dialogButton(persianUi ? "Ù„ØºÙˆ" : "Cancel", false);
        Button save = dialogButton(persianUi ? "Ø°Ø®ÛŒØ±Ù‡" : "Save", true);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        saveParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(save, saveParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(shell)
                .create();

        reset.setOnClickListener(v -> {
            draft[0] = ProxyTuning.balanced();
            refresh.run();
        });
        clearCache.setOnClickListener(v -> {
            ProxyService.clearStrategyCache();
            toast("Strategy cache cleared.");
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            ProxyTuning value = draft[0];
            value.fakeProbeEnabled = fakeProbeEnabled.isChecked();
            value.strategyCacheEnabled = strategyCacheEnabled.isChecked();
            value.fakeProbeCount = readInt(fakeProbeCount, value.fakeProbeCount);
            value.fakeProbeDelayMs = readInt(fakeProbeDelay, value.fakeProbeDelayMs);
            value.multiFragmentSize = readInt(multiFragmentSize, value.multiFragmentSize);
            value.sniSplitDelayMs = readInt(sniSplitDelay, value.sniSplitDelayMs);
            value.tlsRecordDelayMs = readInt(tlsRecordDelay, value.tlsRecordDelayMs);
            value.multiDelayMs = readInt(multiDelay, value.multiDelayMs);
            value.halfDelayMs = readInt(halfDelay, value.halfDelayMs);
            value.routeProbeTimeoutMs = readInt(routeTimeout, value.routeProbeTimeoutMs);
            value.sanitize();
            ProxyTuningStore.save(this, value);
            ProxyService.clearStrategyCache();
            dialog.dismiss();
            toast("Tuning saved.");
            if (ProxyService.isRunning()) {
                restartProxy();
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private Switch tuningSwitch(String label, boolean checked) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setChecked(checked);
        view.setTextColor(getResources().getColor(R.color.ink));
        view.setTextSize(14);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackgroundResource(R.drawable.bg_modern_row);
        view.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        params.setMargins(0, dp(8), 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private View labeledInput(String label, EditText input) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        wrapperParams.setMargins(0, 0, 0, dp(8));
        wrapper.setLayoutParams(wrapperParams);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(getResources().getColor(R.color.muted));
        title.setTextSize(12);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(4), 0, dp(4), dp(4));
        wrapper.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        input.setHint("");
        input.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
        ));
        wrapper.addView(input);
        return wrapper;
    }

    private void setModeButtonState(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_run : R.drawable.bg_outline_button);
        button.setTextColor(selected ? Color.WHITE : getResources().getColor(R.color.ink));
    }

    private String nextLogLevel(String current) {
        if (ProxyTuning.LOG_MINIMAL.equals(current)) {
            return ProxyTuning.LOG_NORMAL;
        }
        if (ProxyTuning.LOG_NORMAL.equals(current)) {
            return ProxyTuning.LOG_VERBOSE;
        }
        return ProxyTuning.LOG_MINIMAL;
    }

    private String logLabel(String logLevel) {
        if (ProxyTuning.LOG_MINIMAL.equals(logLevel)) {
            return "Minimal";
        }
        if (ProxyTuning.LOG_VERBOSE.equals(logLevel)) {
            return "Verbose";
        }
        return "Normal";
    }

    private void showConfigEditor(ProxyConfig existing) {
        boolean isNew = existing == null;
        ProxyConfig draft = isNew ? new ProxyConfig() : existing.copy();

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(16), dp(18), dp(14));
        shell.setBackgroundResource(R.drawable.bg_card);

        TextView title = new TextView(this);
        title.setText(isNew
                ? (persianUi ? "افزودن کانفیگ" : "Add Config")
                : (persianUi ? "ویرایش کانفیگ" : "Edit Config"));
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(20);
        title.setLetterSpacing(0.02f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        shell.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText(persianUi ? "آدرس مسیر، SNI و لینک اصلی را تنظیم کنید." : "Tune the route endpoint, SNI, and source link.");
        subtitle.setTextColor(getResources().getColor(R.color.muted));
        subtitle.setTextSize(12);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.setMargins(0, dp(4), 0, dp(12));
        shell.addView(subtitle, subtitleParams);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        EditText name = edit(persianUi ? "نام" : "Name", draft.name, InputType.TYPE_CLASS_TEXT);
        EditText address = edit(persianUi ? "آدرس" : "Address", draft.address, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText fallback = edit(persianUi ? "آدرس پشتیبان" : "Fallback address", draft.fallbackAddress, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = edit(persianUi ? "پورت" : "Port", String.valueOf(draft.port), InputType.TYPE_CLASS_NUMBER);
        EditText sni = edit("SNI", draft.sni, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText source = edit(persianUi ? "لینک کانفیگ" : "Source URI", draft.sourceUri, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name);
        form.addView(address);
        form.addView(fallback);
        form.addView(port);
        form.addView(sni);
        form.addView(source);

        ScrollView formScroll = new ScrollView(this);
        formScroll.setFillViewport(false);
        formScroll.addView(form);
        shell.addView(formScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(330)
        ));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        actionParams.setMargins(0, dp(12), 0, 0);
        shell.addView(actions, actionParams);

        Button cancel = dialogButton(persianUi ? "لغو" : "Cancel", false);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        Button delete = null;
        if (!isNew) {
            delete = dialogButton(persianUi ? "حذف" : "Delete", false);
            delete.setTextColor(getResources().getColor(R.color.danger));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            deleteParams.setMargins(dp(8), 0, 0, 0);
            actions.addView(delete, deleteParams);
        }

        Button save = dialogButton(persianUi ? "ذخیره" : "Save", true);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        saveParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(save, saveParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(shell)
                .create();

        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            String sourceValue = source.getText().toString().trim();
            if (sourceValue.isEmpty()) {
                toast(persianUi ? "لینک کانفیگ لازم است." : "Source URI is required.");
                return;
            }
            ProxyConfig parsedSource = VlessParser.parseOne(sourceValue);
            if (parsedSource == null) {
                toast(persianUi ? "لینک باید کانفیگ VLESS یا Trojan معتبر باشد." : "Source URI must be a supported VLESS or Trojan config.");
                return;
            }
            String addr = firstNonEmpty(address.getText().toString().trim(), parsedSource.address);
            String host = firstNonEmpty(sni.getText().toString().trim(), parsedSource.sni);
            int parsedPort = readInt(port, parsedSource.port);
            if (addr.isEmpty() || host.isEmpty() || parsedPort < 1 || parsedPort > 65535) {
                toast(persianUi ? "آدرس، SNI و پورت لازم هستند." : "Address, SNI and port are required.");
                return;
            }
            draft.name = firstNonEmpty(name.getText().toString().trim(), parsedSource.name, addr);
            draft.address = addr;
            draft.fallbackAddress = fallback.getText().toString().trim();
            draft.port = parsedPort;
            draft.sni = host;
            draft.method = "combined";
            draft.sourceUri = sourceValue;
            draft.protocol = parsedSource.protocol;
            draft.configHost = parsedSource.configHost;
            draft.configPort = parsedSource.configPort;
            if (isNew) {
                draft.id = UUID.randomUUID().toString();
                draft.origin = "user";
                profiles.add(draft);
                selectedId = draft.id;
                autoMode = false;
                profileStore.setAutoMode(false);
                updateModeSwitch();
            } else {
                replaceProfile(draft);
            }
            saveSelectedId(draft);
            saveProfiles();
            seedScannerDefaults();
            refreshProfiles();
            dialog.dismiss();
        });
        if (delete != null) {
            delete.setOnClickListener(v -> {
                profiles.removeIf(p -> p.id.equals(existing.id));
                selectedId = autoMode ? profileStore.getSelectedSuggestedId(profiles) : profileStore.getSelectedManualId(profiles);
                saveProfiles();
                refreshProfiles();
                dialog.dismiss();
            });
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private EditText edit(String hint, String value, int inputType) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value == null ? "" : value);
        view.setInputType(inputType);
        view.setSingleLine(true);
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        view.setTextColor(getResources().getColor(R.color.ink));
        view.setHintTextColor(getResources().getColor(R.color.muted));
        view.setTextSize(14);
        view.setBackgroundResource(R.drawable.bg_input);
        view.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        params.setMargins(0, 0, 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private Button dialogButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : getResources().getColor(R.color.ink));
        button.setBackgroundResource(primary ? R.drawable.bg_run : R.drawable.bg_outline_button);
        return button;
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
        ProxyConfig selected = selectedProfileOrNull();
        if (selected != null) {
            saveSelectedId(selected);
        } else {
            profileStore.setSelectedId("");
        }
    }

    private void importConfigsFromClipboard() {
        String text = clipboardText();
        List<ProxyConfig> imported = VlessParser.parseMany(text);
        if (imported.isEmpty()) {
            toast(persianUi ? "کلیپ‌بورد کانفیگ معتبر ندارد." : "Clipboard does not contain a supported config.");
            return;
        }
        for (ProxyConfig profile : imported) {
            profile.origin = "user";
        }
        profiles.addAll(imported);
        selectedId = imported.get(imported.size() - 1).id;
        autoMode = false;
        profileStore.setAutoMode(false);
        profileStore.setSelectedManualId(selectedId);
        updateModeSwitch();
        saveProfiles();
        seedScannerDefaults();
        refreshProfiles();
        toast(persianUi ? imported.size() + " کانفیگ وارد شد." : "Imported " + imported.size() + " config(s).");
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

    private void toggleScanner() {
        if (scanning) {
            stopScanner();
            return;
        }
        startScanner(false);
    }

    private void pingBookmarkedSni() {
        if (scanning) {
            stopScanner();
            return;
        }
        List<SniSpoofScanner.Result> saved = SniBookmarkStore.load(this);
        if (saved.isEmpty()) {
            toast(persianUi ? "اول نتیجه‌های SNI را ذخیره کنید." : "Bookmark SNI results first.");
            return;
        }
        startScanner(true);
    }

    private void startScanner(boolean bookmarksOnly) {
        try {
            stopScanner();
            int runId = ++scannerRunId;
            scanResults.clear();
            resultsContainer.removeAllViews();
            scannerResultsScroll.scrollTo(0, 0);

            List<String> domains = bookmarksOnly
                    ? bookmarkedDomains()
                    : parseDomains(readAssetText("sni-spoof/domains.txt").toString(), SCAN_MAX_DOMAINS);
            if (domains.isEmpty()) {
                scannerStatusText.setText(bookmarksOnly
                        ? (persianUi ? "SNI ذخیره‌شده‌ای برای پینگ نیست." : "No bookmarked SNI to ping.")
                        : (persianUi ? "خطای اسکنر: دامنه‌ای وجود ندارد." : "Scanner error: no domains."));
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
            startScanButton.setEnabled(true);
            if (!bookmarksOnly) {
                startScanButton.setText(persianUi ? "توقف" : "Stop");
                startScanButton.setBackgroundResource(R.drawable.bg_stop);
            }
            pingBookmarksButton.setEnabled(false);
            pingBookmarksButton.setText(bookmarksOnly
                    ? (persianUi ? "در حال پینگ..." : "Pinging...")
                    : (persianUi ? "پینگ SNIهای ذخیره‌شده" : "Ping Saved SNI"));
            pingBookmarksButton.setBackgroundResource(bookmarksOnly ? R.drawable.bg_stop : R.drawable.bg_outline_button);
            scannerStatusText.setText((bookmarksOnly
                    ? (persianUi ? "در حال پینگ ذخیره‌شده‌ها " : "Pinging saved ")
                    : (persianUi ? "در حال اسکن " : "Scanning "))
                    + domains.size() + (persianUi ? " دامنه" : " domain(s)"));
            scanner.start(settings, new SniSpoofScanner.Callback() {
                @Override
                public void onProgress(int done, int total, SniSpoofScanner.Result result) {
                    mainHandler.post(() -> {
                        if (runId != scannerRunId || !scanning) {
                            return;
                        }
                        if (result.ok()) {
                            scanResults.add(result);
                            renderResults();
                        }
                        scannerStatusText.setText((persianUi ? "اسکن " : "Scan ") + done + "/" + total + "  OK: " + countOk(scanResults));
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
                        }
                        scannerStatusText.setText((cancelled
                                ? (persianUi ? "اسکن متوقف شد" : "Scan stopped")
                                : (persianUi ? "اسکن تمام شد" : "Scan finished")) + "  OK: " + countOk(scanResults));
                        scanning = false;
                        scanner = null;
                        renderResults();
                        if (bookmarksOnly) {
                            SniBookmarkStore.upsertAll(MainActivity.this, scanResults);
                            refreshBookmarks();
                        }
                        startScanButton.setEnabled(true);
                        startScanButton.setText(persianUi ? "اسکن" : "Scan");
                        startScanButton.setBackgroundResource(R.drawable.bg_run);
                        pingBookmarksButton.setText(persianUi ? "پینگ SNIهای ذخیره‌شده" : "Ping Saved SNI");
                        updatePingSavedButtonState();
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        if (runId != scannerRunId) {
                            return;
                        }
                        scannerStatusText.setText((persianUi ? "خطای اسکنر: " : "Scanner error: ") + message);
                        scanning = false;
                        scanner = null;
                        startScanButton.setEnabled(true);
                        startScanButton.setText(persianUi ? "اسکن" : "Scan");
                        startScanButton.setBackgroundResource(R.drawable.bg_run);
                        pingBookmarksButton.setText(persianUi ? "پینگ SNIهای ذخیره‌شده" : "Ping Saved SNI");
                        updatePingSavedButtonState();
                    });
                }
            });
        } catch (Exception e) {
            scannerStatusText.setText((persianUi ? "خطای اسکنر: " : "Scanner error: ") + e.getMessage());
            scanning = false;
            startScanButton.setEnabled(true);
            startScanButton.setText(persianUi ? "اسکن" : "Scan");
            startScanButton.setBackgroundResource(R.drawable.bg_run);
            pingBookmarksButton.setText(persianUi ? "پینگ SNIهای ذخیره‌شده" : "Ping Saved SNI");
            updatePingSavedButtonState();
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
        startScanButton.setText(persianUi ? "اسکن" : "Scan");
        startScanButton.setBackgroundResource(R.drawable.bg_run);
        if (pingBookmarksButton != null) {
            pingBookmarksButton.setText(persianUi ? "پینگ SNIهای ذخیره‌شده" : "Ping Saved SNI");
            updatePingSavedButtonState();
        }
        if (wasScanning && scannerStatusText != null) {
            scannerStatusText.setText((persianUi ? "اسکن متوقف شد  OK: " : "Scan stopped  OK: ") + countOk(scanResults));
        }
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
            empty.setText(persianUi ? "SNI فعالی پیدا نشد." : "No working SNI result found.");
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
            more.setText(persianUi
                    ? "نمایش " + limit + " نتیجه اول از " + sorted.size()
                    : "Showing first " + limit + " of " + sorted.size() + " results");
            more.setGravity(Gravity.CENTER);
            more.setTextColor(getResources().getColor(R.color.muted));
            more.setTextSize(12);
            resultsContainer.addView(more);
        }
    }

    private List<String> bookmarkedDomains() {
        List<String> domains = new ArrayList<>();
        for (SniSpoofScanner.Result result : SniBookmarkStore.load(this)) {
            domains.add(result.domain);
        }
        return domains;
    }

    private void refreshBookmarks() {
        if (bookmarksContainer == null) {
            return;
        }
        bookmarksContainer.removeAllViews();
        bookmarkedScanResults.clear();
        bookmarkedScanResults.addAll(SniBookmarkStore.load(this));
        bookmarkTitleText.setText((persianUi ? "SNIهای ذخیره‌شده" : "Bookmarked SNI") + " (" + bookmarkedScanResults.size() + ")");
        updatePingSavedButtonState();
        if (bookmarkedScanResults.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(persianUi ? "هنوز SNI ذخیره نشده. نتیجه‌های سریع را از لیست بالا ذخیره کنید." : "No saved SNI yet. Save fast scanner results from the list above.");
            empty.setTextColor(getResources().getColor(R.color.muted));
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(10), 0, dp(6));
            bookmarksContainer.addView(empty);
            return;
        }
        int limit = Math.min(12, bookmarkedScanResults.size());
        for (int i = 0; i < limit; i++) {
            bookmarksContainer.addView(bookmarkRow(bookmarkedScanResults.get(i), i + 1));
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

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(titleRow);

        TextView title = new TextView(this);
        title.setText(String.format(Locale.US, "%02d  %s  %s", index, result.domain, result.ok() ? "OK" : (persianUi ? "ناموفق" : "FAIL")));
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        boolean saved = SniBookmarkStore.contains(this, result.domain);
        TextView bookmark = smallAction(saved ? (persianUi ? "ذخیره شد" : "SAVED") : (persianUi ? "ذخیره" : "SAVE"), saved);
        titleRow.addView(bookmark, new LinearLayout.LayoutParams(dp(72), dp(32)));
        bookmark.setOnClickListener(v -> {
            if (!result.ok()) {
                toast(persianUi ? "فقط نتیجه‌های فعال SNI قابل ذخیره هستند." : "Only working SNI results can be bookmarked.");
                return;
            }
            SniBookmarkStore.toggle(this, result);
            refreshBookmarks();
            renderResults();
        });

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
        subtitle.setTextColor(pingColor(result.pingMs));
        subtitle.setTextSize(12);
        subtitle.setTextDirection(View.TEXT_DIRECTION_LTR);
        subtitle.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        row.addView(subtitle);

        row.setOnClickListener(v -> applyScanResult(result));
        row.setOnLongClickListener(v -> {
            copyScanResult(result);
            return true;
        });
        return row;
    }

    private View bookmarkRow(SniSpoofScanner.Result result, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundResource(R.drawable.bg_selected_config);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(7));
        row.setLayoutParams(params);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(top);

        TextView title = new TextView(this);
        title.setText(String.format(Locale.US, "%02d  %s", index, result.domain));
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(13);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView remove = smallAction(persianUi ? "حذف" : "REMOVE", false);
        top.addView(remove, new LinearLayout.LayoutParams(dp(82), dp(32)));
        remove.setOnClickListener(v -> {
            SniBookmarkStore.toggle(this, result);
            refreshBookmarks();
            renderResults();
        });

        TextView detail = new TextView(this);
        detail.setText(String.format(Locale.US, "%s  /  %dms  /  stable %d%%",
                result.resolvedIp,
                result.pingMs,
                result.stability));
        detail.setTextColor(pingColor(result.pingMs));
        detail.setTextSize(12);
        detail.setTextDirection(View.TEXT_DIRECTION_LTR);
        detail.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        row.addView(detail);

        row.setOnClickListener(v -> applyScanResult(result));
        row.setOnLongClickListener(v -> {
            copyScanResult(result);
            return true;
        });
        return row;
    }

    private TextView smallAction(String label, boolean active) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(active ? Color.WHITE : getResources().getColor(R.color.accent));
        view.setTextSize(11);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setBackgroundResource(active ? R.drawable.bg_run : R.drawable.bg_outline_button);
        return view;
    }

    private void updatePingSavedButtonState() {
        if (pingBookmarksButton == null) {
            return;
        }
        boolean active = !SniBookmarkStore.load(this).isEmpty() && !scanning;
        pingBookmarksButton.setEnabled(active);
        pingBookmarksButton.setAlpha(active ? 1.0f : 0.55f);
        pingBookmarksButton.setBackgroundResource(active ? R.drawable.bg_run : R.drawable.bg_outline_button);
        pingBookmarksButton.setTextColor(active ? Color.WHITE : getResources().getColor(R.color.muted));
    }

    private int pingColor(int pingMs) {
        if (pingMs <= 450) {
            return getResources().getColor(R.color.accent_green);
        }
        if (pingMs <= 900) {
            return getResources().getColor(R.color.accent);
        }
        if (pingMs <= 1500) {
            return getResources().getColor(R.color.warning);
        }
        return getResources().getColor(R.color.danger);
    }

    private StringBuilder readAssetText(String path) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(path)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (Exception e) {
            toast((persianUi ? "خواندن ممکن نیست: " : "Cannot read ") + path);
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
        toast(persianUi ? "نتیجه SNI کپی شد." : "SNI result copied.");
    }

    private void applyScanResult(SniSpoofScanner.Result result) {
        if (!result.ok() || result.resolvedIp.equals("N/A")) {
            toast(persianUi ? "این SNI آدرس قابل استفاده ندارد." : "This SNI has no usable address.");
            return;
        }
        String[] options = new String[]{
                persianUi ? "اعمال روی همه کانفیگ‌ها" : "Apply to all configs",
                persianUi ? "اعمال روی کانفیگ‌های پیشنهادی" : "Apply to suggested configs",
                persianUi ? "اعمال روی کانفیگ‌های دستی" : "Apply to manual configs"
        };
        new AlertDialog.Builder(this)
                .setTitle(persianUi ? "اعمال SNI" : "Apply SNI")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        applyScanResultToProfiles(result, profiles, persianUi ? "همه کانفیگ‌ها" : "all configs");
                    } else if (which == 1) {
                        applyScanResultToProfiles(result, suggestedProfiles(), persianUi ? "کانفیگ‌های پیشنهادی" : "suggested configs");
                    } else {
                        applyScanResultToProfiles(result, manualProfiles(), persianUi ? "کانفیگ‌های دستی" : "manual configs");
                    }
                })
                .show();
    }

    private void applyScanResultToProfiles(SniSpoofScanner.Result result, List<ProxyConfig> targets, String label) {
        if (targets.isEmpty()) {
            toast(persianUi ? label + " برای به‌روزرسانی وجود ندارد." : "No " + label + " to update.");
            return;
        }
        for (ProxyConfig profile : targets) {
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
        toast(persianUi ? "SNI روی " + label + " اعمال شد." : "SNI applied to " + label + ".");
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

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private void openTelegramSupport() {
        Intent appIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=UacSniSpoofer"));
        try {
            startActivity(appIntent);
        } catch (Exception ignored) {
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/UacSniSpoofer"));
            try {
                startActivity(webIntent);
            } catch (Exception e) {
                toast(persianUi ? "لینک پشتیبانی باز نشد." : "Support link cannot be opened.");
            }
        }
    }

    private void showHamburgerMenu() {
        int drawerWidth = Math.min(dp(280), (int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(getResources().getColor(R.color.surface));
        drawer.setPadding(dp(14), dp(18) + currentStatusInset(), dp(14), dp(14) + currentNavigationInset());

        TextView title = new TextView(this);
        title.setText("UAC Spoofer");
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTextSize(20);
        title.setLetterSpacing(0.02f);
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

        addDrawerItem(drawer, persianUi ? "تست واقعی مسیر" : "Reality Probe", () -> closeDrawer(popup, drawer, this::openRealityProbe));
        addDrawerItem(drawer, persianUi ? "برنامه‌های خارج از VPN" : "Apps Bypass", () -> closeDrawer(popup, drawer, this::openAppsBypass));
        addDrawerItem(drawer, persianUi ? "کانال تلگرام" : "Telegram Channel", () -> closeDrawer(popup, drawer, this::openTelegramSupport));

        addDrawerItem(drawer, "Advanced / Tuning", () -> closeDrawer(popup, drawer, this::showAdvancedTuningDialog));

        drawer.setTranslationX(-drawerWidth);
        popup.showAtLocation(pageScroll, Gravity.LEFT | Gravity.TOP, 0, 0);
        drawer.animate().translationX(0).setDuration(180).start();
    }

    private int currentStatusInset() {
        return rootLayout == null ? statusBarHeight() : Math.max(rootLayout.getPaddingTop(), statusBarHeight());
    }

    private int currentNavigationInset() {
        return rootLayout == null ? navigationBarHeight() : Math.max(rootLayout.getPaddingBottom(), navigationBarHeight());
    }

    private void addDrawerItem(LinearLayout drawer, String label, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextColor(getResources().getColor(R.color.ink));
        item.setTextSize(14);
        item.setTypeface(null, android.graphics.Typeface.BOLD);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), 0, dp(14), 0);
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

    private void openRealityProbe() {
        startActivity(new Intent(this, RealityProbeActivity.class));
    }

    private void toggleTheme() {
        ThemeStore.setDark(this, !ThemeStore.isDark(this));
        recreate();
    }

    private void updateThemeButton() {
        themeButton.setText(ThemeStore.isDark(this)
                ? (persianUi ? "روشن" : "Light")
                : (persianUi ? "تیره" : "Dark"));
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

    private void showTab(String tab) {
        boolean home = "home".equals(tab);
        boolean configs = "configs".equals(tab);
        boolean scan = "scan".equals(tab);
        boolean logs = "logs".equals(tab);
        boolean support = "support".equals(tab);
        statusSection.setVisibility(home ? View.VISIBLE : View.GONE);
        homeSpacer.setVisibility(home ? View.VISIBLE : View.GONE);
        configsSection.setVisibility(configs ? View.VISIBLE : View.GONE);
        scannerSection.setVisibility(scan ? View.VISIBLE : View.GONE);
        logsSection.setVisibility(logs ? View.VISIBLE : View.GONE);
        supportSection.setVisibility(support ? View.VISIBLE : View.GONE);
        setNavState(navHomeButton, home);
        setNavState(navConfigsButton, configs);
        setNavState(navScanButton, scan);
        setNavState(navLogsButton, logs);
        setNavState(navSupportButton, support);
        mainContent.setGravity(Gravity.NO_GRAVITY);
        pageScroll.post(() -> {
            mainContent.setMinimumHeight(home ? pageScroll.getHeight() : 0);
            if (home) {
                int headerAndPadding = Math.max(0, mainContent.getHeight() - statusSection.getHeight());
                statusSection.setMinimumHeight(Math.max(statusSection.getHeight(), pageScroll.getHeight() - headerAndPadding - dp(12)));
                statusSection.setGravity(Gravity.CENTER_VERTICAL);
            } else {
                statusSection.setMinimumHeight(0);
                statusSection.setGravity(Gravity.NO_GRAVITY);
            }
        });
        if (scan && scanPanel.getVisibility() != View.VISIBLE && bookmarksSection.getVisibility() != View.VISIBLE) {
            showSniSubTab("scan");
        }
        pageScroll.post(() -> pageScroll.smoothScrollTo(0, 0));
    }

    private void showSniSubTab(String tab) {
        boolean saved = "saved".equals(tab);
        showingSavedSni = saved;
        scanPanel.setVisibility(saved ? View.GONE : View.VISIBLE);
        bookmarksSection.setVisibility(saved ? View.VISIBLE : View.GONE);
        if (!scanning) {
            setNavState(sniScanTabButton, !saved);
            sniScanTabButton.setText(persianUi ? "اسکن" : "Scan");
        }
        setNavState(sniSavedTabButton, saved);
        if (scannerSection.getVisibility() != View.VISIBLE) {
            showTab("scan");
        }
        pageScroll.post(() -> pageScroll.smoothScrollTo(0, 0));
    }

    private void setNavState(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_nav_active : R.drawable.bg_nav_idle);
        button.setTextColor(selected ? Color.WHITE : getResources().getColor(R.color.ink));
    }

    private void ensureStartsAtTop() {
        pageScroll.setFocusableInTouchMode(true);
        pageScroll.requestFocus();
        pageScroll.post(() -> pageScroll.scrollTo(0, 0));
        pageScroll.postDelayed(() -> pageScroll.scrollTo(0, 0), 120);
    }

    private void updateBlinkingButtons() {
        blinkOn = !blinkOn;
        runButton.setAlpha(1.0f);
        startScanButton.setAlpha(1.0f);
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
                statusText.setText(vpnOnText());
                statusText.setBackgroundResource(R.drawable.bg_modern_panel);
                statusText.setTextColor(getResources().getColor(R.color.accent_green));
                runButton.setEnabled(true);
                runButton.setText(disconnectLabel());
                runButton.setBackgroundResource(R.drawable.bg_connect_button_connected);
                connectionRingView.setMode(ConnectionRingView.MODE_CONNECTED);
                targetText.setText(targetLabel);
                ProxyConfig selected = selectedProfileOrNull();
                connectedPingText.setText(selected != null && selected.lastPingOk
                        ? pingValueText(selected.lastPingMs)
                        : pingEmptyText());
            } else {
                String error = ProxyService.getLastError();
                statusText.setText(error.isEmpty() ? vpnOffText() : (persianUi ? "پروکسی ناموفق بود" : "Proxy failed"));
                statusText.setBackgroundResource(R.drawable.bg_modern_panel);
                statusText.setTextColor(error.isEmpty() ? getResources().getColor(R.color.muted) : getResources().getColor(R.color.danger));
                runButton.setEnabled(true);
                if (!pingingConfigs && !waitingForProxyBeforeVpn && !startAfterVpnConsent) {
                    runButton.setText(runLabel());
                    runButton.setBackgroundResource(R.drawable.bg_connect_button);
                    connectionRingView.setMode(ConnectionRingView.MODE_IDLE);
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

