package it.gallenti.spese.domain.report

import it.gallenti.spese.domain.model.Group
import it.gallenti.spese.domain.model.Transaction
import java.time.YearMonth

/** Totali di un insieme di movimenti (un mese o un periodo). */
data class Totals(
    val ricorrenti: Long = 0,
    val resto: Long = 0,
    val extra: Long = 0,
    val entrate: Long = 0,
    /** Spesa per gruppo e categoria, in centesimi. */
    val byCategory: Map<Group, Map<String, Long>> = emptyMap(),
) {
    val spese: Long get() = ricorrenti + resto + extra
    val saldo: Long get() = entrate - spese

    fun of(group: Group): Long = when (group) {
        Group.RICORRENTI -> ricorrenti
        Group.RESTO -> resto
        Group.EXTRA -> extra
        Group.ENTRATE -> entrate
        Group.INTERNI -> 0
    }

    operator fun div(n: Int): Totals = if (n <= 1) this else copy(
        ricorrenti = ricorrenti / n, resto = resto / n, extra = extra / n, entrate = entrate / n,
        byCategory = byCategory.mapValues { (_, m) -> m.mapValues { it.value / n } },
    )
}

data class MonthRow(val month: YearMonth, val totals: Totals)

data class Report(
    val from: YearMonth,
    val to: YearMonth,
    val months: List<MonthRow>,
    val total: Totals,
) {
    val monthCount: Int get() = months.size
    val average: Totals get() = total / monthCount.coerceAtLeast(1)
}

object ReportCalculator {

    /** Tutti i mesi coperti dai movimenti, dal più vecchio al più recente, senza buchi. */
    fun allMonths(transactions: List<Transaction>): List<YearMonth> {
        val min = transactions.minOfOrNull { it.month } ?: return emptyList()
        val max = transactions.maxOf { it.month }
        return generateSequence(min) { it.plusMonths(1) }.takeWhile { !it.isAfter(max) }.toList()
    }

    fun totals(transactions: List<Transaction>, groupOf: (Transaction) -> Group = { it.group }): Totals {
        var r = 0L; var a = 0L; var e = 0L; var i = 0L
        val cat = HashMap<Group, HashMap<String, Long>>()
        for (t in transactions) {
            when (val g = groupOf(t)) {
                Group.INTERNI -> Unit
                Group.ENTRATE -> i += t.amountCents
                Group.RICORRENTI, Group.RESTO, Group.EXTRA -> {
                    val s = t.spendingCents
                    when (g) {
                        Group.RICORRENTI -> r += s
                        Group.RESTO -> a += s
                        else -> e += s
                    }
                    cat.getOrPut(g) { HashMap() }.merge(t.category, s, Long::plus)
                }
            }
        }
        return Totals(r, a, e, i, cat)
    }

    fun build(
        transactions: List<Transaction>,
        from: YearMonth,
        to: YearMonth,
        groupOf: (Transaction) -> Group = { it.group },
    ): Report {
        val (start, end) = if (from.isAfter(to)) to to from else from to to
        val byMonth = transactions.groupBy { it.month }
        val months = generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(end) }
            .map { m -> MonthRow(m, totals(byMonth[m].orEmpty(), groupOf)) }.toList()
        val inRange = transactions.filter { !it.month.isBefore(start) && !it.month.isAfter(end) }
        return Report(start, end, months, totals(inRange, groupOf))
    }
}
