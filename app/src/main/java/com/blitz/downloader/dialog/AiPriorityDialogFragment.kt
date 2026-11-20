package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.blitz.downloader.R
import com.blitz.downloader.config.AiService
import com.blitz.downloader.config.AppSettings

class AiPriorityDialogFragment : ComposeDialogFragment() {
    @Composable
    override fun DialogContent() {
        val configs = remember { AppSettings.getAiConnections(requireContext()) }
        var order by rememberSaveable {
            mutableStateOf(ArrayList(configs.filter { it.validationError == null }.map { it.service.id }))
        }

        fun move(from: Int, to: Int) {
            order = ArrayList(order).apply { add(to, removeAt(from)) }
        }

        DialogHeadline(stringResource(R.string.settings_ai_priority_title))
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                stringResource(if (order.size < 2) R.string.settings_ai_priority_need_keys else R.string.settings_ai_priority_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            order.forEachIndexed { index, id ->
                val service = AiService.fromId(id)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${service.displayName}", Modifier.weight(1f))
                    TextButton(enabled = index > 0, onClick = { move(index, index - 1) }) {
                        Text(stringResource(R.string.settings_ai_priority_up))
                    }
                    TextButton(enabled = index < order.lastIndex, onClick = { move(index, index + 1) }) {
                        Text(stringResource(R.string.settings_ai_priority_down))
                    }
                }
            }
        }
        DialogActions(trailing = {
            TextButton(onClick = { dismiss() }) { Text(stringResource(android.R.string.cancel)) }
            TextButton(enabled = order.size > 1, onClick = {
                val active = order.map(AiService::fromId)
                val inactive = configs.map { it.service }.filterNot { it in active }
                AppSettings.setAiPriority(requireContext(), active + inactive)
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle())
                dismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        })
    }

    companion object {
        const val REQUEST_KEY = "AiPriority"
        fun show(host: Fragment) {
            if (host.childFragmentManager.findFragmentByTag(REQUEST_KEY) != null) return
            AiPriorityDialogFragment().show(host.childFragmentManager, REQUEST_KEY)
        }
    }
}
