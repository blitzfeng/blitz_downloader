package com.blitz.downloader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadedVideoEntity::class, VideoTagEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadedVideoDao(): DownloadedVideoDao

    abstract fun videoTagDao(): VideoTagDao

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

        /**
         * v3 → v4：新增 `desc`（视频描述）、`likeType`（冗余列，已由 v4→v5 移除）、
         * `collectionType`（收藏夹名称）三列，旧记录默认为空字符串。
         *
         * 注：`likeType` 列语义与 `downloadType` 重叠，在 v4→v5 中通过重建表将其删除。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloaded_videos ADD COLUMN desc TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloaded_videos ADD COLUMN likeType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloaded_videos ADD COLUMN collectionType TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v4 → v5：
         * - 删除冗余列 `likeType`（SQLite 不支持直接 DROP COLUMN，通过重建表实现）。
         * - 新增 `videoAuthorSecUserId`（视频创作者稳定 ID，用于管理页按作者过滤）。
         * - 新增 `sourceOwnerSecUserId`（下载来源账户 ID：post 类型填目标用户 ID；
         *   like/collect/collects 类型填 App 所有者 [com.blitz.downloader.config.AppConfig.MY_SEC_USER_ID]）。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloaded_videos_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        awemeId TEXT NOT NULL,
                        downloadType TEXT NOT NULL,
                        userName TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        mediaType TEXT NOT NULL DEFAULT 'video',
                        filePath TEXT NOT NULL DEFAULT '',
                        coverPath TEXT NOT NULL DEFAULT '',
                        desc TEXT NOT NULL DEFAULT '',
                        collectionType TEXT NOT NULL DEFAULT '',
                        videoAuthorSecUserId TEXT NOT NULL DEFAULT '',
                        sourceOwnerSecUserId TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO downloaded_videos_new
                        (id, awemeId, downloadType, userName, createdAtMillis,
                         mediaType, filePath, coverPath, desc, collectionType)
                    SELECT id, awemeId, downloadType, userName, createdAtMillis,
                           mediaType, filePath, coverPath, desc, collectionType
                    FROM downloaded_videos
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE downloaded_videos")
                db.execSQL("ALTER TABLE downloaded_videos_new RENAME TO downloaded_videos")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_downloaded_videos_awemeId " +
                        "ON downloaded_videos (awemeId)",
                )
            }
        }

        /**
         * v5 → v6：
         * - 新建 `video_tags` 表，支持用户自定义标签（多对多，方案 C）。
         * - 新增 `collectId`（收藏夹稳定 ID，与 `collectionType` 对应）。
         * - 新增 `userRelation`（视频与账户所有者的关系标签，格式 `like|<夹名>` 等）。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS video_tags (
                        awemeId TEXT NOT NULL,
                        tagName TEXT NOT NULL,
                        PRIMARY KEY (awemeId, tagName),
                        FOREIGN KEY (awemeId) REFERENCES downloaded_videos(awemeId)
                            ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_video_tags_tagName ON video_tags (tagName)",
                )
                db.execSQL(
                    "ALTER TABLE downloaded_videos ADD COLUMN collectId TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE downloaded_videos ADD COLUMN userRelation TEXT NOT NULL DEFAULT ''",
                )
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
