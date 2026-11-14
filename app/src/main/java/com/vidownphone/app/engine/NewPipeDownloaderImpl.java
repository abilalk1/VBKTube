package com.vidownphone.app.engine;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Custom NewPipe Downloader implementation backed by OkHttpClient and WebView CookieBridge.
 * Inspired by DefaultYTDownloaderImpl in shibaFoss/AIO-Video-Downloader.
 */
public class NewPipeDownloaderImpl extends Downloader {

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final byte[] EMPTY_BODY = new byte[0];

    private final OkHttpClient okHttpClient;

    public NewPipeDownloaderImpl(@NonNull OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    @Override
    public Response execute(@NonNull Request request) throws IOException, ReCaptchaException {
        String httpMethod = request.httpMethod().toUpperCase(Locale.ROOT);
        String url = request.url();

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9,tr;q=0.8");

        // Attach any headers requested by NewPipeExtractor
        Map<String, List<String>> headers = request.headers();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String headerName = entry.getKey();
                List<String> headerValueList = entry.getValue();
                if (headerValueList == null || headerValueList.isEmpty()) continue;
                builder.removeHeader(headerName);
                for (String value : headerValueList) {
                    builder.addHeader(headerName, value);
                }
            }
        }

        // Attach WebView cookies if user signed in via built-in browser
        String cookie = CookieBridge.getCookieHeaderForUrl(url);
        if (cookie != null && !cookie.isEmpty() && (headers == null || !headers.containsKey("Cookie"))) {
            builder.addHeader("Cookie", cookie);
        }

        RequestBody requestBody = RequestBody.create(
                request.dataToSend() != null ? request.dataToSend() : EMPTY_BODY,
                null
        );

        switch (httpMethod) {
            case "HEAD":
                builder.head();
                break;
            case "POST":
                builder.post(requestBody);
                break;
            case "PUT":
                builder.put(requestBody);
                break;
            case "DELETE":
                if (request.dataToSend() != null) {
                    builder.delete(requestBody);
                } else {
                    builder.delete();
                }
                break;
            case "GET":
            default:
                builder.get();
                break;
        }

        try (okhttp3.Response response = okHttpClient.newCall(builder.build()).execute()) {
            if (response.code() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }

            ResponseBody body = response.body();
            String responseBodyToReturn = body != null ? body.string() : "";
            String latestUrl = response.request().url().toString();

            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBodyToReturn,
                    latestUrl
            );
        }
    }
}
