package com.vidownphone.app.ui.settings;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.vidownphone.app.R;
import com.vidownphone.app.data.AppDatabase;
import com.vidownphone.app.data.DownloadTask;
import com.vidownphone.app.databinding.FragmentSettingsBinding;
import com.vidownphone.app.engine.EngineManager;
import com.vidownphone.app.service.DownloadService;
import com.vidownphone.app.util.PreferenceHelper;

import java.io.File;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupThemeSelector();
        setupLanguageSelector();
        setupEngineAndStorageSection();
        refreshServiceIndicators();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshServiceIndicators();
        updateCacheSizeDisplay();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            refreshServiceIndicators();
            updateCacheSizeDisplay();
        }
    }

    private void setupThemeSelector() {
        int currentTheme = PreferenceHelper.getThemeMode(requireContext());
        if (currentTheme == PreferenceHelper.THEME_LIGHT) {
            binding.rbThemeLight.setChecked(true);
        } else if (currentTheme == PreferenceHelper.THEME_DARK) {
            binding.rbThemeDark.setChecked(true);
        } else {
            binding.rbThemeSystem.setChecked(true);
        }

        binding.rgTheme.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_theme_light) {
                PreferenceHelper.setThemeMode(requireContext(), PreferenceHelper.THEME_LIGHT);
            } else if (checkedId == R.id.rb_theme_dark) {
                PreferenceHelper.setThemeMode(requireContext(), PreferenceHelper.THEME_DARK);
            } else {
                PreferenceHelper.setThemeMode(requireContext(), PreferenceHelper.THEME_SYSTEM);
            }
        });
    }

    private void setupLanguageSelector() {
        String lang = PreferenceHelper.getLanguage(requireContext());
        if ("en".equalsIgnoreCase(lang)) {
            binding.rbLangEn.setChecked(true);
        } else {
            binding.rbLangTr.setChecked(true);
        }

        binding.rgLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_lang_en) {
                PreferenceHelper.setLanguage(requireContext(), "en");
            } else if (checkedId == R.id.rb_lang_tr) {
                PreferenceHelper.setLanguage(requireContext(), "tr");
            }
        });
    }

    private void setupEngineAndStorageSection() {
        // Update Channel (Nightly vs Stable)
        String currentChannel = PreferenceHelper.getUpdateChannel(requireContext());
        if ("STABLE".equalsIgnoreCase(currentChannel)) {
            binding.rbChannelStable.setChecked(true);
        } else {
            binding.rbChannelNightly.setChecked(true);
        }

        binding.rgUpdateChannel.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_channel_stable) {
                PreferenceHelper.setUpdateChannel(requireContext(), "STABLE");
            } else {
                PreferenceHelper.setUpdateChannel(requireContext(), "NIGHTLY");
            }
        });

        binding.switchShowTips.setChecked(PreferenceHelper.isShowHomeTips(requireContext()));
        binding.switchShowTips.setOnCheckedChangeListener((buttonView, isChecked) ->
                PreferenceHelper.setShowHomeTips(requireContext(), isChecked));

        binding.switchAdblock.setChecked(PreferenceHelper.isAdBlockEnabled(requireContext()));
        binding.switchAdblock.setOnCheckedChangeListener((buttonView, isChecked) ->
                PreferenceHelper.setAdBlockEnabled(requireContext(), isChecked));

        binding.tvStoragePath.setText(R.string.storage_location_desc);

        binding.switchAutoUpdateCheck.setChecked(PreferenceHelper.isAutoUpdateCheckEnabled(requireContext()));
        binding.switchAutoUpdateCheck.setOnCheckedChangeListener((b, on) ->
                PreferenceHelper.setAutoUpdateCheckEnabled(requireContext(), on));
        binding.btnCheckUpdates.setOnClickListener(v ->
                com.vidownphone.app.util.UpdateChecker.check(requireActivity(), true));
        binding.btnLegal.setOnClickListener(v -> {
            if (getActivity() instanceof com.vidownphone.app.ui.MainActivity) {
                ((com.vidownphone.app.ui.MainActivity) getActivity()).showDisclaimerDialog(false);
            }
        });

        // Button 1: Re-find & Activate All Services immediately
        binding.btnReinitServices.setOnClickListener(v -> triggerServiceOperation(false));

        // Button 2: Re-initialize + Update yt-dlp binary from GitHub cloud
        binding.btnUpdateYtdlp.setOnClickListener(v -> triggerServiceOperation(true));

        binding.btnClearCache.setOnClickListener(v -> {
            binding.btnClearCache.setEnabled(false);
            binding.tvCacheSize.setText(R.string.cache_calculating);
            final android.content.Context appCtx = requireContext().getApplicationContext();
            Executors.newSingleThreadExecutor().execute(() -> {
                long freed = DownloadService.cleanAllCacheAndTempFiles(appCtx);
                mainHandler.post(() -> {
                    if (!isAdded() || binding == null) return;
                    binding.btnClearCache.setEnabled(true);
                    updateCacheSizeDisplay();
                    String freedStr = freed > 0 ? com.vidownphone.app.util.FormatUtils.formatFileSize(freed) : "0 B";
                    Toast.makeText(requireContext(),
                            getString(R.string.cache_cleared_toast, freedStr),
                            Toast.LENGTH_SHORT).show();
                });
            });
        });

        binding.btnClearCompleted.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_completed_title)
                    .setMessage(R.string.clear_completed_dialog_msg)
                    .setPositiveButton(R.string.clear_completed_and_files, (dialog, which) -> {
                        android.content.Context ctx = requireContext().getApplicationContext();
                        Executors.newSingleThreadExecutor().execute(() -> {
                            java.util.List<DownloadTask> completed = AppDatabase.getInstance(ctx)
                                    .downloadDao()
                                    .getCompletedTasksSync(DownloadTask.STATUS_COMPLETED);
                            if (completed != null) {
                                for (DownloadTask t : completed) {
                                    if (t.getFilePath() != null) {
                                        File f = new File(t.getFilePath());
                                        if (f.exists()) f.delete();
                                        try {
                                            android.net.Uri contentUri = t.isAudioOnly()
                                                    ? android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                                                    : android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                                            ctx.getContentResolver().delete(contentUri,
                                                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
                                                    new String[]{f.getName()});
                                        } catch (Exception ignored) {
                                        }
                                    }
                                    DownloadService.deleteTaskWorkFiles(ctx, t.getId());
                                }
                            }
                            AppDatabase.getInstance(ctx)
                                    .downloadDao()
                                    .clearCompletedTasks(DownloadTask.STATUS_COMPLETED);
                            DownloadService.cleanAllCacheAndTempFiles(ctx);
                            mainHandler.post(() -> {
                                if (isAdded()) {
                                    updateCacheSizeDisplay();
                                    Toast.makeText(requireContext(), R.string.history_cleared_toast, Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    })
                    .setNeutralButton(R.string.clear_history_only, (dialog, which) -> {
                        final android.content.Context appCtx = requireContext().getApplicationContext();
                        Executors.newSingleThreadExecutor().execute(() -> {
                            AppDatabase.getInstance(appCtx)
                                    .downloadDao()
                                    .clearCompletedTasks(DownloadTask.STATUS_COMPLETED);
                            mainHandler.post(() -> {
                                if (isAdded()) {
                                    updateCacheSizeDisplay();
                                    Toast.makeText(requireContext(), R.string.history_cleared_toast, Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        updateCacheSizeDisplay();
    }

    private void updateCacheSizeDisplay() {
        if (binding == null || !isAdded()) return;
        binding.tvCacheSize.setText(R.string.cache_calculating);
        final android.content.Context appCtx = requireContext().getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            long size = DownloadService.getWorkDirectoryAndCacheSize(appCtx);
            mainHandler.post(() -> {
                if (!isAdded() || binding == null) return;
                if (size <= 0) {
                    binding.tvCacheSize.setText(R.string.cache_clean);
                } else {
                    binding.tvCacheSize.setText(com.vidownphone.app.util.FormatUtils.formatFileSize(size));
                }
            });
        });
    }

    private void refreshServiceIndicators() {
        if (binding == null || !isAdded()) return;

        // If services haven't finished startup init yet, trigger a quick check
        if (!EngineManager.isNewPipeReady() || !EngineManager.isYtdlpReady()) {
            final android.content.Context appCtx = requireContext().getApplicationContext();
            Executors.newSingleThreadExecutor().execute(() -> {
                EngineManager.ensureInitializedSync(appCtx);
                mainHandler.post(this::applyIndicatorsToViews);
            });
        } else {
            applyIndicatorsToViews();
        }
    }

    private void applyIndicatorsToViews() {
        if (binding == null || !isAdded()) return;
        updateBadge(binding.tvStatusNewpipe, EngineManager.isNewPipeReady());
        updateBadge(binding.tvStatusYtdlp, EngineManager.isYtdlpReady());
        updateBadge(binding.tvStatusFfmpeg, EngineManager.isFfmpegReady());
        updateBadge(binding.tvStatusSniffer, true);

        String ver = EngineManager.getCachedYtdlpVersion();
        String channel = PreferenceHelper.getUpdateChannel(requireContext());
        if (ver != null && !ver.isEmpty()) {
            binding.tvYtdlpVersion.setText(getString(R.string.settings_ytdlp_version, ver, channel));
        } else if (EngineManager.isYtdlpReady()) {
            binding.tvYtdlpVersion.setText(getString(R.string.settings_ytdlp_builtin_ready, channel));
        } else {
            binding.tvYtdlpVersion.setText(R.string.settings_ytdlp_tap_to_start);
        }
    }

    private void updateBadge(TextView badge, boolean active) {
        int color = ContextCompat.getColor(requireContext(), active ? R.color.success : R.color.warning);
        badge.setText(active ? R.string.service_status_active : R.string.service_status_inactive);
        badge.setTextColor(color);
    }

    private void triggerServiceOperation(boolean performRemoteUpdate) {
        if (binding == null || !isAdded()) return;
        binding.btnReinitServices.setEnabled(false);
        binding.btnUpdateYtdlp.setEnabled(false);
        binding.layoutServiceProgress.setVisibility(View.VISIBLE);
        binding.tvServiceProgress.setText(R.string.updating_engine);

        EngineManager.reinitializeAndUpdateServices(
                requireContext(),
                performRemoteUpdate,
                new EngineManager.EngineCallback() {
                    @Override
                    public void onProgress(String statusText) {
                        if (!isAdded() || binding == null) return;
                        binding.tvServiceProgress.setText(statusText);
                    }

                    @Override
                    public void onComplete(boolean allReady, boolean updated, String version, String message) {
                        if (!isAdded() || binding == null) return;
                        binding.btnReinitServices.setEnabled(true);
                        binding.btnUpdateYtdlp.setEnabled(true);
                        binding.layoutServiceProgress.setVisibility(View.GONE);
                        applyIndicatorsToViews();
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
