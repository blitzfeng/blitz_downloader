package com.blitz.downloader.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 媒体读取权限申请的统一入口。
 *
 * 必要性：本 App 把封面/视频写到 `Download/bDouyin/` 公共目录并通过 MediaStore 写入；
 * 加载时既要走 `File` 读路径（图集/封面）也要走 `Uri` 读路径（部分场景），二者在 Android 13+
 * 都受 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` 控制。
 *
 * 特别提醒：本 App 在签名变更（如换电脑、重新生成 debug key）后，MediaStore 中原本由
 * 旧签名包写入的文件对当前包来说**不再是 owner**，必须依赖完整的媒体读权限，否则
 * 缩略图 / 封面会报 SecurityException（"has no access to content://media/..."）。
 */
object MediaPermissions {

    /** 当前 SDK 需要请求的运行时媒体读权限集合（Manifest 已声明）。 */
    fun requiredPermissions(): Array<String> {
        return when {
            // Android 14+：分离的媒体权限 + 部分授权选项
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            // Android 13：分离的媒体权限，无 visual-user-selected
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            // Android ≤ 12：READ_EXTERNAL_STORAGE 覆盖所有媒体
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** 是否所有必需权限均已授予。 */
    fun hasAllGranted(activity: ComponentActivity): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 在 Activity onCreate 中调用：注册多权限申请回调，缺失时一次性弹出系统申请窗口。
     * 已全部授予时不弹窗。回调本身不做任何 UI 提示——拒绝后访问失败处由调用方各自处理。
     */
    fun registerAndRequestIfNeeded(activity: ComponentActivity) {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            // 这里不强制 toast；权限拒了之后封面/视频会自然加载失败，
            // 调用方（管理页等）已有占位图兜底，不打扰用户。
        }
        val missing = requiredPermissions().filter { perm ->
            ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
    }
}
