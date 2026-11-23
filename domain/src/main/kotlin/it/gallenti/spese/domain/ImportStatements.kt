package it.gallenti.spese.domain

import it.gallenti.spese.domain.categorize.Reconciler
import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Transaction
import it.gallenti.spese.domain.parser.ParseResult
import it.gallenti.spese.domain.parser.StatementParser

/** Esito dell'importazione di un singolo file. */
sealed interface FileOutcome {
    val fileName: String

    data class Imported(override val fileName: String, val result: ParseResult) : FileOutcome
    data class Failed(override val fileName: String, val reason: String) : FileOutcome
}

data class ImportSummary(val transactions: List<Transaction>, val files: List<FileOutcome>)

/**
 * Caso d'uso: legge più estratti conto (anche di formati diversi), li unisce
 * a quelli già salvati e riclassifica tutto insieme.
 *
 * Lo stesso file importato due volte non duplica i movimenti.
 */
class ImportStatementsUseCase {

    operator fun invoke(files: List<Pair<String, String>>, alreadyStored: List<RawTransaction>): Pair<ImportSummary, List<RawTransaction>> {
        val outcomes = files.map { (name, text) ->
            runCatching { StatementParser.parse(text) }.fold(
                onSuccess = { FileOutcome.Imported(name, it) },
                onFailure = { FileOutcome.Failed(name, it.message ?: "File non leggibile") },
            )
        }
        val incoming = outcomes.filterIsInstance<FileOutcome.Imported>().flatMap { it.result.transactions }
        // Un estratto nuovo della stessa fonte sostituisce i giorni che copre: niente doppioni.
        val replaced = incoming.groupBy { it.source }.mapValues { (_, l) -> l.minOf { it.date } to l.maxOf { it.date } }
        val kept = alreadyStored.filter { old ->
            val range = replaced[old.source] ?: return@filter true
            old.date.isBefore(range.first) || old.date.isAfter(range.second)
        }
        val merged = kept + incoming
        return ImportSummary(Reconciler.reconcile(merged), outcomes) to merged
    }
}
