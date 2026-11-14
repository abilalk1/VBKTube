package com.vidownphone.app.ui.downloads;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.vidownphone.app.R;
import com.vidownphone.app.data.DownloadTask;
import com.vidownphone.app.databinding.ItemDownloadTaskBinding;
import com.vidownphone.app.util.FormatUtils;

import java.util.Locale;
import java.util.Objects;

public class DownloadTaskAdapter extends ListAdapter<DownloadTask, DownloadTaskAdapter.TaskViewHolder> {

    private static final DiffUtil.ItemCallback<DownloadTask> DIFF = new DiffUtil.ItemCallback<DownloadTask>() {
        @Override
        public boolean areItemsTheSame(@NonNull DownloadTask a, @NonNull DownloadTask b) {
            return a.getId() == b.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull DownloadTask a, @NonNull DownloadTask b) {
            return a.getStatus() == b.getStatus()
                    && a.getProgress() == b.getProgress()
                    && a.getFileSize() == b.getFileSize()
                    && a.isAudioOnly() == b.isAudioOnly()
                    && Objects.equals(a.getTitle(), b.getTitle())
                    && Objects.equals(a.getExtension(), b.getExtension())
                    && Objects.equals(a.getFilePath(), b.getFilePath())
                    && Objects.equals(a.getThumbnailUrl(), b.getThumbnailUrl())
                    && Objects.equals(a.getSpeedAndEta(), b.getSpeedAndEta())
                    && Objects.equals(a.getErrorMessage(), b.getErrorMessage());
        }
    };

    public interface TaskActionListener {
        void onPlayClicked(DownloadTask task);
        void onShareClicked(DownloadTask task);
        void onCancelOrRetryClicked(DownloadTask task);
        void onDeleteClicked(DownloadTask task);
    }

    private final Context context;
    private final TaskActionListener listener;

    public DownloadTaskAdapter(Context context, TaskActionListener listener) {
        super(DIFF);
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).getId();
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDownloadTaskBinding binding = ItemDownloadTaskBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new TaskViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        DownloadTask task = getItem(position);
        holder.binding.tvTaskTitle.setText(task.getTitle());

        String extBadge = task.getExtension() != null
                ? task.getExtension().toUpperCase(Locale.US)
                : (task.isAudioOnly() ? "MP3" : "MP4");
        holder.binding.tvFormatBadge.setText(extBadge);

        String extLower = task.getExtension() != null ? task.getExtension().toLowerCase(Locale.US) : "";
        boolean isImage = extLower.equals("jpg") || extLower.equals("jpeg") || extLower.equals("png") || extLower.equals("webp");
        int placeholder = isImage ? R.drawable.ic_image : (task.isAudioOnly() ? R.drawable.ic_audio : R.drawable.ic_video);

        boolean hasThumbUrl = task.getThumbnailUrl() != null && !task.getThumbnailUrl().isEmpty();
        // No File.exists() on the main thread: only completed tasks have a trustworthy local file;
        // Glide loads it off-thread and falls back to the remote thumbnail / placeholder on failure.
        if (task.getStatus() == DownloadTask.STATUS_COMPLETED && task.getFilePath() != null) {
            RequestBuilder<android.graphics.drawable.Drawable> fallback = hasThumbUrl
                    ? Glide.with(context).load(task.getThumbnailUrl()).placeholder(placeholder).error(placeholder)
                    : Glide.with(context).load(placeholder);
            Glide.with(context)
                    .load(new java.io.File(task.getFilePath()))
                    .placeholder(placeholder)
                    .error(fallback)
                    .into(holder.binding.ivTaskThumb);
        } else if (hasThumbUrl) {
            Glide.with(context)
                    .load(task.getThumbnailUrl())
                    .placeholder(placeholder)
                    .into(holder.binding.ivTaskThumb);
        } else {
            holder.binding.ivTaskThumb.setImageResource(placeholder);
        }

        // Remember the layout's default status colour so recycled holders can be restored.
        Object defTag = holder.binding.tvTaskStatus.getTag();
        if (!(defTag instanceof ColorStateList)) {
            defTag = holder.binding.tvTaskStatus.getTextColors();
            holder.binding.tvTaskStatus.setTag(defTag);
        }
        holder.binding.tvTaskStatus.setTextColor((ColorStateList) defTag);

        int status = task.getStatus();
        switch (status) {
            case DownloadTask.STATUS_QUEUED:
                holder.binding.tvTaskStatus.setText(context.getString(R.string.status_queued));
                holder.binding.progressBar.setVisibility(View.VISIBLE);
                holder.binding.progressBar.setIndeterminate(true);
                holder.binding.btnTaskPlay.setVisibility(View.GONE);
                holder.binding.btnTaskShare.setVisibility(View.GONE);
                holder.binding.btnTaskCancelRetry.setVisibility(View.VISIBLE);
                holder.binding.btnTaskCancelRetry.setText(R.string.action_cancel);
                break;

            case DownloadTask.STATUS_DOWNLOADING:
                String speed = task.getSpeedAndEta() != null ? task.getSpeedAndEta() : "";
                holder.binding.tvTaskStatus.setText(context.getString(
                        R.string.status_downloading, task.getProgress(), speed));
                holder.binding.progressBar.setVisibility(View.VISIBLE);
                holder.binding.progressBar.setIndeterminate(task.getProgress() <= 0);
                holder.binding.progressBar.setProgress(task.getProgress());
                holder.binding.btnTaskPlay.setVisibility(View.GONE);
                holder.binding.btnTaskShare.setVisibility(View.GONE);
                holder.binding.btnTaskCancelRetry.setVisibility(View.VISIBLE);
                holder.binding.btnTaskCancelRetry.setText(R.string.action_cancel);
                break;

            case DownloadTask.STATUS_COMPLETED:
                String sizeText = FormatUtils.formatFileSize(task.getFileSize());
                String doneText = context.getString(
                        R.string.status_completed, sizeText.isEmpty() ? extBadge : sizeText);
                String warn = task.getErrorMessage();
                if (warn != null && !warn.isEmpty()) {
                    // Completed with a warning (e.g. saved without sound): subtle amber note.
                    doneText = doneText + "\n" + warn;
                    holder.binding.tvTaskStatus.setTextColor(
                            androidx.core.content.ContextCompat.getColor(context, R.color.warning));
                }
                holder.binding.tvTaskStatus.setText(doneText);
                holder.binding.progressBar.setVisibility(View.GONE);
                holder.binding.btnTaskPlay.setVisibility(View.VISIBLE);
                holder.binding.btnTaskPlay.setText(isImage ? R.string.action_view : R.string.action_play);
                holder.binding.btnTaskPlay.setIconResource(isImage ? R.drawable.ic_image : R.drawable.ic_play);
                holder.binding.btnTaskShare.setVisibility(View.VISIBLE);
                holder.binding.btnTaskCancelRetry.setVisibility(View.GONE);
                break;

            case DownloadTask.STATUS_FAILED:
                String err = task.getErrorMessage() != null ? task.getErrorMessage() : context.getString(R.string.status_error_unknown);
                holder.binding.tvTaskStatus.setText(context.getString(R.string.status_failed, err));
                holder.binding.progressBar.setVisibility(View.GONE);
                holder.binding.btnTaskPlay.setVisibility(View.GONE);
                holder.binding.btnTaskShare.setVisibility(View.GONE);
                holder.binding.btnTaskCancelRetry.setVisibility(View.VISIBLE);
                holder.binding.btnTaskCancelRetry.setText(R.string.action_retry);
                break;

            case DownloadTask.STATUS_CANCELED:
            default:
                holder.binding.tvTaskStatus.setText(context.getString(R.string.status_canceled));
                holder.binding.progressBar.setVisibility(View.GONE);
                holder.binding.btnTaskPlay.setVisibility(View.GONE);
                holder.binding.btnTaskShare.setVisibility(View.GONE);
                holder.binding.btnTaskCancelRetry.setVisibility(View.VISIBLE);
                holder.binding.btnTaskCancelRetry.setText(R.string.action_retry);
                break;
        }

        holder.binding.btnTaskPlay.setOnClickListener(v -> {
            DownloadTask t = currentTask(holder);
            if (t != null) listener.onPlayClicked(t);
        });
        holder.binding.btnTaskShare.setOnClickListener(v -> {
            DownloadTask t = currentTask(holder);
            if (t != null) listener.onShareClicked(t);
        });
        holder.binding.btnTaskCancelRetry.setOnClickListener(v -> {
            DownloadTask t = currentTask(holder);
            if (t != null) listener.onCancelOrRetryClicked(t);
        });
        holder.binding.btnTaskDelete.setOnClickListener(v -> {
            DownloadTask t = currentTask(holder);
            if (t != null) listener.onDeleteClicked(t);
        });
    }

    private DownloadTask currentTask(TaskViewHolder holder) {
        int pos = holder.getBindingAdapterPosition();
        return pos == RecyclerView.NO_POSITION ? null : getItem(pos);
    }

    static class TaskViewHolder extends RecyclerView.ViewHolder {
        final ItemDownloadTaskBinding binding;

        TaskViewHolder(ItemDownloadTaskBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
