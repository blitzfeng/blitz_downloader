package com.blitz.downloader.util

import android.content.Context
import android.util.TypedValue
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding

/**
 * 获取当前主题下的标准 ActionBar 高度（通常为 56dp）。
 */
fun Context.getActionBarHeight(): Int {
    val tv = TypedValue()
    return if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true) ||
        theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
    ) {
        TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
    } else {
        (56 * resources.displayMetrics.density).toInt()
    }
}

/**
 * 适配沉浸式状态栏：
 * 1. 将状态栏高度设为 Toolbar 的 paddingTop；
 * 2. 显式将 Toolbar 的 LayoutParams.height 设为「标准 ActionBar 高度 + 状态栏高度」；
 * 3. 保持 minimumHeight 为标准的 ActionBar 高度（56dp），**严禁**将 statusBarTop 加进 minimumHeight！
 *
 * 原因：
 * Toolbar 内部布局算法（Toolbar.onLayout / layoutChildRight / getChildTop）使用
 * `alignmentOffset = (childHeight - alignmentHeight) / 2` 计算导航图标和 ActionMenuView 的位置，
 * 其中 `alignmentHeight = Math.min(minimumHeight, height)`。若把 statusBarTop 加进 minimumHeight，
 * 会导致 alignmentOffset 为负数，进而将右侧菜单按钮和左侧返回图标额外向下偏移 (statusBarTop / 2)，
 * 导致按钮整体偏下甚至底部被裁剪显示不全；而标题不使用 alignmentOffset，导致标题与按钮错位。
 * 通过调整 layoutParams.height，既能让无菜单项的页面（下载、设置）标题具备足够的底部 margin，
 * 又能保证有菜单项的页面（管理页、标签管理等）按钮与图标精确垂直居中且不被裁剪。
 */
fun Toolbar.applyStatusBarPadding(statusBarTop: Int) {
    updatePadding(top = statusBarTop)
    val actionBarHeight = context.getActionBarHeight()
    minimumHeight = actionBarHeight
    layoutParams?.let { lp ->
        lp.height = actionBarHeight + statusBarTop
        layoutParams = lp
    }
}
