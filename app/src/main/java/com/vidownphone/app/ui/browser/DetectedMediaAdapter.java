package com.vidownphone.app.ui.browser;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.vidownphone.app.R;
import com.vidownphone.app.databinding.ItemDetectedMediaBinding;
import com.vidownphone.app.model.DetectedStream;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DetectedMediaAdapter extends RecyclerView.Adapter<DetectedMediaAdapter.StreamViewHolder> {

    public interface OnStreamDownloadClickListener {
        boolean onStreamDownloadClick(DetectedStream stream);
    }

    private final List<DetectedStream> streams;
    private final OnStreamDownloadClickListener listener;
    private final Set<String> downloadedUrls = new HashSet<>();

    public DetectedMediaAdapter(List<DetectedStream> streams, OnStreamDownloadClickListener listener) {
        this.streams = streams;
        this.listener = listener;
    }

    @NonNull
    @Override
    public StreamViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDetectedMediaBinding binding = ItemDetectedMediaBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new StreamViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull StreamViewHolder holder, int position) {
        DetectedStream stream = streams.get(position);
        holder.binding.tvStreamTitle.setText(stream.getTitle());

        String detail = stream.getMimeType();
        if (detail == null || detail.isEmpty()) {
            detail = stream.getStreamUrl();
        }
        holder.binding.tvStreamUrl.setText(detail);

        int iconRes = stream.isImage() ? R.drawable.ic_image
                : (stream.isAudioOnly() ? R.drawable.ic_audio : R.drawable.ic_video);

        String thumbUrl = stream.getThumbnailUrl();
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            holder.binding.ivStreamIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.binding.ivStreamIcon.setImageTintList(null);
            if (thumbUrl.startsWith("data:image")) {
                try {
                    int comma = thumbUrl.indexOf(',');
                    byte[] data = android.util.Base64.decode(
                            comma != -1 ? thumbUrl.substring(comma + 1) : thumbUrl,
                            android.util.Base64.DEFAULT
                    );
                    Glide.with(holder.binding.ivStreamIcon.getContext())
                            .asBitmap()
                            .load(data)
                            .placeholder(iconRes)
                            .error(iconRes)
                            .into(holder.binding.ivStreamIcon);
                } catch (Exception e) {
                    holder.binding.ivStreamIcon.setImageResource(iconRes);
                }
            } else {
                Glide.with(holder.binding.ivStreamIcon.getContext())
                        .load(thumbUrl)
                        .placeholder(iconRes)
                        .error(iconRes)
                        .into(holder.binding.ivStreamIcon);
            }
        } else if (!stream.isImage() && !stream.isAudioOnly() && stream.getStreamUrl() != null && stream.getStreamUrl().startsWith("http")) {
            Glide.with(holder.binding.ivStreamIcon.getContext()).clear(holder.binding.ivStreamIcon);
            com.vidownphone.app.util.VideoThumbnailHelper.loadVideoThumbnail(
                    holder.binding.ivStreamIcon,
                    stream.getStreamUrl(),
                    iconRes
            );
        } else {
            Glide.with(holder.binding.ivStreamIcon.getContext()).clear(holder.binding.ivStreamIcon);
            holder.binding.ivStreamIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            holder.binding.ivStreamIcon.setImageResource(iconRes);
        }

        boolean alreadyClicked = downloadedUrls.contains(stream.getStreamUrl());
        holder.binding.btnDownloadStream.setText(alreadyClicked
                ? R.string.detected_media_downloaded : R.string.detected_media_download);
        holder.binding.btnDownloadStream.setEnabled(!alreadyClicked);

        holder.binding.btnDownloadStream.setOnClickListener(v -> {
            // Only mark as downloaded if the download was really handed over for enqueueing
            if (listener.onStreamDownloadClick(stream)) {
                downloadedUrls.add(stream.getStreamUrl());
                holder.binding.btnDownloadStream.setText(R.string.detected_media_downloaded);
                holder.binding.btnDownloadStream.setEnabled(false);
            }
        });
    }

    @Override
    public int getItemCount() {
        return streams != null ? streams.size() : 0;
    }

    static class StreamViewHolder extends RecyclerView.ViewHolder {
        final ItemDetectedMediaBinding binding;

        StreamViewHolder(ItemDetectedMediaBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
