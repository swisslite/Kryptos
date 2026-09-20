package com.kryptos.android.keyboard

object LetterAlternates {

    val tables = mapOf(
        "ru" to mapOf(
            "е" to listOf("е", "ё"),
            "ь" to listOf("ь", "ъ"),
        ),
        "pt" to mapOf(
            "a" to listOf("a", "á", "ã", "â", "à", "ª"),
            "c" to listOf("c", "ç"),
            "e" to listOf("e", "é", "ê"),
            "i" to listOf("i", "í"),
            "n" to listOf("n", "ñ"),
            "o" to listOf("o", "ó", "õ", "ô", "º"),
            "u" to listOf("u", "ú", "ü"),
        ),
    )

    fun forLabel(label: String, language: String): List<String> {
        val lower = label.lowercase()
        val base = tables[language]?.get(lower) ?: return emptyList()
        return if (label == lower) base else base.map { it.uppercase() }
    }
}
