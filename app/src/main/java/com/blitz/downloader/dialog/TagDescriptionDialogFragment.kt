package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.blitz.downloader.R
import com.blitz.downloader.ui.theme.BlitzTheme

/**
 * 标签描述与 AI 属性设置编辑弹窗（Compose / Material 3）。
 *
 * 支持编辑：
 * - 标签描述文本（辅助 `ai-tag-suggestions` 的 AI 建议理解标签判断标准，限长 200 字符）
 * - 「参与 AI 分析」（enableAi，默认为 true；关闭后排除在候选词表外）
 * - 「子标签互斥单选」（isExclusive，仅当该标签存在子标签时可见；开启后 Prompt 约束至多推荐 1 项或父标签兜底）
 */
class TagDescriptionDialogFragment : ComposeDialogFragment() {

    private val tagName: String
        get() = requireArguments().getString(ARG_TAG_NAME).orEmpty()

    private val currentDescription: String
        get() = requireArguments().getString(ARG_CURRENT_DESCRIPTION).orEmpty()

    private val currentEnableAi: Boolean
        get() = requireArguments().getBoolean(ARG_ENABLE_AI, true)

    private val currentIsExclusive: Boolean
        get() = requireArguments().getBoolean(ARG_IS_EXCLUSIVE, false)

    private val hasChildren: Boolean
        get() = requireArguments().getBoolean(ARG_HAS_CHILDREN, false)

    @Composable
    override fun DialogContent() {
        TagDescriptionDialogContent(
            tagName = tagName,
            currentDescription = currentDescription,
            currentEnableAi = currentEnableAi,
            currentIsExclusive = currentIsExclusive,
            hasChildren = hasChildren,
            onConfirm = { description, enableAi, isExclusive ->
                finishWith(description, enableAi, isExclusive)
            },
            onCancel = { dismiss() },
        )
    }

    private fun finishWith(description: String, enableAi: Boolean, isExclusive: Boolean) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_TAG_NAME, tagName)
                putString(RESULT_DESCRIPTION, description)
                putBoolean(RESULT_ENABLE_AI, enableAi)
                putBoolean(RESULT_IS_EXCLUSIVE, isExclusive)
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "TagDescriptionDialogFragment"
        const val RESULT_TAG_NAME = "tagName"
        const val RESULT_DESCRIPTION = "description"
        const val RESULT_ENABLE_AI = "enableAi"
        const val RESULT_IS_EXCLUSIVE = "isExclusive"

        /** 单条描述的最大字符数，控制发给 AI 建议服务的标签词表体积。 */
        const val MAX_LENGTH = 200

        private const val ARG_TAG_NAME = "arg_tag_name"
        private const val ARG_CURRENT_DESCRIPTION = "arg_current_description"
        private const val ARG_ENABLE_AI = "arg_enable_ai"
        private const val ARG_IS_EXCLUSIVE = "arg_is_exclusive"
        private const val ARG_HAS_CHILDREN = "arg_has_children"
        private const val TAG = "TagDescriptionDialogFragment"

        /**
         * 在 [activity] 的 supportFragmentManager 上弹出。
         */
        fun show(
            activity: FragmentActivity,
            tagName: String,
            currentDescription: String,
            enableAi: Boolean = true,
            isExclusive: Boolean = false,
            hasChildren: Boolean = false,
        ) {
            TagDescriptionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAG_NAME, tagName)
                    putString(ARG_CURRENT_DESCRIPTION, currentDescription)
                    putBoolean(ARG_ENABLE_AI, enableAi)
                    putBoolean(ARG_IS_EXCLUSIVE, isExclusive)
                    putBoolean(ARG_HAS_CHILDREN, hasChildren)
                }
            }.show(activity.supportFragmentManager, TAG)
        }
    }
}

@Composable
private fun TagDescriptionDialogContent(
    tagName: String,
    currentDescription: String,
    currentEnableAi: Boolean,
    currentIsExclusive: Boolean,
    hasChildren: Boolean,
    onConfirm: (String, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(currentDescription) }
    var enableAi by rememberSaveable { mutableStateOf(currentEnableAi) }
    var isExclusive by rememberSaveable { mutableStateOf(currentIsExclusive) }

    DialogHeadline(stringResource(R.string.tag_description_dialog_title, tagName))
    Spacer(Modifier.height(12.dp))

    // Switch row: 参与 AI 建议分析
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = stringResource(R.string.tag_ai_enable_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.tag_ai_enable_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enableAi,
            onCheckedChange = { enableAi = it },
        )
    }

    if (hasChildren) {
        Spacer(Modifier.height(8.dp))
        // Switch row: 子标签互斥单选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.tag_exclusive_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.tag_exclusive_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = isExclusive,
                onCheckedChange = { isExclusive = it },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= TagDescriptionDialogFragment.MAX_LENGTH) text = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
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
            TextButton(onClick = { onConfirm(text.trim(), enableAi, isExclusive) }) {
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
                tagName = "颜值",
                currentDescription = "面容姣好，气质出众",
                currentEnableAi = true,
                currentIsExclusive = true,
                hasChildren = true,
                onConfirm = { _, _, _ -> },
                onCancel = {},
            )
        }
    }
}

