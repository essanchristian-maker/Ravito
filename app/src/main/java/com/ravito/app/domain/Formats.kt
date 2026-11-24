package com.ravito.app.domain

object Formats {
    /** 120000 -> "120 000" */
    fun groupThousands(value: Long): String {
        val negative = value < 0
        val digits = kotlin.math.abs(value).toString()
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return if (negative) "-$sb" else sb.toString()
    }

    /** 120000 -> "120 000 F" */
    fun amount(value: Long): String = groupThousands(value) + " F"

    /** Garde uniquement les chiffres et retire l'indicatif 225 de la Côte d'Ivoire. */
    fun normalizePhone(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00225")) d = d.removePrefix("00225")
        else if (d.startsWith("225") && d.length == 13) d = d.removePrefix("225")
        return d.ifEmpty { null }
    }

    /** "0102030405" -> "01 02 03 04 05" */
    fun prettyPhone(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        return if (phone.length == 10) phone.chunked(2).joinToString(" ") else phone
    }

    fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        "$count " + if (count > 1) plural else singular
}
