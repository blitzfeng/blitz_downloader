package com.blitz.downloader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadedVideoEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadedVideoDao(): DownloadedVideoDao

    companion object {
        private const val DB_NAME = "blitz_downloader.db"

        /**
         * v1 → v2：新增 `mediaType`（默认 'video'）和 `filePath`（默认空字符串）两列。
         * 现有记录均为视频类型，filePath 留空（旧记录无路径信息）。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloaded_videos ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'video'")
                db.execSQL("ALTER TABLE downloaded_videos ADD COLUMN filePath TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2 → v3：新增 `coverPath`（本地封面路径，默认空字符串）。
         * 旧记录无封面，显示时回退到占位图。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloaded_videos ADD COLUMN coverPath TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
