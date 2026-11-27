package com.blitz.downloader.data.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException

/** 源库保持打开；目标单文件独立于源库 WAL，可直接被旧恢复入口使用。 */
object DatabaseSnapshot {
    data class Schema(val type: String, val name: String, val sql: String)
    data class Info(val version: Int, val applicationId: Long, val schema: List<Schema>, val counts: Map<String, Long>)

    suspend fun create(room: AppDatabase, target: File): Info {
        val expected = room.withTransaction { copy(room.openHelper.writableDatabase, target) }
        val actual = validate(target)
        if (expected != actual) throw IOException("数据库快照内容校验失败")
        return actual
    }

    internal fun copy(source: SupportSQLiteDatabase, target: File): Info {
        val schema = source.query("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(Schema(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
        }.filter { !it.name.startsWith("sqlite_") }
        if (schema.any { it.sql.trimStart().startsWith("CREATE VIRTUAL TABLE", ignoreCase = true) })
            throw IOException("暂不支持包含虚拟表的数据库备份")
        val version = source.version
        val applicationId = source.query("PRAGMA application_id").use { it.moveToFirst(); it.getLong(0) }
        val counts = linkedMapOf<String, Long>()
        SQLiteDatabase.openOrCreateDatabase(target, null).use { dest ->
            dest.execSQL("PRAGMA foreign_keys=OFF")
            dest.beginTransaction()
            try {
                // Android 新建 SQLite 文件可能已创建 locale 元数据表。
                dest.execSQL("DROP TABLE IF EXISTS android_metadata")
                schema.filter { it.type == "table" }.forEach { table ->
                    dest.execSQL(table.sql)
                    counts[table.name] = source.query("SELECT * FROM ${quote(table.name)}").use { rows -> copyRows(rows, dest, table.name) }
                }
                val sequenceExists = source.query("SELECT 1 FROM sqlite_master WHERE name='sqlite_sequence'").use { it.moveToFirst() }
                if (sequenceExists) {
                    dest.execSQL("DELETE FROM sqlite_sequence")
                    source.query("SELECT * FROM sqlite_sequence").use { copyRows(it, dest, "sqlite_sequence") }
                }
                schema.filter { it.type != "table" }.sortedBy { if (it.type == "view") 0 else 1 }.forEach { dest.execSQL(it.sql) }
                dest.version = version
                dest.execSQL("PRAGMA application_id=$applicationId")
                dest.setTransactionSuccessful()
            } finally { dest.endTransaction() }
        }
        return Info(version, applicationId, schema, counts)
    }

    private fun copyRows(rows: Cursor, dest: SQLiteDatabase, table: String): Long {
        val columns = rows.columnNames.joinToString(",") { quote(it) }
        val placeholders = List(rows.columnCount) { "?" }.joinToString(",")
        var count = 0L
        dest.compileStatement("INSERT INTO ${quote(table)} ($columns) VALUES ($placeholders)").use { insert ->
            while (rows.moveToNext()) {
                insert.clearBindings()
                for (i in 0 until rows.columnCount) {
                    when (rows.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> insert.bindNull(i + 1)
                        Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(i + 1, rows.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(i + 1, rows.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(i + 1, rows.getBlob(i))
                        else -> insert.bindString(i + 1, rows.getString(i))
                    }
                }
                insert.executeInsert(); count++
            }
        }
        return count
    }

    fun validate(file: File): Info {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) throw IOException("数据库完整性校验失败")
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use {
                if (it.moveToFirst()) throw IOException("数据库外键校验失败")
            }
            val schema = db.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name", null).use { cursor ->
                buildList { while (cursor.moveToNext()) {
                    if (!cursor.getString(1).startsWith("sqlite_")) add(Schema(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                } }
            }
            val tables = schema.filter { it.type == "table" }
            if (db.version < 1 || tables.none { it.name == "downloaded_videos" } || tables.none { it.name == "tags" })
                throw IOException("不是有效的应用数据库")
            val counts = tables.associate { table ->
                table.name to db.rawQuery("SELECT count(*) FROM ${quote(table.name)}", null).use { it.moveToFirst(); it.getLong(0) }
            }
            val applicationId = db.rawQuery("PRAGMA application_id", null).use { it.moveToFirst(); it.getLong(0) }
            return Info(db.version, applicationId, schema, counts)
        }
    }

    private fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""
}
