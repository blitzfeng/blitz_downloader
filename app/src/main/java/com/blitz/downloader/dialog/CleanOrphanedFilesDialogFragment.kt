package com.blitz.downloader.dialog

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.blitz.downloader.R
import com.blitz.downloader.util.NumberFormatUtils
import com.blitz.downloader.util.OrphanMediaFile
import com.blitz.downloader.util.OrphanMediaKind
import com.blitz.downloader.viewmodel.CleanOrphanEvent
import com.blitz.downloader.viewmodel.CleanOrphanUiState
import com.blitz.downloader.viewmodel.CleanOrphanedFilesViewModel

/**
 * 清理未记录的残留本地文件弹窗（Compose / Material 3）。
 *
 * 扫描下载目录中没有数据库记录引用的孤儿文件（视频、图集图片、实况图动图本体、独立封面），
 * 列表呈现供用户查看，并支持一键或勾选删除以释放存储空间。
 */
class CleanOrphanedFilesDialogFragment : ComposeDialogFragment() {

    private val viewModel: CleanOrphanedFilesViewModel by viewModels()

    @Composable
    override fun DialogContent() {
        val context = LocalContext.current
        val state by viewModel.uiState.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.events.collect { event ->
                when (event) {
                    is CleanOrphanEvent.DeleteDone -> {
                        val freedStr = NumberFormatUtils.formatFileSize(event.freedBytes)
                        Toast.makeText(
                            context,
                            context.getString(R.string.orphan_cleaner_done_toast, event.count, freedStr),
                            Toast.LENGTH_SHORT,
                        ).show()
                        parentFragmentManager.setFragmentResult(
                            REQUEST_KEY,
                            Bundle().apply { putInt(RESULT_DELETED_COUNT, event.count) },
                        )
                        dismiss()
                    }
                }
            }
        }

        DialogHeadline(stringResource(R.string.orphan_cleaner_title))
        Spacer(Modifier.height(12.dp))

        when (val s = state) {
            is CleanOrphanUiState.Scanning -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.orphan_cleaner_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is CleanOrphanUiState.Empty -> {
                Text(
                    text = stringResource(R.string.orphan_cleaner_empty_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                DialogActions(trailing = {
                    TextButton(onClick = { dismiss() }) {
                        Text(stringResource(R.string.orphan_cleaner_btn_close))
                    }
                })
            }

            is CleanOrphanUiState.Ready -> {
                val totalBytes = s.files.sumOf { it.sizeBytes }
                val selectedFiles = s.files.filter { it.file.absolutePath in s.selectedPaths }
                val selectedBytes = selectedFiles.sumOf { it.sizeBytes }
                val allSelected = s.selectedPaths.size == s.files.size && s.files.isNotEmpty()

                Text(
                    text = stringResource(
                        R.string.orphan_cleaner_found_summary,
                        s.files.size,
                        NumberFormatUtils.formatFileSize(totalBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.orphan_cleaner_selected_summary,
                            selectedFiles.size,
                            NumberFormatUtils.formatFileSize(selectedBytes),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = { viewModel.selectAll(!allSelected) },
                        enabled = !s.isDeleting,
                    ) {
                        Text(
                            text = stringResource(
                                if (allSelected) R.string.orphan_cleaner_deselect_all
                                else R.string.orphan_cleaner_select_all,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    items(s.files, key = { it.file.absolutePath }) { item ->
                        OrphanFileItemRow(
                            item = item,
                            isSelected = item.file.absolutePath in s.selectedPaths,
                            enabled = !s.isDeleting,
                            onToggle = { viewModel.toggleSelection(item.file.absolutePath) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                DialogActions(
                    trailing = {
                        TextButton(
                            onClick = { dismiss() },
                            enabled = !s.isDeleting,
                        ) {
                            Text(stringResource(R.string.manage_confirm_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.deleteSelected() },
                            enabled = s.selectedPaths.isNotEmpty() && !s.isDeleting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            if (s.isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onError,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.orphan_cleaner_deleting))
                            } else {
                                Text(stringResource(R.string.orphan_cleaner_btn_delete, selectedFiles.size))
                            }
                        }
                    },
                )
            }
        }
    }

    companion object {
        const val TAG = "CleanOrphanedFilesDialog"
        const val REQUEST_KEY = "CleanOrphanedFilesDialogFragment"
        const val RESULT_DELETED_COUNT = "result_deleted_count"

        fun newInstance() = CleanOrphanedFilesDialogFragment()
    }
}

@Composable
private fun OrphanFileItemRow(
    item: OrphanMediaFile,
    isSelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            Color.Transparent
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
            Spacer(Modifier.width(8.dp))
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.file)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OrphanKindBadge(item.mediaKind)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = NumberFormatUtils.formatFileSize(item.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrphanKindBadge(kind: OrphanMediaKind) {
    val (labelRes, bg, fg) = when (kind) {
        OrphanMediaKind.VIDEO -> Triple(
            R.string.orphan_cleaner_kind_video,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        OrphanMediaKind.IMAGE -> Triple(
            R.string.orphan_cleaner_kind_image,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        OrphanMediaKind.COVER -> Triple(
            R.string.orphan_cleaner_kind_cover,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
