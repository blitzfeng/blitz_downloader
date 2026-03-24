package com.blitz.downloader.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.blitz.downloader.R
import com.blitz.downloader.databinding.FragmentSingleDownloadBinding
import com.blitz.downloader.download.DouyinVideoHttp
import com.blitz.downloader.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SingleDownloadFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var tvTip: TextView
    private var videoTitle = "douyin_video.mp4"
    private var isVideoDetected = false
    private var downloadDialog: AlertDialog? = null

    private var isLoad = false
    private lateinit var binding: FragmentSingleDownloadBinding
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentSingleDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etUrl = view.findViewById(R.id.etUrl)
        tvTip = view.findViewById(R.id.tvTip)
        webView = view.findViewById(R.id.webView)

        view.findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        view.findViewById<Button>(R.id.btnGo).setOnClickListener { loadUrl() }

        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view.loadUrl(url)
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                var url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                if (url.endsWith(".js")) return super.shouldInterceptRequest(view, request)

                if (url.contains("https://www.iesdouyin.com/aweme") && !isLoad) {
                    Log.i("SingleDownload", "intercepted: $url")
                    url = url.replace("playwm", "play")
                    isLoad = true
                    reload(url)
                }
                if (url.contains("mime_type=video_mp4") && !isVideoDetected) {
                    isVideoDetected = true
                    Log.e("SingleDownload", "detected video: $url")
                    activity?.runOnUiThread { showDownloadDialog(url) }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript("document.title") { rawTitle ->
                    if (!rawTitle.isNullOrBlank() && rawTitle.length > 2) {
                        val cleaned = rawTitle
                            .removeSurrounding("\"")
                            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_")
                            .trim()
                            .take(80)
                        if (cleaned.isNotBlank()) {
                            videoTitle = "$cleaned.mp4"
                        }
                    }
                }
            }
        }
    }

    private fun reload(url: String) {
        activity?.runOnUiThread {
            webView.loadUrl(url)
        }
    }

    private fun loadUrl() {
        isLoad = false
        val input = etUrl.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(requireContext(), "请输入链接", Toast.LENGTH_SHORT).show()
            return
        }
        val url = UrlUtils.extractFirstUrl(input)
        if (url == null) {
            Toast.makeText(requireContext(), "未检测到有效链接", Toast.LENGTH_SHORT).show()
            return
        }
        isVideoDetected = false
        tvTip.text = "正在加载页面，请等待视频播放…"
        webView.loadUrl(url)
    }

    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(requireContext(), "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).text?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(requireContext(), "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val url = UrlUtils.extractFirstUrl(text)
        if (url != null) {
            etUrl.setText(url)
            Toast.makeText(requireContext(), "已粘贴链接", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "剪贴板中没有链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadDialog(videoUrl: String) {
        if (downloadDialog?.isShowing == true) return

        tvTip.text = "已检测到视频资源"

        downloadDialog = AlertDialog.Builder(requireContext())
            .setTitle("检测到视频")
            .setMessage("是否下载该无水印视频？\n\n文件名: $videoTitle")
            .setPositiveButton("下载") { dialog, _ ->
                dialog.dismiss()
                downloadVideo(videoUrl)
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                isVideoDetected = false
            }
            .create()
        downloadDialog?.show()
    }

    private fun downloadVideo(videoUrl: String) {
        tvTip.text = "正在下载…"
        lifecycleScope.launch {
            val success = saveVideoToDownloads(videoUrl, videoTitle)
            if (success) {
                tvTip.text = "下载完成: $videoTitle"
                Toast.makeText(requireContext(), "保存成功", Toast.LENGTH_SHORT).show()
            } else {
                tvTip.text = "下载失败，请重试"
                Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
            }
            isVideoDetected = false
        }
    }

    private suspend fun saveVideoToDownloads(videoUrl: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val resolver = requireContext().contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BlitzDownloader")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values) ?: return@withContext false

                resolver.openOutputStream(uri)?.use { outStream ->
                    val conn = URL(videoUrl).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 120_000
                    DouyinVideoHttp.applyCdnHeaders(conn)
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        Log.w("SingleDownload", "CDN HTTP $code url=${videoUrl.take(120)}")
                        conn.disconnect()
                        return@withContext false
                    }
                    conn.inputStream.use { input -> input.copyTo(outStream) }
                    conn.disconnect()
                }

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                Log.e("SingleDownload", "download failed", e)
                false
            }
        }

    override fun onDestroyView() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroyView()
    }
}
