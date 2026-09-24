package com.blitz.downloader.dialog

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blitz.downloader.BlitzApp
import com.blitz.downloader.R
import com.blitz.downloader.data.CameraVideoOrganizer
import com.blitz.downloader.model.CameraMoveOutcome
import com.blitz.downloader.model.CameraOrganizationPhase
import com.blitz.downloader.model.CameraOrganizationState
import kotlinx.coroutines.launch

@Composable
fun CameraOrganizationEntry(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(R.color.color_card_background)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.camera_organize_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.camera_organize_summary), style = MaterialTheme.typography.bodySmall)
        }
    }
}

class CameraOrganizationDialogFragment : ComposeDialogFragment() {
    private val organizer get() = BlitzApp.instance.cameraVideoOrganizer
    private var current by mutableStateOf(CameraOrganizationState())
    private var notice by mutableStateOf<String?>(null)

    private val allFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (organizer.hasAccess()) organizer.scan() else notice = getString(R.string.camera_organize_permission_denied)
    }
    private val legacy = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (organizer.hasAccess()) organizer.scan() else notice = getString(R.string.camera_organize_permission_denied)
    }
    private val sourceTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> acceptTree(uri, true) }
    private val downloadTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> acceptTree(uri, false) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        current = organizer.state.value
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                organizer.state.collect { current = it }
            }
        }
    }

    private fun acceptTree(uri: Uri?, source: Boolean) {
        if (uri == null) {
            notice = getString(R.string.camera_organize_permission_denied)
            return
        }
        runCatching { organizer.saveTree(uri, source) }
            .onSuccess { scanOrAuthorize() }
            .onFailure { notice = it.message }
    }

    private fun scanOrAuthorize() {
        notice = null
        runCatching {
            when {
                organizer.hasAccess() -> organizer.scan()
                Build.VERSION.SDK_INT >= 30 -> {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
                    val available = intent.resolveActivity(requireContext().packageManager) != null
                    allFiles.launch(if (available) intent else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                Build.VERSION.SDK_INT == 29 -> {
                    if (!organizer.hasTree(true)) {
                        notice = getString(R.string.camera_organize_select_source)
                        sourceTree.launch(null)
                    } else {
                        notice = getString(R.string.camera_organize_select_download)
                        downloadTree.launch(null)
                    }
                }
                else -> legacy.launch(CameraVideoOrganizer.legacyPermissions)
            }
        }.onFailure { notice = it.message ?: getString(R.string.camera_organize_permission_denied) }
    }

    private fun sendResult(confirm: Boolean) {
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf("batch" to current.batchId, "confirm" to confirm))
    }

    override fun onCancel(dialog: DialogInterface) {
        sendResult(false)
        super.onCancel(dialog)
    }

    @Composable
    override fun DialogContent() {
        DialogHeadline(stringResource(R.string.camera_organize_title))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.camera_organize_rules), style = MaterialTheme.typography.bodySmall)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            current.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (current.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (current.phase == CameraOrganizationPhase.SCANNING) stringResource(R.string.camera_organize_scanning)
                    else stringResource(R.string.camera_organize_progress, current.results.size, current.candidates.size))
                current.currentName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (current.phase == CameraOrganizationPhase.IDLE) {
                Text(stringResource(R.string.camera_organize_access_help), style = MaterialTheme.typography.bodySmall)
            }
            if (current.phase == CameraOrganizationPhase.PREVIEW) {
                Text(if (current.candidates.isEmpty()) stringResource(R.string.camera_organize_empty)
                    else stringResource(R.string.camera_organize_preview, current.candidates.size,
                        Formatter.formatFileSize(requireContext(), current.candidates.sumOf { it.size.coerceAtLeast(0) })))
            }
            if (current.phase == CameraOrganizationPhase.FINISHED && current.candidates.isNotEmpty()) {
                Text(stringResource(R.string.camera_organize_result,
                    current.results.count { it.outcome == CameraMoveOutcome.SUCCESS },
                    current.results.count { it.outcome == CameraMoveOutcome.SKIPPED },
                    current.results.count { it.outcome == CameraMoveOutcome.FAILED }, current.unprocessed))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.phase == CameraOrganizationPhase.PREVIEW) {
                    items(current.candidates, key = { it.id }) { item ->
                        Column {
                            Text(item.name + " · " + Formatter.formatFileSize(requireContext(), item.size.coerceAtLeast(0)), style = MaterialTheme.typography.bodyMedium)
                            Text("→ Download/${com.blitz.downloader.model.CameraVideoRules.destinationFolder(item.mime)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    items(current.results) { result ->
                        Column {
                            val label = when (result.outcome) {
                                CameraMoveOutcome.SUCCESS -> R.string.camera_organize_success
                                CameraMoveOutcome.SKIPPED -> R.string.camera_organize_skipped
                                CameraMoveOutcome.FAILED -> R.string.camera_organize_failed
                            }
                            Text("${result.source} · ${stringResource(label)}")
                            result.destination?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            result.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            result.indexWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (current.leftovers.isNotEmpty()) {
                    item { Text(stringResource(R.string.camera_organize_leftovers), color = MaterialTheme.colorScheme.error) }
                    items(current.leftovers) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        DialogActions {
            TextButton(onClick = { sendResult(false); dismiss() }) { Text(stringResource(R.string.camera_organize_close)) }
            if (current.phase == CameraOrganizationPhase.PREVIEW && current.candidates.isNotEmpty()) {
                TextButton(onClick = { sendResult(true) }) { Text(stringResource(R.string.camera_organize_confirm)) }
            } else if (!current.busy) {
                TextButton(onClick = { sendResult(false); scanOrAuthorize() }) { Text(stringResource(R.string.camera_organize_scan)) }
            }
        }
    }

    companion object { const val REQUEST_KEY = "camera_organization_action" }
}
