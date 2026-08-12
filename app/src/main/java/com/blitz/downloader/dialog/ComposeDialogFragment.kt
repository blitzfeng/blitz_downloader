package com.blitz.downloader.dialog

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import com.blitz.downloader.R
import com.blitz.downloader.ui.theme.BlitzTheme

/**
 * Compose 弹窗的公共壳：DialogFragment 只出「窗口 + 遮罩」，容器与内容全部由 Compose 画。
 *
 * 新增 Compose 弹窗继承它并实现 [DialogContent] 即可，**不要**再各写一份窗口设置——下面这几步
 * 少任何一步都会在圆角容器外露出一圈浅色直角（app 主题里的 `android:background = color_surface`
 * 会被每个从 XML 膨胀出来的 View 继承，`themes.xml` 里 TextInputLayout 踩过同一个坑）：
 *
 * 1. [R.style.Theme_BlitzDownloader_ComposeDialog]：清掉 windowBackground / background，
 *    并开 `windowIsTranslucent`；
 * 2. 窗口自身 background 设透明，并显式 `setDimAmount`（translucent 窗口不会自动带遮罩）；
 * 3. [clearInheritedWindowBackgrounds]：PhoneWindow 装的 `screen_simple.xml` 不止一层
 *    （DecorView → LinearLayout → ContentFrameLayout → 我们的 ComposeView），只清直接父级不够。
 *
 * 用 DialogFragment 而不是 Compose 的 `Dialog`/`AlertDialog` Composable：一来能拿参数 Bundle 与
 * `rememberSaveable`，转屏不丢弹窗；二来在 DialogFragment 里再调 Compose 的 `AlertDialog` 会
 * 再开一个窗口，遮罩叠两层。
 */
abstract class ComposeDialogFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_BlitzDownloader_ComposeDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        // inflater.context 已带弹窗主题；这句只是显式声明「这层不画背景」
        setBackgroundColor(Color.TRANSPARENT)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            BlitzTheme {
                DialogContainer { DialogContent() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            // 撑满宽度交给 Compose 侧按 M3 规范（最大 560dp）收窄，窄屏上才能吃满可用宽度
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            // 确保遮罩层（dim）不因 windowIsTranslucent = true 而丢失
            setDimAmount(0.6f)
        }
        clearInheritedWindowBackgrounds()
    }

    /** 弹窗内容：已经处在 [DialogContainer] 的 Column 里，直接按顺序发标题 / 正文 / 按钮行即可。 */
    @Composable
    protected abstract fun DialogContent()

    /** 从内容视图沿父链一路清到 DecorView，去掉每层从 app 主题继承来的背景。 */
    private fun clearInheritedWindowBackgrounds() {
        var parent: View? = requireView().parent as? View
        while (parent != null) {
            parent.background = null
            parent = parent.parent as? View
        }
    }
}

/** M3 基础弹窗容器：28dp 圆角 + surfaceContainerHigh + 最大 560dp 宽。 */
@Composable
fun DialogContainer(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            content = { Column(Modifier.padding(vertical = 24.dp)) { content() } },
        )
    }
}

/** M3 弹窗标题。 */
@Composable
fun DialogHeadline(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

/**
 * 弹窗底部按钮行。[leading] 用来放中性动作（M2 时代的 neutral 按钮），没有就只右对齐主次按钮。
 */
@Composable
fun DialogActions(
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = if (leading == null) Arrangement.End else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Row { trailing() }
    }
}
