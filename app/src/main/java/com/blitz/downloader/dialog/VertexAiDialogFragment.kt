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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.blitz.downloader.R
import com.blitz.downloader.config.AiConnectionConfig
import com.blitz.downloader.config.AiService

/** 配置通过 FragmentResult 返回，取消与转屏不会提前改写偏好。 */
class VertexAiDialogFragment : ComposeDialogFragment() {
    @Composable
    override fun DialogContent() {
        var apiKey by rememberSaveable { mutableStateOf(requireArguments().getString(KEY).orEmpty()) }
        var model by rememberSaveable { mutableStateOf(requireArguments().getString(MODEL).orEmpty()) }
        var selected by rememberSaveable { mutableStateOf(requireArguments().getString(SERVICE).orEmpty()) }
        val modelValid = AiConnectionConfig.isSupportedModel(model.trim())
        DialogHeadline(stringResource(R.string.settings_vertex_title))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.settings_ai_service), style = MaterialTheme.typography.labelLarge)
            AiService.entries.forEach { service ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = service.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected == service.id, onClick = { selected = service.id })
                    Text(service.displayName)
                }
            }
            Text(stringResource(R.string.settings_vertex_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Vertex AI API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.settings_vertex_model)) },
                supportingText = { Text(stringResource(R.string.settings_vertex_model_hint)) },
                isError = !modelValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DialogActions(trailing = {
            TextButton(onClick = { dismiss() }) { Text(stringResource(android.R.string.cancel)) }
            TextButton(enabled = modelValid, onClick = {
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle().apply {
                    putString(KEY, apiKey.trim())
                    putString(MODEL, model.trim())
                    putString(SERVICE, selected)
                })
                dismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        })
    }

    companion object {
        const val REQUEST_KEY = "VertexAiConfig"
        const val KEY = "apiKey"
        const val MODEL = "model"
        const val SERVICE = "service"
        fun show(host: Fragment, key: String, model: String, service: AiService) {
            if (host.childFragmentManager.findFragmentByTag(REQUEST_KEY) != null) return
            VertexAiDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY, key)
                    putString(MODEL, model)
                    putString(SERVICE, service.id)
                }
            }.show(host.childFragmentManager, REQUEST_KEY)
        }
    }
}

@Composable
fun VertexAiSettingsRow(summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.settings_vertex_title), style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
