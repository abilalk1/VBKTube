package com.vidownphone.app.ui.share;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.vidownphone.app.R;
import com.vidownphone.app.data.DownloadTask;
import com.vidownphone.app.databinding.ActivityShareDownloadBinding;
import com.vidownphone.app.engine.HybridExtractor;
import com.vidownphone.app.model.AnalyzedMedia;
import com.vidownphone.app.model.MediaFormatOption;
import com.vidownphone.app.service.DownloadService;
import com.vidownphone.app.ui.MainActivity;
import com.vidownphone.app.ui.home.FormatOptionAdapter;
import com.vidownphone.app.util.FormatUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Floating translucent Material 3 Bottom-Sheet Activity that pops up directly over
 * Instagram, YouTube, TikTok, X, or Browser when the user taps "Share -> VBKTube".
 */
public class ShareDownloadActivity extends AppCompatActivity {

    private ActivityShareDownloadBinding binding;
    private HybridExtractor extractor;
    private FormatOptionAdapter adapter;
    private AnalyzedMedia currentMedia;
    private String sharedUrl;
    private boolean isMultiSlide = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityShareDownloadBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        extractor = new HybridExtractor(this);

        // Tap outside the bottom sheet to dismiss
        binding.rootShareScrim.setOnClickListener(v -> finish());
        binding.sheetContainer.setOnClickListener(v -> {
            // Consume clicks inside the sheet
        });
        binding.btnCloseShare.setOnClickListener(v -> finish());

        CharSequence rawCs = getIntent() != null ? getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT) : null;
        String rawText = rawCs != null ? rawCs.toString() : null;
        sharedUrl = FormatUtils.extractFirstUrl(rawText);

        if (sharedUrl == null || sharedUrl.isEmpty()) {
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        binding.tvSharedUrl.setText(sharedUrl);

        binding.btnOpenInApp.setOnClickListener(v -> {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.setAction(Intent.ACTION_SEND);
            mainIntent.setType("text/plain");
            mainIntent.putExtra(Intent.EXTRA_TEXT, sharedUrl);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(mainIntent);
            finish();
        });

        binding.btnShareOpenBrowser.setOnClickListener(v -> {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.putExtra("open_browser_url", sharedUrl);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(mainIntent);
            finish();
        });

        startAnalysis();
    }

    private void startAnalysis() {
        binding.layoutShareLoading.setVisibility(View.VISIBLE);
        binding.layoutShareReady.setVisibility(View.GONE);
        binding.layoutShareError.setVisibility(View.GONE);

        extractor.analyzeUrl(sharedUrl, new HybridExtractor.ExtractionCallback() {
            @Override
            public void onSuccess(AnalyzedMedia media) {
                if (isFinishing() || isDestroyed()) return;
                currentMedia = media;
                bindMediaOptions(media);
            }

            @Override
            public void onError(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                binding.layoutShareLoading.setVisibility(View.GONE);
                binding.layoutShareReady.setVisibility(View.GONE);
                binding.layoutShareError.setVisibility(View.VISIBLE);
            }
        });
    }

    private void bindMediaOptions(AnalyzedMedia media) {
        binding.layoutShareLoading.setVisibility(View.GONE);
        binding.layoutShareError.setVisibility(View.GONE);
        binding.layoutShareReady.setVisibility(View.VISIBLE);

        binding.tvShareTitle.setText(media.getTitle());

        StringBuilder meta = new StringBuilder();
        if (media.getUploader() != null && !media.getUploader().isEmpty()) {
            meta.append(media.getUploader());
        }
        if (media.getDurationSeconds() > 0) {
            if (meta.length() > 0) meta.append(" • ");
            meta.append(FormatUtils.formatDuration(media.getDurationSeconds()));
        }
        binding.tvShareMeta.setText(meta.length() > 0 ? meta.toString() : media.getSourceUrl());

        boolean hasAudio = false;
        boolean hasVideo = false;
        boolean hasPhoto = false;
        int slideCount = 0;

        for (MediaFormatOption opt : media.getFormatOptions()) {
            if (opt.getLabel() != null && (opt.getLabel().contains("Kaydırma #")
                    || opt.getLabel().contains("Slayt #")
                    || opt.getLabel().contains("Hikaye #")
                    || opt.getLabel().contains("Galeri #"))) {
                slideCount++;
            }
            if (opt.isAudioOnly()) {
                hasAudio = true;
            } else {
                String ext = opt.getExtension() != null ? opt.getExtension().toLowerCase(Locale.US) : "";
                if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp")) {
                    hasPhoto = true;
                } else {
                    hasVideo = true;
                }
            }
        }
        isMultiSlide = media.isMultiMedia() || slideCount > 1;
        if (slideCount <= 1 && isMultiSlide) {
            slideCount = media.getFormatOptions().size();
        }

        int placeholder = hasPhoto && !hasVideo ? R.drawable.ic_image : (hasAudio && !hasVideo ? R.drawable.ic_audio : R.drawable.ic_video);

        if (media.getThumbnailUrl() != null && !media.getThumbnailUrl().isEmpty()) {
            Glide.with(this)
                    .load(media.getThumbnailUrl())
                    .placeholder(placeholder)
                    .into(binding.ivShareThumb);
        } else {
            binding.ivShareThumb.setImageResource(placeholder);
        }

        if (isMultiSlide) {
            boolean isStory = (media.getTitle() != null && media.getTitle().contains("Hikaye"))
                    || (media.getSourceUrl() != null && media.getSourceUrl().contains("/stories/"));
            binding.chipShareVideo.setText(getString(isStory ? R.string.format_chip_all_stories : R.string.format_chip_all_slides, slideCount));
        } else if (hasPhoto && !hasVideo) {
            binding.chipShareVideo.setText(R.string.tab_photo_formats);
        } else if (hasPhoto && hasVideo) {
            binding.chipShareVideo.setText(R.string.format_chip_video_and_image);
        }

        if (!hasAudio) {
            binding.chipShareAudio.setVisibility(View.GONE);
        }

        adapter = new FormatOptionAdapter(this);
        adapter.setOnOptionClickListener(selected -> {
            if (selected.getThumbnailUrl() != null && !selected.getThumbnailUrl().isEmpty()) {
                Glide.with(this)
                        .load(selected.getThumbnailUrl())
                        .placeholder(placeholder)
                        .into(binding.ivShareThumb);
            }
        });
        adapter.setOnSelectionChangedListener((selectedCount, totalCount) -> updateShareSelectionUi());
        binding.rvShareFormats.setLayoutManager(new LinearLayoutManager(this));
        binding.rvShareFormats.setAdapter(adapter);

        binding.chipGroupShareType.setOnCheckedStateChangeListener((group, checkedIds) -> {
            boolean showAudio = checkedIds.contains(R.id.chip_share_audio);
            filterOptions(showAudio);
        });

        if (isMultiSlide) {
            adapter.setMultiSelectMode(true);
            binding.layoutShareMultiSelectHeader.setVisibility(View.VISIBLE);
            binding.btnShareToggleSelectAll.setOnClickListener(v -> adapter.selectAll(!adapter.isAllSelected()));
        } else {
            adapter.setMultiSelectMode(false);
            binding.layoutShareMultiSelectHeader.setVisibility(View.GONE);
            binding.btnShareStartDownload.setText(R.string.btn_start_download);
            binding.btnShareStartDownload.setEnabled(true);
        }

        filterOptions(false);
        updateShareSelectionUi();

        binding.btnShareStartDownload.setOnClickListener(v -> {
            if (isMultiSlide) {
                List<MediaFormatOption> selectedList = adapter.getSelectedOptions();
                if (!selectedList.isEmpty()) {
                    enqueueAllDownloads(media, selectedList);
                    finish();
                }
            } else {
                MediaFormatOption selected = adapter.getSelectedOption();
                if (selected != null) {
                    enqueueOptionDownload(media, selected);
                    finish();
                }
            }
        });
    }

    private void updateShareSelectionUi() {
        if (!isMultiSlide || binding == null || adapter == null) return;
        int selectedCount = adapter.getSelectedCount();
        int totalCount = adapter.getItemCount();

        binding.tvShareSelectionCount.setText(getString(R.string.selected_media_count, selectedCount, totalCount));

        boolean allSelected = adapter.isAllSelected();
        binding.btnShareToggleSelectAll.setText(allSelected ? R.string.deselect_all : R.string.select_all);

        if (selectedCount == 0) {
            binding.btnShareStartDownload.setText(R.string.btn_select_at_least_one);
            binding.btnShareStartDownload.setEnabled(false);
        } else if (allSelected) {
            binding.btnShareStartDownload.setText(getString(R.string.btn_download_all_media, selectedCount));
            binding.btnShareStartDownload.setEnabled(true);
        } else {
            binding.btnShareStartDownload.setText(getString(R.string.btn_download_selected_count, selectedCount));
            binding.btnShareStartDownload.setEnabled(true);
        }
    }

    private void enqueueAllDownloads(AnalyzedMedia media, List<MediaFormatOption> options) {
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

            DownloadService.enqueueDownload(this, task);
            count++;
        }
        Toast.makeText(this,
                getString(R.string.multi_download_enqueued, count),
                Toast.LENGTH_SHORT).show();
    }

    private void filterOptions(boolean audioOnly) {
        if (currentMedia == null) return;
        List<MediaFormatOption> filtered = new ArrayList<>();
        for (MediaFormatOption opt : currentMedia.getFormatOptions()) {
            if (opt.isAudioOnly() == audioOnly) {
                filtered.add(opt);
            }
        }
        if (filtered.isEmpty() && !currentMedia.getFormatOptions().isEmpty()) {
            filtered.addAll(currentMedia.getFormatOptions());
        }
        adapter.submitList(filtered);
    }

    private void enqueueOptionDownload(AnalyzedMedia media, MediaFormatOption option) {
        DownloadTask task = new DownloadTask();
        task.setUrl(media.getSourceUrl());
        String baseTitle = media.getTitle();
        if (isMultiSlide && option.getLabel() != null) {
            baseTitle = baseTitle + " - " + option.getLabel();
        }
        task.setTitle(baseTitle);
        task.setThumbnailUrl(option.getThumbnailUrl() != null ? option.getThumbnailUrl() : media.getThumbnailUrl());
        task.setFormatId(option.getFormatId());
        task.setFormatLabel(option.getLabel());
        task.setExtension(option.getExtension());
        task.setAudioOnly(option.isAudioOnly());
        task.setDirectHttp(option.isDirectHttp());
        task.setStatus(DownloadTask.STATUS_QUEUED);
        task.setCreatedAt(System.currentTimeMillis());

        DownloadService.enqueueDownload(this, task);
        Toast.makeText(this,
                getString(R.string.download_started_toast, task.getTitle()),
                Toast.LENGTH_SHORT).show();
    }
}
