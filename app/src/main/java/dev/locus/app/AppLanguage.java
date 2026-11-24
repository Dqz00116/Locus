package dev.locus.app;

import android.annotation.SuppressLint;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

enum AppLanguage {
    SYSTEM("", R.string.app_language_system),
    ENGLISH("en", R.string.app_language_english),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.app_language_simplified_chinese);

    private static final String PREFERENCES = "appearance";
    private static final String KEY = "appLanguage";

    final String tag;
    final int label;

    AppLanguage(String tag, int label) {
        this.tag = tag;
        this.label = label;
    }

    static AppLanguage current(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            for (int index = 0; index < locales.size(); index++) {
                for (AppLanguage language : values()) {
                    if (language != SYSTEM && LocaleList.matchesLanguageAndScript(
                            locales.get(index), LocaleList.forLanguageTags(language.tag).get(0))) {
                        return language;
                    }
                }
            }
            return SYSTEM;
        }
        return legacyChoice(context);
    }

    void apply(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            preferences(context).edit().putString(KEY, tag).apply();
        }
    }

    static void migrateLegacyChoice(Context context) {
        if (Build.VERSION.SDK_INT < 33 || !preferences(context).contains(KEY)) return;
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        AppLanguage previous = legacyChoice(context);
        if (manager.getApplicationLocales().isEmpty() && previous != SYSTEM) previous.apply(context);
        preferences(context).edit().remove(KEY).apply();
    }

    // The app bundle includes both languages in the base APK.
    @SuppressLint("AppBundleLocaleChanges")
    static Resources legacyResources(Context context) {
        AppLanguage language = legacyChoice(context);
        if (language == SYSTEM) return null;
        Configuration override = new Configuration();
        override.setLocales(LocaleList.forLanguageTags(language.tag));
        return context.createConfigurationContext(override).getResources();
    }

    private static AppLanguage legacyChoice(Context context) {
        String tag = preferences(context).getString(KEY, SYSTEM.tag);
        for (AppLanguage language : values()) {
            if (language.tag.equals(tag)) return language;
        }
        return SYSTEM;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
