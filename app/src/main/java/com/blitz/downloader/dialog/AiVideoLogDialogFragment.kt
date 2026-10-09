package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.viewModels
import com.blitz.downloader.R
import com.blitz.downloader.ui.AiAnalysisLogContent
import com.blitz.downloader.viewmodel.TagEditDialogViewModel

/** 子弹窗只负责日志展示，复用父弹窗 ViewModel，关闭不会取消分析或重建标签勾选状态。 */
class AiVideoLogDialogFragment : ComposeDialogFragment() {
    private val viewModel: TagEditDialogViewModel by viewModels({ requireParentFragment() })

    @Composable
    override fun DialogContent() {
        val awemeId = requireArguments().getString("awemeId").orEmpty()
        val flow = remember(awemeId) { viewModel.logsForVideo(awemeId) }
        val logs by flow.collectAsState(initial = emptyList())
        AiAnalysisLogContent(
            logs = logs,
            onClearLogs = { viewModel.clearVideoLogs(awemeId) },
            emptyMessage = stringResource(R.string.tag_edit_ai_logs_empty),
        )
        TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.tag_edit_ai_logs_close)) }
    }

    companion object {
        fun newInstance(awemeId: String) = AiVideoLogDialogFragment().apply {
            arguments = Bundle().apply { putString("awemeId", awemeId) }
        }
    }
}
