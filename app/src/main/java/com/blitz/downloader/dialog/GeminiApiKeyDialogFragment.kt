package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.blitz.downloader.R
import com.blitz.downloader.ui.theme.BlitzTheme

/**
 * 设置页「Gemini API Key」编辑弹窗（Compose / Material 3），窗口层面的处理见 [ComposeDialogFragment]。
 *
 * **明文展示、明文存储**——`ai-tag-suggestions` design.md Decision 3 已明确这是用户接受的取舍
 * （自用 App，当前阶段不引入 `androidx.security:security-crypto`），这里不做遮罩/显隐切换，
 * 不是遗漏，是与存储侧的决策保持一致：既然落盘就是明文，弹窗里再遮一层没有实质安全收益，
 * 只会增加复制/核对 Key 的操作成本。
 */
class GeminiApiKeyDialogFragment : ComposeDialogFragment() {

    private val currentKey: String
        get() = requireArguments().getString(ARG_CURRENT_KEY).orEmpty()

    @Composable
    override fun DialogContent() {
        GeminiApiKeyDialogContent(
            currentKey = currentKey,
            onConfirm = { key -> finishWith(key) },
            onCancel = { dismiss() },
        )
    }

    private fun finishWith(apiKey: String) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply { putString(RESULT_API_KEY, apiKey) },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "GeminiApiKeyDialogFragment"
        const val RESULT_API_KEY = "apiKey"

        private const val ARG_CURRENT_KEY = "arg_current_key"
        private const val TAG = "GeminiApiKeyDialogFragment"

        /**
         * 在 [host] 的 childFragmentManager 上弹出（`SettingsFragment` 是 Fragment，不是 Activity——
         * 早期版本这里错写成 `activity.supportFragmentManager`，导致 `SettingsFragment` 监听的
         * `childFragmentManager` 收不到结果，表现为"粘贴 Key 保存后仍显示未配置"）。
         * 宿主监听结果用 `childFragmentManager.setFragmentResultListener(REQUEST_KEY, ...)`。
         */
        fun show(host: Fragment, currentKey: String) {
            GeminiApiKeyDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_CURRENT_KEY, currentKey) }
            }.show(host.childFragmentManager, TAG)
        }
    }
}

@Composable
private fun GeminiApiKeyDialogContent(
    currentKey: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(currentKey) }

    DialogHeadline(stringResource(R.string.settings_gemini_api_key_dialog_title))
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_gemini_api_key_dialog_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.padding(horizontal = 24.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
        placeholder = { Text(stringResource(R.string.settings_gemini_api_key_dialog_placeholder)) },
    )
    Spacer(Modifier.height(8.dp))
    DialogActions(
        trailing = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            // 清空 Key 是有效操作（等同于关闭/重置配置），不做非空校验
            TextButton(onClick = { onConfirm(text.trim()) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun GeminiApiKeyDialogPreview() {
    BlitzTheme {
        DialogContainer {
            GeminiApiKeyDialogContent(currentKey = "", onConfirm = {}, onCancel = {})
        }
    }
}
