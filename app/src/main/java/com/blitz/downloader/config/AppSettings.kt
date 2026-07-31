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
}
