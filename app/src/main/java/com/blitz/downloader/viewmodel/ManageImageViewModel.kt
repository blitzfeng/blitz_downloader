package com.blitz.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.blitz.downloader.data.DownloadMediaType
import com.blitz.downloader.data.db.DownloadedVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理页「图片」Tab 的取数与写库。
 *
 * 与视频 Tab 共用 [ManageTabViewModel] 的整条取数链路，差别只在于：不检查文件存在性、
 * 不加载用户标签、不支持清除失效——这些能力位与既有行为保持一致，不要顺手「补齐」。
 */
class ManageImageViewModel(app: Application) : ManageTabViewModel(app) {

    override val mediaType: String = DownloadMediaType.IMAGE

    /** 打开图片浏览页；文件不存在时只提示。 */
    fun openImageViewer(entity: DownloadedVideoEntity) {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { readResolver.status(entity.filePath) }
            if (status != com.blitz.downloader.data.DownloadedMediaReadResolver.Status.READABLE) {
                emit(if (status == com.blitz.downloader.data.DownloadedMediaReadResolver.Status.MISSING)
                    ManageTabEvent.FileNotFound else ManageTabEvent.PermissionNeeded)
                return@launch
            }
            val title = entity.desc.trim().ifBlank { entity.userName.ifBlank { entity.awemeId } }
            val app = getApplication<com.blitz.downloader.BlitzApp>()
            val record = app.database.mediaRecoveryDao().provenance(entity.awemeId)
            val members = record?.let { app.mediaRecovery.gson.fromJson(it.inputsJson,com.blitz.downloader.model.RecoveryInputs::class.java).members }.orEmpty()
            emit(ManageTabEvent.OpenImageViewer(entity.filePath, title, members))
        }
    }
}
