package com.loopcast.util

import android.util.Log as AndroidLog

object Log {
    private const val tag = "LoopCast"
    private val keyPattern = Regex("""(?:[A-Za-z0-9]{4}-){3,4}[A-Za-z0-9]{4}""")
    private val urlKeyPattern = Regex("""(/live2/)[^/?\s]+""", RegexOption.IGNORE_CASE)

    fun d(message: String, throwable: Throwable? = null) {
        AndroidLog.d(tag, redact(message), throwable)
    }

    fun i(message: String, throwable: Throwable? = null) {
        AndroidLog.i(tag, redact(message), throwable)
    }

    fun w(message: String, throwable: Throwable? = null) {
        AndroidLog.w(tag, redact(message), throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        AndroidLog.e(tag, redact(message), throwable)
    }

    fun redact(message: String): String {
        return message
            .replace(urlKeyPattern, "$1****")
            .replace(keyPattern, "****")
    }
}
