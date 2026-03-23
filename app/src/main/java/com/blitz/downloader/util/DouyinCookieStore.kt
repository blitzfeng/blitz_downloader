package com.blitz.downloader.util

import android.content.Context
import com.blitz.downloader.api.DouyinApiClient

/**
 * 将 [DouyinApiClient.globalCookie] 持久化，供列表接口与进程重启后复用。
 */
object DouyinCookieStore {

    private const val PREFS_NAME = "douyin_cookie_prefs"
    private const val KEY_COOKIE_LINE = "global_cookie_line"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 从磁盘恢复到内存（应用启动时调用一次即可）。 */
    fun restoreIntoClient() {
        val ctx = appContext ?: return
        val line = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE_LINE, null)?.trim().orEmpty()
        if (line.isEmpty()) return
        DouyinApiClient.globalCookie = line
        DouyinCookieSync.applyDerivedTokensFromCookieLine(line)
    }

    fun persistCurrentCookie() {
        val ctx = appContext ?: return
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val line = DouyinApiClient.globalCookie?.trim().orEmpty()
        if (line.isEmpty()) {
            sp.remove(KEY_COOKIE_LINE)
        } else {
            sp.putString(KEY_COOKIE_LINE, line)
        }
        sp.apply()
    }
}
