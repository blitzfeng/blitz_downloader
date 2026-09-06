package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 压缩后的个人偏好摘要（`preference_profile` 表，自然语言文本），累计一定数量新反馈后调用
 * LLM（纯文本总结，不带图片）重新生成一次，不做模型微调/训练。
 *
 * 只保留最新一条会被读取（查询取 [updatedAtMillis] 最大的一行），旧版本不主动清理——
 * 量级很小，不是当前需要解决的问题。
 */
@Entity(tableName = "preference_profile")
data class PreferenceProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val version: Int,
    val profileText: String,
    /** 生成这版摘要时依据的反馈样本数。 */
    val sampleCount: Int,
    val updatedAtMillis: Long,
)
