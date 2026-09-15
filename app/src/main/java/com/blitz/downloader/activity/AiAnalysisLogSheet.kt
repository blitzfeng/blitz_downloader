package com.blitz.downloader.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blitz.downloader.R
import com.blitz.downloader.data.db.AppDatabase
import com.blitz.downloader.llm.AiAnalysisLogEntry
import com.blitz.downloader.llm.AiAnalysisLogFormatter
import com.blitz.downloader.llm.AiAnalysisLogStatus
import com.blitz.downloader.llm.TagSuggestionRequestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAnalysisLogSheet(
    logs: List<AiAnalysisLogEntry>,
    onDismiss: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var showPromptTemplateDialog by remember { mutableStateOf(false) }

    if (showPromptTemplateDialog) {
        PromptPreviewDialog(
            onDismiss = { showPromptTemplateDialog = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            // 顶栏：标题、统计与批量操作
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.batch_tag_review_log_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "${logs.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showPromptTemplateDialog = true }) {
                        Text(
                            text = "Prompt 模板",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    if (logs.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val text = AiAnalysisLogFormatter.formatAllEntriesToPlainText(logs)
                                copyToClipboard(context, "AiAnalysisLogs", text)
                                Toast.makeText(context, R.string.batch_tag_review_log_copied, Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.batch_tag_review_log_copy_all),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        TextButton(onClick = onClearLogs) {
                            Text(
                                text = stringResource(R.string.batch_tag_review_log_clear),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.batch_tag_review_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(logs.reversed(), key = { it.id }) { entry ->
                        AiAnalysisLogCard(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
fun AiAnalysisLogCard(entry: AiAnalysisLogEntry) {
    var expanded by remember { mutableStateOf(false) }
    var showFullPrompt by remember { mutableStateOf(false) }
    var showRawRequest by remember { mutableStateOf(false) }
    var showRawResponse by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(14.dp),
        ) {
            // 头部：状态徽章、耗时、标题与复制单条
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    LogStatusBadge(status = entry.status)
                    if (entry.durationMs > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${entry.durationMs} ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = entry.videoTitle.ifBlank { entry.awemeId },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val text = AiAnalysisLogFormatter.formatEntryToPlainText(entry)
                            copyToClipboard(context, "AiLog-${entry.awemeId}", text)
                            Toast.makeText(context, R.string.batch_tag_review_log_copied, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy_content),
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = if (expanded) "▲ 收起" else "▼ 详情",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            // 摘要标签栏：空格分开
            if (entry.suggestedTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entry.suggestedTags.forEach { tag ->
                        val pct = (tag.confidence * 100).toInt()
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = "${tag.tagName}   ${pct}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            } else if (entry.status == AiAnalysisLogStatus.FAILED) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "失败: ${entry.errorMessage ?: "调用异常"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 展开详情区域
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // 1. 请求数据区
                    SectionHeader(title = "【接口请求数据】")
                    Spacer(modifier = Modifier.height(4.dp))
                    if (entry.authorName.isNotBlank()) {
                        DetailField(label = "视频作者", content = entry.authorName)
                    }
                    val displayDesc = when {
                        entry.videoDesc.isNotBlank() -> entry.videoDesc
                        entry.videoTitle.isNotBlank() && entry.videoTitle != entry.awemeId -> entry.videoTitle
                        else -> "(无文案)"
                    }
                    DetailField(label = "视频文案", content = displayDesc)
                    if (entry.requestAuthorTagsSummary.isNotBlank()) {
                        DetailField(label = "作者高频标签", content = entry.requestAuthorTagsSummary)
                    }
                    if (entry.requestEvidenceSamplesSummary.isNotBlank()) {
                        DetailField(label = "历史审核参考", content = entry.requestEvidenceSamplesSummary)
                    }
                    if (entry.requestFramesSummary.isNotBlank()) {
                        DetailField(label = "抽取图片", content = entry.requestFramesSummary)
                    }
                    if (entry.requestVocabularySummary.isNotBlank()) {
                        DetailField(label = "可选词表", content = entry.requestVocabularySummary)
                    }
                    if (entry.requestPrompt.isNotBlank()) {
                        DetailField(label = "提示词目标", content = entry.requestPrompt)
                    }
                    val promptToDisplay = entry.fullPrompt.ifBlank { entry.requestPrompt }
                    if (promptToDisplay.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showFullPrompt = !showFullPrompt },
                        ) {
                            Text(
                                text = if (showFullPrompt) "▼ 隐藏请求完整 Prompt" else "▶ 查看请求完整 Prompt（含词表与描述）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (showFullPrompt) {
                            PromptBox(prompt = promptToDisplay)
                        }
                    }
                    if (entry.rawRequestBody.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showRawRequest = !showRawRequest },
                        ) {
                            Text(
                                text = if (showRawRequest) "▼ 隐藏请求脱敏 JSON" else "▶ 查看请求脱敏 JSON",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (showRawRequest) {
                            JsonBox(json = entry.rawRequestBody)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. 接口返回区
                    SectionHeader(title = "【接口返回数据】")
                    Spacer(modifier = Modifier.height(4.dp))
                    if (entry.status == AiAnalysisLogStatus.FAILED) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = "调用失败：${entry.errorMessage ?: "未知错误"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    } else {
                        DetailField(
                            label = "推荐标签",
                            content = AiAnalysisLogFormatter.formatCandidates(entry.suggestedTags),
                        )
                        if (entry.visualFeatureProfile != null) {
                            DetailField(
                                label = "视觉观察",
                                content = AiAnalysisLogFormatter.formatVisualProfile(entry.visualFeatureProfile),
                            )
                        }
                        if (!entry.tokenUsage.isNullOrBlank()) {
                            DetailField(label = "Token 统计", content = entry.tokenUsage)
                        }
                        if (entry.rawResponseBody.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showRawResponse = !showRawResponse },
                            ) {
                                Text(
                                    text = if (showRawResponse) "▼ 隐藏原始返回 JSON" else "▶ 查看原始返回 JSON",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (showRawResponse) {
                                JsonBox(json = entry.rawResponseBody)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DetailField(label: String, content: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "• $label:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp, top = 2.dp),
        )
    }
}

@Composable
private fun JsonBox(json: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E),
    ) {
        Text(
            text = json,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            color = Color(0xFFE0E0E0),
            modifier = Modifier
                .padding(10.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun PromptBox(prompt: String) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "完整 Prompt 内容 (${prompt.length} 字)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9E9E9E),
                )
                TextButton(
                    onClick = {
                        copyToClipboard(context, "AiPrompt", prompt)
                        Toast.makeText(context, "Prompt 已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text(
                        text = "复制 Prompt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = Color(0xFFE0E0E0),
                )
            }
        }
    }
}

@Composable
private fun PromptPreviewDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var promptText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val tags = runCatching {
                AppDatabase.getInstance(context).tagDao().getAllEntities()
            }.getOrDefault(emptyList())
            promptText = TagSuggestionRequestBuilder.buildPreviewPrompt(tags)
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "AI 请求 Prompt 模板与词表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "当前发送给大模型的固定角色设定、视觉维度定义、打标原则、标签词表与分类单选互斥规则：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else {
                    PromptBox(prompt = promptText)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (promptText.isNotBlank()) {
                        copyToClipboard(context, "AiPromptTemplate", promptText)
                        Toast.makeText(context, "Prompt 模板已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text("复制 Prompt")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun LogStatusBadge(status: AiAnalysisLogStatus) {
    when (status) {
        AiAnalysisLogStatus.RUNNING -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xFFE3F2FD), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = Color(0xFF1976D2),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "生成中",
                    color = Color(0xFF1976D2),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        AiAnalysisLogStatus.SUCCESS -> {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFE8F5E9),
            ) {
                Text(
                    text = "成功",
                    color = Color(0xFF2E7D32),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        AiAnalysisLogStatus.FAILED -> {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFFFEBEE),
            ) {
                Text(
                    text = "失败",
                    color = Color(0xFFC62828),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
