package it.gallenti.spese.domain.model

import java.time.LocalDate
import java.time.YearMonth

/** Macro-gruppo in cui finisce ogni movimento. */
enum class Group(val label: String) {
    RICORRENTI("Ricorrenti"),
    RESTO("Tutto il resto"),
    EXTRA("Extra"),
    ENTRATE("Entrate"),
    INTERNI("Giroconti e risparmi"),
    ;

    val isSpending: Boolean get() = this == RICORRENTI || this == RESTO || this == EXTRA
}

/** Da quale estratto conto arriva il movimento. */
enum class Source(val label: String) {
    BANCA("Conto principale"),
    ING("ING"),
    PAYPAL("PayPal"),
    ALTRO("Altro CSV"),
}

/**
 * Un movimento già classificato.
 *
 * @property amountCents importo in centesimi: negativo = uscita, positivo = entrata.
 */
data class Transaction(
    val id: String,
    val date: LocalDate,
    val name: String,
    val amountCents: Long,
    val group: Group,
    val category: String,
    val description: String,
    val source: Source,
) {
    val month: YearMonth get() = YearMonth.from(date)

    /** Spesa positiva (un rimborso del negozio la rende negativa). */
    val spendingCents: Long get() = -amountCents
}

/** Movimento letto dal CSV, prima della classificazione. */
data class RawTransaction(
    val date: LocalDate,
    val name: String,
    val amountCents: Long,
    val description: String,
    val source: Source,
    /** Tipologia/causale indicata dalla banca (es. "Addebiti diretti", "Pagamento Carta"). */
    val kind: String = "",
    /** Codice MCC dell'esercente, se presente nella descrizione. */
    val mcc: String? = null,
    /** Classificazione già decisa dal parser (es. rate PayPal), se presente. */
    val forced: Pair<Group, String>? = null,
)
