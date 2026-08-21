package com.blitz.downloader.dialog

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.blitz.downloader.R
import com.blitz.downloader.ui.LivePhotoPlayer

/**
 * 图集选图 / 预览弹窗（Compose / Material 3），窗口层面的处理见 [ComposeDialogFragment]。
 * 替代旧的 View 版 `PhotoSelectionBottomSheet`——横向滑动逐张预览，**实况图（动图）自动循环播放**
 * （[imageVideoUrls] 里该张 mp4 非空即为动图，数据源是**网络 URL**、还没下载，走网络流）。
 *
 * 语义与旧版一致：[editable] 时每张一个勾选框、**默认全选**，结果归一化——全选返回 `null`
 * （而不是全量下标集合），一张都没选返回空集合；[editable] 为 false 时纯预览、不回结果。
 *
 * 结果走 `FragmentResult`（[REQUEST_KEY]）：记录 id 原样回传，宿主用
 * `childFragmentManager.setFragmentResultListener` 接收。勾选状态用 `rememberSaveable`，转屏不丢。
 */
class PhotoSelectionDialogFragment : ComposeDialogFragment() {

    private val awemeId: String
        get() = requireArguments().getString(ARG_ID).orEmpty()

    private val imageUrls: List<String>
        get() = requireArguments().getStringArrayList(ARG_IMAGE_URLS).orEmpty()

    /** 与 [imageUrls] 等长；空串代表该张是普通静态图（无动图 mp4）。 */
    private val videoUrls: List<String>
        get() = requireArguments().getStringArrayList(ARG_VIDEO_URLS).orEmpty()

    private val initialSelection: Set<Int>?
        get() = requireArguments().getIntArray(ARG_INITIAL)?.toSet()

    private val editable: Boolean
        get() = requireArguments().getBoolean(ARG_EDITABLE)

    @Composable
    override fun DialogContent() {
        PhotoSelectionContent(
            imageUrls = imageUrls,
            videoUrls = videoUrls,
            initialSelection = initialSelection,
            editable = editable,
            onDone = { allSelected, indices ->
                if (editable) finishWith(allSelected, indices)
                dismiss()
            },
        )
    }

    /** editable 时回传结果：allSelected=true → 宿主侧解释为「全选(null)」；否则回传具体下标集合。 */
    private fun finishWith(allSelected: Boolean, indices: IntArray) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_ID, awemeId)
                putBoolean(RESULT_ALL, allSelected)
                putIntArray(RESULT_INDICES, indices)
            },
        )
    }

    companion object {
        const val REQUEST_KEY = "PhotoSelectionDialogFragment"

        const val RESULT_ID = "id"

        /** true = 全选（宿主解释为 `null`）；false 时看 [RESULT_INDICES]（空数组 = 一张都没选）。 */
        const val RESULT_ALL = "all"
        const val RESULT_INDICES = "indices"

        private const val ARG_ID = "arg_id"
        private const val ARG_IMAGE_URLS = "arg_image_urls"
        private const val ARG_VIDEO_URLS = "arg_video_urls"
        private const val ARG_INITIAL = "arg_initial"
        private const val ARG_EDITABLE = "arg_editable"
        private const val TAG = "PhotoSelectionDialogFragment"

        /**
         * 在 [host] 的 childFragmentManager 上弹出。宿主监听结果用
         * `childFragmentManager.setFragmentResultListener(REQUEST_KEY, ...)`。
         *
         * @param initialSelection 初始勾选下标；`null` 表示全选。
         * @param editable false 时隐藏勾选、纯预览，关闭不回结果。
         */
        fun show(
            host: Fragment,
            id: String,
            imageUrls: List<String>,
            imageVideoUrls: List<String?>,
            initialSelection: Set<Int>?,
            editable: Boolean,
        ) {
            if (imageUrls.isEmpty()) return
            PhotoSelectionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, id)
                    putStringArrayList(ARG_IMAGE_URLS, ArrayList(imageUrls))
                    // Bundle 存不了 List<String?>：null 用空串占位，读时空串当无动图。
                    putStringArrayList(ARG_VIDEO_URLS, ArrayList(imageVideoUrls.map { it ?: "" }))
                    initialSelection?.let { putIntArray(ARG_INITIAL, it.toIntArray()) }
                    putBoolean(ARG_EDITABLE, editable)
                }
            }.show(host.childFragmentManager, TAG)
        }
    }
}

@Composable
private fun PhotoSelectionContent(
    imageUrls: List<String>,
    videoUrls: List<String>,
    initialSelection: Set<Int>?,
    editable: Boolean,
    onDone: (allSelected: Boolean, indices: IntArray) -> Unit,
) {
    val context = LocalContext.current
    val total = imageUrls.size
    val pagerState = rememberPagerState(pageCount = { total })
    // 勾选状态用已选下标数组承载，rememberSaveable 转屏不丢；默认全选。
    var checked by rememberSaveable {
        mutableStateOf(initialSelection?.toIntArray() ?: IntArray(total) { it })
    }
    val checkedSet = checked.toSet()
    // 预览区高度按屏高算，卡片内其余元素（标题/页码/按钮）约占另外三成，整体不超屏。
    val previewHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

    DialogHeadline(
        stringResource(if (editable) R.string.photo_pick_title else R.string.photo_pick_title_preview),
    )
    if (editable) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.photo_pick_count, checkedSet.size, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }

    Spacer(Modifier.height(12.dp))
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .height(previewHeight)
            .background(Color.Black),
    ) { page ->
        val videoUrl = videoUrls.getOrNull(page)?.takeIf { it.isNotBlank() }
        if (videoUrl != null) {
            // 实况图：网络 mp4，仅当前页播放
            LivePhotoPlayer(
                mediaSource = videoUrl,
                coverModel = imageUrls[page],
                isActive = pagerState.currentPage == page,
                modifier = Modifier.fillMaxWidth().height(previewHeight),
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrls[page])
                    .crossfade(true)
                    .placeholder(R.drawable.ic_video_placeholder)
                    .error(R.drawable.ic_video_placeholder)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(previewHeight),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    if (total > 1) {
        Text(
            text = stringResource(R.string.photo_pick_page_indicator, pagerState.currentPage + 1, total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (editable) {
        val current = pagerState.currentPage
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = current in checkedSet,
                onCheckedChange = { wantChecked ->
                    val s = checked.toMutableSet()
                    if (wantChecked) s.add(current) else s.remove(current)
                    checked = s.toIntArray()
                },
            )
            Text(stringResource(R.string.photo_pick_item_checkbox))
        }
    }

    Spacer(Modifier.height(8.dp))
    DialogActions(
        leading = if (editable) {
            {
                TextButton(onClick = {
                    checked = if (checkedSet.size == total) IntArray(0) else IntArray(total) { it }
                }) {
                    Text(
                        stringResource(
                            if (checkedSet.size == total) R.string.photo_pick_deselect_all
                            else R.string.photo_pick_select_all,
                        ),
                    )
                }
            }
        } else {
            null
        },
        trailing = {
            TextButton(onClick = {
                val sorted = checkedSet.toIntArray().sortedArray()
                onDone(sorted.size == total, sorted)
            }) {
                Text(stringResource(R.string.photo_pick_done))
            }
        },
    )
}
