package com.blitz.downloader.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 标签多选栅格：两个标签弹窗（单条编辑 / 多选批量）共用。
 *
 * 固定两列——标签数量偏多，单列会把弹窗拉得很长、还得一直滚；超出 [maxHeight] 才滚动。
 */
@Composable
fun TagCheckGrid(
    allTags: List<String>,
    checked: List<String>,
    onToggle: (String) -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp = 320.dp,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.heightIn(max = maxHeight),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(allTags, key = { it }) { tag ->
            TagCheckRow(tag = tag, checked = tag in checked, onToggle = { onToggle(tag) })
        }
    }
}

@Composable
private fun TagCheckRow(tag: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 白色行 + 浅紫容器（surfaceContainerHigh）的对比，让每个标签自成一张可点的卡片；
            // background 必须排在 toggleable 之前，否则白底会盖住点击水波
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onCheckedChange = null：点击语义交给整行的 toggleable，避免读屏时读出两个可点目标
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = tag,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            // 两列宽度有限，长标签截断而不是把复选框挤出格子
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 记住勾选集合，转屏 / 进程重建后仍在。
 *
 * [initial] 只在首次组合时生效（恢复时用保存下来的那份），所以「单条编辑」预勾选当前标签、
 * 「批量添加」从空开始，都用这一个函数。
 */
@Composable
fun rememberCheckedTags(initial: Collection<String> = emptyList()): SnapshotStateList<String> =
    rememberSaveable(saver = checkedTagsSaver) { initial.toMutableStateList() }

private val checkedTagsSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)
