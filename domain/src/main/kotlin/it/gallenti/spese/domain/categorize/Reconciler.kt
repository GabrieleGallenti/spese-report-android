package it.gallenti.spese.domain.categorize

import it.gallenti.spese.domain.model.Group
import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Source
import it.gallenti.spese.domain.model.Transaction
import java.security.MessageDigest

/**
 * Unisce movimenti di più estratti conto evitando i doppi conteggi.
 *
 * Se c'è l'estratto PayPal, gli addebiti "PayPal" su banca/ING dalla data di inizio
 * dell'estratto PayPal diventano giroconti: la spesa vera è la riga PayPal col negozio.
 */
object Reconciler {

    const val PAYPAL_TOPUP = "Ricarica PayPal (dettaglio nelle righe PayPal)"

    fun reconcile(raw: List<RawTransaction>): List<Transaction> {
        val paypalStart = raw.filter { it.source == Source.PAYPAL }.minOfOrNull { it.date }
        val seen = HashMap<String, Int>()
        return raw.map { r ->
            var (group, category) = Categorizer.categorize(r)
            val mentionsPayPal = "PAYPAL" in "${r.name} ${r.description}".uppercase()
            if (paypalStart != null && r.source != Source.PAYPAL && mentionsPayPal && !r.date.isBefore(paypalStart) &&
                group != Group.INTERNI
            ) {
                group = Group.INTERNI
                category = PAYPAL_TOPUP
            }
            val key = "${r.source}|${r.date}|${r.amountCents}|${r.description}"
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            Transaction(
                id = sha1("$key|$n"),
                date = r.date,
                name = r.name,
                amountCents = r.amountCents,
                group = group,
                category = category,
                description = r.description,
                source = r.source,
            )
        }.sortedByDescending { it.date }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
