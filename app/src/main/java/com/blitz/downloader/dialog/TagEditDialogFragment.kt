package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.blitz.downloader.R
import com.blitz.downloader.ui.theme.BlitzTheme

/**
 * 单条记录的「设置标签」弹窗（Compose / Material 3），窗口层面的处理见 [ComposeDialogFragment]。
 *
 * 语义是**整体覆盖**（不是追加）：确认时把勾选结果原样写回，取消勾选即删标签。所以
 * **「确定」不做非空校验**——清空全部标签是有效操作，这点与 [BatchTagDialogFragment] 相反。
 *
 * 当前已打的标签由 [ARG_CURRENT_TAGS] 传入做预勾选；勾选状态用 `rememberSaveable` 保存，转屏不丢。
 * 结果走 `FragmentResult`（[REQUEST_KEY]），记录 id 原样回传，宿主不必自己缓存。
 */
class TagEditDialogFragment : ComposeDialogFragment() {

    private val awemeId: String
        get() = requireArguments().getString(ARG_AWEME_ID).orEmpty()

    private val allTags: List<String>
        get() = requireArguments().getStringArrayList(ARG_ALL_TAGS).orEmpty()

    private val currentTags: List<String>
        get() = requireArguments().getStringArrayList(ARG_CURRENT_TAGS).orEmpty()

    @Composable
    override fun DialogContent() {
        TagEditDialogContent(
            allTags = allTags,
            currentTags = currentTags,
            onConfirm = { tags -> finishWith(tags) },
            onCancel = { dismiss() },
        )
    }

    private fun finishWith(tags: List<String>) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_AWEME_ID, awemeId)
                putStringArrayList(RESULT_TAGS, ArrayList(tags))
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "TagEditDialogFragment"

        const val RESULT_AWEME_ID = "awemeId"

        /** 勾选结果，**整体覆盖**该记录的标签（空列表 = 清空）。 */
        const val RESULT_TAGS = "tags"

        private const val ARG_AWEME_ID = "arg_aweme_id"
        private const val ARG_ALL_TAGS = "arg_all_tags"
        private const val ARG_CURRENT_TAGS = "arg_current_tags"
        private const val TAG = "TagEditDialogFragment"

        /**
         * 在 [host] 的 childFragmentManager 上弹出。
         * 宿主监听结果用 `childFragmentManager.setFragmentResultListener(REQUEST_KEY, ...)`。
         */
        fun show(
            host: Fragment,
            awemeId: String,
            allTags: List<String>,
            currentTags: Collection<String>,
        ) {
            TagEditDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_AWEME_ID, awemeId)
                    putStringArrayList(ARG_ALL_TAGS, ArrayList(allTags))
                    putStringArrayList(ARG_CURRENT_TAGS, ArrayList(currentTags))
                }
            }.show(host.childFragmentManager, TAG)
        }
    }
}

@Composable
private fun TagEditDialogContent(
    allTags: List<String>,
    currentTags: List<String>,
    onConfirm: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val checked = rememberCheckedTags(currentTags)

    DialogHeadline(stringResource(R.string.manage_edit_tags_title))
    Spacer(Modifier.height(16.dp))
    TagCheckGrid(
        allTags = allTags,
        checked = checked,
        onToggle = { tag -> if (tag in checked) checked.remove(tag) else checked.add(tag) },
    )
    Spacer(Modifier.height(16.dp))
    DialogActions(
        trailing = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            // 覆盖语义：一个都不勾也要能确认（= 清空该记录的标签），所以不做 enabled 判断
            TextButton(onClick = { onConfirm(allTags.filter { it in checked }) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun TagEditDialogPreview() {
    BlitzTheme {
        DialogContainer {
            TagEditDialogContent(
                allTags = listOf("美腿", "可爱", "纯欲", "波霸", "小沟", "穿搭"),
                currentTags = listOf("可爱", "穿搭"),
                onConfirm = {},
                onCancel = {},
            )
        }
    }
}
