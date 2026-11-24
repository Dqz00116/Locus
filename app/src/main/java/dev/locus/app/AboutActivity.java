package dev.locus.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class AboutActivity extends Activity {
    private static final String SHOW_THIRD_PARTY = "thirdParty";
    private static final String DOCUMENT_NAME = "documentName";
    private static final String DOCUMENT_FILES = "documentFiles";
    private static final String LICENSE_DIRECTORY = "licenses/";
    private static final String FRP_DIRECTORY = LICENSE_DIRECTORY + "frp";
    private static final String YAMUX_SOURCE = "yamux-source.txt";
    private static final String GITHUB_PROFILE_URL = "https://github.com/Dqz00116";
    private static final String GITHUB_REPOSITORY_URL = GITHUB_PROFILE_URL + "/Locus";
    private Resources legacyLanguageResources;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < 33) {
            legacyLanguageResources = AppLanguage.legacyResources(getBaseContext());
        }

        Intent request = getIntent();
        ArrayList<String> documents = request.getStringArrayListExtra(DOCUMENT_FILES);
        if (documents != null) {
            showLicense(request.getStringExtra(DOCUMENT_NAME), documents);
        } else if (request.getBooleanExtra(SHOW_THIRD_PARTY, false)) {
            showThirdParty();
        } else {
            showAbout();
        }
    }

    @Override
    public Resources getResources() {
        return legacyLanguageResources == null ? super.getResources() : legacyLanguageResources;
    }

    private void showAbout() {
        CharSequence appName = getText(R.string.app_name);
        LinearLayout content = content(getString(R.string.about_title, appName));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(64), dp(64));
        iconSize.gravity = Gravity.CENTER_HORIZONTAL;
        iconSize.topMargin = dp(16);
        content.addView(icon, iconSize);

        TextView name = text(appName, 28, R.color.text_primary, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(8), 0, 0);
        name.setAccessibilityHeading(true);
        content.addView(name);

        TextView version = text(installedVersion(), 14, R.color.text_secondary, false);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(4), 0, dp(20));
        version.setTextIsSelectable(true);
        content.addView(version);

        LinearLayout project = group(content, R.string.about_project);
        row(project, getText(R.string.about_repository), getText(R.string.about_github_repository),
                () -> openWebsite(GITHUB_REPOSITORY_URL));
        row(project, getText(R.string.about_profile), getText(R.string.about_github_username),
                () -> openWebsite(GITHUB_PROFILE_URL));

        LinearLayout legal = group(content, R.string.about_legal);
        row(legal, getText(R.string.about_project_license), getText(R.string.about_mpl),
                () -> openLicense(getText(R.string.about_mpl), Collections.singletonList(LICENSE_DIRECTORY + "mpl-2.0.txt")));
        row(legal, getText(R.string.about_third_party), null, () ->
                startActivity(new Intent(this, AboutActivity.class).putExtra(SHOW_THIRD_PARTY, true)));
    }

    private CharSequence installedVersion() {
        try {
            PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
            return getString(R.string.about_version, pkg.versionName, pkg.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void showThirdParty() {
        LinearLayout content = content(getText(R.string.about_third_party));
        LinearLayout terminal = group(content, R.string.about_terminal);
        licenseRow(terminal, R.string.about_termux,
                "termux-terminal-source.txt", "terminal-apache-2.0.txt");
        licenseRow(terminal, R.string.about_android_terminal_emulator, "android-terminal-emulator-notice.txt");
        licenseRow(terminal, R.string.about_wcwidth, "wcwidth-mit.txt");

        LinearLayout ssh = group(content, R.string.about_ssh);
        licenseRow(ssh, R.string.about_jsch, "jsch-2.28.8/LICENSE.txt");
        licenseRow(ssh, R.string.about_jzlib, "jsch-2.28.8/LICENSE.JZlib.txt");
        licenseRow(ssh, R.string.about_jbcrypt, "jsch-2.28.8/LICENSE.jBCrypt.txt");
        licenseRow(ssh, R.string.about_bouncy_castle, "bcprov-jdk15to18-1.87/LICENSE.html");

        LinearLayout frp = group(content, R.string.about_frp);
        licenseRow(frp, R.string.about_android_ndk, "android-ndk-crt-bsd-2-clause.txt");
        Map<String, List<String>> modules = new TreeMap<>();
        try {
            collectFrpLicenses(FRP_DIRECTORY, modules);
        } catch (IOException e) {
            frp.addView(licenseError());
        }
        addModule(frp, getText(R.string.about_go_runtime), modules.remove("go"));
        addModule(frp, getText(R.string.about_yamux_source), modules.remove(YAMUX_SOURCE));
        for (Map.Entry<String, List<String>> module : modules.entrySet()) {
            addModule(frp, module.getKey(), module.getValue());
        }
    }

    private void addModule(LinearLayout frp, CharSequence name, List<String> files) {
        if (files != null) row(frp, name, null, () -> openLicense(name, files));
    }

    private void collectFrpLicenses(String path, Map<String, List<String>> modules) throws IOException {
        String[] entries = getAssets().list(path);
        if (entries == null) return;
        if (entries.length == 0) {
            if (path.equals(FRP_DIRECTORY)) throw new IOException();
            int moduleStart = FRP_DIRECTORY.length() + 1;
            int lastSlash = path.lastIndexOf('/');
            String module = lastSlash < moduleStart ? path.substring(moduleStart) : path.substring(moduleStart, lastSlash);
            modules.computeIfAbsent(module, key -> new ArrayList<>()).add(path);
            return;
        }
        Arrays.sort(entries);
        for (String entry : entries) {
            collectFrpLicenses(path + "/" + entry, modules);
        }
    }

    private void licenseRow(LinearLayout group, int label, String... files) {
        CharSequence name = getText(label);
        List<String> paths = new ArrayList<>();
        for (String file : files) paths.add(LICENSE_DIRECTORY + file);
        row(group, name, null, () -> openLicense(name, paths));
    }

    private void openLicense(CharSequence name, List<String> files) {
        startActivity(new Intent(this, AboutActivity.class)
                .putExtra(DOCUMENT_NAME, name.toString())
                .putStringArrayListExtra(DOCUMENT_FILES, new ArrayList<>(files)));
    }

    private void showLicense(String name, List<String> files) {
        LinearLayout content = content(getText(R.string.about_license_details));
        TextView heading = text(name, 22, R.color.text_primary, true);
        heading.setAccessibilityHeading(true);
        heading.setPadding(dp(8), dp(16), dp(8), dp(12));
        content.addView(heading);
        for (String file : files) {
            CharSequence document;
            try {
                document = readLicense(file);
            } catch (IOException e) {
                content.addView(licenseError());
                continue;
            }
            TextView body = text(document, 14, R.color.text_primary, false);
            body.setTextIsSelectable(true);
            body.setLineSpacing(dp(3), 1);
            body.setPadding(dp(16), dp(14), dp(16), dp(14));
            body.setBackground(LocusChrome.surface(this, getColor(R.color.surface), 0, 14));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            size.bottomMargin = dp(8);
            content.addView(body, size);
        }
    }

    private CharSequence readLicense(String file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(getAssets().open(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
        }
        if (file.endsWith(".html")) {
            return Html.fromHtml(text.toString(), Html.FROM_HTML_MODE_LEGACY).toString().trim();
        }
        return text;
    }

    private TextView licenseError() {
        TextView error = text(getText(R.string.about_license_unavailable), 14, R.color.text_secondary, false);
        error.setPadding(dp(16), dp(12), dp(16), dp(12));
        return error;
    }

    private void openWebsite(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.about_link_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout content(CharSequence title) {
        setTitle(title);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.workspace_background));

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(getColor(R.color.surface));
        toolbar.setMinimumHeight(dp(56));
        toolbar.setTitle(title);
        toolbar.setTitleTextColor(getColor(R.color.text_primary));
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationContentDescription(R.string.about_navigate_back);
        toolbar.setNavigationOnClickListener(view -> finish());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.about_scroll);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout column = new LinearLayout(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), dp(600));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        return column;
    }

    private LinearLayout group(LinearLayout column, int label) {
        TextView heading = text(getText(label), 13, R.color.text_secondary, true);
        heading.setAccessibilityHeading(true);
        heading.setPadding(dp(12), dp(12), dp(12), dp(6));
        column.addView(heading);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(LocusChrome.surface(this, getColor(R.color.surface), 0, 14));
        group.setClipToOutline(true);
        column.addView(group);
        return group;
    }

    private void row(LinearLayout group, CharSequence label, CharSequence detail, Runnable action) {
        if (group.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(getColor(R.color.divider));
            LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerSize.setMarginStart(dp(16));
            dividerSize.setMarginEnd(dp(16));
            group.addView(divider, dividerSize);
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPaddingRelative(dp(16), dp(8), dp(12), dp(8));
        row.setBackground(LocusChrome.action(this, false));
        row.setOnClickListener(view -> action.run());
        row.setFocusable(true);
        row.setScreenReaderFocusable(true);
        group.addView(row);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(label, 16, R.color.text_primary, false));
        if (detail != null) labels.addView(text(detail, 13, R.color.text_secondary, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_end);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(16)));
    }

    private TextView text(CharSequence label, int sizeSp, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(color));
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
