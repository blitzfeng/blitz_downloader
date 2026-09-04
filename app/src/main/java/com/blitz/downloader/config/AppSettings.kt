package com.blitz.downloader.config

import android.content.Context

/**
 * 用户可在设置页调整的偏好项（SharedPreferences）。
 *
 * 与 [AppConfig] 的区别：[AppConfig] 是编译期常量，这里是运行时可改的用户偏好。
 * 读取一律走 getter，不做内存缓存——设置页改完后其他页面下次查询就能拿到新值，
 * 不需要跨 Activity 的变更通知。
 */
object AppSettings {

    private const val PREFS_NAME = "blitz_app_settings"

    /** 标签多选筛选是否取交集（true = 同时含全部选中标签；false = 含任一即可）。 */
    private const val KEY_TAG_FILTER_MATCH_ALL = "tag_filter_match_all"

    /** 视频下载画质偏好，存 [VideoQualityPreference.name]；见该枚举的 KDoc。 */
    private const val KEY_VIDEO_QUALITY_PREFERENCE = "video_quality_preference"

    /** 批量打标签弹窗自动预勾选的高频阈值（1-5，默认 2）。 */
    private const val KEY_HIGH_FREQ_TAG_THRESHOLD = "high_freq_tag_threshold"

    /** `author_tag_frequency` 缓存表上次全量重算的时间戳（0 = 从未分析）。 */
    private const val KEY_TAG_FREQ_LAST_ANALYZED_AT = "tag_freq_last_analyzed_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 管理页标签栏多选时的匹配方式，默认 **交集**：
     * 选中越多结果越少，符合「叠加筛选」的直觉。关掉则变为并集（含任一标签即命中）。
     */
    fun isTagFilterMatchAll(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TAG_FILTER_MATCH_ALL, true)

    fun setTagFilterMatchAll(context: Context, matchAll: Boolean) {
        prefs(context).edit().putBoolean(KEY_TAG_FILTER_MATCH_ALL, matchAll).apply()
    }

    /**
     * 批量下载列表接口挑选视频直链时的画质偏好，默认 [VideoQualityPreference.HIGHEST]。
     * 只影响**之后**新加载的列表页/续拉页，已经映射进内存的 [com.blitz.downloader.model.VideoItemUiModel]
     * 不会跟着改（下载直链在取数时就已经选定），改完需要重新进入/刷新列表页才生效。
     */
    fun getVideoQualityPreference(context: Context): VideoQualityPreference {
        val name = prefs(context).getString(KEY_VIDEO_QUALITY_PREFERENCE, null)
        return VideoQualityPreference.entries.firstOrNull { it.name == name }
            ?: VideoQualityPreference.HIGHEST
    }

    fun setVideoQualityPreference(context: Context, preference: VideoQualityPreference) {
        prefs(context).edit().putString(KEY_VIDEO_QUALITY_PREFERENCE, preference.name).apply()
    }

    /**
     * 批量打标签弹窗自动预勾选的高频阈值：某作者名下出现次数达到该值即算高频标签。
     * 默认 2（次），范围 1-5。只影响 `author_tag_frequency` 缓存表的**读取**过滤，
     * 改这个值不需要重新分析，见 [com.blitz.downloader.data.VideoTagRepository.getHighFrequencyTagsForAuthor]。
     */
    fun getHighFrequencyTagThreshold(context: Context): Int =
        prefs(context).getInt(KEY_HIGH_FREQ_TAG_THRESHOLD, 2).coerceIn(1, 5)

    fun setHighFrequencyTagThreshold(context: Context, threshold: Int) {
        prefs(context).edit().putInt(KEY_HIGH_FREQ_TAG_THRESHOLD, threshold.coerceIn(1, 5)).apply()
    }

    /** `author_tag_frequency` 缓存表上次全量重算的时间戳，0 表示从未分析过。 */
    fun getTagFrequencyLastAnalyzedAtMillis(context: Context): Long =
        prefs(context).getLong(KEY_TAG_FREQ_LAST_ANALYZED_AT, 0L)

    fun setTagFrequencyLastAnalyzedAtMillis(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_TAG_FREQ_LAST_ANALYZED_AT, millis).apply()
    }
}
