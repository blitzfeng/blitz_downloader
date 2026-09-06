package com.blitz.downloader.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.BlitzApp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [com.blitz.downloader.dialog.TagEditDialogFragment] 的「AI 建议」按钮承载的网络+数据库操作——
 * 按项目约定这类操作必须走 ViewModel，不能直接从 DialogFragment 发起。Fragment 级作用域
 * （`by viewModels()`），转屏不丢进行中的请求状态。
 *
 * 复用 [BlitzApp.aiTagSuggestionRepository] 单例，不自己 new——避免每次弹窗都重新构造
 * `GeminiProvider` 的 OkHttp 客户端。
 */
class TagEditDialogViewModel(app: Application) : AndroidViewModel(app) {

    private val repo get() = (getApplication<Application>() as BlitzApp).aiTagSuggestionRepository
    private val tagRepo get() = (getApplication<Application>() as BlitzApp).videoTagRepository

    private val _aiState = MutableStateFlow<AiSuggestionState>(AiSuggestionState.Idle)
    val aiState: StateFlow<AiSuggestionState> = _aiState.asStateFlow()

    /**
     * 发起一次 AI 建议请求。[coverPath]/[videoFilePath] 与 `downloaded_videos.coverPath`/`filePath`
     * 同一套约定——**相对 `Environment.getExternalStorageDirectory()` 的路径，不是绝对路径**
     * （见 `ManageGridAdapter`/`VideoPlayerActivity`/`ImageViewerActivity` 等既有读取点，
     * 这里最初漏了这一步，直接拿相对路径当绝对路径 `File(coverPath)` 读，永远读不到文件，
     * 表现为"封面明明存在却提示不存在"）。
     *
     * [coverPath] 读取失败（文件不存在等）直接判定失败，不发起网络请求——封面是请求的必需部分；
     * [videoFilePath] 为空/文件不存在时仅用封面（`AiTagSuggestionRepository`/`VideoFrameExtractor`
     * 内部已处理，这里不用重复判断）。
     */
    fun requestSuggestion(awemeId: String, secUserId: String, desc: String, coverPath: String, videoFilePath: String) {
        if (_aiState.value is AiSuggestionState.Loading) return
        _aiState.value = AiSuggestionState.Loading
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val storageRoot = Environment.getExternalStorageDirectory()
                val coverBytes = runCatching { File(storageRoot, coverPath).readBytes() }.getOrNull()
                if (coverBytes == null) {
                    Result.failure(IllegalStateException("封面图片不存在"))
                } else {
                    val videoFile = videoFilePath.takeIf { it.isNotBlank() }?.let { File(storageRoot, it) }
                    repo.requestSuggestion(awemeId, secUserId, desc, coverBytes, videoFile)
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    val names = withContext(Dispatchers.IO) {
                        tagRepo.getAvailableTagEntities()
                            .filter { it.id in result.candidateTagIds }
                            .map { it.tagName }
                            .toSet()
                    }
                    _aiState.value = AiSuggestionState.Success(result.analysisId, names)
                },
                onFailure = { _aiState.value = AiSuggestionState.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    sealed interface AiSuggestionState {
        data object Idle : AiSuggestionState
        data object Loading : AiSuggestionState
        data class Success(val analysisId: Long, val suggestedTagNames: Set<String>) : AiSuggestionState
        data class Failed(val message: String) : AiSuggestionState
    }
}
