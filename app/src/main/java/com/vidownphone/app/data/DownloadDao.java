package com.vidownphone.app.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface DownloadDao {

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    LiveData<List<DownloadTask>> observeAllTasks();

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    DownloadTask getTaskByIdSync(long id);

    @Insert
    long insertTask(DownloadTask task);

    @Update
    void updateTask(DownloadTask task);

    @Delete
    void deleteTask(DownloadTask task);

    @Query("SELECT * FROM download_tasks WHERE status = 0 OR status = 1")
    List<DownloadTask> getActiveTasksSync();

    @Query("SELECT * FROM download_tasks WHERE status = :completedStatus")
    List<DownloadTask> getCompletedTasksSync(int completedStatus);

    @Query("SELECT filePath FROM download_tasks WHERE filePath IS NOT NULL")
    List<String> getAllFilePathsSync();

    @Query("SELECT id FROM download_tasks")
    List<Long> getAllTaskIdsSync();

    @Query("DELETE FROM download_tasks WHERE status = :completedStatus")
    void clearCompletedTasks(int completedStatus);

    /** Marks a QUEUED/DOWNLOADING task as CANCELED; never touches a finished task. */
    @Query("UPDATE download_tasks SET status = 4, speedAndEta = '' WHERE id = :id AND (status = 0 OR status = 1)")
    int markCanceledIfActive(long id);

    /** Shows a FAILED/CANCELED task as QUEUED again when it is retried (no-op once it is DOWNLOADING). */
    @Query("UPDATE download_tasks SET status = 0, progress = 0, errorMessage = NULL, speedAndEta = '' "
            + "WHERE id = :id AND (status = 3 OR status = 4)")
    int markQueuedForRetry(long id);

    /** On app start: tasks left QUEUED/DOWNLOADING by a previous process become FAILED. */
    @Query("UPDATE download_tasks SET status = 3, errorMessage = :message, speedAndEta = '' "
            + "WHERE (status = 0 OR status = 1) AND createdAt < :createdBefore")
    int markInterruptedTasksFailed(long createdBefore, String message);
}
