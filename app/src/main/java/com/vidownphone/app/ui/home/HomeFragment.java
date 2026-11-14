package com.vidownphone.app.ui.home;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vidownphone.app.R;
import com.vidownphone.app.data.DownloadTask;
import com.vidownphone.app.databinding.FragmentHomeBinding;
import com.vidownphone.app.engine.EngineManager;
import com.vidownphone.app.engine.HybridExtractor;
import com.vidownphone.app.model.AnalyzedMedia;
import com.vidownphone.app.model.MediaFormatOption;
import com.vidownphone.app.service.DownloadService;
import com.vidownphone.app.ui.MainActivity;
import com.vidownphone.app.util.FormatUtils;

import java.util.List;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private HybridExtractor extractor;
    private String pendingUrlToAnalyze;
    private boolean pendingAutoDownload;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        extractor = new HybridExtractor(requireContext());

        binding.btnPaste.setOnClickListener(v -> pasteFromClipboard());
        binding.btnAnalyze.setOnClickListener(v -> startUrlAnalysis(false));
        binding.btnQuickDownload.setOnClickListener(v -> startUrlAnalysis(true));

        binding.layoutEnginePill.setOnClickListener(v -> refreshServicesFromHero());

        binding.layoutTipsHeader.setOnClickListener(v -> {
            boolean nextCollapsed = !com.vidownphone.app.util.PreferenceHelper.isHomeTipsCollapsed(requireContext());
            com.vidownphone.app.util.PreferenceHelper.setHomeTipsCollapsed(requireContext(), nextCollapsed);
            android.transition.TransitionManager.beginDelayedTransition((ViewGroup) binding.getRoot());
            refreshTipsSectionUi();
        });


        // Y-09: the sheet reports through the FragmentResult API so it works after recreation.
        getChildFragmentManager().setFragmentResultListener(
                FormatSelectionBottomSheet.REQUEST_KEY, getViewLifecycleOwner(),
                (requestKey, result) -> {
                    AnalyzedMedia media = (AnalyzedMedia) result.getSerializable(FormatSelectionBottomSheet.RESULT_MEDIA);
                    if (media == null) return;
                    java.io.Serializable single = result.getSerializable(FormatSelectionBottomSheet.RESULT_OPTION);
                    java.io.Serializable multi = result.getSerializable(FormatSelectionBottomSheet.RESULT_OPTIONS);
                    if (single instanceof MediaFormatOption) {
                        enqueueMediaDownload(media, (MediaFormatOption) single);
                    } else if (multi instanceof java.util.List) {
                        @SuppressWarnings("unchecked")
                        List<MediaFormatOption> options = (List<MediaFormatOption>) multi;
                        enqueueAllMediaDownloads(media, options);
                    }
                });

        // O-18: clear the error as soon as the user edits the URL.
        binding.etUrl.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (binding != null && binding.urlInputLayout.getError() != null) {
                    binding.urlInputLayout.setError(null);
                    binding.urlInputLayout.setErrorEnabled(false);
                    binding.urlInputLayout.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT);
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        setupPlatformCards();
        refreshEngineBadgeUi();
        refreshTipsSectionUi();

        if (pendingUrlToAnalyze != null) {
            String url = pendingUrlToAnalyze;
            boolean auto = pendingAutoDownload;
            pendingUrlToAnalyze = null;
            pendingAutoDownload = false;
            binding.etUrl.setText(url);
            startUrlAnalysis(auto);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshEngineBadgeUi();
        refreshShortcutsList();
        refreshTipsSectionUi();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            refreshEngineBadgeUi();
            refreshShortcutsList();
            refreshTipsSectionUi();
        }
    }

    /**
     * First Back press while the URL field has focus only clears the focus (and hides the keyboard)
     * instead of leaving the app and silently losing the typed link.
     */
    public boolean consumeBackPress() {
        if (binding == null || !isAdded() || isHidden()) return false;
        if (binding.etUrl.hasFocus()) {
            binding.etUrl.clearFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(binding.etUrl.getWindowToken(), 0);
            return true;
        }
        return false;
    }

    private void refreshTipsSectionUi() {
        if (binding == null || !isAdded()) return;
        boolean showTips = com.vidownphone.app.util.PreferenceHelper.isShowHomeTips(requireContext());
        if (!showTips) {
            binding.cardHowItWorks.setVisibility(View.GONE);
            return;
        }
        binding.cardHowItWorks.setVisibility(View.VISIBLE);
        boolean collapsed = com.vidownphone.app.util.PreferenceHelper.isHomeTipsCollapsed(requireContext());
        binding.layoutTipsSteps.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        binding.tvTipsToggle.setText(collapsed ? R.string.tips_expand_btn : R.string.tips_collapse_btn);
    }

    private void refreshEngineBadgeUi() {
        if (binding == null || !isAdded()) return;
        if (EngineManager.isAllCoreServicesReady()) {
            binding.tvEnginePill.setText(R.string.hero_engine_badge_active);
        } else {
            binding.tvEnginePill.setText(R.string.hero_engine_badge_repair);
        }
    }

    private void refreshServicesFromHero() {
        if (binding == null || !isAdded()) return;
        binding.tvEnginePill.setText(R.string.updating_engine);
        EngineManager.reinitializeAndUpdateServices(requireContext(), false, new EngineManager.EngineCallback() {
            @Override
            public void onProgress(String statusText) {
                if (binding == null || !isAdded()) return;
                binding.tvEnginePill.setText(statusText);
            }

            @Override
            public void onComplete(boolean allReady, boolean updated, String version, String message) {
                if (binding == null || !isAdded()) return;
                refreshEngineBadgeUi();
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private ShortcutAdapter shortcutAdapter;

    private void setupPlatformCards() {
        binding.rvShortcuts.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2));
        shortcutAdapter = new ShortcutAdapter(new ShortcutAdapter.ShortcutClickListener() {
            @Override
            public void onShortcutClicked(com.vidownphone.app.model.BrowserShortcut shortcut) {
                openSite(shortcut.getUrl());
            }

            @Override
            public void onShortcutDeleteClicked(com.vidownphone.app.model.BrowserShortcut shortcut) {
                showDeleteShortcutDialog(shortcut);
            }
        });
        binding.rvShortcuts.setAdapter(shortcutAdapter);
        refreshShortcutsList();

        binding.btnAddShortcut.setOnClickListener(v -> showAddShortcutDialog());
    }

    private void refreshShortcutsList() {
        if (shortcutAdapter != null && isAdded()) {
            shortcutAdapter.setShortcuts(com.vidownphone.app.util.PreferenceHelper.getShortcuts(requireContext()));
        }
    }

    private void showAddShortcutDialog() {
        android.widget.LinearLayout container = new android.widget.LinearLayout(requireContext());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        final com.google.android.material.textfield.TextInputLayout tilTitle = new com.google.android.material.textfield.TextInputLayout(requireContext());
        tilTitle.setHint(getString(R.string.shortcut_name_hint));
        tilTitle.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        final com.google.android.material.textfield.TextInputEditText etTitle = new com.google.android.material.textfield.TextInputEditText(requireContext());
        etTitle.setSingleLine(true);
        tilTitle.addView(etTitle);
        container.addView(tilTitle);

        final com.google.android.material.textfield.TextInputLayout tilUrl = new com.google.android.material.textfield.TextInputLayout(requireContext());
        tilUrl.setHint(getString(R.string.shortcut_url_hint));
        tilUrl.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = pad / 2;
        tilUrl.setLayoutParams(lp);
        final com.google.android.material.textfield.TextInputEditText etUrl = new com.google.android.material.textfield.TextInputEditText(requireContext());
        etUrl.setSingleLine(true);
        etUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        tilUrl.addView(etUrl);
        container.addView(tilUrl);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.add_shortcut_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String title = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
                    String url = etUrl.getText() != null ? etUrl.getText().toString().trim() : "";
                    if (!url.isEmpty()) {
                        com.vidownphone.app.util.PreferenceHelper.addCustomShortcut(requireContext(), title, url);
                        refreshShortcutsList();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDeleteShortcutDialog(com.vidownphone.app.model.BrowserShortcut shortcut) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_shortcut_title)
                .setMessage(getString(R.string.delete_shortcut_msg, shortcut.getTitle()))
                .setPositiveButton(R.string.btn_delete, (dialog, which) -> {
                    com.vidownphone.app.util.PreferenceHelper.deleteShortcut(requireContext(), shortcut.getId());
                    refreshShortcutsList();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openSite(String url) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openUrlInBrowser(url);
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(requireContext(), R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
            String raw = clip.getItemAt(0).getText().toString();
            String url = FormatUtils.extractFirstUrl(raw);
            if (url != null && url.contains("instagram.com/") && url.contains("exln=")) {
                url = url.replace("exln=", "rpxt=");
            }
            binding.etUrl.setText(url != null ? url : raw.trim());
            Toast.makeText(requireContext(), R.string.clipboard_detected, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(), R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
        }
    }

    public void populateAndAnalyzeUrl(String url) {
        populateAndAnalyzeUrl(url, false);
    }

    public void populateAndAnalyzeUrl(String url, boolean autoDownload) {
        if (binding == null) {
            pendingUrlToAnalyze = url;
            pendingAutoDownload = autoDownload;
            return;
        }
        binding.etUrl.setText(url);
        startUrlAnalysis(autoDownload);
    }

    private void startUrlAnalysis(boolean quickBestDownload) {
        String rawInput = binding.etUrl.getText() != null ? binding.etUrl.getText().toString().trim() : "";
        if (rawInput.isEmpty()) {
            binding.urlInputLayout.setError(getString(R.string.error_empty_url));
            return;
        }
        String url = FormatUtils.extractFirstUrl(rawInput);
        if (url == null) {
            binding.urlInputLayout.setError(getString(R.string.error_invalid_url));
            return;
        }
        Fragment existingSheet = getChildFragmentManager().findFragmentByTag("format_sheet");
        if (existingSheet instanceof FormatSelectionBottomSheet) {
            ((FormatSelectionBottomSheet) existingSheet).dismissAllowingStateLoss();
        }
        binding.urlInputLayout.setError(null);
        setLoadingState(true);

        extractor.analyzeUrl(url, new HybridExtractor.ExtractionCallback() {
            @Override
            public void onSuccess(AnalyzedMedia media) {
                if (!isAdded() || binding == null) return;
                setLoadingState(false);
                refreshEngineBadgeUi();

                if (quickBestDownload && !media.getFormatOptions().isEmpty()) {
                    MediaFormatOption best = media.getFormatOptions().get(0);
                    enqueueMediaDownload(media, best);
                } else {
                    Fragment prev = getChildFragmentManager().findFragmentByTag("format_sheet");
                    if (prev instanceof FormatSelectionBottomSheet) {
                        ((FormatSelectionBottomSheet) prev).dismissAllowingStateLoss();
                    }
                    FormatSelectionBottomSheet sheet = FormatSelectionBottomSheet.newInstance(media);
                    sheet.show(getChildFragmentManager(), "format_sheet");
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded() || binding == null) return;
                setLoadingState(false);
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void enqueueMediaDownload(AnalyzedMedia media, MediaFormatOption option) {
        DownloadTask task = new DownloadTask();
        task.setUrl(media.getSourceUrl());
        String taskTitle = media.getTitle();
        if (media.isMultiMedia() && option.getLabel() != null) {
            taskTitle = taskTitle + " - " + option.getLabel();
        }
        task.setTitle(taskTitle);
        task.setThumbnailUrl(option.getThumbnailUrl() != null ? option.getThumbnailUrl() : media.getThumbnailUrl());
        task.setFormatId(option.getFormatId());
        task.setFormatLabel(option.getLabel());
        task.setExtension(option.getExtension());
        task.setAudioOnly(option.isAudioOnly());
        task.setDirectHttp(option.isDirectHttp());
        task.setStatus(DownloadTask.STATUS_QUEUED);
        task.setProgress(0);
        task.setCreatedAt(System.currentTimeMillis());

        DownloadService.enqueueDownload(requireContext(), task);
        Toast.makeText(requireContext(),
                getString(R.string.download_started_toast, taskTitle),
                Toast.LENGTH_SHORT).show();

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateToDownloads();
        }
    }

    private void enqueueAllMediaDownloads(AnalyzedMedia media, List<MediaFormatOption> options) {
        if (options == null || options.isEmpty()) return;
        int count = 0;
        for (MediaFormatOption option : options) {
            DownloadTask task = new DownloadTask();
            task.setUrl(media.getSourceUrl());

            String taskTitle;
            if (option.getDetails() != null && !option.getDetails().isEmpty() && !option.getDetails().startsWith("http")) {
                taskTitle = option.getDetails();
            } else if (option.getLabel() != null && !option.getLabel().isEmpty()) {
                taskTitle = media.getTitle() + " - " + option.getLabel();
            } else {
                taskTitle = media.getTitle() + " (" + (count + 1) + ")";
            }

            task.setTitle(taskTitle);
            task.setThumbnailUrl(option.getThumbnailUrl() != null ? option.getThumbnailUrl() : media.getThumbnailUrl());
            task.setFormatId(option.getFormatId());
            task.setFormatLabel(option.getLabel());
            task.setExtension(option.getExtension());
            task.setAudioOnly(option.isAudioOnly());
            task.setDirectHttp(option.isDirectHttp());
            task.setStatus(DownloadTask.STATUS_QUEUED);
            task.setProgress(0);
            task.setCreatedAt(System.currentTimeMillis() + count);

            DownloadService.enqueueDownload(requireContext(), task);
            count++;
        }

        Toast.makeText(requireContext(),
                getString(R.string.multi_download_enqueued, count),
                Toast.LENGTH_SHORT).show();

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateToDownloads();
        }
    }

    private void setLoadingState(boolean loading) {
        binding.layoutAnalyzing.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnAnalyze.setEnabled(!loading);
        binding.btnQuickDownload.setEnabled(!loading);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
