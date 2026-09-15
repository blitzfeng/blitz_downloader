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
 * - **打开时按选中记录涉及的作者历史高频标签自动预勾选**（[preCheckedTags]，由宿主 ViewModel
 *   算好传入，见 [com.blitz.downloader.viewmodel.ManageVideoViewModel.requestBatchTagPicker]），
 *   不再永远从空白开始；预勾选非空时会在标题下方提示，避免用户看到「平白无故已经勾了几个」。
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

    private val preCheckedTags: List<String>
        get() = requireArguments().getStringArrayList(ARG_PRECHECKED_TAGS).orEmpty()

    /** 标签名 → 上级标签名，只含有上级的条目；见 [com.blitz.downloader.data.VideoTagRepository.getParentMap]。 */
    private val parentMap: Map<String, String>
        get() {
            val keys = requireArguments().getStringArrayList(ARG_PARENT_MAP_KEYS).orEmpty()
            val values = requireArguments().getStringArrayList(ARG_PARENT_MAP_VALUES).orEmpty()
            return keys.zip(values).toMap()
        }

    @Composable
    override fun DialogContent() {
        BatchTagDialogContent(
            selectedCount = awemeIds.size,
            allTags = allTags,
            preCheckedTags = preCheckedTags,
            parentMap = parentMap,
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
        private const val ARG_PRECHECKED_TAGS = "arg_prechecked_tags"
        private const val ARG_PARENT_MAP_KEYS = "arg_parent_map_keys"
        private const val ARG_PARENT_MAP_VALUES = "arg_parent_map_values"
        private const val TAG = "BatchTagDialogFragment"

        /**
         * 在 [host] 的 childFragmentManager 上弹出。
         * 宿主监听结果用 `childFragmentManager.setFragmentResultListener(REQUEST_KEY, ...)`。
         * [preCheckedTags] 是宿主算好的自动预勾选集合（默认空，即回到原来「从空白开始」的行为）。
         * [parentMap]（标签名 → 上级标签名）默认空，缺省时勾选界面不会有"选中带默认值"的联动；
         * 不会对 [preCheckedTags] 做任何祖先展开——高频标签预勾选与层级默认值是两套独立机制，
         * 互不干涉。
         */
        fun show(
            host: Fragment,
            awemeIds: List<String>,
            allTags: List<String>,
            preCheckedTags: Collection<String> = emptySet(),
            parentMap: Map<String, String> = emptyMap(),
        ) {
            BatchTagDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_AWEME_IDS, ArrayList(awemeIds))
                    putStringArrayList(ARG_ALL_TAGS, ArrayList(allTags))
                    putStringArrayList(ARG_PRECHECKED_TAGS, ArrayList(preCheckedTags))
                    putStringArrayList(ARG_PARENT_MAP_KEYS, ArrayList(parentMap.keys))
                    putStringArrayList(ARG_PARENT_MAP_VALUES, ArrayList(parentMap.values))
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
    preCheckedTags: List<String>,
    parentMap: Map<String, String>,
    onConfirmTags: (List<String>) -> Unit,
    onConfirmBump: () -> Unit,
    onCancel: () -> Unit,
) {
    var stage by rememberSaveable { mutableStateOf(Stage.PICK_TAGS) }
    val checked = rememberCheckedTags(preCheckedTags)

    when (stage) {
        Stage.PICK_TAGS -> {
            DialogHeadline(stringResource(R.string.manage_set_tags_title, selectedCount))
            if (preCheckedTags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.manage_set_tags_prechecked_hint, preCheckedTags.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            TagCheckGrid(
                allTags = allTags,
                checked = checked,
                onToggle = { tag ->
                    if (tag in checked) {
                        checked.remove(tag) // 取消：只影响这一个标签，不联动
                    } else {
                        checked.add(tag)
                        val parent = parentMap[tag]
                        if (!parent.isNullOrBlank()) checked.add(parent) // 选中：顺手带上父标签默认值
                    }
                },
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
                allTags = listOf("美腿", "可爱", "纯欲", "波霸", "乳沟", "穿搭"),
                preCheckedTags = listOf("美腿"),
                parentMap = emptyMap(),
                onConfirmTags = {},
                onConfirmBump = {},
                onCancel = {},
            )
        }
    }
}
