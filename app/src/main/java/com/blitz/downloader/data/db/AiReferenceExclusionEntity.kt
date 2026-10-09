package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 按视频排除历史参考；独立于反馈记录，重新审核后不会意外恢复参考资格。 */
@Entity(tableName = "ai_reference_exclusion")
data class AiReferenceExclusionEntity(
    @PrimaryKey val awemeId: String,
    val excludedAtMillis: Long,
)
