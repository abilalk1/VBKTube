package com.vidownphone.app.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.vidownphone.app.R;
import com.vidownphone.app.databinding.BottomSheetFormatsBinding;
import com.vidownphone.app.model.AnalyzedMedia;
import com.vidownphone.app.model.MediaFormatOption;
import com.vidownphone.app.util.FormatUtils;

import java.util.ArrayList;
import java.util.List;

public class FormatSelectionBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_MEDIA = "arg_media";

    /** FragmentResult request key (delivered to the parent FragmentManager). Survives recreation. */
    public static final String REQUEST_KEY = "format_sheet_result";
    /** Result bundle: the analyzed media this sheet was created for (AnalyzedMedia). */
    public static final String RESULT_MEDIA = "result_media";
    /** Result bundle: the single chosen option (MediaFormatOption), present for single downloads. */
    public static final String RESULT_OPTION = "result_option";
    /** Result bundle: all chosen options (ArrayList of MediaFormatOption), present for multi downloads. */
    public static final String RESULT_OPTIONS = "result_options";

    private BottomSheetFormatsBinding binding;
    private AnalyzedMedia analyzedMedia;
    private FormatOptionAdapter adapter;
    private boolean isMultiSlidePost = false;

    public static FormatSelectionBottomSheet newInstance(AnalyzedMedia media) {
        FormatSelectionBottomSheet sheet = new FormatSelectionBottomSheet();
        Bundle args = new Bundle();
        args.putSerializable(ARG_MEDIA, media);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = BottomSheetFormatsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getArguments() != null) {
            analyzedMedia = (AnalyzedMedia) getArguments().getSerializable(ARG_MEDIA);
        }
        if (analyzedMedia == null) {
            dismissAllowingStateLoss();
            return;
        }

        binding.tvVideoTitle.setText(analyzedMedia.getTitle());

        StringBuilder meta = new StringBuilder();
        if (analyzedMedia.getUploader() != null && !analyzedMedia.getUploader().isEmpty()) {
            meta.append(analyzedMedia.getUploader());
        }
        if (analyzedMedia.getDurationSeconds() > 0) {
            if (meta.length() > 0) meta.append(" • ");
            meta.append(FormatUtils.formatDuration(analyzedMedia.getDurationSeconds()));
        }
        binding.tvVideoMeta.setText(meta.length() > 0 ? meta.toString() : analyzedMedia.getSourceUrl());

        boolean hasAudio = false;
        boolean hasVideo = false;
        boolean hasPhoto = false;
        int slideCount = 0;
        for (MediaFormatOption opt : analyzedMedia.getFormatOptions()) {
            if (opt.getLabel() != null && (opt.getLabel().contains("Slayt #")
                    || opt.getLabel().contains("Kaydırma #")
                    || opt.getLabel().contains("Hikaye #")
                    || opt.getLabel().contains("Galeri #"))) {
                slideCount++;
            }
            if (opt.isAudioOnly()) {
                hasAudio = true;
            } else {
                String ext = opt.getExtension() != null ? opt.getExtension().toLowerCase(java.util.Locale.US) : "";
                if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp")) {
                    hasPhoto = true;
                } else {
                    hasVideo = true;
                }
            }
        }
        isMultiSlidePost = analyzedMedia.isMultiMedia() || slideCount > 1;
        if (slideCount <= 1 && isMultiSlidePost) {
            slideCount = analyzedMedia.getFormatOptions().size();
        }

        int placeholder = hasPhoto && !hasVideo ? R.drawable.ic_image : (hasAudio && !hasVideo ? R.drawable.ic_audio : R.drawable.ic_video);

        if (analyzedMedia.getThumbnailUrl() != null && !analyzedMedia.getThumbnailUrl().isEmpty()) {
            Glide.with(this)
                    .load(analyzedMedia.getThumbnailUrl())
                    .placeholder(placeholder)
                    .into(binding.ivThumbnail);
        } else {
            binding.ivThumbnail.setImageResource(placeholder);
        }

        if (isMultiSlidePost) {
            boolean isStory = (analyzedMedia.getTitle() != null && analyzedMedia.getTitle().contains("Hikaye"))
                    || (analyzedMedia.getSourceUrl() != null && analyzedMedia.getSourceUrl().contains("/stories/"));
            binding.chipTypeVideo.setText(getString(isStory ? R.string.format_chip_all_stories : R.string.format_chip_all_slides, slideCount));
        } else if (hasPhoto && !hasVideo) {
            binding.chipTypeVideo.setText(R.string.tab_photo_formats);
        } else if (hasPhoto && hasVideo) {
            binding.chipTypeVideo.setText(R.string.format_chip_video_and_image);
        }

        if (!hasAudio) {
            binding.chipTypeAudio.setVisibility(View.GONE);
        }

        adapter = new FormatOptionAdapter(requireContext());
        adapter.setOnOptionClickListener(selected -> {
            if (selected.getThumbnailUrl() != null && !selected.getThumbnailUrl().isEmpty() && binding != null) {
                Glide.with(this)
                        .load(selected.getThumbnailUrl())
                        .placeholder(placeholder)
                        .into(binding.ivThumbnail);
            }
        });
        adapter.setOnSelectionChangedListener((selectedCount, totalCount) -> updateSelectionUi());
        binding.rvFormats.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvFormats.setAdapter(adapter);

        binding.chipGroupType.setOnCheckedStateChangeListener((group, checkedIds) -> {
            boolean showAudio = checkedIds.contains(R.id.chip_type_audio);
            filterOptions(showAudio);
        });

        if (isMultiSlidePost) {
            adapter.setMultiSelectMode(true);
            binding.layoutMultiSelectHeader.setVisibility(View.VISIBLE);
            binding.btnToggleSelectAll.setOnClickListener(v -> adapter.selectAll(!adapter.isAllSelected()));
        } else {
            adapter.setMultiSelectMode(false);
            binding.layoutMultiSelectHeader.setVisibility(View.GONE);
            binding.btnConfirmDownload.setText(R.string.btn_start_download);
            binding.btnConfirmDownload.setEnabled(true);
        }

        filterOptions(false);
        updateSelectionUi();

        binding.btnConfirmDownload.setOnClickListener(v -> {
            Bundle result = new Bundle();
            result.putSerializable(RESULT_MEDIA, analyzedMedia);
            if (isMultiSlidePost) {
                List<MediaFormatOption> selectedList = adapter.getSelectedOptions();
                if (selectedList.isEmpty()) return;
                result.putSerializable(RESULT_OPTIONS, new ArrayList<>(selectedList));
            } else {
                MediaFormatOption selected = adapter.getSelectedOption();
                if (selected == null) return;
                result.putSerializable(RESULT_OPTION, selected);
            }
            getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
            dismissAllowingStateLoss();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        // Always open fully expanded so the pinned download button is visible (landscape / small screens).
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<FrameLayout> behavior = ((BottomSheetDialog) getDialog()).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void updateSelectionUi() {
        if (!isMultiSlidePost || binding == null || adapter == null) return;
        int selectedCount = adapter.getSelectedCount();
        int totalCount = adapter.getItemCount();

        binding.tvSelectionCount.setText(getString(R.string.selected_media_count, selectedCount, totalCount));

        boolean allSelected = adapter.isAllSelected();
        binding.btnToggleSelectAll.setText(allSelected ? R.string.deselect_all : R.string.select_all);

        if (selectedCount == 0) {
            binding.btnConfirmDownload.setText(R.string.btn_select_at_least_one);
            binding.btnConfirmDownload.setEnabled(false);
        } else if (allSelected) {
            binding.btnConfirmDownload.setText(getString(R.string.btn_download_all_media, selectedCount));
            binding.btnConfirmDownload.setEnabled(true);
        } else {
            binding.btnConfirmDownload.setText(getString(R.string.btn_download_selected_count, selectedCount));
            binding.btnConfirmDownload.setEnabled(true);
        }
    }

    private void filterOptions(boolean audioOnly) {
        List<MediaFormatOption> filtered = new ArrayList<>();
        for (MediaFormatOption opt : analyzedMedia.getFormatOptions()) {
            if (opt.isAudioOnly() == audioOnly) {
                filtered.add(opt);
            }
        }
        if (filtered.isEmpty() && !analyzedMedia.getFormatOptions().isEmpty()) {
            filtered.addAll(analyzedMedia.getFormatOptions());
        }
        adapter.submitList(filtered);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
