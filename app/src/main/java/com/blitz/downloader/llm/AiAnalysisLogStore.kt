package com.blitz.downloader.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 内存日志仓库，用于收集并向 UI 暴露 AI 分析请求与响应日志。
 * 纯内存保存，最多保留 [MAX_LOG_CAPACITY] 条最近记录（环形淘汰最旧日志）。
 */
object AiAnalysisLogStore {

    const val MAX_LOG_CAPACITY = 100

    private val lock = ReentrantLock()
    private val entries = mutableListOf<AiAnalysisLogEntry>()
    private val _logs = MutableStateFlow<List<AiAnalysisLogEntry>>(emptyList())

    /** 观察日志列表变化的 StateFlow。 */
    val logs: StateFlow<List<AiAnalysisLogEntry>> = _logs.asStateFlow()

    /**
     * 追加一条新日志。超过容量上限时自动淘汰最旧的一条。
     */
    fun addEntry(entry: AiAnalysisLogEntry) {
        lock.withLock {
            if (entries.size >= MAX_LOG_CAPACITY) {
                entries.removeAt(0)
            }
            entries.add(entry)
            _logs.value = entries.toList()
        }
    }

    /**
     * 更新指定 id 的日志条目（如调用结束时补充耗时、响应结果与状态）。
     */
    fun updateEntry(id: String, updater: (AiAnalysisLogEntry) -> AiAnalysisLogEntry) {
        lock.withLock {
            val index = entries.indexOfFirst { it.id == id }
            if (index >= 0) {
                entries[index] = updater(entries[index])
                _logs.value = entries.toList()
            }
        }
    }

    /**
     * 清空当前所有日志条目。
     */
    fun clear() {
        lock.withLock {
            entries.clear()
            _logs.value = emptyList()
        }
    }

    /**
     * 获取当前日志列表的快照。
     */
    fun getEntries(): List<AiAnalysisLogEntry> {
        lock.withLock {
            return entries.toList()
        }
    }
}
