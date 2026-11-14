package com.vidownphone.app.ui.downloads;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vidownphone.app.R;
import com.vidownphone.app.data.AppDatabase;
import com.vidownphone.app.data.DownloadTask;
import com.vidownphone.app.databinding.FragmentDownloadsBinding;
import com.vidownphone.app.service.DownloadService;
import com.vidownphone.app.ui.player.PlayerActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class DownloadsFragment extends Fragment implements DownloadTaskAdapter.TaskActionListener {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_ACTIVE = 1;
    private static final int FILTER_COMPLETED = 2;

    private FragmentDownloadsBinding binding;
    private DownloadTaskAdapter adapter;
    private List<DownloadTask> allTasks = new ArrayList<>();
    private int currentFilter = FILTER_ALL;
    private long lastNewestId = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDownloadsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new DownloadTaskAdapter(requireContext(), this);
        binding.rvDownloads.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvDownloads.setItemAnimator(null);
        binding.rvDownloads.setAdapter(adapter);

        binding.chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_filter_active)) {
                currentFilter = FILTER_ACTIVE;
            } else if (checkedIds.contains(R.id.chip_filter_completed)) {
                currentFilter = FILTER_COMPLETED;
            } else {
                currentFilter = FILTER_ALL;
            }
            applyCurrentFilter();
        });

        AppDatabase.getInstance(requireContext())
                .downloadDao()
                .observeAllTasks()
                .observe(getViewLifecycleOwner(), tasks -> {
                    allTasks = tasks != null ? tasks : new ArrayList<>();
                    long newestId = -1;
                    DownloadTask newest = null;
                    for (DownloadTask t : allTasks) {
                        if (t.getId() > newestId) {
                            newestId = t.getId();
                            newest = t;
                        }
                    }
                    boolean hasNewItem = lastNewestId >= 0 && newestId > lastNewestId;
                    lastNewestId = Math.max(lastNewestId, newestId);
                    // D-11: a freshly started download must not be hidden by the current filter
                    if (hasNewItem && newest != null && !isVisibleUnderFilter(newest)) {
                        resetFilterToAll();
                    } else {
                        applyCurrentFilter();
                    }
                });
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && binding != null && currentFilter == FILTER_COMPLETED) {
            // Tab shown again: make sure in-progress items are not hidden by "completed" filter
            for (DownloadTask t : allTasks) {
                if (t.getStatus() == DownloadTask.STATUS_QUEUED
                        || t.getStatus() == DownloadTask.STATUS_DOWNLOADING) {
                    resetFilterToAll();
                    break;
                }
            }
        }
    }

    private boolean isVisibleUnderFilter(DownloadTask t) {
        int s = t.getStatus();
        if (currentFilter == FILTER_ACTIVE) {
            return s == DownloadTask.STATUS_QUEUED || s == DownloadTask.STATUS_DOWNLOADING;
        } else if (currentFilter == FILTER_COMPLETED) {
            return s == DownloadTask.STATUS_COMPLETED;
        }
        return true;
    }

    private void resetFilterToAll() {
        if (binding == null) return;
        currentFilter = FILTER_ALL;
        binding.chipGroupFilter.check(R.id.chip_filter_all);
        applyCurrentFilter();
    }

    private void applyCurrentFilter() {
        if (binding == null) return;
        List<DownloadTask> filtered = new ArrayList<>();
        for (DownloadTask t : allTasks) {
            if (currentFilter == FILTER_ACTIVE) {
                if (t.getStatus() == DownloadTask.STATUS_QUEUED
                        || t.getStatus() == DownloadTask.STATUS_DOWNLOADING) {
                    filtered.add(t);
                }
            } else if (currentFilter == FILTER_COMPLETED) {
                if (t.getStatus() == DownloadTask.STATUS_COMPLETED) {
                    filtered.add(t);
                }
            } else {
                filtered.add(t);
            }
        }

        adapter.submitList(filtered);
        binding.layoutEmptyDownloads.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        binding.rvDownloads.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onPlayClicked(DownloadTask task) {
        if (task.getFilePath() == null) return;
        File file = new File(task.getFilePath());
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.player_error, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = file.getName().toLowerCase(java.util.Locale.US);
        boolean isImage = name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp");
        if (isImage) {
            try {
                Uri uri = FileProvider.getUriForFile(
                        requireContext(),
                        requireContext().getPackageName() + ".fileprovider",
                        file
                );
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(uri, "image/*");
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(viewIntent, task.getTitle()));
            } catch (Exception e) {
                Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        Intent intent = new Intent(requireContext(), PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_MEDIA_PATH, task.getFilePath());
        intent.putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, task.getTitle());
        startActivity(intent);
    }

    @Override
    public void onShareClicked(DownloadTask task) {
        if (task.getFilePath() == null) return;
        File file = new File(task.getFilePath());
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.player_error, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    file
            );
            String name = file.getName().toLowerCase(java.util.Locale.US);
            boolean isImage = name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp");
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(isImage ? "image/*" : (task.isAudioOnly() ? "audio/*" : "video/*"));
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, task.getTitle()));
        } catch (Exception e) {
            Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCancelOrRetryClicked(DownloadTask task) {
        if (task.getStatus() == DownloadTask.STATUS_DOWNLOADING
                || task.getStatus() == DownloadTask.STATUS_QUEUED) {
            DownloadService.cancelDownload(requireContext(), task.getId());
        } else {
            DownloadService.retryDownload(requireContext(), task.getId());
        }
    }

    @Override
    public void onDeleteClicked(DownloadTask task) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.delete_confirm_positive, (d, w) -> performDelete(task))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void performDelete(DownloadTask task) {
        android.content.Context appContext = requireContext().getApplicationContext();
        DownloadService.cancelDownload(appContext, task.getId());
        Executors.newSingleThreadExecutor().execute(() -> {
            // 1. Clean up any internal partial chunks/temp files
            DownloadService.deleteTaskWorkFiles(appContext, task.getId());

            // 2. Clean up external target file
            if (task.getFilePath() != null) {
                File f = new File(task.getFilePath());
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                // 3. Clean up from public MediaStore (Gallery) if exported
                try {
                    String name = f.getName().toLowerCase(java.util.Locale.US);
                    boolean isImage = name.endsWith(".jpg") || name.endsWith(".jpeg")
                            || name.endsWith(".png") || name.endsWith(".webp");
                    Uri contentUri = isImage
                            ? android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            : (task.isAudioOnly()
                            ? android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            : android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                    String selection;
                    String[] selectionArgs;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // Only entries inside our own gallery folder
                        selection = android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND "
                                + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
                        selectionArgs = new String[]{f.getName(), "%VBKTube%"};
                    } else {
                        // API 26-28: restrict to our own folders by DATA path
                        selection = android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND ("
                                + android.provider.MediaStore.MediaColumns.DATA + " LIKE ? OR "
                                + android.provider.MediaStore.MediaColumns.DATA + " LIKE ?)";
                        selectionArgs = new String[]{f.getName(), "%/VBKTube/%", "%/VidownPhone/%"};
                    }
                    appContext.getContentResolver().delete(contentUri, selection, selectionArgs);
                } catch (Exception ignored) {
                }
            }

            // 4. Remove task record from database
            AppDatabase.getInstance(appContext).downloadDao().deleteTask(task);
        });
        Toast.makeText(requireContext(), R.string.file_deleted_toast, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
