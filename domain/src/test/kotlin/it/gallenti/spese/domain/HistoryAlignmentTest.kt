package it.gallenti.spese.domain

import it.gallenti.spese.domain.categorize.HistoryAlignment
import it.gallenti.spese.domain.categorize.Reconciler
import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Source
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryAlignmentTest {

    private fun raw(source: Source, date: String, cents: Long = -1000L) =
        RawTransaction(LocalDate.parse(date), "Negozio", cents, "movimento $date", source)

    private val banca = listOf(raw(Source.BANCA, "2025-11-18"), raw(Source.BANCA, "2025-12-02"))
    private val ing = listOf(raw(Source.ING, "2025-04-07"), raw(Source.ING, "2026-01-28"))
    private val paypal = listOf(raw(Source.PAYPAL, "2025-11-27"), raw(Source.PAYPAL, "2026-01-05"))

    @Test
    fun `la data comune e la piu recente tra le prime date delle fonti`() {
        assertEquals(LocalDate.parse("2025-11-27"), HistoryAlignment.commonStart(banca + ing + paypal))
    }

    @Test
    fun `con una sola fonte non c e niente da allineare`() {
        assertNull(HistoryAlignment.commonStart(banca))
        assertEquals(banca, HistoryAlignment.align(banca))
    }

    @Test
    fun `scarta i movimenti precedenti alla data comune in tutte le fonti`() {
        val aligned = HistoryAlignment.align(banca + ing + paypal)
        assertEquals(4, aligned.size)
        assertEquals(LocalDate.parse("2025-11-27"), aligned.minOf { it.date })
    }

    @Test
    fun `i CSV generici di qualsiasi banca partecipano all allineamento`() {
        val generic = listOf(raw(Source.ALTRO, "2024-01-01"), raw(Source.ALTRO, "2026-01-10"))
        val all = banca + generic
        assertEquals(LocalDate.parse("2025-11-18"), HistoryAlignment.commonStart(all))
        assertEquals(listOf("2026-01-10"), HistoryAlignment.align(all).filter { it.source == Source.ALTRO }.map { it.date.toString() })
    }

    @Test
    fun `un CSV generico piu recente taglia le altre fonti`() {
        val generic = listOf(raw(Source.ALTRO, "2026-03-01"))
        assertEquals(LocalDate.parse("2026-03-01"), HistoryAlignment.commonStart(banca + generic))
        assertEquals(generic, HistoryAlignment.align(banca + generic))
    }

    @Test
    fun `reconcile applica l allineamento`() {
        val tx = Reconciler.reconcile(banca + ing + paypal)
        assertEquals(4, tx.size)
    }

    @Test
    fun `il riepilogo dell import riporta la data di allineamento`() {
        val summary = ImportStatementsUseCase()(emptyList(), banca + paypal).first
        assertEquals(LocalDate.parse("2025-11-27"), summary.alignedFrom)
    }
}
