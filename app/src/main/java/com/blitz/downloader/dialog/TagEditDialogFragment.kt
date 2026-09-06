package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.blitz.downloader.R
import com.blitz.downloader.config.AppSettings
import com.blitz.downloader.ui.theme.BlitzTheme
import com.blitz.downloader.viewmodel.TagEditDialogViewModel

/**
 * 单条记录的「设置标签」弹窗（Compose / Material 3），窗口层面的处理见 [ComposeDialogFragment]。
 *
 * 语义是**整体覆盖**（不是追加）：确认时把勾选结果原样写回，取消勾选即删标签。所以
 * **「确定」不做非空校验**——清空全部标签是有效操作，这点与 [BatchTagDialogFragment] 相反。
 *
 * 当前已打的标签由 [ARG_CURRENT_TAGS] 传入做预勾选；勾选状态用 `rememberSaveable` 保存，转屏不丢。
 * 结果走 `FragmentResult`（[REQUEST_KEY]），记录 id 原样回传，宿主不必自己缓存。
 *
 * **「AI 建议」入口**（`ai-tag-suggestions`）：功能开关关闭时不展示（`AppSettings` 属于运行时偏好，
 * 不算"网络与数据库操作"，弹窗直接读取，不必经过 ViewModel）；点击后调用 [TagEditDialogViewModel]
 * （网络+数据库操作走 ViewModel，是本弹窗唯一持有的一个）发起请求，返回结果与当前已勾选集合
 * 取**并集**（不覆盖，见 design.md Decision 5），失败展示错误提示但不影响手动勾选保存。
 */
class TagEditDialogFragment : ComposeDialogFragment() {

    private val viewModel: TagEditDialogViewModel by viewModels()

    private val awemeId: String
        get() = requireArguments().getString(ARG_AWEME_ID).orEmpty()

    private val secUserId: String
        get() = requireArguments().getString(ARG_SEC_USER_ID).orEmpty()

    private val desc: String
        get() = requireArguments().getString(ARG_DESC).orEmpty()

    private val coverPath: String
        get() = requireArguments().getString(ARG_COVER_PATH).orEmpty()

    private val videoFilePath: String
        get() = requireArguments().getString(ARG_VIDEO_FILE_PATH).orEmpty()

    private val allTags: List<String>
        get() = requireArguments().getStringArrayList(ARG_ALL_TAGS).orEmpty()

    private val currentTags: List<String>
        get() = requireArguments().getStringArrayList(ARG_CURRENT_TAGS).orEmpty()

    /** 标签名 → 上级标签名，只含有上级的条目；见 [com.blitz.downloader.data.VideoTagRepository.getParentMap]。 */
    private val parentMap: Map<String, String>
        get() {
            val keys = requireArguments().getStringArrayList(ARG_PARENT_MAP_KEYS).orEmpty()
            val values = requireArguments().getStringArrayList(ARG_PARENT_MAP_VALUES).orEmpty()
            return keys.zip(values).toMap()
        }

    @Composable
    override fun DialogContent() {
        val aiState by viewModel.aiState.collectAsState()
        TagEditDialogContent(
            allTags = allTags,
            currentTags = currentTags,
            parentMap = parentMap,
            aiSuggestionEnabled = AppSettings.isAiSuggestionEnabled(requireContext()),
            aiState = aiState,
            onRequestAiSuggestion = {
                viewModel.requestSuggestion(awemeId, secUserId, desc, coverPath, videoFilePath)
            },
            onConfirm = { tags, aiAnalysisId -> finishWith(tags, aiAnalysisId) },
            onCancel = { dismiss() },
        )
    }

    private fun finishWith(tags: List<String>, aiAnalysisId: Long?) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_AWEME_ID, awemeId)
                putStringArrayList(RESULT_TAGS, ArrayList(tags))
                // 只在本次编辑用过「AI 建议」时携带，未使用时不带这个 key（与既有行为等价）
                if (aiAnalysisId != null) putLong(RESULT_AI_ANALYSIS_ID, aiAnalysisId)
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "TagEditDialogFragment"

        const val RESULT_AWEME_ID = "awemeId"

        /** 勾选结果，**整体覆盖**该记录的标签（空列表 = 清空）。 */
        const val RESULT_TAGS = "tags"

        /** 本次编辑用过「AI 建议」时携带，关联的 `VideoAiAnalysisEntity.id`，供宿主写入反馈样例。 */
        const val RESULT_AI_ANALYSIS_ID = "aiAnalysisId"

        private const val ARG_AWEME_ID = "arg_aweme_id"
        private const val ARG_SEC_USER_ID = "arg_sec_user_id"
        private const val ARG_DESC = "arg_desc"
        private const val ARG_COVER_PATH = "arg_cover_path"
        private const val ARG_VIDEO_FILE_PATH = "arg_video_file_path"
        private const val ARG_ALL_TAGS = "arg_all_tags"
        private const val ARG_CURRENT_TAGS = "arg_current_tags"
        private const val ARG_PARENT_MAP_KEYS = "arg_parent_map_keys"
        private const val ARG_PARENT_MAP_VALUES = "arg_parent_map_values"
        private const val TAG = "TagEditDialogFragment"

        /**
         * 在 [host] 的 childFragmentManager 上弹出。
         * 宿主监听结果用 `childFragmentManager.setFragmentResultListener(REQUEST_KEY, ...)`。
         * [parentMap]（标签名 → 上级标签名）默认空，缺省时勾选界面不会有"选中带默认值"的联动。
         *
         * [secUserId]/[desc]/[coverPath]/[videoFilePath] 是「AI 建议」需要的上下文，功能关闭时
         * 弹窗不会用到这几个值，调用方仍需按需传入（AI 功能随时可能被用户开启）。
         */
        fun show(
            host: Fragment,
            awemeId: String,
            allTags: List<String>,
            currentTags: Collection<String>,
            parentMap: Map<String, String> = emptyMap(),
            secUserId: String = "",
            desc: String = "",
            coverPath: String = "",
            videoFilePath: String = "",
        ) {
            TagEditDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_AWEME_ID, awemeId)
                    putString(ARG_SEC_USER_ID, secUserId)
                    putString(ARG_DESC, desc)
                    putString(ARG_COVER_PATH, coverPath)
                    putString(ARG_VIDEO_FILE_PATH, videoFilePath)
                    putStringArrayList(ARG_ALL_TAGS, ArrayList(allTags))
                    putStringArrayList(ARG_CURRENT_TAGS, ArrayList(currentTags))
                    putStringArrayList(ARG_PARENT_MAP_KEYS, ArrayList(parentMap.keys))
                    putStringArrayList(ARG_PARENT_MAP_VALUES, ArrayList(parentMap.values))
                }
            }.show(host.childFragmentManager, TAG)
        }
    }
}

@Composable
private fun TagEditDialogContent(
    allTags: List<String>,
    currentTags: List<String>,
    parentMap: Map<String, String>,
    aiSuggestionEnabled: Boolean,
    aiState: TagEditDialogViewModel.AiSuggestionState,
    onRequestAiSuggestion: () -> Unit,
    onConfirm: (tags: List<String>, aiAnalysisId: Long?) -> Unit,
    onCancel: () -> Unit,
) {
    val checked = rememberCheckedTags(currentTags)
    var usedAiAnalysisId by rememberSaveable { mutableStateOf<Long?>(null) }

    // 建议结果与当前已勾选集合取并集（不覆盖），词表外的名字理论上不会出现（Repository 已按
    // tagId 过滤），这里再兜一层 `it in allTags` 纯属防御。
    LaunchedEffect(aiState) {
        if (aiState is TagEditDialogViewModel.AiSuggestionState.Success) {
            checked.addAll(aiState.suggestedTagNames.filter { it in allTags })
            usedAiAnalysisId = aiState.analysisId
        }
    }

    DialogHeadline(stringResource(R.string.manage_edit_tags_title))
    Spacer(Modifier.height(16.dp))
    if (aiSuggestionEnabled) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onRequestAiSuggestion,
                enabled = aiState !is TagEditDialogViewModel.AiSuggestionState.Loading,
            ) {
                Text(stringResource(R.string.tag_edit_ai_suggest_button))
            }
            if (aiState is TagEditDialogViewModel.AiSuggestionState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }
        if (aiState is TagEditDialogViewModel.AiSuggestionState.Failed) {
            Text(
                text = stringResource(R.string.tag_edit_ai_suggest_failed, aiState.message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
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
        trailing = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            // 覆盖语义：一个都不勾也要能确认（= 清空该记录的标签），所以不做 enabled 判断
            TextButton(onClick = { onConfirm(allTags.filter { it in checked }, usedAiAnalysisId) }) {
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
                parentMap = emptyMap(),
                aiSuggestionEnabled = true,
                aiState = TagEditDialogViewModel.AiSuggestionState.Idle,
                onRequestAiSuggestion = {},
                onConfirm = { _, _ -> },
                onCancel = {},
            )
        }
    }
}
