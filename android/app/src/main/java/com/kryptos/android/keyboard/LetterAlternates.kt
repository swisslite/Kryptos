package com.kryptos.android.keyboard

object LetterAlternates {

    val table = mapOf(
        "е" to listOf("е", "ё"),
        "ь" to listOf("ь", "ъ"),
    )

    fun forLabel(label: String): List<String> {
        val lower = label.lowercase()
        val base = table[lower] ?: return emptyList()
        return if (label == lower) base else base.map { it.uppercase() }
    }
}
