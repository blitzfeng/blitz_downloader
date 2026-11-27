package com.blitz.downloader.service

import java.util.concurrent.atomic.AtomicReference

internal object AiWorkGate {
    private val current = AtomicReference<String?>(null)
    fun acquire(owner: String): Boolean = current.compareAndSet(null, owner)
    fun release(owner: String) {
        val held = current.get()
        if (held == owner) current.compareAndSet(held, null)
    }
}
