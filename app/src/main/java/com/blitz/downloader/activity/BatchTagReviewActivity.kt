package com.blitz.downloader.activity

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog as ComposeAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.blitz.downloader.R
import com.blitz.downloader.data.db.DownloadedVideoEntity
import com.blitz.downloader.ui.theme.BlitzTheme
import com.blitz.downloader.viewmodel.BatchTagReviewEvent
import com.blitz.downloader.viewmodel.BatchTagReviewUiState
import com.blitz.downloader.viewmodel.BatchTagReviewViewModel
import com.blitz.downloader.viewmodel.TagEditFilter
import com.blitz.downloader.viewmodel.TagReviewGroup
import java.io.File

/**
 * 批量下载后的 AI 标签整理页面（Compose / Material 3）。
 *
 * 遵循项目「新增 UI 一律 Compose」约定。
 * 加载最近批次全部视频与上一批次未打标视频，提供批量 LLM 分析入口、
 * 按建议标签分组批量确认/跳过、单视频取消勾选与按修改次数筛选。
 */
/**
 * 跟踪批量标签整理页面的会话打开状态，供管理页返回时触发列表刷新。
 */
object BatchTagReviewResultState {
    @Volatile
    private var needsRefresh = false

    fun notifyOpened() {
        needsRefresh = true
    }

    fun consumeNeedsRefresh(): Boolean {
        return if (needsRefresh) {
            needsRefresh = false
            true
        } else {
            false
        }
    }
}

class BatchTagReviewActivity : ComponentActivity() {

    private val viewModel: BatchTagReviewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        BatchTagReviewResultState.notifyOpened()
        setContent {
            BlitzTheme {
                BatchTagReviewScreen(
                    viewModel = viewModel,
                    onBackClick = {
                        setResult(RESULT_OK)
                        finish()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchTagReviewScreen(
    viewModel: BatchTagReviewViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showPreviewSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BatchTagReviewEvent.ShowAiDisabledHint -> {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.tag_edit_ai_suggest_button)
                        .setMessage(R.string.batch_tag_review_ai_disabled_hint)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                is BatchTagReviewEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showPreviewSheet) {
        CandidateVideosPreviewSheet(
            videos = uiState.allVideos,
            excludedIds = uiState.excludedAwemeIds,
            isAnalyzing = uiState.isAnalyzing,
            onToggleExclusion = { awemeId -> viewModel.toggleVideoExclusion(awemeId) },
            onRestoreAll = { viewModel.restoreAllExcludedVideos() },
            onDismiss = { showPreviewSheet = false },
        )
    }

    val onPlayVideo: (TagReviewGroup, DownloadedVideoEntity) -> Unit = remember(viewModel, context) {
        { group, currentVideo ->
            val storageRoot = Environment.getExternalStorageDirectory()
            @Suppress("DEPRECATION")
            val file = File(storageRoot, currentVideo.filePath)
            if (!file.exists()) {
                Toast.makeText(context, R.string.player_file_not_found, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.markVideoWatched(currentVideo.awemeId)
                val videos = group.videos
                val position = videos.indexOfFirst { it.awemeId == currentVideo.awemeId }.coerceAtLeast(0)
                val intent = VideoPlayerActivity.createListFileIntent(
                    context = context,
                    filePaths = ArrayList(videos.map { it.filePath }),
                    titles = ArrayList(videos.map { it.desc.trim().ifBlank { it.userName.ifBlank { it.awemeId } } }),
                    subtitles = ArrayList(videos.map { it.userName }),
                    position = position,
                    awemeIds = ArrayList(videos.map { it.awemeId }),
                )
                context.startActivity(intent)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.batch_tag_review_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back_arrow),
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showPreviewSheet = true },
                        enabled = !uiState.isReviewCompleted && !uiState.isAnalyzing,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_preview_eye),
                            contentDescription = stringResource(R.string.batch_tag_review_preview_entry),
                            modifier = Modifier.let {
                                if (uiState.isReviewCompleted || uiState.isAnalyzing) {
                                    it.alpha(0.38f)
                                } else it
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!uiState.hasBatches || uiState.allVideos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.batch_tag_review_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                BatchHeaderSection(
                    uiState = uiState,
                    onStartLlm = { viewModel.startBatchAnalysis() },
                    onOpenPreview = { showPreviewSheet = true },
                    onFilterChange = { viewModel.setEditFilter(it) },
                )

                GroupsListSection(
                    uiState = uiState,
                    onToggleVideo = { tag, id -> viewModel.toggleVideoSelection(tag, id) },
                    onInvertSelection = { viewModel.invertGroupSelection(it) },
                    onConfirmGroup = { viewModel.confirmGroup(it) },
                    onSkipGroup = { viewModel.skipGroup(it) },
                    onUndoGroup = { viewModel.undoGroup(it) },
                    onPlayVideo = onPlayVideo,
                )
            }
        }
    }
}

@Composable
private fun BatchHeaderSection(
    uiState: BatchTagReviewUiState,
    onStartLlm: () -> Unit,
    onOpenPreview: () -> Unit,
    onFilterChange: (TagEditFilter) -> Unit,
) {
    val totalCount = uiState.allVideos.size
    val excludedCount = uiState.excludedAwemeIds.size
    val activeCount = totalCount - excludedCount

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = when {
                    uiState.isReviewCompleted -> {
                        stringResource(
                            R.string.batch_tag_review_total_items_completed,
                            totalCount,
                        )
                    }
                    excludedCount > 0 -> {
                        stringResource(
                            R.string.batch_tag_review_total_items_with_excluded,
                            totalCount,
                            activeCount,
                            excludedCount,
                        )
                    }
                    else -> {
                        stringResource(
                            R.string.batch_tag_review_total_items,
                            totalCount,
                            uiState.latestBatchCount,
                            uiState.prevBatchUnlabeledCount,
                        )
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onStartLlm,
                    enabled = !uiState.isAnalyzing && !uiState.isReviewCompleted && activeCount > 0,
                ) {
                    Text(
                        text = if (uiState.isAnalyzing) {
                            stringResource(R.string.batch_tag_review_llm_running)
                        } else {
                            stringResource(R.string.batch_tag_review_start_llm)
                        },
                    )
                }

                OutlinedButton(
                    onClick = onOpenPreview,
                    enabled = !uiState.isAnalyzing && !uiState.isReviewCompleted,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_preview_eye),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.batch_tag_review_btn_preview))
                }
            }

            if (uiState.isAnalyzing && uiState.analysisProgress.total > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.batch_tag_review_llm_progress_format,
                            uiState.analysisProgress.done,
                            uiState.analysisProgress.total,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${(uiState.analysisProgress.done.toFloat() / uiState.analysisProgress.total.toFloat() * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        uiState.analysisProgress.done.toFloat() / uiState.analysisProgress.total.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 筛选标签修改次数
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagEditFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = uiState.editFilter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupsListSection(
    uiState: BatchTagReviewUiState,
    onToggleVideo: (String, String) -> Unit,
    onInvertSelection: (String) -> Unit,
    onConfirmGroup: (String) -> Unit,
    onSkipGroup: (String) -> Unit,
    onUndoGroup: (String) -> Unit,
    onPlayVideo: (TagReviewGroup, DownloadedVideoEntity) -> Unit,
) {
    var groupToUndo by remember { mutableStateOf<String?>(null) }

    if (groupToUndo != null) {
        val tagName = groupToUndo!!
        ComposeAlertDialog(
            onDismissRequest = { groupToUndo = null },
            title = { Text(stringResource(R.string.batch_tag_review_undo_title)) },
            text = { Text(stringResource(R.string.batch_tag_review_undo_message, tagName)) },
            confirmButton = {
                Button(
                    onClick = {
                        groupToUndo = null
                        onUndoGroup(tagName)
                    },
                ) {
                    Text(stringResource(R.string.batch_tag_review_undo_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToUndo = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (uiState.groups.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    uiState.isAnalyzing -> stringResource(R.string.batch_tag_review_llm_running)
                    uiState.isReviewCompleted -> stringResource(R.string.batch_tag_review_all_done)
                    else -> "暂无建议标签分组（请点击「开启 LLM 分析」获取 AI 建议）"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(uiState.groups, key = { it.tagName }) { group ->
                TagGroupCard(
                    group = group,
                    onToggleVideo = { awemeId -> onToggleVideo(group.tagName, awemeId) },
                    onInvertSelection = { onInvertSelection(group.tagName) },
                    onConfirm = { onConfirmGroup(group.tagName) },
                    onSkip = { onSkipGroup(group.tagName) },
                    onUndo = { groupToUndo = group.tagName },
                    onPlayVideo = { video -> onPlayVideo(group, video) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagGroupCard(
    group: TagReviewGroup,
    onToggleVideo: (String) -> Unit,
    onInvertSelection: () -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
    onPlayVideo: (DownloadedVideoEntity) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (group.isProcessed) {
                    Modifier.combinedClickable(
                        onClick = { /* no-op */ },
                        onLongClick = onUndo,
                    )
                } else {
                    Modifier
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (group.isProcessed) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.batch_tag_review_group_title,
                        group.tagName,
                        group.videos.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (group.isProcessed) {
                    val badgeText = if (group.taggedAwemeIds.size < group.videos.size) {
                        "已打标 ${group.taggedAwemeIds.size}/${group.videos.size} (长按撤销)"
                    } else {
                        stringResource(R.string.batch_tag_review_group_processed_badge)
                    }
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (!group.isProcessed) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.batch_tag_review_skip_group),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onInvertSelection,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.batch_tag_review_invert_selection),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.batch_tag_review_confirm_group),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 视频网格列表：固定一行 3 个
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val chunkedVideos = group.videos.chunked(3)
                chunkedVideos.forEach { rowVideos ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowVideos.forEach { video ->
                            val isChecked = group.selectedAwemeIds.contains(video.awemeId)
                            val isTagged = group.taggedAwemeIds.contains(video.awemeId)
                            Box(modifier = Modifier.weight(1f)) {
                                VideoItemThumbnail(
                                    video = video,
                                    isChecked = isChecked,
                                    isProcessed = group.isProcessed,
                                    isTagged = isTagged,
                                    onToggle = { onToggleVideo(video.awemeId) },
                                    onPlay = { onPlayVideo(video) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        repeat(3 - rowVideos.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoItemThumbnail(
    video: DownloadedVideoEntity,
    isChecked: Boolean,
    isProcessed: Boolean,
    isTagged: Boolean = false,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val storageRoot = Environment.getExternalStorageDirectory()
    @Suppress("DEPRECATION")
    val coverFile = File(storageRoot, video.coverPath)

    val borderColor = when {
        !isProcessed && isChecked -> MaterialTheme.colorScheme.primary
        isProcessed && isTagged -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isProcessed) 0.5f else 1f)
    }
    val borderWidth = if ((!isProcessed && isChecked) || (isProcessed && isTagged)) 2.dp else 1.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .then(
                if (!isProcessed) {
                    Modifier.clickable { onToggle() }
                } else {
                    Modifier
                }
            ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coverFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = video.desc,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // 已处理且未打标的视频，封面略降透明度，突出已打标项
                            if (isProcessed && !isTagged) Modifier.alpha(0.72f) else Modifier
                        ),
                )

                // 播放按钮：居中半透明圆形按钮
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClick = onPlay,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_arrow),
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }

                if (!isProcessed) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(32.dp),
                    )
                } else if (isTagged) {
                    // 已处理且确认打标：右上角显示精美圆底对号标记
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "已打标",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                if (video.tagEditCount > 0) {
                    Text(
                        text = "已改${video.tagEditCount}次",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f),
                                RoundedCornerShape(topEnd = 4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }

            Text(
                text = video.desc.ifBlank { video.userName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = if (isProcessed && !isTagged) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

private enum class CandidatePreviewFilter {
    ALL,
    INCLUDED,
    EXCLUDED,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateVideosPreviewSheet(
    videos: List<DownloadedVideoEntity>,
    excludedIds: Set<String>,
    isAnalyzing: Boolean,
    onToggleExclusion: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by rememberSaveable { mutableStateOf(CandidatePreviewFilter.ALL) }
    val includedCount = videos.count { it.awemeId !in excludedIds }
    val excludedCount = excludedIds.size

    val displayedVideos = remember(videos, excludedIds, filter) {
        when (filter) {
            CandidatePreviewFilter.ALL -> videos
            CandidatePreviewFilter.INCLUDED -> videos.filter { it.awemeId !in excludedIds }
            CandidatePreviewFilter.EXCLUDED -> videos.filter { it.awemeId in excludedIds }
        }
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
            // 顶栏：标题、统计与完成按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.batch_tag_review_preview_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (excludedCount > 0) {
                            stringResource(
                                R.string.batch_tag_review_preview_summary_excluded,
                                videos.size,
                                includedCount,
                                excludedCount,
                            )
                        } else {
                            stringResource(
                                R.string.batch_tag_review_preview_summary,
                                videos.size,
                                includedCount,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (excludedCount > 0 && !isAnalyzing) {
                        TextButton(onClick = onRestoreAll) {
                            Text(stringResource(R.string.batch_tag_review_preview_restore_all))
                        }
                    }
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.batch_tag_review_preview_done))
                    }
                }
            }

            // 筛选标签行：全部 / 待分析 / 已排除
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = filter == CandidatePreviewFilter.ALL,
                    onClick = { filter = CandidatePreviewFilter.ALL },
                    label = { Text(stringResource(R.string.batch_tag_review_preview_filter_all, videos.size)) },
                )
                FilterChip(
                    selected = filter == CandidatePreviewFilter.INCLUDED,
                    onClick = { filter = CandidatePreviewFilter.INCLUDED },
                    label = { Text(stringResource(R.string.batch_tag_review_preview_filter_included, includedCount)) },
                )
                if (excludedCount > 0) {
                    FilterChip(
                        selected = filter == CandidatePreviewFilter.EXCLUDED,
                        onClick = { filter = CandidatePreviewFilter.EXCLUDED },
                        label = { Text(stringResource(R.string.batch_tag_review_preview_filter_excluded, excludedCount)) },
                    )
                }
            }

            if (isAnalyzing) {
                Text(
                    text = stringResource(R.string.batch_tag_review_preview_analyzing_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 封面网格："只显示封面即可，可以选择移除某些item，不参与llm分析"
            if (displayedVideos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.batch_tag_review_no_videos_to_analyze),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayedVideos, key = { it.awemeId }) { video ->
                        val isExcluded = excludedIds.contains(video.awemeId)
                        CandidateCoverGridItem(
                            video = video,
                            isExcluded = isExcluded,
                            isAnalyzing = isAnalyzing,
                            onToggle = { onToggleExclusion(video.awemeId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCoverGridItem(
    video: DownloadedVideoEntity,
    isExcluded: Boolean,
    isAnalyzing: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val storageRoot = Environment.getExternalStorageDirectory()
    val coverFile = File(storageRoot, video.coverPath)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isExcluded) 1.dp else 1.5.dp,
                color = if (isExcluded) {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = !isAnalyzing) { onToggle() },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(coverFile)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .let { if (isExcluded) it.alpha(0.35f) else it },
        )

        if (isExcluded) {
            // 蒙层与已排除标记
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.batch_tag_review_preview_excluded_badge),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // 右上角恢复(+)图标
            if (!isAnalyzing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            RoundedCornerShape(13.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_white),
                        contentDescription = stringResource(R.string.batch_tag_review_preview_restore_all),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            // 右上角移除(X)图标
            if (!isAnalyzing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(13.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_manage),
                        contentDescription = "移除",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
