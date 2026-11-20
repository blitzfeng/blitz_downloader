package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.blitz.downloader.R
import com.blitz.downloader.config.AiConnectionConfig
import com.blitz.downloader.config.AiService
import com.blitz.downloader.config.AppSettings

/** 密钥编辑值仅保存在组合内存中，确认后才写入应用偏好。 */
class AiProviderDialogFragment : ComposeDialogFragment() {
    @Composable
    override fun DialogContent() {
        val service = AiService.fromId(requireArguments().getString(SERVICE))
        val openAi = service == AiService.OPENAI
        val config = remember { AppSettings.getAiConnections(requireContext()).first { it.service == service } }
        var apiKey by remember { mutableStateOf(config.apiKey) }
        var model by rememberSaveable { mutableStateOf(config.model) }
        val cleanKey = apiKey.trim()
        val modelValid = AiConnectionConfig.isSupportedModel(model.trim(), service)
        val keyValid = cleanKey.all { it.code in 33..126 }
        DialogHeadline(stringResource(if (openAi) R.string.settings_openai_title else R.string.settings_vertex_title))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(
                stringResource(if (openAi) R.string.settings_openai_hint else R.string.settings_vertex_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.settings_ai_key_label, service.displayName)) },
                isError = !keyValid,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.settings_ai_model_label, service.displayName)) },
                supportingText = {
                    Text(stringResource(if (openAi) R.string.settings_openai_model_hint else R.string.settings_vertex_model_hint))
                },
                isError = !modelValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DialogActions(trailing = {
            TextButton(onClick = { dismiss() }) { Text(stringResource(android.R.string.cancel)) }
            TextButton(enabled = keyValid && (modelValid || cleanKey.isEmpty()), onClick = {
                val savedModel = if (modelValid) model.trim() else config.model
                if (openAi) {
                    AppSettings.setOpenAiConfig(requireContext(), cleanKey, savedModel)
                } else {
                    AppSettings.setVertexConfig(requireContext(), cleanKey, savedModel)
                }
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle())
                dismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        })
    }

    companion object {
        const val REQUEST_KEY = "AiProviderConfig"
        private const val SERVICE = "service"
        fun show(host: Fragment, service: AiService) {
            if (host.childFragmentManager.findFragmentByTag(REQUEST_KEY) != null) return
            AiProviderDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(SERVICE, service.id)
                }
            }.show(host.childFragmentManager, REQUEST_KEY)
        }
    }
}

@Composable
fun AiSettingsRow(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
