package com.kryptos.android.keyboard

object PunctDoubleTap {

    const val WINDOW_MS = 600L

    fun replacesPeriod(
        enabled: Boolean,
        passwordField: Boolean,
        sinceLastTap: Long,
        beforeCaret: String,
        period: String,
    ): Boolean = enabled &&
        !passwordField &&
        sinceLastTap in 0 until WINDOW_MS &&
        beforeCaret == period
}
