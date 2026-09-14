package com.blitz.downloader.util

/**
 * 抖音风格的中文数字简写：
 * - `<= 0`        → 空串（调用方据此 GONE 隐藏控件）
 * - `< 1万`       → 原始整数（`987`）
 * - `< 1亿`       → `1.2w`，整数则省略小数点（`12w`、`1.2w`）
 * - `>= 1亿`      → `1.2亿`，规则同上
 *
 * 与抖音 App 显示风格对齐；不引入 Locale 依赖。
 */
object NumberFormatUtils {

    private const val WAN: Long = 10_000L
    private const val YI: Long = 100_000_000L

    fun formatChineseCount(n: Long): String {
        if (n <= 0L) return ""
        if (n < WAN) return n.toString()
        return when {
            n < YI -> formatWithUnit(n, WAN, "w")
            else -> formatWithUnit(n, YI, "亿")
        }
    }

    /** 保留一位小数，整数时省略 `.0`。例：12345/万 → `1.2w`；20000/万 → `2w`。 */
    private fun formatWithUnit(value: Long, unitBase: Long, suffix: String): String {
        // 向下取一位小数：先 *10 整除，再拆出整数 / 小数
        val scaled = value * 10 / unitBase
        val whole = scaled / 10
        val frac = scaled % 10
        return if (frac == 0L) "$whole$suffix" else "$whole.$frac$suffix"
    }

    /**
     * 将字节大小格式化为易读的文本（B / KB / MB / GB）。
     */
    fun formatFileSize(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
