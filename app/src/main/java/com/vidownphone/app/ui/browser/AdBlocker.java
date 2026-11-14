package com.vidownphone.app.ui.browser;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AdBlocker {

    private static final Set<String> AD_DOMAINS = new HashSet<>(Arrays.asList(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adnxs.com",
            "adsrvr.org",
            "taboola.com",
            "outbrain.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "exoclick.com",
            "juicyads.com",
            "scorecardresearch.com",
            "zedo.com",
            "criteo.com",
            "moatads.com"
    ));

    private AdBlocker() {
    }

    public static boolean isAdUrl(String url) {
        if (url == null) return false;
        String host;
        try {
            host = android.net.Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null || host.isEmpty()) return false;
        host = host.toLowerCase(Locale.US);
        for (String domain : AD_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    public static WebResourceResponse createEmptyResponse() {
        return new WebResourceResponse(
                "text/plain",
                "utf-8",
                new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8))
        );
    }
}
