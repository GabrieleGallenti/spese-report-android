package it.gallenti.spese.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.gallenti.spese.data.LocalStore
import it.gallenti.spese.domain.FileOutcome
import it.gallenti.spese.domain.ImportStatementsUseCase
import it.gallenti.spese.domain.categorize.Reconciler
import it.gallenti.spese.domain.model.Group
import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Source
import it.gallenti.spese.domain.model.Transaction
import it.gallenti.spese.domain.report.Report
import it.gallenti.spese.domain.report.ReportCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.YearMonth

data class ReportUiState(
    val loading: Boolean = true,
    val transactions: List<Transaction> = emptyList(),
    val overrides: Map<String, Group> = emptyMap(),
    val months: List<YearMonth> = emptyList(),
    val from: YearMonth? = null,
    val to: YearMonth? = null,
    val report: Report? = null,
    val groupFilter: Group? = null,
    val sourceFilter: Source? = null,
    val query: String = "",
    val message: String? = null,
) {
    fun groupOf(t: Transaction): Group = overrides[t.id] ?: t.group

    val visibleTransactions: List<Transaction>
        get() {
            val f = from ?: return emptyList()
            val l = to ?: return emptyList()
            val q = query.trim().lowercase()
            return transactions.filter { t ->
                !t.month.isBefore(f) && !t.month.isAfter(l) &&
                    (groupFilter == null || groupOf(t) == groupFilter) &&
                    (sourceFilter == null || t.source == sourceFilter) &&
                    (q.isEmpty() || "${t.name} ${t.category} ${t.description}".lowercase().contains(q))
            }
        }

    val sources: List<Source> get() = transactions.map { it.source }.distinct().sorted()
}

class ReportViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LocalStore(app)
    private val importStatements = ImportStatementsUseCase()
    private var raw: List<RawTransaction> = emptyList()

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            raw = store.loadTransactions()
            val tx = withContext(Dispatchers.Default) { Reconciler.reconcile(raw) }
            setData(tx, keepPeriod = false)
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val files = withContext(Dispatchers.IO) { uris.map { readFile(it) } }
                val (summary, merged) = withContext(Dispatchers.Default) { importStatements(files, raw) }
                raw = merged
                store.saveTransactions(merged)
                setData(summary.transactions, keepPeriod = false)
                _state.update { it.copy(message = summary.files.joinToString("\n") { o -> o.describe() }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = "Import non riuscito: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun setPeriod(from: YearMonth, to: YearMonth) = _state.update { s ->
        val (a, b) = if (from.isAfter(to)) to to from else from to to
        s.copy(from = a, to = b).withReport()
    }

    /** Ultimi [n] mesi (0 = tutto). */
    fun setPreset(n: Int) {
        val months = _state.value.months
        if (months.isEmpty()) return
        val to = months.last()
        val from = if (n <= 0) months.first() else months[(months.size - n).coerceAtLeast(0)]
        setPeriod(from, to)
    }

    fun selectMonth(m: YearMonth) = setPeriod(m, m)

    fun setGroupFilter(g: Group?) = _state.update { it.copy(groupFilter = g) }
    fun setSourceFilter(s: Source?) = _state.update { it.copy(sourceFilter = s) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun changeGroup(t: Transaction, group: Group) {
        val newValue = group.takeIf { it != t.group }
        store.setOverride(t.id, newValue)
        _state.update { s ->
            val o = s.overrides.toMutableMap().apply { if (newValue == null) remove(t.id) else put(t.id, newValue) }
            s.copy(overrides = o).withReport()
        }
    }

    fun resetOverrides() {
        store.clearOverrides()
        _state.update { it.copy(overrides = emptyMap(), message = "Gruppi riportati alla classificazione automatica").withReport() }
    }

    fun clearAll() = viewModelScope.launch {
        store.clearAll()
        raw = emptyList()
        _state.value = ReportUiState(loading = false, message = "Dati cancellati dal telefono")
    }

    private fun setData(tx: List<Transaction>, keepPeriod: Boolean) {
        val months = ReportCalculator.allMonths(tx)
        _state.update { s ->
            val to = if (keepPeriod && s.to in months) s.to else months.lastOrNull()
            val from = if (keepPeriod && s.from in months) s.from else months.getOrNull((months.size - 6).coerceAtLeast(0))
            s.copy(loading = false, transactions = tx, overrides = store.loadOverrides(), months = months, from = from, to = to)
                .withReport()
        }
    }

    private fun ReportUiState.withReport(): ReportUiState {
        val f = from ?: return copy(report = null)
        val l = to ?: return copy(report = null)
        return copy(report = ReportCalculator.build(transactions, f, l, this::groupOf))
    }

    private fun readFile(uri: Uri): Pair<String, String> {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Impossibile aprire $name")
        require(bytes.size <= MAX_FILE_BYTES) { "$name è troppo grande (max 20 MB)" }
        return name to decode(bytes)
    }

    /** UTF-8 se valido, altrimenti Windows-1252 (export di molte banche). */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }

    private fun FileOutcome.describe(): String = when (this) {
        is FileOutcome.Imported -> "✓ $fileName: ${result.transactions.size} movimenti (${result.format.label})" +
            if (result.skippedRows > 0) ", ${result.skippedRows} righe ignorate" else ""
        is FileOutcome.Failed -> "✗ $fileName: $reason"
    }

    private companion object {
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
    }
}
