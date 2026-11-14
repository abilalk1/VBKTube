package com.vidownphone.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

public final class PreferenceHelper {

    private static final String PREFS_NAME = "vidown_prefs";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_LANGUAGE = "app_language";
    private static final String KEY_ADBLOCK = "browser_adblock";
    private static final String KEY_SHOW_HOME_TIPS = "show_home_tips";
    private static final String KEY_HOME_TIPS_COLLAPSED = "home_tips_collapsed";
    private static final String KEY_UPDATE_CHANNEL = "ytdlp_update_channel";
    private static final String KEY_LAST_ENGINE_UPDATE = "last_engine_update_ms";
    private static final String KEY_NOTIF_PERMISSION_ASKED = "notif_permission_asked";
    private static final String KEY_NOTIF_DENIED_HINT_SHOWN = "notif_denied_hint_shown";
    private static final String KEY_DISCLAIMER_SHOWN = "disclaimer_shown";

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private PreferenceHelper() {
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getThemeMode(Context context) {
        return getPrefs(context).getInt(KEY_THEME, THEME_DARK);
    }

    public static void setThemeMode(Context context, int mode) {
        getPrefs(context).edit().putInt(KEY_THEME, mode).apply();
        applyThemeMode(mode);
    }

    public static void applySavedTheme(Context context) {
        applyThemeMode(getThemeMode(context));
    }

    private static void applyThemeMode(int mode) {
        switch (mode) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case THEME_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static String getLanguage(Context context) {
        return getPrefs(context).getString(KEY_LANGUAGE, "tr");
    }

    public static void setLanguage(Context context, String langCode) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, langCode).apply();
        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(langCode);
        AppCompatDelegate.setApplicationLocales(appLocale);
    }

    public static void applySavedLocale(Context context) {
        String savedLang = getPrefs(context).getString(KEY_LANGUAGE, null);
        if (savedLang != null && !savedLang.isEmpty()) {
            LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
            if (current.isEmpty() || !savedLang.equals(current.toLanguageTags())) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedLang));
            }
        } else {
            // Default to Turkish on first launch as requested
            setLanguage(context, "tr");
        }
    }

    public static boolean isAdBlockEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_ADBLOCK, true);
    }

    public static void setAdBlockEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_ADBLOCK, enabled).apply();
    }

    public static boolean isShowHomeTips(Context context) {
        return getPrefs(context).getBoolean(KEY_SHOW_HOME_TIPS, true);
    }

    public static void setShowHomeTips(Context context, boolean show) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_HOME_TIPS, show).apply();
    }

    public static boolean isHomeTipsCollapsed(Context context) {
        return getPrefs(context).getBoolean(KEY_HOME_TIPS_COLLAPSED, false);
    }

    public static void setHomeTipsCollapsed(Context context, boolean collapsed) {
        getPrefs(context).edit().putBoolean(KEY_HOME_TIPS_COLLAPSED, collapsed).apply();
    }

    public static String getUpdateChannel(Context context) {
        return getPrefs(context).getString(KEY_UPDATE_CHANNEL, "NIGHTLY");
    }

    public static void setUpdateChannel(Context context, String channel) {
        getPrefs(context).edit().putString(KEY_UPDATE_CHANNEL, channel).apply();
    }

    public static long getLastEngineUpdateTime(Context context) {
        return getPrefs(context).getLong(KEY_LAST_ENGINE_UPDATE, 0L);
    }

    public static void setLastEngineUpdateTime(Context context, long timestampMs) {
        getPrefs(context).edit().putLong(KEY_LAST_ENGINE_UPDATE, timestampMs).apply();
    }

    public static boolean wasNotificationPermissionAsked(Context context) {
        return getPrefs(context).getBoolean(KEY_NOTIF_PERMISSION_ASKED, false);
    }

    public static void setNotificationPermissionAsked(Context context) {
        getPrefs(context).edit().putBoolean(KEY_NOTIF_PERMISSION_ASKED, true).apply();
    }

    public static boolean wasNotificationDeniedHintShown(Context context) {
        return getPrefs(context).getBoolean(KEY_NOTIF_DENIED_HINT_SHOWN, false);
    }

    public static void setNotificationDeniedHintShown(Context context) {
        getPrefs(context).edit().putBoolean(KEY_NOTIF_DENIED_HINT_SHOWN, true).apply();
    }

    public static boolean isAutoUpdateCheckEnabled(Context c) {
        return getPrefs(c).getBoolean("auto_update_check", true);
    }

    public static void setAutoUpdateCheckEnabled(Context c, boolean on) {
        getPrefs(c).edit().putBoolean("auto_update_check", on).apply();
    }

    public static long getLastUpdateCheck(Context c) {
        return getPrefs(c).getLong("last_update_check_ms", 0L);
    }

    public static void setLastUpdateCheck(Context c, long ms) {
        getPrefs(c).edit().putLong("last_update_check_ms", ms).apply();
    }

    public static boolean wasDisclaimerShown(Context context) {
        return getPrefs(context).getBoolean(KEY_DISCLAIMER_SHOWN, false);
    }

    public static void setDisclaimerShown(Context context) {
        getPrefs(context).edit().putBoolean(KEY_DISCLAIMER_SHOWN, true).apply();
    }

    private static final String KEY_SHORTCUTS = "custom_browser_shortcuts";

    public static java.util.List<com.vidownphone.app.model.BrowserShortcut> getShortcuts(Context context) {
        String json = getPrefs(context).getString(KEY_SHORTCUTS, null);
        java.util.List<com.vidownphone.app.model.BrowserShortcut> list = new java.util.ArrayList<>();
        if (json != null && !json.trim().isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    com.vidownphone.app.model.BrowserShortcut bs = com.vidownphone.app.model.BrowserShortcut.fromJson(arr.getJSONObject(i));
                    if (bs != null) {
                        list.add(bs);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty()) {
            list = getDefaultShortcuts();
            saveShortcuts(context, list);
        } else {
            // Auto-migrate: replace old beIN shortcut with Facebook
            boolean migrated = false;
            for (int i = 0; i < list.size(); i++) {
                if ("bein".equals(list.get(i).getId())) {
                    list.set(i, new com.vidownphone.app.model.BrowserShortcut(
                            "fb", "Facebook", "https://m.facebook.com/watch", "Reels, Videolar & Watch", "facebook", false
                    ));
                    migrated = true;
                }
            }
            if (migrated) {
                saveShortcuts(context, list);
            }
        }
        return list;
    }

    public static java.util.List<com.vidownphone.app.model.BrowserShortcut> getDefaultShortcuts() {
        java.util.List<com.vidownphone.app.model.BrowserShortcut> defaults = new java.util.ArrayList<>();
        defaults.add(new com.vidownphone.app.model.BrowserShortcut("yt", "YouTube", "https://m.youtube.com", "4K, 1080p, Shorts...", "youtube", false));
        defaults.add(new com.vidownphone.app.model.BrowserShortcut("ig", "Instagram", "https://www.instagram.com", "Reels, Post & IGTV", "instagram", false));
        defaults.add(new com.vidownphone.app.model.BrowserShortcut("tw", "X / Twitter", "https://x.com", "HD Video & GIF", "twitter", false));
        defaults.add(new com.vidownphone.app.model.BrowserShortcut("tt", "TikTok", "https://www.tiktok.com", "Filigransız HD & Ses", "tiktok", false));
        defaults.add(new com.vidownphone.app.model.BrowserShortcut("rd", "Reddit", "https://www.reddit.com", "Topluluk Videoları", "reddit", false));
        defaults.add(new com.vidownphone.app.model.BrowserShortcut("fb", "Facebook", "https://m.facebook.com/watch", "Reels, Videolar & Watch", "facebook", false));
        return defaults;
    }

    public static void saveShortcuts(Context context, java.util.List<com.vidownphone.app.model.BrowserShortcut> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (list != null) {
            for (com.vidownphone.app.model.BrowserShortcut bs : list) {
                arr.put(bs.toJson());
            }
        }
        getPrefs(context).edit().putString(KEY_SHORTCUTS, arr.toString()).apply();
    }

    public static void addCustomShortcut(Context context, String title, String url) {
        if (url == null || url.trim().isEmpty()) return;
        String cleanUrl = url.trim();
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://" + cleanUrl;
        }
        String cleanTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : context.getString(com.vidownphone.app.R.string.shortcut_custom_site_default);
        String host = cleanUrl;
        try {
            java.net.URI uri = new java.net.URI(cleanUrl);
            if (uri.getHost() != null) {
                host = uri.getHost().replaceFirst("^www\\.", "");
            }
        } catch (Exception ignored) {
        }
        java.util.List<com.vidownphone.app.model.BrowserShortcut> list = getShortcuts(context);
        list.add(new com.vidownphone.app.model.BrowserShortcut(
                "custom_" + System.currentTimeMillis(),
                cleanTitle,
                cleanUrl,
                host,
                "custom",
                true
        ));
        saveShortcuts(context, list);
    }

    public static void deleteShortcut(Context context, String id) {
        java.util.List<com.vidownphone.app.model.BrowserShortcut> list = getShortcuts(context);
        java.util.List<com.vidownphone.app.model.BrowserShortcut> updated = new java.util.ArrayList<>();
        for (com.vidownphone.app.model.BrowserShortcut bs : list) {
            if (!bs.getId().equals(id)) {
                updated.add(bs);
            }
        }
        saveShortcuts(context, updated);
    }
}
