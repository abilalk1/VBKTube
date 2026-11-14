package com.vidownphone.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {DownloadTask.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract DownloadDao downloadDao();

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "vidown_database.db"
                            )
                            // No destructive fallback: wiping the DB would lose the user's download library.
                            // When bumping `version`, add a Migration here, e.g.
                            //   .addMigrations(MIGRATION_1_2)
                            // with: static final Migration MIGRATION_1_2 = new Migration(1, 2) {
                            //   public void migrate(SupportSQLiteDatabase db) { db.execSQL("ALTER TABLE ..."); } };
                            .build();
                }
            }
        }
        return instance;
    }
}
