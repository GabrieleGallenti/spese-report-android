package it.gallenti.spese.domain

import it.gallenti.spese.domain.categorize.Categorizer
import it.gallenti.spese.domain.categorize.Reconciler
import it.gallenti.spese.domain.csv.parseAmountCents
import it.gallenti.spese.domain.model.Group
import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Source
import it.gallenti.spese.domain.model.Transaction
import it.gallenti.spese.domain.parser.StatementFormat
import it.gallenti.spese.domain.parser.StatementParser
import it.gallenti.spese.domain.parser.UnsupportedStatementException
import it.gallenti.spese.domain.report.ReportCalculator
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StatementImportTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResource(name)) { "fixture $name mancante" }.readText()

    private fun importAll(vararg names: String): List<Transaction> =
        ImportStatementsUseCase()(names.map { it to fixture(it) }, emptyList()).first.transactions

    private fun List<Transaction>.byName(part: String): Transaction = first { part in it.name || part in it.description }

    @Test
    fun `importi in formato italiano e con punto decimale`() {
        assertEquals(130000L, parseAmountCents("+1.300,00", decimalComma = true))
        assertEquals(-2034L, parseAmountCents("-20,34", decimalComma = true))
        assertEquals(-72123L, parseAmountCents("-721.23 €", decimalComma = false))
        assertEquals(null, parseAmountCents("", decimalComma = true))
    }

    @Test
    fun `riconosce i tre formati`() {
        assertEquals(StatementFormat.MEDIOBANCA, StatementParser.parse(fixture("banca_esempio.csv")).format)
        assertEquals(StatementFormat.PAYPAL, StatementParser.parse(fixture("paypal_esempio.csv")).format)
        assertEquals(StatementFormat.ING, StatementParser.parse(fixture("ing_esempio.csv")).format)
    }

    @Test
    fun `CSV sconosciuto da errore leggibile`() {
        assertFailsWith<UnsupportedStatementException> { StatementParser.parse("a;b;c\n1;2;3\n") }
    }

    @Test
    fun `banca - classificazione dei movimenti`() {
        val tx = importAll("banca_esempio.csv")
        assertEquals(9, tx.size)
        assertEquals(Group.ENTRATE to "Stipendio", tx.byName("EMOLUMENTI").let { it.group to it.category })
        assertEquals(Group.RICORRENTI to "Affitto", tx.byName("AFFITTO").let { it.group to it.category })
        assertEquals(Group.RESTO to "Spesa", tx.byName("LIDL").let { it.group to it.category })
        assertEquals(Group.EXTRA to "Ristoranti, bar e delivery", tx.byName("PIZZERIA").let { it.group to it.category })
        assertEquals(Group.RICORRENTI to "Telefono", tx.byName("WIND TRE").let { it.group to it.category })
        assertEquals(Group.INTERNI, tx.byName("ACCANTONAMENTO").group)
        assertEquals(Group.RESTO to "Carburante", tx.byName("ESSO").let { it.group to it.category })
        // "AGOSTO" non deve far scattare la regola "AGOS" (finanziaria)
        assertEquals(Group.ENTRATE to "Rimborsi e altre entrate", tx.byName("LUCA BIANCHI").let { it.group to it.category })
        // la data usata è quella di valuta (giorno dell'acquisto)
        assertEquals(LocalDate.of(2026, 3, 8), tx.byName("LIDL").date)
    }

    @Test
    fun `parole intere - PRESSO non e ESSO`() {
        val raw = RawTransaction(LocalDate.of(2026, 1, 1), "Negozio", -1000, "Pagamento presso NEGOZIO QUALSIASI", Source.ING, "Pagamento Carta")
        assertEquals(Group.EXTRA to "Altro", Categorizer.categorize(raw))
    }

    @Test
    fun `paypal - negozi veri, rate escluse, valuta convertita`() {
        val tx = importAll("paypal_esempio.csv")
        assertEquals(5, tx.size)
        assertEquals(Group.EXTRA to "Ristoranti, bar e delivery", tx.byName("Just Eat").let { it.group to it.category })
        val flight = tx.byName("Ryanair")
        assertEquals(Group.EXTRA to "Viaggi e tempo libero", flight.group to flight.category)
        assertTrue("Paga in 3" in flight.description)
        assertEquals(Group.INTERNI, tx.byName("rata Paga in 3").group)
        assertEquals(Group.ENTRATE, tx.byName("Anna Verdi").group)
        assertEquals(-910L, tx.byName("Tutorials Dojo").amountCents)
    }

    @Test
    fun `con PayPal gli addebiti PayPal in banca non si contano due volte`() {
        val tx = importAll("banca_esempio.csv", "paypal_esempio.csv")
        assertEquals(Reconciler.PAYPAL_TOPUP, tx.first { it.source == Source.BANCA && "PAYPAL" in it.description }.category)
        val march = ReportCalculator.build(tx, YearMonth.of(2026, 3), YearMonth.of(2026, 3)).total
        assertEquals(51999L, march.ricorrenti)
        assertEquals(4550L + 910L, march.resto)
        assertEquals(2800L + 3000L + 12000L, march.extra)
    }

    @Test
    fun `ING - carta, stipendio e saldi esclusi`() {
        val tx = importAll("ing_esempio.csv")
        assertEquals(5, tx.size)
        assertEquals(Group.RESTO to "Auto", tx.byName("Autozatti").let { it.group to it.category })
        assertEquals(Group.EXTRA, tx.byName("Circolo").group)
        assertEquals(Group.RESTO to "Spese bancarie", tx.byName("Commiss").let { it.group to it.category })
        assertEquals(Group.ENTRATE to "Stipendio", tx.byName("STIPENDIO").let { it.group to it.category })
        assertEquals(Group.INTERNI, tx.byName("Giroconto").group)
    }

    @Test
    fun `report multi-mese con medie`() {
        val tx = importAll("banca_esempio.csv", "paypal_esempio.csv", "ing_esempio.csv")
        val report = ReportCalculator.build(tx, YearMonth.of(2026, 2), YearMonth.of(2026, 3))
        assertEquals(2, report.monthCount)
        val march = report.months.last().totals
        assertEquals(51999L, march.ricorrenti)
        assertEquals(4550L + 910L + 300L + 25880L, march.resto)
        assertEquals(2800L + 3000L + 12000L + 1200L, march.extra)
        assertEquals(180000L + 1500L + 188500L, march.entrate)
        assertEquals(5000L, report.months.first().totals.resto)
        assertEquals(report.total.spese / 2, report.average.spese)
    }

    @Test
    fun `reimportare lo stesso file non duplica`() {
        val use = ImportStatementsUseCase()
        val (_, stored) = use(listOf("b" to fixture("banca_esempio.csv")), emptyList())
        val (again, _) = use(listOf("b" to fixture("banca_esempio.csv")), stored)
        assertEquals(9, again.transactions.size)
        assertEquals(9, again.transactions.map { it.id }.toSet().size)
    }

    @Test
    fun `cambio gruppo manuale nel report`() {
        val tx = importAll("ing_esempio.csv")
        val autoId = tx.byName("Autozatti").id
        val report = ReportCalculator.build(tx, YearMonth.of(2026, 3), YearMonth.of(2026, 3)) { t ->
            if (t.id == autoId) Group.RICORRENTI else t.group
        }
        assertEquals(25880L, report.total.ricorrenti)
    }
}
