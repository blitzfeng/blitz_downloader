package com.blitz.downloader.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.blitz.downloader.R
import com.blitz.downloader.dialog.AiReferenceImageDialogFragment
import com.blitz.downloader.model.AiReferenceVideo
import com.blitz.downloader.model.filterAiReferenceVideos
import com.blitz.downloader.ui.theme.BlitzTheme
import com.blitz.downloader.util.DownloadedMediaFileManager
import com.blitz.downloader.viewmodel.AiReferenceManageEvent
import com.blitz.downloader.viewmodel.AiReferenceManageViewModel

/** 管理反馈案例的参考资格；移除不改动视频、标签或真实审核历史。 */
class AiReferenceManageActivity : AppCompatActivity() {
    private val viewModel: AiReferenceManageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_VIDEO).orEmpty()
        viewModel.load(sourceId)
        setContent {
            BlitzTheme {
                AiReferenceManageScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onRetry = { viewModel.load(sourceId) },
                    onPreview = { path ->
                        if (supportFragmentManager.findFragmentByTag("AiReferenceImage") == null) {
                            AiReferenceImageDialogFragment.newInstance(path)
                                .show(supportFragmentManager, "AiReferenceImage")
                        }
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_SOURCE_VIDEO = "sourceAwemeId"
        fun createIntent(context: Context, sourceAwemeId: String = "") =
            Intent(context, AiReferenceManageActivity::class.java).putExtra(EXTRA_SOURCE_VIDEO, sourceAwemeId)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AiReferenceManageScreen(
    viewModel: AiReferenceManageViewModel,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPreview: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    var allAuthors by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val visible = remember(state.videos, state.authorId, query, showRemoved, allAuthors) {
        filterAiReferenceVideos(state.videos, query, state.authorId.takeUnless { allAuthors }, showRemoved)
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(visible) {
        val visibleIds = visible.map { it.awemeId }.toSet()
        selected = selected.filter { it in visibleIds }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AiReferenceManageEvent.Saved -> {
                    selected = emptyList()
                    snackbar.showSnackbar(context.getString(
                        if (event.excluded) R.string.ai_reference_removed_count else R.string.ai_reference_restored_count,
                        event.count,
                    ))
                }
                is AiReferenceManageEvent.Failed -> snackbar.showSnackbar(
                    context.getString(R.string.ai_reference_save_failed, event.message),
                )
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_reference_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back_arrow), contentDescription = stringResource(R.string.ai_reference_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        enabled = visible.isNotEmpty() && !state.busy,
                        onClick = { selected = if (selected.size == visible.size) emptyList() else visible.map { it.awemeId } },
                    ) {
                        Text(stringResource(if (visible.isNotEmpty() && selected.size == visible.size)
                            R.string.ai_reference_deselect_all else R.string.ai_reference_select_all))
                    }
                    Button(enabled = selected.isNotEmpty() && !state.busy, onClick = {
                        viewModel.setExcluded(selected, !showRemoved)
                    }) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(if (showRemoved) R.string.ai_reference_restore_selected
                            else R.string.ai_reference_remove_selected, selected.size))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.ai_reference_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; selected = emptyList() },
                enabled = !state.busy,
                label = { Text(stringResource(R.string.ai_reference_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.authorId.isNotBlank()) {
                    FilterChip(selected = !allAuthors, onClick = { allAuthors = false; selected = emptyList() },
                        enabled = !state.busy, label = { Text(state.authorName.ifBlank { stringResource(R.string.ai_reference_current_author) }) })
                    FilterChip(selected = allAuthors, onClick = { allAuthors = true; selected = emptyList() },
                        enabled = !state.busy, label = { Text(stringResource(R.string.ai_reference_all_authors)) })
                }
                FilterChip(selected = !showRemoved, onClick = { showRemoved = false; selected = emptyList() },
                    enabled = !state.busy, label = { Text(stringResource(R.string.ai_reference_available)) })
                FilterChip(selected = showRemoved, onClick = { showRemoved = true; selected = emptyList() },
                    enabled = !state.busy, label = { Text(stringResource(R.string.ai_reference_removed)) })
            }
            Text(stringResource(R.string.ai_reference_count, visible.size, selected.size),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.ai_reference_load_failed, state.loadError.orEmpty()), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.ai_reference_retry)) }
                }
                visible.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ai_reference_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.awemeId }) { video ->
                        AiReferenceCard(video, video.awemeId in selected, !state.busy, onToggle = {
                            selected = if (video.awemeId in selected) selected - video.awemeId else selected + video.awemeId
                        }, onPreview = onPreview)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiReferenceCard(
    video: AiReferenceVideo,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onPreview: (String) -> Unit,
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(video.authorName.ifBlank { stringResource(R.string.ai_reference_unknown_author) }, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (video.excluded) R.string.ai_reference_removed else R.string.ai_reference_available),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(video.desc.ifBlank { stringResource(R.string.ai_reference_no_caption) }, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.ai_reference_video_id, video.awemeId), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (video.acceptedTags.isNotEmpty()) Text(stringResource(R.string.ai_reference_accepted, video.acceptedTags.joinToString("、")))
            if (video.rejectedTags.isNotEmpty()) Text(stringResource(R.string.ai_reference_rejected, video.rejectedTags.joinToString("、")))
            if (video.missedTags.isNotEmpty()) Text(stringResource(R.string.ai_reference_missed, video.missedTags.joinToString("、")))
            if (video.evidencePaths.isEmpty()) {
                Text(stringResource(R.string.ai_reference_no_image), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.ai_reference_images), style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(video.evidencePaths) { path ->
                        var failed by remember(path) { mutableStateOf(false) }
                        Box(Modifier.size(96.dp).clickable { onPreview(path) }, contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = remember(path) { DownloadedMediaFileManager.resolveFile(path) },
                                contentDescription = stringResource(R.string.ai_reference_images),
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                onError = { failed = true }, onSuccess = { failed = false },
                            )
                            if (failed) Text(stringResource(R.string.ai_reference_image_missing), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
