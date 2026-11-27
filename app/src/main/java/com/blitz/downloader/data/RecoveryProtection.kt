package com.blitz.downloader.data

import android.content.Context

object RecoveryProtection {
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("media_recovery", Context.MODE_PRIVATE)
            .getBoolean("protection_enabled", true)
}
