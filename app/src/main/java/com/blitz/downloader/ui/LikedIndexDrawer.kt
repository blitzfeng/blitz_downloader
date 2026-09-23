package com.blitz.downloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 点赞浏览控制集中在抽屉内，关闭后不占列表空间。 */
@Composable
fun LikedIndexDrawer(
    onContinue: () -> Unit,
    onReset: () -> Unit,
) {
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("点赞索引", style = MaterialTheme.typography.headlineSmall)
            Text("继续浏览：回到上次附近，向下滑动自动加载更多。首次使用会从头开始。")
            Text("重置：清除当前用户的浏览缓存和位置，从最新点赞重新开始。")
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("继续浏览") }
            OutlinedButton(onClick = { confirmRestart = true }, modifier = Modifier.fillMaxWidth()) { Text("重置") }
            if (confirmRestart) {
                Text("将清除当前用户的点赞浏览缓存和位置，并从头加载。")
                Row {
                    TextButton(onClick = { confirmRestart = false }) { Text("取消") }
                    TextButton(onClick = { confirmRestart = false; onReset() }) { Text("确认重置") }
                }
            }
        }
    }
}
