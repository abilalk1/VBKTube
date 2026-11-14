package com.vidownphone.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.vidownphone.app.R;
import com.vidownphone.app.databinding.ActivityMainBinding;
import com.vidownphone.app.ui.browser.BrowserFragment;
import com.vidownphone.app.ui.downloads.DownloadsFragment;
import com.vidownphone.app.ui.home.HomeFragment;
import com.vidownphone.app.ui.settings.SettingsFragment;
import com.google.android.material.snackbar.Snackbar;
import com.vidownphone.app.util.FormatUtils;
import com.vidownphone.app.util.PreferenceHelper;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    /** Process-local secret that only our own code can attach to an intent (auto-download is never honoured otherwise). */
    public static final String EXTRA_AUTO_DOWNLOAD_TOKEN = "internal_auto_download_token";
    private static volatile String autoDownloadToken;

    /** Creates a one-shot token; put it in the intent extra EXTRA_AUTO_DOWNLOAD_TOKEN together with "auto_download". */
    public static String newAutoDownloadToken() {
        autoDownloadToken = java.util.UUID.randomUUID().toString();
        return autoDownloadToken;
    }

    private static final String TAG_HOME = "tag_home";
    private static final String TAG_BROWSER = "tag_browser";
    private static final String TAG_DOWNLOADS = "tag_downloads";
    private static final String TAG_SETTINGS = "tag_settings";

    private ActivityMainBinding binding;
    private HomeFragment homeFragment;
    private BrowserFragment browserFragment;
    private DownloadsFragment downloadsFragment;
    private SettingsFragment settingsFragment;
    private Fragment activeFragment;
    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_VidownPhone);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);
        if (savedInstanceState == null) {
            if (PreferenceHelper.wasDisclaimerShown(this)) {
                requestNeededPermissions();
            } else {
                showDisclaimerDialog(true);
            }
        }
        setupFragments(savedInstanceState);
        setupBottomNavigation();
        if (savedInstanceState == null && PreferenceHelper.wasDisclaimerShown(this)) {
            com.vidownphone.app.util.UpdateChecker.checkAutomatically(this);
        }
        // Y-08: only handle the launching intent on a fresh start, never after recreation (rotation, theme, language).
        if (savedInstanceState == null) {
            handleIncomingShareIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    private void setupFragments(Bundle savedInstanceState) {
        FragmentManager fm = getSupportFragmentManager();
        homeFragment = (HomeFragment) fm.findFragmentByTag(TAG_HOME);
        browserFragment = (BrowserFragment) fm.findFragmentByTag(TAG_BROWSER);
        downloadsFragment = (DownloadsFragment) fm.findFragmentByTag(TAG_DOWNLOADS);
        settingsFragment = (SettingsFragment) fm.findFragmentByTag(TAG_SETTINGS);

        if (homeFragment == null) {
            homeFragment = new HomeFragment();
        }
        if (browserFragment == null) {
            browserFragment = new BrowserFragment();
        }
        if (downloadsFragment == null) {
            downloadsFragment = new DownloadsFragment();
        }
        if (settingsFragment == null) {
            settingsFragment = new SettingsFragment();
        }

        if (savedInstanceState == null) {
            FragmentTransaction ft = fm.beginTransaction();
            ft.add(R.id.nav_host_container, settingsFragment, TAG_SETTINGS).hide(settingsFragment);
            ft.add(R.id.nav_host_container, downloadsFragment, TAG_DOWNLOADS).hide(downloadsFragment);
            ft.add(R.id.nav_host_container, browserFragment, TAG_BROWSER).hide(browserFragment);
            ft.add(R.id.nav_host_container, homeFragment, TAG_HOME);
            ft.commitNow();
            activeFragment = homeFragment;
        } else {
            String activeTag = savedInstanceState.getString("KEY_ACTIVE_TAG", TAG_HOME);
            Fragment target = homeFragment;
            int navItemId = R.id.nav_home;
            if (TAG_SETTINGS.equals(activeTag)) {
                target = settingsFragment;
                navItemId = R.id.nav_settings;
            } else if (TAG_DOWNLOADS.equals(activeTag)) {
                target = downloadsFragment;
                navItemId = R.id.nav_downloads;
            } else if (TAG_BROWSER.equals(activeTag)) {
                target = browserFragment;
                navItemId = R.id.nav_browser;
            }

            FragmentTransaction ft = fm.beginTransaction();
            for (Fragment f : new Fragment[]{homeFragment, browserFragment, downloadsFragment, settingsFragment}) {
                if (f != null && f.isAdded()) {
                    if (f == target) {
                        ft.show(f);
                    } else {
                        ft.hide(f);
                    }
                }
            }
            ft.commitNow();
            activeFragment = target;
            binding.bottomNavigation.setSelectedItemId(navItemId);
        }
    }

    private void setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                switchFragment(homeFragment);
                return true;
            } else if (itemId == R.id.nav_browser) {
                switchFragment(browserFragment);
                return true;
            } else if (itemId == R.id.nav_downloads) {
                switchFragment(downloadsFragment);
                return true;
            } else if (itemId == R.id.nav_settings) {
                switchFragment(settingsFragment);
                return true;
            }
            return false;
        });
    }

    private void switchFragment(Fragment target) {
        if (target == null) {
            return;
        }
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        for (Fragment f : new Fragment[]{homeFragment, browserFragment, downloadsFragment, settingsFragment}) {
            if (f != null && f.isAdded()) {
                if (f == target) {
                    ft.show(f);
                } else {
                    ft.hide(f);
                }
            }
        }
        ft.commitAllowingStateLoss();
        activeFragment = target;
        if (binding != null && binding.liquidBackground != null) {
            binding.liquidBackground.setPaused(target == browserFragment);
        }
    }

    public void openUrlInBrowser(String url) {
        binding.bottomNavigation.setSelectedItemId(R.id.nav_browser);
        if (browserFragment != null) {
            browserFragment.loadExternalUrl(url);
        }
    }

    public void openUrlForAnalysis(String url) {
        openUrlForAnalysis(url, false);
    }

    public void openUrlForAnalysis(String url, boolean autoDownload) {
        binding.bottomNavigation.setSelectedItemId(R.id.nav_home);
        if (homeFragment != null) {
            homeFragment.populateAndAnalyzeUrl(url, autoDownload);
        }
    }

    public void navigateToDownloads() {
        binding.bottomNavigation.setSelectedItemId(R.id.nav_downloads);
    }

    private void handleIncomingShareIntent(Intent intent) {
        if (intent == null) return;
        String browserUrl = intent.getStringExtra("open_browser_url");
        boolean handled = false;
        if (browserUrl != null && !browserUrl.isEmpty()) {
            openUrlInBrowser(browserUrl);
            handled = true;
        } else if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            CharSequence sharedCs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String sharedText = sharedCs != null ? sharedCs.toString() : null;
            String token = intent.getStringExtra(EXTRA_AUTO_DOWNLOAD_TOKEN);
            boolean trusted = token != null && token.equals(autoDownloadToken);
            boolean autoDownload = trusted && intent.getBooleanExtra("auto_download", false);
            if (trusted) autoDownloadToken = null;
            String extractedUrl = FormatUtils.extractFirstUrl(sharedText);
            if (extractedUrl != null) {
                openUrlForAnalysis(extractedUrl, autoDownload);
            }
            handled = true;
        }
        if (handled) {
            // Consume the intent so a later recreation / re-delivery cannot process it again.
            intent.removeExtra("open_browser_url");
            intent.removeExtra(Intent.EXTRA_TEXT);
            intent.removeExtra("auto_download");
            intent.removeExtra(EXTRA_AUTO_DOWNLOAD_TOKEN);
            setIntent(new Intent());
        }
    }

    public void showDisclaimerDialog(boolean firstRun) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.disclaimer_title)
                .setMessage(R.string.disclaimer_message)
                .setCancelable(!firstRun)
                .setPositiveButton(R.string.disclaimer_ok, (d, w) -> {
                    if (firstRun) {
                        PreferenceHelper.setDisclaimerShown(this);
                        requestNeededPermissions();
                    }
                })
                .show();
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        // Notification permission: ask only once per install (and never again on recreation).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !PreferenceHelper.wasNotificationPermissionAsked(this)) {
            PreferenceHelper.setNotificationPermissionAsked(this);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        if (!permissions.isEmpty()) {
            permissionLauncher.launch(permissions.toArray(new String[0]));
        }
    }

    private void onPermissionsResult(java.util.Map<String, Boolean> result) {
        Boolean notif = result.get(Manifest.permission.POST_NOTIFICATIONS);
        if (notif != null && !notif && !PreferenceHelper.wasNotificationDeniedHintShown(this)) {
            PreferenceHelper.setNotificationDeniedHintShown(this);
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.notification_permission_denied, Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.bottomNavigation)
                        .show();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        String tag = TAG_HOME;
        if (activeFragment == settingsFragment) tag = TAG_SETTINGS;
        else if (activeFragment == downloadsFragment) tag = TAG_DOWNLOADS;
        else if (activeFragment == browserFragment) tag = TAG_BROWSER;
        outState.putString("KEY_ACTIVE_TAG", tag);
    }

    @Override
    public void onBackPressed() {
        if (activeFragment == browserFragment && browserFragment != null && browserFragment.canGoBack()) {
            browserFragment.goBack();
            return;
        }
        if (activeFragment != homeFragment) {
            binding.bottomNavigation.setSelectedItemId(R.id.nav_home);
            return;
        }
        if (homeFragment != null && homeFragment.consumeBackPress()) {
            return;
        }
        super.onBackPressed();
    }
}
