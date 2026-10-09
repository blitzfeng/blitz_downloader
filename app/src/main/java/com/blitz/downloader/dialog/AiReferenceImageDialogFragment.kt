package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.blitz.downloader.R
import com.blitz.downloader.util.DownloadedMediaFileManager

/** 预览原始参考图，不重新上传或生成分析。 */
class AiReferenceImageDialogFragment : ComposeDialogFragment() {
    @Composable
    override fun DialogContent() {
        val path = requireArguments().getString("path").orEmpty()
        var failed by remember(path) { mutableStateOf(false) }
        DialogHeadline(stringResource(R.string.ai_reference_image_title))
        Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.6f).dp)
            .padding(16.dp), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = remember(path) { DownloadedMediaFileManager.resolveFile(path) },
                contentDescription = stringResource(R.string.ai_reference_image_title),
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                onError = { failed = true }, onSuccess = { failed = false },
            )
            if (failed) Text(stringResource(R.string.ai_reference_image_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DialogActions(trailing = {
            TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.ai_reference_close)) }
        })
    }

    companion object {
        fun newInstance(path: String) = AiReferenceImageDialogFragment().apply {
            arguments = Bundle().apply { putString("path", path) }
        }
    }
}
