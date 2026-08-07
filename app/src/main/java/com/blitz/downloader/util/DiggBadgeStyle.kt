package com.blitz.downloader.util

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/**
 * 点赞数徽标（`tvDiggCount`）里心形图标的着色规则，批量下载页与管理页共用：
 * - 未点赞：白色实心（对齐抖音列表的默认样式）
 * - 我点赞过：抖音红
 *
 * 「我点赞过」的判定来源不同：列表页用接口的 `user_digged`，管理页用入库的
 * [com.blitz.downloader.data.db.DownloadedVideoEntity.userRelation]（见
 * [com.blitz.downloader.data.DownloadedVideoRepository.hasLikeRelation]）。
 */
object DiggBadgeStyle {

    private const val COLOR_UNLIKED = 0xFFFFFFFF.toInt()
    private const val COLOR_LIKED = 0xFFFE2C55.toInt()   // 抖音红

    fun tintHeart(badge: TextView, liked: Boolean) {
        val color = if (liked) COLOR_LIKED else COLOR_UNLIKED
        TextViewCompat.setCompoundDrawableTintList(badge, ColorStateList.valueOf(color))
    }
}
