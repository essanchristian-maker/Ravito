package com.ravito.app.domain

import java.text.Normalizer

object TextNormalizer {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("[\u00A0\u202F\u2007\t\r\n ]+")

    /** Retire les accents et uniformise les espaces, en gardant la casse. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return marks.replace(decomposed, "")
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .let { spaces.replace(it, " ") }
            .trim()
    }
}
