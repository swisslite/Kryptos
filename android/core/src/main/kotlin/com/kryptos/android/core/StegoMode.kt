package com.kryptos.android.core

enum class StegoMode(val key: String) {
    WORDS("words"),
    SMART("smart"),
    LETTERS("letters");

    companion object {
        fun resolve(raw: String?, legacySmart: Boolean): StegoMode =
            entries.firstOrNull { it.key == raw } ?: if (legacySmart) SMART else WORDS
    }
}
