package com.blitz.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次分析产出的结构化视觉证据（`video_visual_feature` 表），即 VisualFeatureProfile。
 *
 * [profileJson] 直接存 Gson 序列化字符串，不引入 Room `TypeConverter` 存复杂对象——项目
 * `data/db/` 下没有任何 `TypeConverter` 先例，读取时在 Repository 层手动反序列化即可。
 * 结构分维度（face/expression/bodyAndStyling/clothing/action）描述可见证据，而不是一句话摘要，
 * 用于让"无清晰人脸时不推荐颜值类标签"这类判断有据可查。
 */
@Entity(tableName = "video_visual_feature")
data class VideoVisualFeatureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联 [VideoAiAnalysisEntity.id]。 */
    val analysisId: Long,
    val awemeId: String,
    val profileJson: String,
    val createdAtMillis: Long,
)
