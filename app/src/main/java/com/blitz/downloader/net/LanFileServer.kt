package com.blitz.downloader.net

import android.util.Log
import com.blitz.downloader.download.MediaExportManager
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * 内嵌的极简 HTTP/1.1 文件服务器，用于「导出到电脑」的局域网无线路径：
 * 手机与电脑连同一 WiFi，电脑浏览器打开本机地址即可逐个或打包（zip）下载选中的视频 / 图集。
 *
 * 设计取舍：
 * - **零第三方依赖**：手写请求行 + 头解析，只支持 GET，够用即可，不引入 NanoHTTPD。
 * - 路由：`/` 列表页；`/f?i=N` 下载第 N 个文件；`/all.zip` 流式打包全部文件。
 * - 单文件带 `Content-Length`（浏览器可显示进度）；zip 流长度未知，用 `Connection: close` 收尾。
 * - 每个连接开一个 daemon 线程处理，简单可靠；导出文件数量级不大，无需线程池。
 *
 * 生命周期由调用方（[com.blitz.downloader.activity.ManageActivity]）持有：[start] 后务必在
 * 页面销毁 / 用户停止时调用 [stop]，否则监听线程与端口会泄漏。
 *
 * @param onTransfer 传输完成回调，见 [TransferEvent]。**在连接线程触发**，调用方负责切线程。
 */
class LanFileServer(
    private val files: List<MediaExportManager.ExportFile>,
    private val onTransfer: ((TransferEvent) -> Unit)? = null,
) {

    /**
     * 一次「已完整发出」的传输。
     *
     * 语义边界要清楚：它表示**服务端已把全部字节写出 socket 且未报错**，
     * 不代表电脑确认落盘——HTTP 没有反向通道，浏览器取消保存、写盘失败等都看不到。
     * 因此它适合做提示与计数，不适合当权威状态。
     *
     * 中途被取消 / 断网会在 `copyTo` 抛 `IOException`（EPIPE / ECONNRESET），此时**不**回调。
     * `all.zip` 只在整包完整写完时回调一次（半个包对电脑是不可用的，不该计数）。
     *
     * @param awemeIds 本次传输涉及的记录 id 集合（单文件 1 个；整包为所有成功写入的记录）。
     * @param label    用于 UI 展示的名称（文件名或 `all.zip`）。
     */
    data class TransferEvent(
        val awemeIds: Set<String>,
        val label: String,
        val isZip: Boolean,
    )

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    /** 实际绑定的端口；[start] 成功后有效。 */
    var port: Int = -1
        private set

    /**
     * 在 `0.0.0.0` 上绑定并开始 accept。从 [preferredPort] 起逐个尝试，直到成功或超出重试范围。
     * @return 实际监听端口。
     * @throws BindException 端口区间内均无法绑定。
     */
    fun start(preferredPort: Int = 8080): Int {
        val ss = bind(preferredPort)
        serverSocket = ss
        port = ss.localPort
        running = true
        thread(isDaemon = true, name = "LanFileServer-accept") {
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    break // stop() 关闭 socket 会走到这里
                }
                thread(isDaemon = true) {
                    try {
                        handle(socket)
                    } catch (e: Exception) {
                        Log.w(TAG, "connection error", e)
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }
        }
        return port
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun bind(preferredPort: Int): ServerSocket {
        var lastError: Exception? = null
        for (p in preferredPort..(preferredPort + PORT_RETRY_RANGE)) {
            try {
                return ServerSocket(p, BACKLOG, InetAddress.getByName("0.0.0.0"))
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw BindException("无法在 $preferredPort..${preferredPort + PORT_RETRY_RANGE} 绑定端口: ${lastError?.message}")
    }

    // ── 请求处理 ───────────────────────────────────────────────────────────────

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val out = BufferedOutputStream(socket.getOutputStream())

        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            writeText(out, "400 Bad Request", "bad request")
            return
        }
        val method = parts[0]
        val target = parts[1]
        // 读掉剩余请求头直到空行（GET 无请求体，不再继续读）
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
        }

        if (method != "GET" && method != "HEAD") {
            writeText(out, "405 Method Not Allowed", "only GET supported")
            return
        }
        // HEAD 只回响应头、不写 body，也不算一次传输（否则浏览器/下载器的探测请求会虚增导出计数）
        val writeBody = method == "GET"

        val path = target.substringBefore("?")
        val query = target.substringAfter("?", "")
        when {
            path == "/" -> serveIndex(out, writeBody)
            path == "/all.zip" -> serveZip(out, writeBody)
            path == "/f" -> serveFile(out, parseIntParam(query, "i"), writeBody)
            else -> writeText(out, "404 Not Found", "not found")
        }
    }

    /** 回调隔离：业务回调抛异常不应该弄死连接线程。 */
    private fun notifyTransfer(event: TransferEvent) {
        val cb = onTransfer ?: return
        runCatching { cb(event) }.onFailure { Log.w(TAG, "onTransfer callback failed", it) }
    }

    private fun serveIndex(out: OutputStream, writeBody: Boolean) {
        val totalBytes = files.sumOf { it.file.length() }
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>bDouyin 导出</title>")
        sb.append("<style>")
        sb.append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f5f5f7;color:#1d1d1f}")
        sb.append(".wrap{max-width:760px;margin:0 auto;padding:20px 16px}")
        sb.append("h1{font-size:20px;margin:8px 0}")
        sb.append(".meta{color:#6e6e73;font-size:13px;margin-bottom:16px}")
        sb.append(".allbtn{display:block;text-align:center;background:#0071e3;color:#fff;text-decoration:none;")
        sb.append("padding:14px;border-radius:12px;font-size:16px;margin-bottom:16px}")
        sb.append("ul{list-style:none;padding:0;margin:0}")
        sb.append("li{background:#fff;border-radius:10px;margin-bottom:8px;padding:12px 14px;")
        sb.append("display:flex;justify-content:space-between;align-items:center;gap:12px}")
        sb.append("a.file{color:#0071e3;text-decoration:none;word-break:break-all;flex:1}")
        sb.append(".size{color:#8e8e93;font-size:12px;white-space:nowrap}")
        sb.append("</style></head><body><div class=\"wrap\">")
        sb.append("<h1>bDouyin 导出</h1>")
        sb.append("<div class=\"meta\">共 ${files.size} 个文件 · ${humanSize(totalBytes)}</div>")
        if (files.isNotEmpty()) {
            sb.append("<a class=\"allbtn\" href=\"/all.zip\">⬇ 打包下载全部 (zip)</a>")
        }
        sb.append("<ul>")
        files.forEachIndexed { i, ef ->
            sb.append("<li><a class=\"file\" href=\"/f?i=$i\">")
            sb.append(escapeHtml(ef.entryName))
            sb.append("</a><span class=\"size\">").append(humanSize(ef.file.length())).append("</span></li>")
        }
        sb.append("</ul></div></body></html>")

        val body = sb.toString().toByteArray(Charsets.UTF_8)
        writeHeader(
            out, "200 OK",
            linkedMapOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Connection" to "close",
            ),
        )
        if (writeBody) out.write(body)
        out.flush()
    }

    private fun serveFile(out: OutputStream, index: Int, writeBody: Boolean) {
        val ef = files.getOrNull(index)
        if (ef == null || !ef.file.isFile) {
            writeText(out, "404 Not Found", "file not found")
            return
        }
        writeHeader(
            out, "200 OK",
            linkedMapOf(
                "Content-Type" to mimeOf(ef.file.name),
                "Content-Length" to ef.file.length().toString(),
                "Content-Disposition" to contentDisposition(ef.entryName),
                "Connection" to "close",
            ),
        )
        if (!writeBody) {
            out.flush()
            return
        }
        try {
            FileInputStream(ef.file).use { it.copyTo(out, STREAM_BUFFER) }
            out.flush()
        } catch (e: Exception) {
            // 对端取消 / 断网：字节没发完，不算一次成功传输
            Log.w(TAG, "transfer aborted: ${ef.entryName}", e)
            return
        }
        notifyTransfer(TransferEvent(setOf(ef.awemeId), ef.entryName, isZip = false))
    }

    private fun serveZip(out: OutputStream, writeBody: Boolean) {
        // zip 长度未知：不发 Content-Length，靠 Connection: close 通知浏览器传输结束。
        writeHeader(
            out, "200 OK",
            linkedMapOf(
                "Content-Type" to "application/zip",
                "Content-Disposition" to contentDisposition("bDouyin_export.zip"),
                "Connection" to "close",
            ),
        )
        if (!writeBody) {
            out.flush()
            return
        }
        val zos = ZipOutputStream(out)
        zos.setLevel(Deflater.NO_COMPRESSION) // 视频/图片已压缩，仅打包
        // 整包语义：任一条目失败（客户端断开等）即整包不可用，不计数
        val sentIds = LinkedHashSet<String>()
        var aborted = false
        for (ef in files) {
            if (!ef.file.isFile) continue
            try {
                zos.putNextEntry(ZipEntry(ef.entryName))
                FileInputStream(ef.file).use { it.copyTo(zos, STREAM_BUFFER) }
                zos.closeEntry()
                sentIds.add(ef.awemeId)
            } catch (e: Exception) {
                Log.w(TAG, "zip entry failed: ${ef.entryName}", e)
                aborted = true
                break // 客户端断开等：停止即可
            }
        }
        val finished = runCatching {
            zos.finish()
            out.flush()
        }.isSuccess
        if (!aborted && finished && sentIds.isNotEmpty()) {
            notifyTransfer(TransferEvent(sentIds, ZIP_LABEL, isZip = true))
        }
    }

    // ── HTTP 辅助 ──────────────────────────────────────────────────────────────

    private fun writeHeader(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun writeText(out: OutputStream, status: String, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        writeHeader(
            out, status,
            linkedMapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Connection" to "close",
            ),
        )
        out.write(body)
        out.flush()
    }

    /** 逐字节读一行（以 \n 结尾，去掉尾随 \r）。到达流尾且无内容返回 null。 */
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return sb.toString() // 防御超长行
        }
    }

    private fun parseIntParam(query: String, key: String): Int {
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0 && pair.substring(0, idx) == key) {
                return pair.substring(idx + 1).toIntOrNull() ?: -1
            }
        }
        return -1
    }

    /** `Content-Disposition` attachment：ASCII 兜底名 + RFC 5987 UTF-8 名，兼容各浏览器与中文名。 */
    private fun contentDisposition(name: String): String {
        val ascii = buildString {
            name.forEach { c -> append(if (c.code in 32..126 && c != '"' && c != '\\') c else '_') }
        }
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    companion object {
        private const val TAG = "LanFileServer"
        private const val BACKLOG = 50
        private const val PORT_RETRY_RANGE = 20
        private const val STREAM_BUFFER = 64 * 1024
        private const val MAX_LINE = 8 * 1024

        /** 整包传输事件的展示名。 */
        const val ZIP_LABEL = "all.zip"

        /**
         * 取本机在 WiFi/局域网下的 IPv4 站点本地地址（192.168.x / 10.x / 172.16-31.x）。
         * 找不到（未连 WiFi 或仅移动网络）返回 null。
         */
        fun localIpv4(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList().asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull()
        }
    }
}
