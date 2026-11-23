package it.gallenti.spese.domain.parser

import it.gallenti.spese.domain.csv.Csv
import it.gallenti.spese.domain.csv.parseAmountCents
import it.gallenti.spese.domain.model.Group
import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Source
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Esito della lettura di un file. */
data class ParseResult(
    val format: StatementFormat,
    val transactions: List<RawTransaction>,
    val skippedRows: Int,
)

enum class StatementFormat(val label: String, val source: Source) {
    MEDIOBANCA("Conto principale (Elenco movimenti)", Source.BANCA),
    ING("ING Conto Corrente Arancio", Source.ING),
    PAYPAL("PayPal", Source.PAYPAL),
    GENERIC("CSV generico", Source.ALTRO),
}

class UnsupportedStatementException(message: String) : IllegalArgumentException(message)

/** Riconosce il formato del CSV e lo legge. */
object StatementParser {

    private val itDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun parse(text: String): ParseResult {
        val clean = text.removePrefix("﻿")
        val lines = clean.lineSequence().take(40).toList()
        return when {
            lines.any { it.startsWith("Operazione;Valuta;Tipologia Operazione") } -> parseMediobanca(clean)
            lines.any { it.startsWith("DATA CONTABILE;DATA VALUTA;USCITE;ENTRATE") } -> parseIng(clean)
            lines.firstOrNull()?.let { "Codice transazione" in it && "Impatto sul saldo" in it } == true -> parsePayPal(clean)
            else -> parseGeneric(clean)
        }
    }

    // ---- Conto principale: "Elenco movimenti" (Operazione;Valuta;Tipologia;Descrizione;Uscite;Entrate)

    private fun parseMediobanca(text: String): ParseResult {
        val rows = Csv.parse(text, ';')
        val start = rows.indexOfFirst { it.firstOrNull() == "Operazione" }
        var skipped = 0
        val out = rows.drop(start + 1).mapNotNull { r ->
            val date = r.getOrNull(1)?.let(::date) ?: r.getOrNull(0)?.let(::date)
            val desc = r.getOrNull(3).orEmpty().normalizeSpaces()
            val amount = (parseAmountCents(r.getOrNull(4).orEmpty(), decimalComma = false) ?: 0L) +
                (parseAmountCents(r.getOrNull(5).orEmpty(), decimalComma = false) ?: 0L)
            if (date == null || r.size < 6 || amount == 0L) {
                if (r.any { it.isNotBlank() }) skipped++
                return@mapNotNull null
            }
            RawTransaction(
                date = date,
                name = mediobancaName(desc),
                amountCents = amount,
                description = desc,
                source = Source.BANCA,
                kind = r[2].trim(),
                mcc = MCC.find(desc)?.groupValues?.get(1),
            )
        }
        return ParseResult(StatementFormat.MEDIOBANCA, out, skipped)
    }

    private val MCC = Regex("""MCC (\d{4})""")

    internal fun mediobancaName(d: String): String {
        Regex("""C/O (.+?) CARTA N""").find(d)?.let { return it.groupValues[1].titleCase() }
        if ("ADDEBITO DIRETTO" in d) {
            Regex("""(?:RCUR|FRST|OOFF)\s+P[Rr][Gg]\.C[Aa][Rr]\.:\s*\S+\s+\S+\s+(.+?)(?: - |$)""")
                .find(d)?.let { return it.groupValues[1].titleCase() }
        }
        if ("BONIFICO" in d) {
            val who = Regex("""(?:A VS FAVORE\s+)?([A-Z][A-Z .']+?)\s+(?:NOTE|COOR)""")
                .find(d.substringAfter("ISTANTANEO").substringAfter("ORDINARIO"))?.groupValues?.get(1)
            val note = Regex("""NOTE: (.+?)(?: DATA REG|$)""").find(d)?.groupValues?.get(1)?.take(60)
            if (who != null) return listOfNotNull(who.trim().titleCase(), note?.lowercase()?.replaceFirstChar { it.uppercase() })
                .joinToString(" · ")
        }
        return d.take(60).titleCase()
    }

    // ---- ING: DATA CONTABILE;DATA VALUTA;USCITE;ENTRATE;CAUSALE;DESCRIZIONE OPERAZIONE

    private fun parseIng(text: String): ParseResult {
        val rows = Csv.parse(text, ';')
        var skipped = 0
        val out = rows.drop(1).mapNotNull { r ->
            val desc = r.getOrNull(5).orEmpty().normalizeSpaces()
            val causale = r.getOrNull(4).orEmpty().trim()
            if (causale.isEmpty() && desc.startsWith("Saldo", ignoreCase = true)) return@mapNotNull null
            val date = r.getOrNull(1)?.let(::date) ?: r.getOrNull(0)?.let(::date)
            val amount = (parseAmountCents(r.getOrNull(2).orEmpty(), decimalComma = true) ?: 0L) +
                (parseAmountCents(r.getOrNull(3).orEmpty(), decimalComma = true) ?: 0L)
            if (date == null || amount == 0L) {
                if (r.any { it.isNotBlank() }) skipped++
                return@mapNotNull null
            }
            val name = Regex("""presso (.+?)(?: - Transazione|$)""").find(desc)?.groupValues?.get(1)
                ?: Regex("""Anagrafica Ordinante (.+?) Note""").find(desc)?.groupValues?.get(1)
                ?: Regex("""A favore di (.+?) IBAN""").find(desc)?.groupValues?.get(1)
                ?: causale.ifEmpty { desc.take(60) }
            RawTransaction(date, name.trim().titleCase(), amount, "ING · $causale · $desc", Source.ING, kind = causale)
        }
        return ParseResult(StatementFormat.ING, out, skipped)
    }

    // ---- PayPal: export "Tutte le transazioni" (CSV con virgola)

    private val paypalInternalTypes = listOf(
        "Bonifico bancario sul conto PayPal", "Versamento generico con carta", "Pagamento con credito acquirenti",
        "Conversione di valuta", "Blocco conto", "Storno di blocco", "Storno di versamento", "Storno di prelievo",
        "Trasferimento avviato dall'utente", "Pagamento credito acquirenti",
    )

    private fun parsePayPal(text: String): ParseResult {
        val rows = Csv.parse(text, ',')
        val header = rows.first().map { it.trim() }
        fun idx(name: String) = header.indexOf(name)
        val iDate = idx("Data"); val iName = idx("Nome"); val iType = idx("Tipo"); val iState = idx("Stato")
        val iCur = idx("Valuta"); val iGross = idx("Lordo"); val iId = idx("Codice transazione")
        val iRef = idx("Codice transazione di riferimento"); val iTitle = idx("Titolo oggetto")
        val iSubject = idx("Oggetto"); val iMsg = idx("Messaggio")
        val body = rows.drop(1).filter { it.size >= header.size - 1 }
        fun col(r: List<String>, i: Int) = if (i >= 0) r.getOrNull(i).orEmpty().trim() else ""

        // Importi in EUR delle conversioni di valuta, per riferimento alla transazione originale.
        val conversions = body.filter { col(it, iType).startsWith("Conversione") && col(it, iCur) == "EUR" }
            .associate { col(it, iRef) to (parseAmountCents(col(it, iGross), true) ?: 0L) }
        val creditFunded = body.filter { col(it, iType).startsWith("Pagamento con credito acquirenti") }
            .map { col(it, iRef) }.toSet()

        var skipped = 0
        val out = body.mapNotNull { r ->
            val type = col(r, iType)
            val name = col(r, iName).normalizeSpaces()
            if (col(r, iState) == "Rifiutata" || name.isEmpty() || name == "PayPal" ||
                paypalInternalTypes.any { type.startsWith(it) }
            ) return@mapNotNull null
            val date = date(col(r, iDate)) ?: run { skipped++; return@mapNotNull null }
            var amount = parseAmountCents(col(r, iGross), true) ?: run { skipped++; return@mapNotNull null }
            if (col(r, iCur) != "EUR") amount = conversions[col(r, iId)] ?: amount
            val what = col(r, iTitle).ifEmpty { col(r, iSubject) }
            val msg = col(r, iMsg)
            val details = listOf(what, msg).filter { it.isNotEmpty() && it != "Subtotale, Commissione di servizio" && !it.all(Char::isDigit) }
            val rate = if (col(r, iId) in creditFunded) "a rate (Paga in 3)" else null
            val desc = (listOf("PayPal", type) + details + listOfNotNull(rate)).joinToString(" · ")
            val isPayPalEurope = name.startsWith("PayPal (Europe)")
            val forced = when {
                isPayPalEurope && amount < 0 -> Group.INTERNI to "Rata PayPal in 3 (acquisto già contato)"
                isPayPalEurope -> Group.ENTRATE to "Rimborsi e altre entrate"
                type == "Pagamento da cellulare" && amount > 0 -> Group.ENTRATE to "Rimborsi e altre entrate"
                else -> null
            }
            val shownName = when {
                isPayPalEurope && amount < 0 -> "PayPal · rata Paga in 3"
                type == "Rimborso di pagamento" -> "$name · rimborso"
                else -> name
            }
            RawTransaction(date, shownName, amount, desc, Source.PAYPAL, kind = type, forced = forced)
        }
        return ParseResult(StatementFormat.PAYPAL, out, skipped)
    }

    // ---- CSV generico: colonne Data + (Importo | Uscite/Entrate) + Descrizione

    private fun parseGeneric(text: String): ParseResult {
        val rows = Csv.parse(text, Csv.guessDelimiter(text))
        val headerAt = rows.indexOfFirst { r -> r.any { it.contains("data", true) || it.contains("date", true) } &&
            r.any { h -> listOf("importo", "amount", "uscite", "entrate", "addebit", "accredit").any { h.contains(it, true) } } }
        if (headerAt < 0) {
            throw UnsupportedStatementException("Formato non riconosciuto: servono almeno le colonne Data, Importo (o Uscite/Entrate) e Descrizione.")
        }
        val h = rows[headerAt].map { it.trim().lowercase() }
        fun find(vararg keys: String) = h.indexOfFirst { c -> keys.any { c.contains(it) } }
        val iDate = h.indexOfFirst { it.contains("valuta") && !it.contains("divisa") }.takeIf { it >= 0 } ?: find("data", "date")
        val iAmount = find("importo", "amount")
        val iOut = find("uscite", "addebit", "dare")
        val iIn = find("entrate", "accredit", "avere")
        val iDesc = find("descrizione", "description", "causale", "dettagli")
        var skipped = 0
        val out = rows.drop(headerAt + 1).mapNotNull { r ->
            val d = r.getOrNull(iDate)?.let(::date)
            val amountText = listOf(iAmount, iOut, iIn).filter { it >= 0 }.mapNotNull { r.getOrNull(it) }
            val decimalComma = amountText.any { Regex("""\d,\d{1,2}\s*€?$""").containsMatchIn(it.trim()) }
            var amount = 0L
            if (iAmount >= 0) amount = parseAmountCents(r.getOrNull(iAmount).orEmpty(), decimalComma) ?: 0L
            else {
                val o = parseAmountCents(r.getOrNull(iOut).orEmpty(), decimalComma) ?: 0L
                val e = parseAmountCents(r.getOrNull(iIn).orEmpty(), decimalComma) ?: 0L
                amount = (if (o > 0) -o else o) + e
            }
            if (d == null || amount == 0L) {
                if (r.any { it.isNotBlank() }) skipped++
                return@mapNotNull null
            }
            val desc = r.getOrNull(iDesc).orEmpty().normalizeSpaces()
            RawTransaction(d, desc.take(60).titleCase(), amount, desc, Source.ALTRO)
        }
        return ParseResult(StatementFormat.GENERIC, out, skipped)
    }

    private fun date(s: String): LocalDate? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return runCatching { LocalDate.parse(t, itDate) }.getOrNull()
            ?: runCatching { LocalDate.parse(t) }.getOrNull()
            ?: runCatching { LocalDate.parse(t, DateTimeFormatter.ofPattern("dd/MM/yy")) }.getOrNull()
    }
}

internal fun String.normalizeSpaces(): String = trim().replace(Regex("""\s+"""), " ")

internal fun String.titleCase(): String =
    if (any { it.isLowerCase() }) this
    else lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
