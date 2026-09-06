package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.blitz.downloader.R
import com.blitz.downloader.ui.theme.BlitzTheme

/**
 * 标签描述编辑弹窗（Compose / Material 3），窗口层面的处理见 [ComposeDialogFragment]。
 *
 * 描述文本辅助 `ai-tag-suggestions` 的 AI 建议理解标签判断标准，与该标签关联了哪些视频、
 * 打过几次标签完全无关——保存走 [com.blitz.downloader.data.VideoTagRepository.setTagDescription]，
 * **不计入** `tagEditCount`（那统计的是"打标签"操作，不是标签名册本身的元数据编辑）。
 *
 * 限长 200 字符：标签词表可能有几十个标签，每个都要带着描述一起发给 LLM 组装进 prompt，
 * 单条描述过长会不必要地推高请求体积。
 */
class TagDescriptionDialogFragment : ComposeDialogFragment() {

    private val tagName: String
        get() = requireArguments().getString(ARG_TAG_NAME).orEmpty()

    private val currentDescription: String
        get() = requireArguments().getString(ARG_CURRENT_DESCRIPTION).orEmpty()

    @Composable
    override fun DialogContent() {
        TagDescriptionDialogContent(
            tagName = tagName,
            currentDescription = currentDescription,
            onConfirm = { description -> finishWith(description) },
            onCancel = { dismiss() },
        )
    }

    private fun finishWith(description: String) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_TAG_NAME, tagName)
                putString(RESULT_DESCRIPTION, description)
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "TagDescriptionDialogFragment"
        const val RESULT_TAG_NAME = "tagName"
        const val RESULT_DESCRIPTION = "description"

        /** 单条描述的最大字符数，控制发给 AI 建议服务的标签词表体积。 */
        const val MAX_LENGTH = 200

        private const val ARG_TAG_NAME = "arg_tag_name"
        private const val ARG_CURRENT_DESCRIPTION = "arg_current_description"
        private const val TAG = "TagDescriptionDialogFragment"

        /**
         * 在 [activity] 的 supportFragmentManager 上弹出——`TagManageActivity` 是纯 Activity，
         * 没有 childFragmentManager 可用，这点与 [TagEditDialogFragment.show] 不同。
         * 宿主监听结果用 `supportFragmentManager.setFragmentResultListener(REQUEST_KEY, ...)`。
         */
        fun show(activity: FragmentActivity, tagName: String, currentDescription: String) {
            TagDescriptionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAG_NAME, tagName)
                    putString(ARG_CURRENT_DESCRIPTION, currentDescription)
                }
            }.show(activity.supportFragmentManager, TAG)
        }
    }
}

@Composable
private fun TagDescriptionDialogContent(
    tagName: String,
    currentDescription: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(currentDescription) }

    DialogHeadline(stringResource(R.string.tag_description_dialog_title, tagName))
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= TagDescriptionDialogFragment.MAX_LENGTH) text = it },
        modifier = Modifier.padding(horizontal = 24.dp),
        placeholder = { Text(stringResource(R.string.tag_description_dialog_placeholder)) },
        supportingText = {
            Text(
                stringResource(
                    R.string.tag_description_dialog_count,
                    text.length,
                    TagDescriptionDialogFragment.MAX_LENGTH,
                ),
            )
        },
        minLines = 3,
        maxLines = 5,
    )
    Spacer(Modifier.height(8.dp))
    DialogActions(
        trailing = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            // 清空描述是有效操作（等同于"取消填写"），不做非空校验
            TextButton(onClick = { onConfirm(text.trim()) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun TagDescriptionDialogPreview() {
    BlitzTheme {
        DialogContainer {
            TagDescriptionDialogContent(
                tagName = "甜妹",
                currentDescription = "笑容甜美、气质清纯为主，不强调性感元素",
                onConfirm = {},
                onCancel = {},
            )
        }
    }
}
