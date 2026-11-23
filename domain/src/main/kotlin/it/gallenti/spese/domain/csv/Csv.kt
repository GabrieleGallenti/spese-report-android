package it.gallenti.spese.domain.csv

/**
 * Lettore CSV minimale (RFC 4180): virgolette, virgolette raddoppiate,
 * a capo dentro i campi, separatore configurabile.
 */
object Csv {

    fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            when {
                inQuotes && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> {
                    row.add(field.toString()); field.clear()
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString()); rows.add(row)
        }
        return rows
    }

    /** Sceglie il separatore più frequente nelle prime righe. */
    fun guessDelimiter(text: String): Char {
        val sample = text.lineSequence().take(15).joinToString("\n")
        return listOf(';', ',', '\t').maxByOrNull { d -> sample.count { it == d } } ?: ';'
    }
}

/**
 * Converte importi testuali in centesimi.
 *
 * @param decimalComma true per "1.234,56" (formato italiano), false per "1234.56".
 */
fun parseAmountCents(raw: String, decimalComma: Boolean): Long? {
    var s = raw.replace("€", "").replace("EUR", "").replace(" ", "").replace(" ", "").trim()
    if (s.isEmpty() || s == "-" || s == "+") return null
    val negative = s.startsWith("-")
    s = s.trimStart('+', '-')
    s = if (decimalComma) s.replace(".", "").replace(',', '.') else s.replace(",", "")
    val value = s.toBigDecimalOrNull() ?: return null
    val cents = value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    return if (negative) -cents else cents
}
