package com.vidownphone.app.ui.home;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.color.MaterialColors;
import com.vidownphone.app.R;
import com.vidownphone.app.databinding.ItemFormatOptionBinding;
import com.vidownphone.app.model.MediaFormatOption;
import com.vidownphone.app.util.FormatUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FormatOptionAdapter extends RecyclerView.Adapter<FormatOptionAdapter.FormatViewHolder> {

    public interface OnOptionClickListener {
        void onOptionSelected(MediaFormatOption option);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount, int totalCount);
    }

    private final Context context;
    private final List<MediaFormatOption> items = new ArrayList<>();
    private final Set<String> downloadedFormatIds = new HashSet<>();
    private final Set<Integer> selectedPositions = new HashSet<>();
    private int selectedPosition = 0;
    private boolean multiSelectMode = false;
    private OnOptionClickListener optionClickListener;
    private OnSelectionChangedListener selectionChangedListener;

    public FormatOptionAdapter(Context context) {
        this.context = context;
    }

    public void setOnOptionClickListener(OnOptionClickListener listener) {
        this.optionClickListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    public void setMultiSelectMode(boolean multiSelectMode) {
        this.multiSelectMode = multiSelectMode;
        selectedPositions.clear();
        if (multiSelectMode) {
            for (int i = 0; i < items.size(); i++) {
                String lbl = items.get(i).getLabel();
                if (lbl != null && (lbl.contains("🎯") || lbl.contains("Seçilen"))) {
                    selectedPositions.add(i);
                }
            }
        } else {
            selectedPosition = 0;
        }
        notifyDataSetChanged();
        if (multiSelectMode && selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selectedPositions.size(), items.size());
        }
    }

    public boolean isMultiSelectMode() {
        return multiSelectMode;
    }

    public void selectAll(boolean selectAll) {
        selectedPositions.clear();
        if (selectAll) {
            for (int i = 0; i < items.size(); i++) {
                selectedPositions.add(i);
            }
        }
        notifyDataSetChanged();
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selectedPositions.size(), items.size());
        }
    }

    public boolean isAllSelected() {
        return !items.isEmpty() && selectedPositions.size() == items.size();
    }

    public int getSelectedCount() {
        return multiSelectMode ? selectedPositions.size() : (selectedPosition >= 0 && selectedPosition < items.size() ? 1 : 0);
    }

    public List<MediaFormatOption> getSelectedOptions() {
        List<MediaFormatOption> result = new ArrayList<>();
        if (multiSelectMode) {
            for (int i = 0; i < items.size(); i++) {
                if (selectedPositions.contains(i)) {
                    result.add(items.get(i));
                }
            }
        } else {
            MediaFormatOption single = getSelectedOption();
            if (single != null) {
                result.add(single);
            }
        }
        return result;
    }

    public void markDownloaded(String formatId) {
        if (formatId != null) {
            downloadedFormatIds.add(formatId);
            notifyDataSetChanged();
        }
    }

    public void submitList(List<MediaFormatOption> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        selectedPosition = 0;
        selectedPositions.clear();
        if (multiSelectMode) {
            for (int i = 0; i < items.size(); i++) {
                String lbl = items.get(i).getLabel();
                if (lbl != null && (lbl.contains("🎯") || lbl.contains("Seçilen"))) {
                    selectedPositions.add(i);
                }
            }
        }
        notifyDataSetChanged();
        if (multiSelectMode && selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selectedPositions.size(), items.size());
        }
    }

    public MediaFormatOption getSelectedOption() {
        if (selectedPosition >= 0 && selectedPosition < items.size()) {
            return items.get(selectedPosition);
        }
        return items.isEmpty() ? null : items.get(0);
    }

    @NonNull
    @Override
    public FormatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFormatOptionBinding binding = ItemFormatOptionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new FormatViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull FormatViewHolder holder, int position) {
        MediaFormatOption option = items.get(position);
        boolean isSelected = multiSelectMode ? selectedPositions.contains(position) : position == selectedPosition;

        if (multiSelectMode) {
            holder.binding.rbSelected.setVisibility(View.GONE);
            holder.binding.cbSelected.setVisibility(View.VISIBLE);
            holder.binding.cbSelected.setChecked(isSelected);
        } else {
            holder.binding.rbSelected.setVisibility(View.VISIBLE);
            holder.binding.cbSelected.setVisibility(View.GONE);
            holder.binding.rbSelected.setChecked(isSelected);
        }

        holder.binding.tvFormatLabel.setText(option.getLabel());
        holder.binding.tvFormatDetails.setText(option.getDetails());

        String thumb = option.getThumbnailUrl();
        String mediaUrl = option.getFormatId() != null && option.getFormatId().startsWith("direct_url:")
                ? option.getFormatId().substring("direct_url:".length())
                : null;
        boolean isVideo = "mp4".equalsIgnoreCase(option.getExtension())
                || (option.getLabel() != null && option.getLabel().contains("Video"));

        if (thumb != null && !thumb.isEmpty()) {
            holder.binding.cardSlideThumb.setVisibility(View.VISIBLE);
            Glide.with(holder.binding.ivSlideThumb.getContext())
                    .load(thumb)
                    .placeholder(isVideo ? R.drawable.ic_video : R.drawable.ic_image)
                    .into(holder.binding.ivSlideThumb);
        } else if (isVideo && mediaUrl != null && mediaUrl.startsWith("http")) {
            holder.binding.cardSlideThumb.setVisibility(View.VISIBLE);
            Glide.with(holder.binding.ivSlideThumb.getContext()).clear(holder.binding.ivSlideThumb);
            com.vidownphone.app.util.VideoThumbnailHelper.loadVideoThumbnail(
                    holder.binding.ivSlideThumb,
                    mediaUrl,
                    R.drawable.ic_video
            );
        } else {
            holder.binding.cardSlideThumb.setVisibility(View.GONE);
        }

        int strokeColor = MaterialColors.getColor(holder.binding.cardFormat,
                isSelected ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorOutline);
        int bgColor = MaterialColors.getColor(holder.binding.cardFormat,
                isSelected ? com.google.android.material.R.attr.colorSecondaryContainer
                        : com.google.android.material.R.attr.colorSurface);
        float density = holder.binding.cardFormat.getResources().getDisplayMetrics().density;
        holder.binding.cardFormat.setStrokeColor(strokeColor);
        holder.binding.cardFormat.setStrokeWidth(Math.round((isSelected ? 2f : 1f) * density));
        holder.binding.cardFormat.setCardBackgroundColor(bgColor);

        if (downloadedFormatIds.contains(option.getFormatId())) {
            holder.binding.tvFormatSize.setText(context.getString(R.string.format_downloaded));
        } else {
            String sizeStr = FormatUtils.formatFileSize(option.getFileSize());
            holder.binding.tvFormatSize.setText(sizeStr.isEmpty()
                    ? context.getString(R.string.unknown_size)
                    : sizeStr);
        }

        holder.binding.cardFormat.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos < 0 || pos >= items.size()) return;

            if (multiSelectMode) {
                if (selectedPositions.contains(pos)) {
                    selectedPositions.remove(pos);
                } else {
                    selectedPositions.add(pos);
                }
                notifyItemChanged(pos);
                if (selectionChangedListener != null) {
                    selectionChangedListener.onSelectionChanged(selectedPositions.size(), items.size());
                }
            } else {
                int previous = selectedPosition;
                selectedPosition = pos;
                notifyItemChanged(previous);
                notifyItemChanged(selectedPosition);
            }

            if (optionClickListener != null) {
                optionClickListener.onOptionSelected(items.get(pos));
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class FormatViewHolder extends RecyclerView.ViewHolder {
        final ItemFormatOptionBinding binding;

        FormatViewHolder(ItemFormatOptionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
