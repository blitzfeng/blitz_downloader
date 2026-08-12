package com.blitz.downloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 全应用 Compose 主题（Material 3）。
 *
 * 色值直接对齐 `res/values/colors.xml` 里的品牌色，让 Compose 弹窗与存量 XML 页面看起来是同一套
 * 设计：primary = `color_primary`（#667EEA），secondary = `color_dopamine_blue`，
 * error = `color_dopamine_pink`，背景 / 文字取 `color_surface` 与 `text_color_*`。
 *
 * **固定浅色，且刻意不开动态取色（Material You）**：XML 主题虽然挂着
 * `Theme.MaterialComponents.DayNight`，但没有 `values-night`，所有颜色都是写死的浅色值。
 * 这里若跟随系统深色或跟随壁纸取色，会出现「深色/异色弹窗盖在浅色页面上」的割裂。
 * 等 XML 侧补齐深色资源后，再在这里加 `darkColorScheme` 与 `isSystemInDarkTheme()` 分支。
 */
private val BlitzLightColorScheme = lightColorScheme(
    primary = Color(0xFF667EEA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE4FB),
    onPrimaryContainer = Color(0xFF1A2456),
    secondary = Color(0xFF4ECDC4),
    onSecondary = Color(0xFF00201D),
    secondaryContainer = Color(0xFFD3F3F0),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFFFFD166),
    onTertiary = Color(0xFF3D2E00),
    error = Color(0xFFEE5A64),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF7F9FE),
    onBackground = Color(0xFF333333),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF333333),
    surfaceVariant = Color(0xFFE6E8F2),
    onSurfaceVariant = Color(0xFF666666),
    // 对话框容器用的就是它（M3 规范：surfaceContainerHigh）
    surfaceContainerHigh = Color(0xFFF1F3FC),
    outline = Color(0xFF9095A6),
    outlineVariant = Color(0xFFCBCEDA),
)

@Composable
fun BlitzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlitzLightColorScheme,
        content = content,
    )
}
