package com.blitz.downloader.llm

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 分析日志格式化工具，提供针对普通用户的明晰排版、长数据空格分隔、Base64 脱敏及 Pretty Print。
 */
object AiAnalysisLogFormatter {

    private val prettyGson: Gson by lazy {
        GsonBuilder().setPrettyPrinting().create()
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 将 `"data":"<base64>"` 里的 base64 字符串替换为 `[图片内容，约 NKB]`。
     */
    fun redactImageData(json: String): String {
        val marker = "\"data\":\""
        val sb = StringBuilder(json.length)
        var cursor = 0
        while (true) {
            val markerStart = json.indexOf(marker, cursor)
            if (markerStart < 0) {
                sb.append(json, cursor, json.length)
                break
            }
            val valueStart = markerStart + marker.length
            val valueEnd = json.indexOf('"', valueStart)
            if (valueEnd < 0) {
                sb.append(json, cursor, json.length)
                break
            }
            sb.append(json, cursor, valueStart)
            val base64Len = valueEnd - valueStart
            val approxKb = (base64Len * 3L / 4L / 1024L).coerceAtLeast(1L)
            sb.append("[图片内容，约 ${approxKb}KB]")
            sb.append('"')
            cursor = valueEnd + 1
        }
        return sb.toString()
    }

    /**
     * 格式化 JSON 字符串为带缩进与空格的 Pretty Print 格式。
     */
    fun formatJson(json: String): String {
        if (json.isBlank()) return ""
        return try {
            val element = JsonParser.parseString(json)
            prettyGson.toJson(element)
        } catch (_: Exception) {
            json
        }
    }

    /**
     * 将候选标签列表格式化为适合普通人阅读的文本，各项之间留有适当空格分隔。
     */
    fun formatCandidates(candidates: List<TagCandidate>): String {
        if (candidates.isEmpty()) return "无推荐标签"
        return candidates.joinToString("\n") { candidate ->
            val pct = (candidate.confidence * 100).toInt()
            val framesStr = if (candidate.evidenceFrames.isNotEmpty()) {
                "依据图片: " + candidate.evidenceFrames.joinToString(" ,  ") { "图 $it" }
            } else {
                "全局依据"
            }
            "  [ ${candidate.tagName} ]   置信度: ${pct}%   ($framesStr)"
        }
    }

    /**
     * 将视觉特征观察维度格式化为分段、空格分隔的明晰文本。
     */
    fun formatVisualProfile(profile: VisualFeatureProfile?): String {
        if (profile == null) return "暂无视觉分析数据"
        val lines = mutableListOf<String>()
        fun addDim(name: String, dim: VisualDimension?) {
            if (dim == null || dim.visibility.equals("none", ignoreCase = true)) {
                lines.add("  • $name: 未见明显特征")
                return
            }
            val traits = if (dim.observableTraits.isNotEmpty()) {
                dim.observableTraits.joinToString("   ")
            } else {
                "无具体描述"
            }
            val frames = if (dim.evidenceFrames.isNotEmpty()) {
                " (图: ${dim.evidenceFrames.joinToString(" ")})"
            } else ""
            lines.add("  • $name [${dim.visibility}]: $traits$frames")
        }

        addDim("面部特征", profile.face)
        addDim("表情神态", profile.expression)
        addDim("身材体态", profile.bodyAndStyling)
        addDim("服饰穿搭", profile.clothing)
        addDim("动作行为", profile.action)
        return lines.joinToString("\n")
    }

    /**
     * 将标签词表格式化为每行数个、以空格充分分隔的长列表排版。
     */
    fun formatVocabulary(vocabulary: List<TagWordEntry>): String {
        if (vocabulary.isEmpty()) return "无词表"
        val chunks = vocabulary.map { "[ ${it.name} ]" }.chunked(4)
        return chunks.joinToString("\n") { chunk ->
            "  " + chunk.joinToString("    ")
        }
    }

    /**
     * 生成单条日志的完整纯文本导出报告，供一键复制或分享。
     */
    fun formatEntryToPlainText(entry: AiAnalysisLogEntry): String = buildString {
        val timeStr = synchronized(dateFormat) {
            dateFormat.format(Date(entry.timestamp))
        }
        val statusStr = when (entry.status) {
            AiAnalysisLogStatus.RUNNING -> "分析中"
            AiAnalysisLogStatus.SUCCESS -> "分析成功"
            AiAnalysisLogStatus.FAILED -> "分析失败"
        }

        appendLine("==================================================")
        appendLine("视频: ${entry.videoTitle.ifBlank { entry.awemeId }}")
        appendLine("状态: $statusStr    耗时: ${entry.durationMs} ms    时间: $timeStr")
        appendLine("--------------------------------------------------")
        appendLine("【请求数据概览】")
        if (entry.authorName.isNotBlank()) {
            appendLine("• 视频作者: ${entry.authorName}")
        }
        if (entry.videoDesc.isNotBlank()) {
            appendLine("• 视频文案: ${entry.videoDesc}")
        }
        if (entry.requestAuthorTagsSummary.isNotBlank()) {
            appendLine("• 作者高频标签: ${entry.requestAuthorTagsSummary}")
        }
        if (entry.requestEvidenceSamplesSummary.isNotBlank()) {
            appendLine("• 历史审核参考: ${entry.requestEvidenceSamplesSummary}")
        }
        if (entry.requestFramesSummary.isNotBlank()) {
            appendLine("• 图片与选帧: ${entry.requestFramesSummary}")
        }
        if (entry.requestPrompt.isNotBlank()) {
            appendLine("• 提示词概要: ${entry.requestPrompt.take(150).replace("\n", " ")}...")
        }
        if (entry.requestVocabularySummary.isNotBlank()) {
            appendLine("• 可选标签词表:")
            appendLine(entry.requestVocabularySummary)
        }
        appendLine("--------------------------------------------------")
        appendLine("【接口返回结果】")
        if (entry.status == AiAnalysisLogStatus.FAILED) {
            appendLine("• 错误信息: ${entry.errorMessage ?: "未知错误"}")
        } else {
            appendLine("• 推荐标签与置信度:")
            appendLine(formatCandidates(entry.suggestedTags))
            if (entry.visualFeatureProfile != null) {
                appendLine("• 视觉证据维度分析:")
                appendLine(formatVisualProfile(entry.visualFeatureProfile))
            }
            if (!entry.tokenUsage.isNullOrBlank()) {
                appendLine("• Token 消耗统计: ${entry.tokenUsage}")
            }
        }
        appendLine("==================================================")
    }

    /**
     * 生成全部日志的合集导出文本。
     */
    fun formatAllEntriesToPlainText(entries: List<AiAnalysisLogEntry>): String = buildString {
        if (entries.isEmpty()) {
            appendLine("暂无任何 AI 分析日志。")
            return@buildString
        }
        appendLine("共 ${entries.size} 条 AI 分析交互记录")
        appendLine()
        entries.forEach { entry ->
            appendLine(formatEntryToPlainText(entry))
            appendLine()
        }
    }
}
