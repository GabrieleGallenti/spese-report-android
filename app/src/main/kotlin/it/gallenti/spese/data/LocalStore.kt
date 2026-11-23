package it.gallenti.spese.data

import android.content.Context
import it.gallenti.spese.domain.model.Group
import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Salvataggio locale (memoria privata dell'app, esclusa dai backup).
 * Si salvano i movimenti letti dai CSV e i cambi di gruppo fatti a mano.
 */
class LocalStore(context: Context) {

    private val file = File(context.filesDir, "movimenti.json")
    private val prefs = context.getSharedPreferences("override_gruppi", Context.MODE_PRIVATE)

    suspend fun loadTransactions(): List<RawTransaction> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it).toRaw() }
        }.getOrDefault(emptyList())
    }

    suspend fun saveTransactions(list: List<RawTransaction>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    fun loadOverrides(): Map<String, Group> =
        prefs.all.mapNotNull { (id, v) -> (v as? String)?.let { name -> runCatching { id to Group.valueOf(name) }.getOrNull() } }.toMap()

    fun setOverride(id: String, group: Group?) {
        prefs.edit().apply { if (group == null) remove(id) else putString(id, group.name) }.apply()
    }

    fun clearOverrides() = prefs.edit().clear().apply()

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        file.delete()
        clearOverrides()
    }

    private fun RawTransaction.toJson() = JSONObject().apply {
        put("d", date.toString())
        put("n", name)
        put("a", amountCents)
        put("ds", description)
        put("s", source.name)
        put("k", kind)
        mcc?.let { put("m", it) }
        forced?.let { (g, c) -> put("fg", g.name); put("fc", c) }
    }

    private fun JSONObject.toRaw() = RawTransaction(
        date = LocalDate.parse(getString("d")),
        name = getString("n"),
        amountCents = getLong("a"),
        description = getString("ds"),
        source = Source.valueOf(getString("s")),
        kind = optString("k"),
        mcc = optString("m").ifEmpty { null },
        forced = if (has("fg")) Group.valueOf(getString("fg")) to getString("fc") else null,
    )
}
