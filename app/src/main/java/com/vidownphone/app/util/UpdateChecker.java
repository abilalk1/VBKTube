package com.vidownphone.app.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vidownphone.app.BuildConfig;
import com.vidownphone.app.R;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Checks GitHub Releases for a newer version and offers to open the release page. */
public final class UpdateChecker {

    private static final String API_URL = "https://api.github.com/repos/abilalk1/VBKTube/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/abilalk1/VBKTube/releases/latest";
    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    private UpdateChecker() {
    }

    /** Automatic check: at most once a day, silent unless an update exists. */
    public static void checkAutomatically(Activity activity) {
        if (!PreferenceHelper.isAutoUpdateCheckEnabled(activity)) return;
        if (System.currentTimeMillis() - PreferenceHelper.getLastUpdateCheck(activity) < ONE_DAY_MS) return;
        check(activity, false);
    }

    /** @param manual true to also report "up to date" and errors to the user. */
    public static void check(Activity activity, boolean manual) {
        final android.content.Context appCtx = activity.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            String tag = null;
            String url = null;
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();
                Request req = new Request.Builder().url(API_URL)
                        .header("Accept", "application/vnd.github+json").build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONObject o = new JSONObject(resp.body().string());
                        tag = o.optString("tag_name", "");
                        url = o.optString("html_url", "");
                    }
                }
                PreferenceHelper.setLastUpdateCheck(appCtx, System.currentTimeMillis());
            } catch (Exception ignored) {
            }
            final String fTag = tag;
            final String fUrl = url;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (fTag == null || fTag.isEmpty()) {
                    if (manual) Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                } else if (isNewer(fTag, BuildConfig.VERSION_NAME)) {
                    showDialog(activity, fTag, fUrl);
                } else if (manual) {
                    Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private static void showDialog(Activity activity, String tag, String url) {
        final String target = (url != null && url.startsWith("https://github.com/")) ? url : RELEASES_PAGE;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(activity.getString(R.string.update_available_msg, tag, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.update_download, (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    static boolean isNewer(String remoteTag, String local) {
        int[] r = parse(remoteTag);
        int[] l = parse(local);
        for (int i = 0; i < 3; i++) {
            if (r[i] != l[i]) return r[i] > l[i];
        }
        return false;
    }

    private static int[] parse(String v) {
        int[] out = new int[3];
        String s = v == null ? "" : v.trim().replaceFirst("^[vV]", "");
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
