package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.blitz.downloader.R
import com.blitz.downloader.ui.theme.BlitzTheme

/**
 * 多选后的「批量添加标签」弹窗 —— 本项目第一个 Compose（Material 3）弹窗。
 * 窗口层面的处理见基类 [ComposeDialogFragment]。
 *
 * 与被它替换掉的 `AlertDialog.Builder().setMultiChoiceItems(...)` 的行为差异，都是有意为之：
 *
 * - **「确定」在一个标签都没勾时是禁用态**，不再是「点了才 toast 提醒没勾选」。
 *   `R.string.manage_set_tags_none_checked` 因此不再被用到（字符串保留，未删）。
 *   注意这条**只适用于本弹窗**：它的语义是「追加」，空集合等于什么都没做；
 *   单条编辑弹窗 [TagEditDialogFragment] 是「整体覆盖」，清空是有效操作，不能禁用。
 * - **「仅次数 +1」的二次确认不再另开一个弹窗**，而是在同一个窗口内换页（[Stage]）。
 *   确认页的「取消」会退回勾选页，不像原来那样整个流程被丢掉。
 * - 勾选状态与当前处在哪一页都用 `rememberSaveable` 保存，**转屏不再丢**（旧的 AlertDialog
 *   由 Fragment 直接 `show()`，转屏即消失）。
 *
 * 结果不通过回调直接回传（那样会把弹窗钉死在某一个 Tab 的 ViewModel 上），而是走
 * `FragmentResult`：宿主 Fragment 用 [REQUEST_KEY] 监听，自己决定调哪个 ViewModel。
 * 待选记录 id 会原样回传，宿主无需自己缓存。
 */
class BatchTagDialogFragment : ComposeDialogFragment() {

    private val awemeIds: List<String>
        get() = requireArguments().getStringArrayList(ARG_AWEME_IDS).orEmpty()

    private val allTags: List<String>
        get() = requireArguments().getStringArrayList(ARG_ALL_TAGS).orEmpty()

    @Composable
    override fun DialogContent() {
        BatchTagDialogContent(
            selectedCount = awemeIds.size,
            allTags = allTags,
            onConfirmTags = { tags -> finishWith(ACTION_ADD_TAGS, tags) },
            onConfirmBump = { finishWith(ACTION_BUMP_COUNT, emptyList()) },
            onCancel = { dismiss() },
        )
    }

    private fun finishWith(action: String, tags: List<String>) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_ACTION, action)
                putStringArrayList(RESULT_AWEME_IDS, ArrayList(awemeIds))
                putStringArrayList(RESULT_TAGS, ArrayList(tags))
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "BatchTagDialogFragment"

        /** 结果 Bundle 的 key：取值为 [ACTION_ADD_TAGS] 或 [ACTION_BUMP_COUNT]。 */
        const val RESULT_ACTION = "action"
        const val RESULT_AWEME_IDS = "awemeIds"
        const val RESULT_TAGS = "tags"

        /** 给 [RESULT_AWEME_IDS] 这些记录**追加** [RESULT_TAGS]。 */
        const val ACTION_ADD_TAGS = "add_tags"

        /** 只给 [RESULT_AWEME_IDS] 的 tagEditCount +1，不动标签（用户已在弹窗内二次确认过）。 */
        const val ACTION_BUMP_COUNT = "bump_count"

        private const val ARG_AWEME_IDS = "arg_aweme_ids"
        private const val ARG_ALL_TAGS = "arg_all_tags"
        private const val TAG = "BatchTagDialogFragment"

        /**
         * 在 [host] 的 childFragmentManager 上弹出。
         * 宿主监听结果用 `childFragmentManager.setFragmentResultListener(REQUEST_KEY, ...)`。
         */
        fun show(host: Fragment, awemeIds: List<String>, allTags: List<String>) {
            BatchTagDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_AWEME_IDS, ArrayList(awemeIds))
                    putStringArrayList(ARG_ALL_TAGS, ArrayList(allTags))
                }
            }.show(host.childFragmentManager, TAG)
        }
    }
}

/** 弹窗内的两页：勾选标签 / 「仅次数 +1」的二次确认。 */
private enum class Stage { PICK_TAGS, CONFIRM_BUMP }

@Composable
private fun BatchTagDialogContent(
    selectedCount: Int,
    allTags: List<String>,
    onConfirmTags: (List<String>) -> Unit,
    onConfirmBump: () -> Unit,
    onCancel: () -> Unit,
) {
    var stage by rememberSaveable { mutableStateOf(Stage.PICK_TAGS) }
    val checked = rememberCheckedTags()

    when (stage) {
        Stage.PICK_TAGS -> {
            DialogHeadline(stringResource(R.string.manage_set_tags_title, selectedCount))
            Spacer(Modifier.height(16.dp))
            TagCheckGrid(
                allTags = allTags,
                checked = checked,
                onToggle = { tag -> if (tag in checked) checked.remove(tag) else checked.add(tag) },
            )
            Spacer(Modifier.height(16.dp))
            DialogActions(
                leading = {
                    TextButton(onClick = { stage = Stage.CONFIRM_BUMP }) {
                        Text(stringResource(R.string.manage_bump_tag_edit_count))
                    }
                },
                trailing = {
                    TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                    TextButton(
                        onClick = { onConfirmTags(allTags.filter { it in checked }) },
                        enabled = checked.isNotEmpty(),
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        Stage.CONFIRM_BUMP -> {
            DialogHeadline(stringResource(R.string.manage_bump_tag_edit_count))
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.manage_bump_tag_edit_count_confirm, selectedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(24.dp))
            DialogActions(
                trailing = {
                    TextButton(onClick = { stage = Stage.PICK_TAGS }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(onClick = onConfirmBump) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BatchTagDialogPreview() {
    BlitzTheme {
        DialogContainer {
            BatchTagDialogContent(
                selectedCount = 12,
                allTags = listOf("美腿", "可爱", "纯欲", "波霸", "小沟", "穿搭"),
                onConfirmTags = {},
                onConfirmBump = {},
                onCancel = {},
            )
        }
    }
}
