package com.uac.spoofer;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppsActivity extends Activity {
    private LinearLayout appsContainer;
    private TextView countText;
    private EditText searchInput;
    private List<AppEntry> allApps = new ArrayList<>();
    private boolean changingCheckState = false;
    private boolean persianUi = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeStore.apply(this);
        super.onCreate(savedInstanceState);
        persianUi = LanguageStore.isPersian(this);
        buildLayout();
        allApps = loadLaunchableApps();
        renderApps();
    }

    private void buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bg_app);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(14));
        header.setBackgroundResource(R.drawable.bg_header);
        root.addView(header, matchWrap());

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleRow, matchWrap());

        Button back = new Button(this);
        back.setText(txt("Back", "بازگشت"));
        back.setTextColor(0xffffffff);
        back.setTextSize(12);
        back.setTypeface(null, android.graphics.Typeface.BOLD);
        back.setBackgroundResource(R.drawable.bg_header_control);
        titleRow.addView(back, new LinearLayout.LayoutParams(dp(64), dp(42)));
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText(txt("Apps Bypass", "برنامه‌های خارج از VPN"));
        title.setTextColor(0xffffffff);
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(12), 0, 0, 0);
        titleRow.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText(txt("Choose apps that stay outside the VPN route", "برنامه‌هایی را انتخاب کنید که از مسیر VPN عبور نکنند"));
        subtitle.setTextColor(0xffd7e8ff);
        subtitle.setTextSize(12);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.setMargins(0, dp(10), 0, 0);
        header.addView(subtitle, subtitleParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, dp(12), 0, 0);
        root.addView(card, cardParams);

        countText = new TextView(this);
        countText.setTextColor(getResources().getColor(R.color.ink));
        countText.setTextSize(16);
        countText.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(countText, matchWrap());

        TextView hint = new TextView(this);
        hint.setText(txt("Checked apps bypass the VPN tunnel. Stop VPN before changing this list.", "برنامه‌های تیک‌خورده خارج از تونل VPN می‌مانند. قبل از تغییر این لیست VPN را خاموش کنید."));
        hint.setTextColor(getResources().getColor(R.color.muted));
        hint.setTextSize(12);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.setMargins(0, dp(6), 0, dp(10));
        card.addView(hint, hintParams);

        searchInput = new EditText(this);
        searchInput.setHint(txt("Search apps", "جستجوی برنامه‌ها"));
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setTextColor(getResources().getColor(R.color.ink));
        searchInput.setHintTextColor(getResources().getColor(R.color.muted));
        searchInput.setTextSize(14);
        searchInput.setBackgroundResource(R.drawable.bg_input);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        searchParams.setMargins(0, 0, 0, dp(10));
        card.addView(searchInput, searchParams);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderApps();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        appsContainer = new LinearLayout(this);
        appsContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(appsContainer, matchWrap());

        setContentView(scroll);
    }

    private void renderApps() {
        appsContainer.removeAllViews();
        List<AppEntry> apps = filteredApps();
        updateCount();
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(allApps.isEmpty()
                    ? txt("No launchable apps found.", "برنامه قابل اجرا پیدا نشد.")
                    : txt("No app matches your search.", "برنامه‌ای با جستجوی شما پیدا نشد."));
            empty.setTextColor(getResources().getColor(R.color.muted));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(18), 0, dp(18));
            appsContainer.addView(empty, matchWrap());
            return;
        }
        for (AppEntry app : apps) {
            appsContainer.addView(appRow(app));
        }
    }

    private View appRow(AppEntry app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(8), dp(9));
        row.setBackgroundResource(BypassStore.isExcluded(this, app.packageName)
                ? R.drawable.bg_selected_config
                : R.drawable.bg_chip);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconParams.setMargins(0, 0, dp(10), 0);
        row.addView(icon, iconParams);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        row.addView(textBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(getResources().getColor(R.color.ink));
        label.setTextSize(15);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        textBox.addView(label, matchWrap());

        TextView pkg = new TextView(this);
        pkg.setText(app.packageName);
        pkg.setTextColor(getResources().getColor(R.color.muted));
        pkg.setTextDirection(View.TEXT_DIRECTION_LTR);
        pkg.setSingleLine(true);
        pkg.setTextSize(12);
        pkg.setTypeface(android.graphics.Typeface.MONOSPACE);
        textBox.addView(pkg, matchWrap());

        CheckBox checkBox = new CheckBox(this);
        checkBox.setChecked(BypassStore.isExcluded(this, app.packageName));
        checkBox.setEnabled(!ProxyService.isRunning());
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(52), dp(52)));

        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (changingCheckState) {
                return;
            }
            if (ProxyService.isRunning()) {
                changingCheckState = true;
                buttonView.setChecked(BypassStore.isExcluded(this, app.packageName));
                changingCheckState = false;
                showStopVpnMessage();
                return;
            }
            BypassStore.setExcluded(this, app.packageName, isChecked);
            renderApps();
        });
        row.setOnClickListener(v -> {
            if (ProxyService.isRunning()) {
                showStopVpnMessage();
                return;
            }
            checkBox.setChecked(!checkBox.isChecked());
        });
        return row;
    }

    private List<AppEntry> filteredApps() {
        String query = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.US);
        List<AppEntry> apps = new ArrayList<>();
        for (AppEntry app : allApps) {
            if (query.isEmpty()
                    || app.label.toLowerCase(Locale.US).contains(query)
                    || app.packageName.toLowerCase(Locale.US).contains(query)) {
                apps.add(app);
            }
        }
        apps.sort(Comparator
                .comparing((AppEntry app) -> !BypassStore.isExcluded(this, app.packageName))
                .thenComparing(app -> app.label.toLowerCase(Locale.US))
                .thenComparing(app -> app.packageName));
        return apps;
    }

    private List<AppEntry> loadLaunchableApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, 0);
        Map<String, AppEntry> unique = new LinkedHashMap<>();
        String self = getPackageName();
        for (ResolveInfo info : resolved) {
            if (info == null || info.activityInfo == null || info.activityInfo.packageName == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (self.equals(packageName) || unique.containsKey(packageName)) {
                continue;
            }
            String label = packageName;
            Drawable icon = null;
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                CharSequence appLabel = pm.getApplicationLabel(appInfo);
                if (appLabel != null && appLabel.length() > 0) {
                    label = appLabel.toString();
                }
                icon = appInfo.loadIcon(pm);
            } catch (Exception ignored) {
            }
            unique.put(packageName, new AppEntry(label, packageName, icon));
        }
        return new ArrayList<>(unique.values());
    }

    private void updateCount() {
        countText.setText(txt("Bypassed apps: ", "برنامه‌های خارج از VPN: ") + BypassStore.count(this));
    }

    private void showStopVpnMessage() {
        Toast.makeText(this, txt("Stop VPN first, then change bypass apps.", "اول VPN را خاموش کنید، بعد لیست برنامه‌ها را تغییر دهید."), Toast.LENGTH_SHORT).show();
    }

    private String txt(String english, String persian) {
        return persianUi ? persian : english;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final Drawable icon;

        AppEntry(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }
}
