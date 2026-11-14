package com.vidownphone.app.ui.browser;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.vidownphone.app.R;
import com.vidownphone.app.data.DownloadTask;
import com.vidownphone.app.databinding.FragmentBrowserBinding;
import com.vidownphone.app.model.DetectedStream;
import com.vidownphone.app.service.DownloadService;
import com.vidownphone.app.ui.MainActivity;
import com.vidownphone.app.util.PreferenceHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BrowserFragment extends Fragment implements DetectedMediaBottomSheet.Callbacks {

    private static final String DEFAULT_HOME_URL = "https://www.google.com";
    private static final String STATE_WEBVIEW = "browser_webview_state";
    private static final String STATE_LAST_URL = "browser_last_url";
    private static final int MAX_DETECTED_ITEMS = 8;

    private FragmentBrowserBinding binding;
    // Own WebView reference (replaced if the render process dies)
    private WebView webView;
    // Viewport-prioritized map: key = normalized URL, value = DetectedStream
    private final LinkedHashMap<String, DetectedStream> activeStreamsMap = new LinkedHashMap<>();
    // Bounded ring buffer of recent full MP4 network streams (for MSE blob: videos in Reels/Stories)
    private static final LinkedList<String> recentNetworkVideos = new LinkedList<>();
    // Bounded ring buffer of recent DASH audio network streams (to pair with video for muxing or standalone MP3)
    private static final LinkedList<String> recentNetworkAudios = new LinkedList<>();
    // Asset-ID correlated maps (from decoded Instagram 'efg' parameter)
    private static final LinkedHashMap<String, String> audioByAssetId = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> videoByAssetId = new LinkedHashMap<>();
    private static volatile long lastNetworkVideoTime = 0L;
    private static volatile long lastNetworkAudioTime = 0L;
    private volatile String activeVisiblePostUrl = null;
    private String pendingExternalUrl;
    // Last known main-frame URL (updated on the UI thread, read from bridge threads)
    private volatile String currentMainFrameUrl = null;
    private final MediaGrabberJsBridge bridgeImpl = new MediaGrabberJsBridge();
    private String lastKnownUrl = null;
    private boolean webViewPaused = false;
    private boolean usingWebMessageListener = false;
    // Fullscreen custom view (video)
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout fullscreenContainer;

    private static boolean isHttpUrl(@Nullable String url) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.US);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBrowserBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        webViewPaused = false;
        webView = binding.webview;
        configureWebView(webView);
        setupToolbarControls();
        if (isHidden()) {
            pauseWebView();
        }

        if (pendingExternalUrl != null) {
            String url = pendingExternalUrl;
            pendingExternalUrl = null;
            loadUrlOrSearch(url);
        } else {
            boolean restored = false;
            String savedUrl = null;
            if (savedInstanceState != null) {
                savedUrl = savedInstanceState.getString(STATE_LAST_URL);
                Bundle wvState = savedInstanceState.getBundle(STATE_WEBVIEW);
                if (wvState != null) {
                    try {
                        restored = webView.restoreState(wvState) != null;
                    } catch (Throwable ignored) {
                        restored = false;
                    }
                }
            }
            if (!restored) {
                loadUrlOrSearch(isHttpUrl(savedUrl) ? savedUrl : DEFAULT_HOME_URL);
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (binding == null) return;
        String url = webView.getUrl();
        if (!isHttpUrl(url)) url = lastKnownUrl;
        if (isHttpUrl(url)) outState.putString(STATE_LAST_URL, url);
        try {
            Bundle wvState = new Bundle();
            webView.saveState(wvState);
            outState.putBundle(STATE_WEBVIEW, wvState);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseWebView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden()) {
            resumeWebView();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            exitFullscreen();
            pauseWebView();
        } else if (isResumed()) {
            resumeWebView();
        }
    }

    private void pauseWebView() {
        if (binding == null || webViewPaused) return;
        webViewPaused = true;
        webView.onPause();
        webView.pauseTimers();
    }

    private void resumeWebView() {
        if (binding == null || !webViewPaused) return;
        webViewPaused = false;
        webView.onResume();
        webView.resumeTimers();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(webView, true);
        } catch (Throwable ignored) {
        }

        installBridge(webView);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (binding == null) return;
                if (newProgress < 100) {
                    binding.browserProgress.setVisibility(View.VISIBLE);
                    binding.browserProgress.setProgress(newProgress);
                } else {
                    binding.browserProgress.setVisibility(View.GONE);
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullscreenView(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (binding == null) return;
                currentMainFrameUrl = url;
                lastKnownUrl = url;
                setUrlBarText(url);
                synchronized (activeStreamsMap) {
                    activeStreamsMap.clear();
                }
                activeVisiblePostUrl = null;
                updateGrabberFab();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (binding == null) return;
                currentMainFrameUrl = url;
                lastKnownUrl = url;
                setUrlBarText(url);
                injectMediaSnifferScript(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (binding == null) return;
                currentMainFrameUrl = url;
                lastKnownUrl = url;
                setUrlBarText(url);
                try {
                    android.webkit.CookieManager.getInstance().flush();
                } catch (Throwable ignored) {
                }
                injectMediaSnifferScript(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
                if (scheme.equals("http") || scheme.equals("https")) {
                    return false;
                }
                if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data")) {
                    return false;
                }
                if (scheme.equals("javascript") || scheme.equals("file") || scheme.equals("content")) {
                    // Never navigate to local/script schemes from page content
                    return true;
                }
                // intent:, instagram:, market:, mailto:, tel: ... -> open externally if possible
                openExternally(uri.toString());
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                handleRenderProcessGone(view);
                return true;
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();

                // Never block main-frame navigations, only sub-resources
                if (!request.isForMainFrame() && getContext() != null
                        && PreferenceHelper.isAdBlockEnabled(getContext())
                        && AdBlocker.isAdUrl(reqUrl)) {
                    return AdBlocker.createEmptyResponse();
                }

                // Only track actual video/audio streams at network level (NEVER dump raw feed images)
                captureNetworkVideoStream(reqUrl);
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    private void setUrlBarText(String url) {
        if (binding == null || url == null) return;
        if (binding.etBrowserUrl.hasFocus()) return; // do not overwrite while the user is typing
        binding.etBrowserUrl.setText(url);
    }

    private void openExternally(String url) {
        if (getContext() == null || url == null) return;
        try {
            Intent intent;
            if (url.toLowerCase(Locale.US).startsWith("intent:")) {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                // Safety: only explicit-free, browsable intents; strip component/selector
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
            }
            startActivity(intent);
        } catch (ActivityNotFoundException | java.net.URISyntaxException | SecurityException ignored) {
            // No handler available: stay in the page
        } catch (Exception ignored) {
        }
    }

    private void handleRenderProcessGone(WebView deadView) {
        if (binding == null || webView == null || getContext() == null) return;
        String urlToReload = isHttpUrl(lastKnownUrl) ? lastKnownUrl : DEFAULT_HOME_URL;
        exitFullscreen();
        ViewGroup parent = (ViewGroup) deadView.getParent();
        ViewGroup.LayoutParams lp = deadView.getLayoutParams();
        int index = parent != null ? parent.indexOfChild(deadView) : -1;
        int viewId = deadView.getId();
        if (parent != null) parent.removeView(deadView);
        try {
            deadView.destroy();
        } catch (Throwable ignored) {
        }
        // Replace the dead WebView with a fresh one (binding.webview is final, so we track our own reference)
        WebView fresh = new WebView(requireContext());
        fresh.setId(viewId);
        if (parent != null) {
            parent.addView(fresh, index >= 0 ? index : parent.getChildCount(), lp);
        }
        webView = fresh;
        webViewPaused = false;
        configureWebView(fresh);
        if (isHidden()) {
            pauseWebView();
        }
        fresh.loadUrl(urlToReload);
    }

    private void setupToolbarControls() {
        binding.btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });

        binding.btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) {
                webView.goForward();
            }
        });

        binding.btnRefresh.setOnClickListener(v -> webView.reload());

        binding.btnHomeUrl.setOnClickListener(v -> loadUrlOrSearch(DEFAULT_HOME_URL));

        binding.etBrowserUrl.setOnEditorActionListener((v, actionId, event) -> {
            boolean imeAction = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE;
            boolean hardwareEnter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (hardwareEnter && event.getAction() != KeyEvent.ACTION_DOWN) {
                return true; // swallow ACTION_UP so Enter triggers only once
            }
            if (imeAction || hardwareEnter) {
                String input = binding.etBrowserUrl.getText().toString().trim();
                loadUrlOrSearch(input);
                binding.etBrowserUrl.clearFocus();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(binding.etBrowserUrl.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        setupDraggableFab();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDraggableFab() {
        binding.fabMediaGrabber.setOnClickListener(v -> openDetectedMediaSheet());
        binding.fabMediaGrabber.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private float dX, dY;
            private static final int CLICK_DRAG_TOLERANCE = 15;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float movedX = Math.abs(event.getRawX() - downRawX);
                        float movedY = Math.abs(event.getRawY() - downRawY);
                        if (movedX > CLICK_DRAG_TOLERANCE || movedY > CLICK_DRAG_TOLERANCE) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            View parent = (View) v.getParent();
                            if (parent != null) {
                                float newX = event.getRawX() + dX;
                                float newY = event.getRawY() + dY;

                                float minX = 0;
                                float maxX = Math.max(0, parent.getWidth() - v.getWidth());
                                float minY = binding.browserToolbar != null ? binding.browserToolbar.getHeight() : 0;
                                float maxY = Math.max(minY, parent.getHeight() - v.getHeight());

                                newX = Math.max(minX, Math.min(newX, maxX));
                                newY = Math.max(minY, Math.min(newY, maxY));

                                v.setX(newX);
                                v.setY(newY);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            v.performClick();
                        }
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    private void openDetectedMediaSheet() {
        String webUrl = webView.getUrl() != null ? webView.getUrl() : "";
        String targetUrlForAnalysis = (activeVisiblePostUrl != null && !activeVisiblePostUrl.isEmpty())
                ? activeVisiblePostUrl
                : webUrl;

        ArrayList<DetectedStream> list;
        synchronized (activeStreamsMap) {
            for (DetectedStream ds : activeStreamsMap.values()) {
                if (!ds.isAudioOnly() && !ds.isImage() && !ds.hasCompanionAudio()) {
                    String matchedAudio = findAudioForVideoUrl(ds.getStreamUrl());
                    if (matchedAudio != null && !matchedAudio.isEmpty()) {
                        ds.setAudioStreamUrl(matchedAudio);
                    }
                }
            }
            list = new ArrayList<>(activeStreamsMap.values());
        }

        if (getChildFragmentManager().findFragmentByTag("detected_media_sheet") != null) return;
        // Callbacks are resolved by the sheet through getParentFragment() (this fragment), so they
        // survive configuration changes / process recreation.
        DetectedMediaBottomSheet sheet = DetectedMediaBottomSheet.newInstance(targetUrlForAnalysis, list);
        sheet.show(getChildFragmentManager(), "detected_media_sheet");
    }

    // ---- DetectedMediaBottomSheet.Callbacks ----

            @Override
            public void onAnalyzeFullPageWithYtDlp(String pageUrl) {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).openUrlForAnalysis(pageUrl);
                }
            }

            @Override
            public void onDownloadDetectedStream(DetectedStream stream) {
                boolean isHls = stream.getStreamUrl().toLowerCase(Locale.US).contains(".m3u8");
                boolean isImgMux = stream.isImageWithAudioMux();
                boolean isImg = stream.isImage();
                String ext;
                if (isImgMux) {
                    ext = "mp4";
                } else if (isImg) {
                    String clean = stream.getStreamUrl().toLowerCase(Locale.US).split("\\?")[0];
                    ext = clean.endsWith(".png") ? "png" : (clean.endsWith(".webp") ? "webp" : "jpg");
                } else {
                    ext = stream.isAudioOnly() ? "mp3" : "mp4";
                }

                DownloadTask task = new DownloadTask();
                task.setUrl(stream.getStreamUrl());
                String baseTitle = stream.getTitle() != null && !stream.getTitle().isEmpty()
                        ? stream.getTitle()
                        : (isImg ? "IG_Fotograf" : "IG_Video");
                // Append short 4-digit timestamp suffix so multiple downloads from same post never collide
                String uniqueSuffix = String.valueOf(System.currentTimeMillis() % 10000);
                task.setTitle(baseTitle + " (" + uniqueSuffix + ")");
                task.setThumbnailUrl(stream.getThumbnailUrl());
                if (stream.isAudioOnly()) {
                    task.setFormatId("ffmpeg_mp3:" + stream.getStreamUrl());
                    task.setFormatLabel(stream.getMimeType());
                    task.setExtension("mp3");
                    task.setAudioOnly(true);
                    task.setDirectHttp(false);
                } else if (isImgMux) {
                    task.setFormatId("image_audio_mux:" + stream.getStreamUrl() + "|" + stream.getAudioStreamUrl());
                    task.setFormatLabel("MP4 • Müzikli Video");
                    task.setExtension("mp4");
                    task.setAudioOnly(false);
                    task.setDirectHttp(false);
                } else {
                    String audioUrl = stream.getAudioStreamUrl();
                    if ((audioUrl == null || audioUrl.isEmpty()) && !isImg && !isHls) {
                        audioUrl = findAudioForVideoUrl(stream.getStreamUrl());
                    }
                    if (audioUrl != null && !audioUrl.isEmpty() && !isImg && !isHls) {
                        task.setFormatId("newpipe_mux:" + stream.getStreamUrl() + "|" + audioUrl);
                        task.setFormatLabel("MP4 • Sesli Video");
                        task.setExtension("mp4");
                        task.setAudioOnly(false);
                        task.setDirectHttp(false);
                    } else {
                        task.setFormatId(isHls ? ("ffmpeg_hls:" + stream.getStreamUrl()) : ("direct_url:" + stream.getStreamUrl()));
                        task.setFormatLabel(stream.getMimeType());
                        task.setExtension(ext);
                        task.setAudioOnly(false);
                        task.setDirectHttp(!isHls);
                    }
                }
                task.setStatus(DownloadTask.STATUS_QUEUED);
                task.setCreatedAt(System.currentTimeMillis());

                DownloadService.enqueueDownload(requireContext(), task);
                Toast.makeText(requireContext(),
                        getString(R.string.download_started_toast, baseTitle),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDownloadAllDetectedStreams(List<DetectedStream> streams) {
                if (streams == null || streams.isEmpty()) return;
                int count = 0;
                for (DetectedStream stream : streams) {
                    boolean isHls = stream.getStreamUrl().toLowerCase(Locale.US).contains(".m3u8");
                    boolean isImgMux = stream.isImageWithAudioMux();
                    boolean isImg = stream.isImage();
                    String ext;
                    if (isImgMux) {
                        ext = "mp4";
                    } else if (isImg) {
                        String clean = stream.getStreamUrl().toLowerCase(Locale.US).split("\\?")[0];
                        ext = clean.endsWith(".png") ? "png" : (clean.endsWith(".webp") ? "webp" : "jpg");
                    } else {
                        ext = stream.isAudioOnly() ? "mp3" : "mp4";
                    }

                    DownloadTask task = new DownloadTask();
                    task.setUrl(stream.getStreamUrl());
                    String baseTitle = stream.getTitle() != null && !stream.getTitle().isEmpty()
                            ? stream.getTitle()
                            : (isImg ? "Medya_Gorsel" : "Medya_Video");
                    task.setTitle(baseTitle + " (" + (count + 1) + ")");
                    task.setThumbnailUrl(stream.getThumbnailUrl());
                    if (stream.isAudioOnly()) {
                        task.setFormatId("ffmpeg_mp3:" + stream.getStreamUrl());
                        task.setFormatLabel(stream.getMimeType());
                        task.setExtension("mp3");
                        task.setAudioOnly(true);
                        task.setDirectHttp(false);
                    } else if (isImgMux) {
                        task.setFormatId("image_audio_mux:" + stream.getStreamUrl() + "|" + stream.getAudioStreamUrl());
                        task.setFormatLabel("MP4 • Müzikli Video");
                        task.setExtension("mp4");
                        task.setAudioOnly(false);
                        task.setDirectHttp(false);
                    } else {
                        String audioUrl = stream.getAudioStreamUrl();
                        if ((audioUrl == null || audioUrl.isEmpty()) && !isImg && !isHls) {
                            audioUrl = findAudioForVideoUrl(stream.getStreamUrl());
                        }
                        if (audioUrl != null && !audioUrl.isEmpty() && !isImg && !isHls) {
                            task.setFormatId("newpipe_mux:" + stream.getStreamUrl() + "|" + audioUrl);
                            task.setFormatLabel("MP4 • Sesli Video");
                            task.setExtension("mp4");
                            task.setAudioOnly(false);
                            task.setDirectHttp(false);
                        } else {
                            task.setFormatId(isHls ? ("ffmpeg_hls:" + stream.getStreamUrl()) : ("direct_url:" + stream.getStreamUrl()));
                            task.setFormatLabel(stream.getMimeType());
                            task.setExtension(ext);
                            task.setAudioOnly(false);
                            task.setDirectHttp(!isHls);
                        }
                    }
                    task.setStatus(DownloadTask.STATUS_QUEUED);
                    task.setCreatedAt(System.currentTimeMillis() + count);

                    DownloadService.enqueueDownload(requireContext(), task);
                    count++;
                }
                Toast.makeText(requireContext(),
                        getString(R.string.multi_download_enqueued, count),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onClearStreams() {
                synchronized (activeStreamsMap) {
                    activeStreamsMap.clear();
                }
                updateGrabberFab();
                if (getContext() != null) {
                    Toast.makeText(getContext(), R.string.detected_cleared_toast, Toast.LENGTH_SHORT).show();
                }
            }

    public void loadExternalUrl(String url) {
        if (binding == null) {
            pendingExternalUrl = url;
            return;
        }
        loadUrlOrSearch(url);
    }

    private void loadUrlOrSearch(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String target = raw.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            if (target.contains(".") && !target.contains(" ")) {
                target = "https://" + target;
            } else {
                target = "https://www.google.com/search?q=" + android.net.Uri.encode(target);
            }
        }
        webView.loadUrl(target);
    }

    private static String stripInstagramByteRange(String url) {
        if (url == null) return null;
        String cleaned = url.replaceAll("([?&])bytestart=[^&]*", "$1")
                .replaceAll("([?&])byteend=[^&]*", "$1")
                .replaceAll("\\?&+", "?")
                .replaceAll("&&+", "&")
                .replaceAll("[?&]$", "");
        return cleaned;
    }

    private static String normalizeKey(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    /**
     * Decodes Instagram's URL-encoded Base64 'efg=' query parameter to inspect vencode_tag and xpv_asset_id.
     */
    private static String decodeInstagramEfg(String url) {
        if (url == null) return "";
        try {
            int idx = url.indexOf("efg=");
            if (idx == -1) return "";
            int start = idx + 4;
            int end = url.indexOf('&', start);
            String encoded = end == -1 ? url.substring(start) : url.substring(start, end);
            String urlDecoded = android.net.Uri.decode(encoded);
            byte[] bytes = android.util.Base64.decode(urlDecoded, android.util.Base64.DEFAULT);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractInstagramAssetId(String decodedEfg) {
        if (decodedEfg == null || decodedEfg.isEmpty()) return null;
        int idx = decodedEfg.indexOf("\"xpv_asset_id\":");
        if (idx == -1) return null;
        int start = idx + "\"xpv_asset_id\":".length();
        while (start < decodedEfg.length() && (decodedEfg.charAt(start) == ' ' || decodedEfg.charAt(start) == '"')) {
            start++;
        }
        int end = start;
        while (end < decodedEfg.length() && Character.isDigit(decodedEfg.charAt(end))) {
            end++;
        }
        return end > start ? decodedEfg.substring(start, end) : null;
    }

    private static boolean isInstagramAudioStreamUrl(String rawUrl, String decodedEfg, boolean isAudioExt, boolean isVideoExt) {
        if (isAudioExt && !isVideoExt) {
            return true;
        }
        if (decodedEfg != null && !decodedEfg.isEmpty()) {
            if (decodedEfg.contains("audio") || decodedEfg.contains("heaac") || decodedEfg.contains("music")) {
                return true;
            }
            if (decodedEfg.contains("dash_baseline") || decodedEfg.contains("dash_vp9")
                    || decodedEfg.contains("dash_h264") || decodedEfg.contains("progressive")) {
                return false;
            }
        }
        String lowerFull = rawUrl != null ? rawUrl.toLowerCase(Locale.US) : "";
        return lowerFull.contains("dash_ln_heaac")
                || lowerFull.contains("dash_baseline_audio")
                || lowerFull.contains("dash_audio")
                || lowerFull.contains("/audio/")
                || lowerFull.contains("_audio_")
                || lowerFull.contains("_a.mp4")
                || lowerFull.contains("audio_resource")
                || lowerFull.contains("audio/mp4")
                || lowerFull.contains("audio/aac")
                || lowerFull.contains("&audio=1")
                || lowerFull.contains("&is_audio=1")
                || lowerFull.contains("stream_type=audio");
    }

    public static String findAudioForVideoUrl(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) return null;
        String decodedEfg = decodeInstagramEfg(videoUrl);
        String assetId = extractInstagramAssetId(decodedEfg);
        synchronized (recentNetworkAudios) {
            if (assetId != null) {
                String byAsset = audioByAssetId.get(assetId);
                if (byAsset != null && !byAsset.isEmpty()) {
                    return byAsset;
                }
            }
            if (!recentNetworkAudios.isEmpty() && (System.currentTimeMillis() - lastNetworkAudioTime < 60000)) {
                return recentNetworkAudios.getFirst();
            }
        }
        return null;
    }

    private void captureNetworkVideoStream(String rawUrl) {
        if (rawUrl == null || rawUrl.startsWith("blob:") || rawUrl.startsWith("data:")) {
            return;
        }
        String cleanPath = rawUrl.toLowerCase(Locale.US).split("\\?")[0];
        boolean isVideo = cleanPath.endsWith(".mp4") || cleanPath.endsWith(".m3u8")
                || cleanPath.endsWith(".webm") || cleanPath.endsWith(".mov");
        boolean isAudio = cleanPath.endsWith(".mp3") || cleanPath.endsWith(".m4a")
                || cleanPath.endsWith(".aac") || cleanPath.endsWith(".ogg");

        if (!isVideo && !isAudio) {
            return;
        }

        String lowerFull = rawUrl.toLowerCase(Locale.US);
        boolean isIg = lowerFull.contains("cdninstagram.com") || lowerFull.contains("fbcdn.net") || lowerFull.contains("instagram.com");

        if (isIg) {
            String decodedEfg = decodeInstagramEfg(rawUrl);
            if (decodedEfg.contains("regular_photo")) {
                return;
            }
            String assetId = extractInstagramAssetId(decodedEfg);
            boolean isIgAudio = isInstagramAudioStreamUrl(rawUrl, decodedEfg, isAudio, isVideo);
            String fullStreamUrl = stripInstagramByteRange(rawUrl);

            if (isIgAudio) {
                synchronized (recentNetworkAudios) {
                    String key = normalizeKey(fullStreamUrl);
                    recentNetworkAudios.removeIf(u -> normalizeKey(u).equals(key));
                    recentNetworkAudios.addFirst(fullStreamUrl);
                    while (recentNetworkAudios.size() > 12) {
                        recentNetworkAudios.removeLast();
                    }
                    if (assetId != null) {
                        audioByAssetId.put(assetId, fullStreamUrl);
                        while (audioByAssetId.size() > 30) {
                            String oldest = audioByAssetId.keySet().iterator().next();
                            audioByAssetId.remove(oldest);
                        }
                    }
                }
                lastNetworkAudioTime = System.currentTimeMillis();

                // Retroactively attach this newly arrived audio stream to any matching video already in activeStreamsMap
                synchronized (activeStreamsMap) {
                    for (DetectedStream ds : activeStreamsMap.values()) {
                        if (!ds.isAudioOnly() && !ds.isImage()) {
                            String dsAsset = extractInstagramAssetId(decodeInstagramEfg(ds.getStreamUrl()));
                            if ((assetId != null && assetId.equals(dsAsset)) || !ds.hasCompanionAudio()) {
                                ds.setAudioStreamUrl(fullStreamUrl);
                            }
                        }
                    }
                }
            } else if (isVideo) {
                synchronized (recentNetworkVideos) {
                    String key = normalizeKey(fullStreamUrl);
                    recentNetworkVideos.removeIf(u -> normalizeKey(u).equals(key));
                    recentNetworkVideos.addFirst(fullStreamUrl);
                    while (recentNetworkVideos.size() > 12) {
                        recentNetworkVideos.removeLast();
                    }
                    if (assetId != null) {
                        videoByAssetId.put(assetId, fullStreamUrl);
                        while (videoByAssetId.size() > 30) {
                            String oldest = videoByAssetId.keySet().iterator().next();
                            videoByAssetId.remove(oldest);
                        }
                    }
                }
                lastNetworkVideoTime = System.currentTimeMillis();
            }
            return;
        }

        // For non-Instagram direct video/audio streams on other sites, keep at most MAX_DETECTED_ITEMS
        String fullStreamUrl = stripInstagramByteRange(rawUrl);
        String ext = cleanPath.substring(cleanPath.lastIndexOf('.') + 1).toUpperCase(Locale.US);
        DetectedStream ds = new DetectedStream(
                fullStreamUrl,
                (isAudio ? "Web Ses (" : "Web Video (") + ext + ")",
                ext + " Doğrudan Medya Akışı",
                isAudio,
                null
        );
        synchronized (activeStreamsMap) {
            String key = ds.getMapKey();
            if (!activeStreamsMap.containsKey(key)) {
                activeStreamsMap.put(key, ds);
                trimMapToLimit();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(this::updateGrabberFab);
                }
            }
        }
    }

    private void trimMapToLimit() {
        while (activeStreamsMap.size() > MAX_DETECTED_ITEMS) {
            String oldestKey = activeStreamsMap.keySet().iterator().next();
            activeStreamsMap.remove(oldestKey);
        }
    }

    private void updateGrabberFab() {
        if (binding == null || !isAdded()) return;
        int count;
        synchronized (activeStreamsMap) {
            count = activeStreamsMap.size();
        }
        if (count > 0) {
            binding.fabMediaGrabber.setText(getString(R.string.detected_media_count, count));
        } else {
            binding.fabMediaGrabber.setText(getString(R.string.btn_analyze_current_page));
        }
    }

    private void injectMediaSnifferScript(WebView webView) {
        String js = "javascript:(function() {" +
                "if (window.__vbkSnifferActive) { window.__vbkScanNow && window.__vbkScanNow(); return; }" +
                "window.__vbkSnifferActive = true;" +
                "function vbkSend(kind, a, b) {" +
                "  try {" +
                "    var br = window.VidownBridge;" +
                "    if (!br) return;" +
                "    if (typeof br.postMessage === 'function') {" +
                "      br.postMessage(JSON.stringify({k: kind, a: a, b: b}));" +
                "    } else if (kind === 'a' && br.onDomAudioStream) {" +
                "      br.onDomAudioStream(a);" +
                "    } else if (kind === 'v' && br.onViewportMediaJson) {" +
                "      br.onViewportMediaJson(a, b);" +
                "    }" +
                "  } catch (e) {}" +
                "}" +
                "function bestFromSrcset(el) {" +
                "  var ss = el.getAttribute('srcset');" +
                "  if (ss) {" +
                "    var parts = ss.split(',');" +
                "    var bestUrl = ''; var maxW = -1;" +
                "    for (var i = 0; i < parts.length; i++) {" +
                "      var seg = parts[i].trim().split(/\\s+/);" +
                "      if (seg.length >= 1 && seg[0].indexOf('http') === 0) {" +
                "        var w = seg.length > 1 ? parseInt(seg[1]) || 0 : 0;" +
                "        if (w >= maxW) { maxW = w; bestUrl = seg[0]; }" +
                "      }" +
                "    }" +
                "    if (bestUrl) return bestUrl;" +
                "  }" +
                "  return el.currentSrc || el.src || '';" +
                "}" +
                "function captureVideoFrame(vEl) {" +
                "  try {" +
                "    if (!vEl || !vEl.videoWidth || !vEl.videoHeight || vEl.readyState < 2) return '';" +
                "    var c = document.createElement('canvas');" +
                "    var maxDim = 240;" +
                "    var scale = Math.min(maxDim / vEl.videoWidth, maxDim / vEl.videoHeight, 1.0);" +
                "    c.width = Math.max(1, Math.round(vEl.videoWidth * scale));" +
                "    c.height = Math.max(1, Math.round(vEl.videoHeight * scale));" +
                "    var ctx = c.getContext('2d');" +
                "    ctx.drawImage(vEl, 0, 0, c.width, c.height);" +
                "    return c.toDataURL('image/jpeg', 0.65);" +
                "  } catch(e) {" +
                "    return '';" +
                "  }" +
                "}" +
                "function findPosterForVideo(m, targetRoot) {" +
                "  var poster = m.poster || '';" +
                "  if (poster && poster.indexOf('http') === 0) return poster;" +
                "  var container = m.closest('section') || m.closest('article') || m.closest('[role=\"dialog\"]') || m.parentElement;" +
                "  if (container) {" +
                "    var cImgs = container.querySelectorAll('img');" +
                "    for (var ci = 0; ci < cImgs.length; ci++) {" +
                "      var cimg = cImgs[ci];" +
                "      var csrc = bestFromSrcset(cimg);" +
                "      if (!csrc || csrc.indexOf('http') !== 0) continue;" +
                "      var calt = (cimg.alt || '').toLowerCase();" +
                "      if (calt.indexOf('profil') !== -1 || calt.indexOf('profile') !== -1) continue;" +
                "      var nw = cimg.naturalWidth || cimg.width || 0;" +
                "      var nh = cimg.naturalHeight || cimg.height || 0;" +
                "      if (nw >= 150 || nh >= 150) return csrc;" +
                "      if (csrc.indexOf('cdninstagram.com') !== -1 || csrc.indexOf('fbcdn.net') !== -1) {" +
                "        if (csrc.indexOf('150x150') === -1 && csrc.indexOf('50x50') === -1 && csrc.indexOf('75x75') === -1) {" +
                "          return csrc;" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "  var frame = captureVideoFrame(m);" +
                "  if (frame) return frame;" +
                "  var root = targetRoot || document;" +
                "  var sImgs = root.querySelectorAll('img');" +
                "  for (var si = 0; si < sImgs.length; si++) {" +
                "    var simg = sImgs[si];" +
                "    var ssrc = bestFromSrcset(simg);" +
                "    if (!ssrc || ssrc.indexOf('http') !== 0) continue;" +
                "    var salt = (simg.alt || '').toLowerCase();" +
                "    if (salt.indexOf('profil') !== -1 || salt.indexOf('profile') !== -1) continue;" +
                "    var snw = simg.naturalWidth || simg.width || 0;" +
                "    if (snw >= 200 && (ssrc.indexOf('cdninstagram.com') !== -1 || ssrc.indexOf('fbcdn.net') !== -1)) {" +
                "      return ssrc;" +
                "    }" +
                "  }" +
                "  return '';" +
                "}" +
                "function findPostMeta(el) {" +
                "  var isStory = location.pathname.indexOf('/stories/') === 0;" +
                "  var user = ''; var link = ''; var caption = '';" +
                "  if (isStory) {" +
                "    var sp = location.pathname.split('/');" +
                "    if (sp.length > 2) user = '@' + sp[2];" +
                "    link = location.href;" +
                "    caption = 'Instagram Hikayesi';" +
                "    return { user: user || '@instagram', link: link, caption: caption };" +
                "  }" +
                "  var art = el.closest('article') || el.closest('[role=\"dialog\"]') || el.closest('main') || document.body;" +
                "  if (art) {" +
                "    var links = art.querySelectorAll('a[href]');" +
                "    for (var i = 0; i < links.length; i++) {" +
                "      var h = links[i].getAttribute('href') || '';" +
                "      if (!link && (h.indexOf('/p/') !== -1 || h.indexOf('/reel/') !== -1 || h.indexOf('/reels/') !== -1)) {" +
                "        link = h.indexOf('http') === 0 ? h : (location.origin + h);" +
                "      }" +
                "      if (!user && h.match(/^\\/[A-Za-z0-9._]+\\/?$/) && h.indexOf('/explore') === -1 && h.indexOf('/reels') === -1 && h.indexOf('/stories') === -1) {" +
                "        var txt = (links[i].innerText || '').trim();" +
                "        if (txt && txt.length >= 2 && txt.length <= 32 && txt.indexOf(' ') === -1) {" +
                "          user = '@' + txt.replace(/^@/, '');" +
                "        }" +
                "      }" +
                "    }" +
                "    var spans = art.querySelectorAll('h1, span[dir=\"auto\"]');" +
                "    for (var j = 0; j < spans.length; j++) {" +
                "      var st = (spans[j].innerText || '').trim();" +
                "      var stLower = st.toLowerCase();" +
                "      if (st.length > 15 " +
                "          && stLower.indexOf('beğen') === -1 " +
                "          && stLower.indexOf('yorum') === -1 " +
                "          && stLower.indexOf('ok simgesi') === -1 " +
                "          && stLower.indexOf('simgesi') === -1 " +
                "          && stLower.indexOf('paylaş') === -1 " +
                "          && stLower.indexOf('seçenekler') === -1) {" +
                "        caption = st.replace(/\\s+/g, ' ').substring(0, 65);" +
                "        break;" +
                "      }" +
                "    }" +
                "  }" +
                "  if (!caption && el.alt && el.alt.length > 4) {" +
                "    var altLower = el.alt.toLowerCase();" +
                "    if (altLower.indexOf('profil') === -1 && altLower.indexOf('ok simgesi') === -1) {" +
                "      caption = el.alt.replace(/\\s+/g, ' ').substring(0, 65);" +
                "    }" +
                "  }" +
                "  return { user: user || '@instagram', link: link || location.href, caption: caption };" +
                "}" +
                "function scanViewport() {" +
                "  var vh = window.innerHeight || document.documentElement.clientHeight;" +
                "  var vw = window.innerWidth || document.documentElement.clientWidth;" +
                "  var isStory = location.pathname.indexOf('/stories/') === 0;" +
                "  var items = [];" +
                "  var activePermalink = '';" +
                "  var articles = document.querySelectorAll('article');" +
                "  var targetRoot = null;" +
                "  var bestDist = 99999;" +
                "  for (var a = 0; a < articles.length; a++) {" +
                "    var r = articles[a].getBoundingClientRect();" +
                "    if (r.bottom > 80 && r.top < vh - 80) {" +
                "      var center = (r.top + r.bottom) / 2;" +
                "      var dist = Math.abs(center - (vh / 2));" +
                "      if (dist < bestDist) { bestDist = dist; targetRoot = articles[a]; }" +
                "    }" +
                "  }" +
                "  if (!targetRoot) targetRoot = document;" +
                "  var meta = findPostMeta(targetRoot === document ? document.body : targetRoot);" +
                "  activePermalink = meta.link;" +
                "  var allAudioNodes = document.querySelectorAll('audio, video');" +
                "  for (var ai = 0; ai < allAudioNodes.length; ai++) {" +
                "    var an = allAudioNodes[ai];" +
                "    var isAudTag = an.tagName.toLowerCase() === 'audio';" +
                "    var isZeroVid = (an.tagName.toLowerCase() === 'video' && an.readyState >= 1 && an.videoWidth === 0 && an.videoHeight === 0);" +
                "    if (isAudTag || isZeroVid) {" +
                "      var aSrc = an.currentSrc || an.src || '';" +
                "      if (aSrc && aSrc.indexOf('http') === 0 && aSrc.indexOf('blob:') !== 0) {" +
                "        vbkSend('a', aSrc, '');" +
                "      }" +
                "    }" +
                "  }" +
                "  var mediaNodes = targetRoot.querySelectorAll('video, img');" +
                "  var slideIdx = 0;" +
                "  var isCarousel = targetRoot !== document && targetRoot.querySelectorAll('ul li').length > 1;" +
                "  for (var k = 0; k < mediaNodes.length; k++) {" +
                "    var m = mediaNodes[k];" +
                "    var rect = m.getBoundingClientRect();" +
                "    var bigEnough = rect.width >= 200 && rect.height >= 200;" +
                "    if (!bigEnough) continue;" +
                "    var inViewY = rect.bottom > 60 && rect.top < vh - 40;" +
                "    if (!inViewY) continue;" +
                "    var inViewX = rect.right > 40 && rect.left < vw - 40;" +
                "    if (!isCarousel && !inViewX) continue;" +
                "    if (m.tagName.toLowerCase() === 'video') {" +
                "      slideIdx++;" +
                "      var vSrc = m.currentSrc || m.src || '';" +
                "      var poster = findPosterForVideo(m, targetRoot);" +
                "      var hasVideoFrames = (m.videoWidth > 0 && m.videoHeight > 0);" +
                "      var isAudioOnlyElem = (m.readyState >= 1 && m.videoWidth === 0 && m.videoHeight === 0);" +
                "      var vUrl = (vSrc && vSrc.indexOf('http') === 0 && vSrc.indexOf('blob:') !== 0) ? vSrc : 'BLOB_ACTIVE_VIDEO';" +
                "      var vTag = isStory ? 'Hikaye Videosu' : (isCarousel ? ('Kaydırma #' + slideIdx + ' Video') : 'Video');" +
                "      items.push({" +
                "        url: vUrl," +
                "        title: meta.user + ' • ' + vTag," +
                "        subtitle: meta.caption || (isStory ? 'Instagram Hikayesi (Şu An Ekranda)' : 'Şu An Ekrandaki Video')," +
                "        thumb: poster," +
                "        isVideo: true," +
                "        hasVideoFrames: hasVideoFrames," +
                "        isAudioOnlyElem: isAudioOnlyElem," +
                "        onScreenNow: inViewX" +
                "      });" +
                "    } else if (m.tagName.toLowerCase() === 'img') {" +
                "      var iUrl = bestFromSrcset(m);" +
                "      if (!iUrl || iUrl.indexOf('http') !== 0 || iUrl.indexOf('data:') === 0) continue;" +
                "      if (m.alt && (m.alt.indexOf('profil resmi') !== -1 || m.alt.indexOf('profile picture') !== -1)) continue;" +
                "      slideIdx++;" +
                "      var iTag = isStory ? 'Hikaye Görseli' : (isCarousel ? ('Kaydırma #' + slideIdx + ' Görsel') : 'Fotoğraf');" +
                "      items.push({" +
                "        url: iUrl," +
                "        title: meta.user + ' • ' + iTag," +
                "        subtitle: meta.caption || (isStory ? 'Instagram Hikayesi (Şu An Ekranda)' : 'Şu An Ekrandaki Görsel')," +
                "        thumb: iUrl," +
                "        isVideo: false," +
                "        onScreenNow: inViewX" +
                "      });" +
                "    }" +
                "  }" +
                "  items.sort(function(x, y) { return (y.onScreenNow ? 1 : 0) - (x.onScreenNow ? 1 : 0); });" +
                "  vbkSend('v', JSON.stringify(items.slice(0, 6)), activePermalink || location.href);" +
                "}" +
                "window.__vbkScanNow = scanViewport;" +
                "scanViewport();" +
                "window.addEventListener('scroll', function() { clearTimeout(window.__vbkSt); window.__vbkSt = setTimeout(scanViewport, 300); }, true);" +
                "document.addEventListener('touchend', function() { setTimeout(scanViewport, 350); }, true);" +
                "document.addEventListener('loadedmetadata', function() { setTimeout(scanViewport, 100); }, true);" +
                "document.addEventListener('loadeddata', function() { setTimeout(scanViewport, 120); }, true);" +
                "document.addEventListener('play', function() { setTimeout(scanViewport, 150); }, true);" +
                "document.addEventListener('playing', function() { setTimeout(scanViewport, 200); }, true);" +
                "document.addEventListener('timeupdate', function(e) {" +
                "  if (e.target && e.target.currentTime > 0.1 && !e.target.__vbkThumbCaptured) {" +
                "    e.target.__vbkThumbCaptured = true;" +
                "    scanViewport();" +
                "  }" +
                "}, true);" +
                "setInterval(scanViewport, 1500);" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public boolean canGoBack() {
        return binding != null && webView.canGoBack();
    }

    public void goBack() {
        if (binding != null && webView.canGoBack()) {
            webView.goBack();
        }
    }

    private static String audioTitleFor(String title) {
        String base = title == null ? "" : title;
        if (base.contains("Videosu") || base.contains("Video")) {
            return base.replace("Videosu", "Ses Dosyası").replace("Video", "Ses Dosyası");
        }
        return base + " (Ses Dosyası)";
    }

    /**
     * Installs the JS bridge. Preferred: WebMessageListener (reports whether the sender is the main
     * frame and its origin). Fallback: addJavascriptInterface, with main-frame URL validation.
     */
    private void installBridge(WebView target) {
        usingWebMessageListener = false;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                Set<String> origins = new java.util.HashSet<>();
                origins.add("*");
                WebViewCompat.addWebMessageListener(target, "VidownBridge", origins,
                        (WebView view, WebMessageCompat message, Uri sourceOrigin, boolean isMainFrame,
                         JavaScriptReplyProxy replyProxy) -> {
                            // Only the top-level frame on an http(s) page may feed the detector
                            if (!isMainFrame) return;
                            String scheme = sourceOrigin != null ? sourceOrigin.getScheme() : null;
                            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                                return;
                            }
                            String data = message.getData();
                            if (data == null) return;
                            try {
                                JSONObject o = new JSONObject(data);
                                String kind = o.optString("k", "");
                                if ("a".equals(kind)) {
                                    handleDomAudioStream(o.optString("a", ""));
                                } else if ("v".equals(kind)) {
                                    handleViewportMedia(o.optString("a", ""), o.optString("b", ""));
                                }
                            } catch (Exception ignored) {
                            }
                        });
                usingWebMessageListener = true;
            } catch (Throwable ignored) {
                usingWebMessageListener = false;
            }
        }
        if (!usingWebMessageListener) {
            target.addJavascriptInterface(bridgeImpl, "VidownBridge");
        }
    }

    // ---- Fullscreen video support ----

    private void showFullscreenView(View view, WebChromeClient.CustomViewCallback callback) {
        if (getActivity() == null || binding == null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        if (customView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        ViewGroup decor = (ViewGroup) getActivity().getWindow().getDecorView();
        fullscreenContainer = new FrameLayout(requireContext());
        fullscreenContainer.setBackgroundColor(android.graphics.Color.BLACK);
        fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decor.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getActivity().getWindow(), decor);
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        try {
            if (getActivity() != null) {
                ViewGroup decor = (ViewGroup) getActivity().getWindow().getDecorView();
                if (fullscreenContainer != null) {
                    fullscreenContainer.removeAllViews();
                    decor.removeView(fullscreenContainer);
                }
                androidx.core.view.WindowCompat.getInsetsController(getActivity().getWindow(), decor)
                        .show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            }
        } catch (Throwable ignored) {
        }
        fullscreenContainer = null;
        customView = null;
        if (customViewCallback != null) {
            try {
                customViewCallback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
            customViewCallback = null;
        }
    }

    // ---- Bridge handlers (validated) ----

    private boolean isTrustedPage() {
        return isHttpUrl(currentMainFrameUrl);
    }

    private void handleDomAudioStream(String audioSrcUrl) {
        if (!isTrustedPage() || !isHttpUrl(audioSrcUrl)) return;
        bridgeImpl.onDomAudioStreamInternal(audioSrcUrl);
    }

    private void handleViewportMedia(String json, String permalink) {
        if (!isTrustedPage()) return;
        bridgeImpl.onViewportMediaInternal(json, permalink);
    }

    private class MediaGrabberJsBridge {
        @JavascriptInterface
        public void onDomAudioStream(String audioSrcUrl) {
            handleDomAudioStream(audioSrcUrl);
        }

        @JavascriptInterface
        public void onViewportMediaJson(String jsonArrayStr, String postPermalink) {
            handleViewportMedia(jsonArrayStr, postPermalink);
        }

        void onDomAudioStreamInternal(String audioSrcUrl) {
            if (audioSrcUrl == null || !isHttpUrl(audioSrcUrl)) return;
            String fullStreamUrl = stripInstagramByteRange(audioSrcUrl);
            String key = normalizeKey(fullStreamUrl);
            synchronized (recentNetworkVideos) {
                recentNetworkVideos.removeIf(u -> normalizeKey(u).equals(key));
            }
            synchronized (recentNetworkAudios) {
                recentNetworkAudios.removeIf(u -> normalizeKey(u).equals(key));
                recentNetworkAudios.addFirst(fullStreamUrl);
                while (recentNetworkAudios.size() > 12) {
                    recentNetworkAudios.removeLast();
                }
            }
            lastNetworkAudioTime = System.currentTimeMillis();
        }

        void onViewportMediaInternal(String jsonArrayStr, String postPermalink) {
            if (isHttpUrl(postPermalink)) {
                activeVisiblePostUrl = postPermalink;
            }
            if (jsonArrayStr == null || jsonArrayStr.isEmpty()) return;

            try {
                JSONArray arr = new JSONArray(jsonArrayStr);
                List<DetectedStream> freshViewportList = new ArrayList<>();

                // First pass: check if there is a visible story/post image on screen (for photo-with-music stories)
                String firstImageUrl = null;
                String firstImageThumb = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    if (!obj.optBoolean("isVideo", false)) {
                        String imgU = obj.optString("url", "");
                        if (imgU.startsWith("http")) {
                            firstImageUrl = imgU;
                            firstImageThumb = obj.optString("thumb", imgU);
                            break;
                        }
                    }
                }

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String url = obj.optString("url", "");
                    String title = obj.optString("title", "Medya");
                    String subtitle = obj.optString("subtitle", "");
                    String thumb = obj.optString("thumb", "");
                    boolean isVideo = obj.optBoolean("isVideo", false);
                    boolean isAudioOnlyElem = obj.optBoolean("isAudioOnlyElem", false);

                    if (isVideo) {
                        // If the <video> element in DOM has videoWidth == 0 (audio-only player on a Photo-with-Music post/story),
                        // offer both a combined Photo+Music MP4 video AND the standalone MP3 option
                        if (isAudioOnlyElem) {
                            String audioUrl = null;
                            synchronized (recentNetworkAudios) {
                                if (!recentNetworkAudios.isEmpty()) {
                                    audioUrl = recentNetworkAudios.getFirst();
                                }
                            }
                            if (audioUrl != null && !audioUrl.isEmpty()) {
                                String audioThumb = (firstImageThumb != null && !firstImageThumb.isEmpty()) ? firstImageThumb : thumb;
                                if (firstImageUrl != null && !firstImageUrl.isEmpty()) {
                                    freshViewportList.add(new DetectedStream(
                                            firstImageUrl,
                                            title,
                                            "MP4 • Müzikli Video (Fotoğraf + Ses)",
                                            false,
                                            audioThumb,
                                            audioUrl
                                    ));
                                }
                                String audioTitle = audioTitleFor(title);
                                freshViewportList.add(new DetectedStream(
                                        audioUrl,
                                        audioTitle,
                                        "MP3 • Ses Dosyası",
                                        true,
                                        audioThumb
                                ));
                            }
                            continue;
                        }

                        if ("BLOB_ACTIVE_VIDEO".equals(url)) {
                            synchronized (recentNetworkVideos) {
                                url = "";
                                for (String candidate : recentNetworkVideos) {
                                    if (!isInstagramAudioStreamUrl(candidate, decodeInstagramEfg(candidate), false, true)) {
                                        url = candidate;
                                        break;
                                    }
                                }
                            }
                        }

                        if (url.isEmpty() || !url.startsWith("http")
                                || isInstagramAudioStreamUrl(url, decodeInstagramEfg(url), false, true)) {
                            String audioUrl = null;
                            synchronized (recentNetworkAudios) {
                                if (!recentNetworkAudios.isEmpty()) {
                                    audioUrl = recentNetworkAudios.getFirst();
                                }
                            }
                            if (audioUrl != null && !audioUrl.isEmpty()) {
                                String audioThumb = (firstImageThumb != null && !firstImageThumb.isEmpty()) ? firstImageThumb : thumb;
                                if (firstImageUrl != null && !firstImageUrl.isEmpty()) {
                                    freshViewportList.add(new DetectedStream(
                                            firstImageUrl,
                                            title,
                                            "MP4 • Müzikli Video (Fotoğraf + Ses)",
                                            false,
                                            audioThumb,
                                            audioUrl
                                    ));
                                }
                                String audioTitle = audioTitleFor(title);
                                freshViewportList.add(new DetectedStream(
                                        audioUrl,
                                        audioTitle,
                                        "MP3 • Ses Dosyası",
                                        true,
                                        audioThumb
                                ));
                            }
                            continue;
                        }

                        // Attach companion audio so the Video (MP4) download merges both video and sound into one MP4 file!
                        String companionAudio = findAudioForVideoUrl(url);
                        String videoSub = (companionAudio != null && !companionAudio.isEmpty())
                                ? "MP4 • Sesli Video (Görüntü + Ses)"
                                : ("MP4 • " + subtitle);
                        freshViewportList.add(new DetectedStream(url, title, videoSub, false, thumb, companionAudio));
                    } else {
                        // Image
                        if (url.isEmpty() || !url.startsWith("http")) continue;
                        freshViewportList.add(new DetectedStream(url, title, subtitle, false, thumb));
                    }
                }

                if (!freshViewportList.isEmpty()) {
                    synchronized (activeStreamsMap) {
                        // Put currently visible screen items at the VERY TOP of activeStreamsMap!
                        LinkedHashMap<String, DetectedStream> reordered = new LinkedHashMap<>();
                        for (DetectedStream ds : freshViewportList) {
                            String key = ds.getMapKey();
                            DetectedStream existing = activeStreamsMap.get(key);
                            if (existing != null) {
                                String bestThumb = (ds.getThumbnailUrl() != null && !ds.getThumbnailUrl().isEmpty())
                                        ? ds.getThumbnailUrl()
                                        : existing.getThumbnailUrl();
                                String bestAudio = ds.hasCompanionAudio()
                                        ? ds.getAudioStreamUrl()
                                        : existing.getAudioStreamUrl();
                                ds = new DetectedStream(ds.getStreamUrl(), ds.getTitle(), ds.getMimeType(), ds.isAudioOnly(), bestThumb, bestAudio);
                            }
                            reordered.put(key, ds);
                        }
                        for (Map.Entry<String, DetectedStream> entry : activeStreamsMap.entrySet()) {
                            if (reordered.size() >= MAX_DETECTED_ITEMS) break;
                            if (!reordered.containsKey(entry.getKey())) {
                                reordered.put(entry.getKey(), entry.getValue());
                            }
                        }
                        activeStreamsMap.clear();
                        activeStreamsMap.putAll(reordered);
                    }
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(BrowserFragment.this::updateGrabberFab);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroyView() {
        exitFullscreen();
        if (webView != null) {
            WebView wv = webView;
            webView = null;
            ViewGroup parent = (ViewGroup) wv.getParent();
            if (parent != null) {
                parent.removeView(wv);
            }
            wv.stopLoading();
            wv.setWebChromeClient(null);
            wv.setWebViewClient(new WebViewClient());
            wv.destroy();
        }
        super.onDestroyView();
        binding = null;
    }
}
