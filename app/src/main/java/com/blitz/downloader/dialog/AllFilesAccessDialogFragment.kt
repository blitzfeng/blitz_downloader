package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.blitz.downloader.R
import com.blitz.downloader.ui.theme.BlitzTheme

/**
 * 开启「对相册隐藏」前的权限说明弹窗（Compose / Material 3），窗口处理见 [ComposeDialogFragment]。
 *
 * 存在的理由：`MANAGE_EXTERNAL_STORAGE` 只能跳系统设置页手动授予，没有普通权限那种弹窗。
 * 直接把用户丢进系统页面会让人一头雾水，所以先解释清楚「为什么一个下载器要所有文件访问权限」。
 *
 * 结果走 `FragmentResult`（[REQUEST_KEY]），待处理的目录名原样回传，宿主不必自己缓存
 * （缓存也扛不住进程重建）。
 */
class AllFilesAccessDialogFragment : ComposeDialogFragment() {

    private val folderName: String
        get() = requireArguments().getString(ARG_FOLDER).orEmpty()

    @Composable
    override fun DialogContent() {
        AllFilesAccessDialogContent(
            onConfirm = {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply { putString(RESULT_FOLDER, folderName) },
                )
                dismiss()
            },
            onCancel = { dismiss() },
        )
    }

    companion object {
        const val REQUEST_KEY = "AllFilesAccessDialogFragment"
        const val RESULT_FOLDER = "result_folder"

        private const val ARG_FOLDER = "arg_folder"

        /** @param folderName 待隐藏目录的枚举名，授权返回后由宿主用它继续未完成的动作。 */
        fun newInstance(folderName: String) = AllFilesAccessDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_FOLDER, folderName) }
        }
    }
}

@Composable
private fun AllFilesAccessDialogContent(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    DialogHeadline(stringResource(R.string.settings_all_files_access_title))
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.settings_all_files_access_msg),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(24.dp))
    DialogActions {
        TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        TextButton(onClick = onConfirm) {
            Text(stringResource(R.string.settings_all_files_access_goto))
        }
    }
}

@Preview
@Composable
private fun AllFilesAccessDialogPreview() {
    BlitzTheme {
        DialogContainer { AllFilesAccessDialogContent(onConfirm = {}, onCancel = {}) }
    }
}
