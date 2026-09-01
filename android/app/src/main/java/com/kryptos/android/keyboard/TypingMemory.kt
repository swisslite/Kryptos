package com.kryptos.android.keyboard

object TypingMemory {

    fun forgetAll() {
        runCatching { SuggestionEngine.clearPersonal() }
        runCatching { PinyinEngine.forget() }
        runCatching { EmojiData.forget() }
    }

    fun beginSession() {
        SuggestionEngine.beginTypingSession()
        PinyinEngine.beginTypingSession()
        EmojiData.beginTypingSession()
    }

    fun forgetSession() {
        SuggestionEngine.forgetTypingSession()
        PinyinEngine.forgetTypingSession()
        EmojiData.forgetTypingSession()
    }
}
