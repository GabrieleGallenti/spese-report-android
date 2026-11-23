package it.gallenti.spese.domain.categorize

import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Source
import java.time.LocalDate

/**
 * Allinea gli storici delle varie fonti alla stessa data di inizio, così i totali sono
 * confrontabili e i giroconti non restano scoperti da un lato solo.
 *
 * La data comune è la più recente tra le prime date delle fonti presenti; i movimenti precedenti
 * vengono scartati. Vale per qualsiasi formato, compresi i CSV generici: tutti i CSV generici,
 * di qualunque banca, contano come un'unica fonte ([Source.ALTRO]).
 */
object HistoryAlignment {

    /** Null se c'è meno di due fonti: non c'è nulla da allineare. */
    fun commonStart(raw: List<RawTransaction>): LocalDate? {
        val firstDates = raw.groupBy { it.source }.map { (_, l) -> l.minOf { it.date } }
        return if (firstDates.size < 2) null else firstDates.max()
    }

    fun align(raw: List<RawTransaction>): List<RawTransaction> {
        val start = commonStart(raw) ?: return raw
        return raw.filter { !it.date.isBefore(start) }
    }
}
