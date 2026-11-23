@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.gallenti.spese.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.gallenti.spese.domain.model.Group
import it.gallenti.spese.domain.model.Source
import it.gallenti.spese.domain.model.Transaction
import it.gallenti.spese.domain.report.Report
import it.gallenti.spese.domain.report.ReportCalculator
import it.gallenti.spese.domain.report.Totals
import it.gallenti.spese.ui.theme.groupColor
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val IT = Locale.ITALY
private val SPENDING = listOf(Group.RICORRENTI, Group.RESTO, Group.EXTRA)
private val CSV_TYPES = arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")

fun euro(cents: Long, decimals: Boolean = true): String {
    val f = NumberFormat.getCurrencyInstance(IT)
    if (!decimals) f.maximumFractionDigits = 0
    return f.format(cents / 100.0)
}

fun YearMonth.long(): String = month.getDisplayName(TextStyle.FULL, IT) + " $year"
fun YearMonth.short(): String = month.getDisplayName(TextStyle.SHORT, IT).take(3) + " " + (year % 100).toString().padStart(2, '0')

@Composable
fun ReportScreen(vm: ReportViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importFiles(it) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Transaction?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spese", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    TextButton(onClick = { picker.launch(CSV_TYPES) }) {
                        Text("Importa CSV", color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮", color = MaterialTheme.colorScheme.onPrimary) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Ripristina gruppi automatici") }, onClick = { menu = false; vm.resetOverrides() })
                            DropdownMenuItem(text = { Text("Cancella tutti i dati") }, onClick = { menu = false; confirmClear = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && state.transactions.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.transactions.isEmpty() -> EmptyState(Modifier.padding(padding)) { picker.launch(CSV_TYPES) }
            else -> ReportContent(state, vm, PaddingValues(top = padding.calculateTopPadding()), onEdit = { editing = it })
        }
    }

    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::consumeMessage,
            confirmButton = { TextButton(onClick = vm::consumeMessage) { Text("OK") } },
            title = { Text("Importazione") },
            text = { Text(msg) },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAll() }) { Text("Cancella") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annulla") } },
            title = { Text("Cancellare tutti i dati?") },
            text = { Text("Movimenti importati e gruppi cambiati a mano verranno eliminati dal telefono. Non si può annullare; i file CSV originali restano dove sono.") },
        )
    }
    editing?.let { t ->
        GroupDialog(t, state.groupOf(t), onDismiss = { editing = null }) { g -> vm.changeGroup(t, g); editing = null }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onImport: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Importa i tuoi estratti conto", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Scegli uno o più file CSV: elenco movimenti del conto, lista transazioni ING, export PayPal o un CSV con colonne Data, Importo e Descrizione. " +
                "L'app divide le spese in ricorrenti, extra e tutto il resto, mese per mese. I dati restano solo sul telefono.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport, modifier = Modifier.height(52.dp)) { Text("Scegli file CSV") }
    }
}

@Composable
private fun ReportContent(state: ReportUiState, vm: ReportViewModel, padding: PaddingValues, onEdit: (Transaction) -> Unit) {
    val report = state.report ?: return
    val visible = state.visibleTransactions
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PeriodPicker(state, vm) }
        item { Kpis(report) }
        item { ChartCard(state, vm) }
        items(SPENDING) { g -> GroupCard(g, report) }
        item { MonthsTable(report) }
        item { TransactionFilters(state, vm, visible) }
        items(visible.take(500), key = { it.id }) { t -> TransactionRow(t, state.groupOf(t)) { onEdit(t) } }
        if (visible.size > 500) item { Text("Mostrati i primi 500: restringi con i filtri.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Text(
                "Giroconti, risparmi, depositi vincolati e ricariche PayPal sono esclusi dai totali. Con l'estratto PayPal gli acquisti " +
                    "contano col nome del negozio e, se a rate, per intero il giorno dell'acquisto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeriodPicker(state: ReportUiState, vm: ReportViewModel) {
    val months = state.months
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Ultimo mese", 3 to "3 mesi", 6 to "6 mesi", 12 to "12 mesi", 0 to "Tutto").forEach { (n, label) ->
                val selected = state.to == months.lastOrNull() &&
                    state.from == (if (n == 0) months.firstOrNull() else months.getOrNull((months.size - n).coerceAtLeast(0)))
                FilterChip(selected = selected, onClick = { vm.setPreset(n) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MonthDropdown("Da", state.from, months, Modifier.weight(1f)) { vm.setPeriod(it, state.to ?: it) }
            MonthDropdown("A", state.to, months, Modifier.weight(1f)) { vm.setPeriod(state.from ?: it, it) }
        }
    }
}

@Composable
private fun MonthDropdown(label: String, value: YearMonth?, months: List<YearMonth>, modifier: Modifier, onPick: (YearMonth) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("$label: ${value?.long() ?: "—"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            months.asReversed().forEach { m ->
                DropdownMenuItem(text = { Text(m.long()) }, onClick = { open = false; onPick(m) })
            }
        }
    }
}

@Composable
private fun Kpis(report: Report) {
    val t = report.total
    val n = report.monthCount
    fun avg(v: Long) = if (n > 1) "${euro(v / n, false)} al mese" else ""
    fun pct(v: Long) = if (t.spese > 0) " · ${(v * 100 / t.spese)}%" else ""
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (report.from == report.to) report.from.long().replaceFirstChar { it.uppercase() }
            else "${report.from.long()} – ${report.to.long()} · $n mesi",
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            val m = Modifier.weight(1f)
            Kpi("Spese totali", t.spese, avg(t.spese), null, m)
            Kpi("Ricorrenti", t.ricorrenti, avg(t.ricorrenti) + pct(t.ricorrenti), Group.RICORRENTI, m)
            Kpi("Tutto il resto", t.resto, avg(t.resto) + pct(t.resto), Group.RESTO, m)
            Kpi("Extra", t.extra, avg(t.extra) + pct(t.extra), Group.EXTRA, m)
            Kpi("Entrate", t.entrate, avg(t.entrate), Group.ENTRATE, m)
            Kpi("Entrate − spese", t.saldo, "giroconti esclusi", if (t.saldo < 0) Group.EXTRA else Group.ENTRATE, m, colorValue = true)
        }
    }
}

@Composable
private fun Kpi(label: String, cents: Long, sub: String, group: Group?, modifier: Modifier, colorValue: Boolean = false) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                group?.takeIf { !colorValue }?.let { Dot(groupColor(it)); Spacer(Modifier.width(6.dp)) }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                euro(cents, decimals = false),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (colorValue && group != null) groupColor(group) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(sub.trim(' ', '·'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))

@Composable
private fun ChartCard(state: ReportUiState, vm: ReportViewModel) {
    val months = state.months
    val perMonth = remember(state.transactions, state.overrides) {
        val byMonth = state.transactions.groupBy { it.month }
        months.map { m -> ReportCalculator.totals(byMonth[m].orEmpty(), state::groupOf) }
    }
    val colors = SPENDING.map { groupColor(it) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Text("Spese mese per mese", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            SPENDING.forEach { g -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(groupColor(g)); Spacer(Modifier.width(4.dp)); Text(g.label, style = MaterialTheme.typography.bodySmall) } }
        }
        Canvas(
            Modifier.fillMaxWidth().height(220.dp).pointerInput(months) {
                detectTapGestures { pos ->
                    if (months.isEmpty()) return@detectTapGestures
                    val i = (pos.x / (size.width.toFloat() / months.size)).toInt().coerceIn(0, months.lastIndex)
                    vm.selectMonth(months[i])
                }
            },
        ) {
            if (months.isEmpty()) return@Canvas
            val max = perMonth.maxOf { t -> SPENDING.sumOf { t.of(it).coerceAtLeast(0) } }.coerceAtLeast(1).toFloat() * 1.05f
            val cw = size.width / months.size
            val bw = (cw * 0.68f).coerceAtMost(46.dp.toPx())
            for (k in 0..3) {
                val y = size.height * k / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            months.forEachIndexed { i, m ->
                val selected = state.from != null && state.to != null && !m.isBefore(state.from) && !m.isAfter(state.to)
                var base = 0f
                SPENDING.forEachIndexed { gi, g ->
                    val v = perMonth[i].of(g).coerceAtLeast(0).toFloat()
                    val h = size.height * v / max
                    drawRect(
                        colors[gi].copy(alpha = if (selected) 1f else 0.28f),
                        topLeft = Offset(i * cw + (cw - bw) / 2, size.height - base - h),
                        size = Size(bw, h),
                    )
                    base += h
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val step = if (months.size > 8) 2 else 1
            months.forEachIndexed { i, m ->
                Text(
                    if (i % step == 0) m.short() else "",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("Tocca una barra per vedere quel mese.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupCard(group: Group, report: Report) {
    val total = report.total.of(group)
    val cats = report.total.byCategory[group].orEmpty().entries.filter { it.value != 0L }.sortedByDescending { it.value }
    val max = cats.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    val color = groupColor(group)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(color))
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(euro(total, false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (report.monthCount > 1) {
                Text("${euro(total / report.monthCount, false)} al mese in media", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            if (cats.isEmpty()) Text("Nessuna spesa", color = MaterialTheme.colorScheme.onSurfaceVariant)
            cats.forEach { (name, v) ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(euro(v), style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(
                    progress = { (v.toFloat() / max).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun MonthsTable(report: Report) {
    Column {
        Text("Confronto tra i mesi", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        TableRow("Mese", "Ricorr.", "Resto", "Extra", "Totale", "Saldo", header = true)
        HorizontalDivider()
        report.months.forEach { r -> MonthLine(r.month.short(), r.totals) }
        if (report.monthCount > 1) {
            HorizontalDivider()
            MonthLine("Totale", report.total, bold = true)
            MonthLine("Media", report.average, bold = true)
        }
    }
}

@Composable
private fun MonthLine(label: String, t: Totals, bold: Boolean = false) =
    TableRow(label, euro(t.ricorrenti, false), euro(t.resto, false), euro(t.extra, false), euro(t.spese, false), euro(t.saldo, false), bold = bold, negative = t.saldo < 0)

@Composable
private fun TableRow(vararg cells: String, header: Boolean = false, bold: Boolean = false, negative: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        cells.forEachIndexed { i, c ->
            Text(
                c,
                modifier = Modifier.weight(if (i == 0) 1.1f else 1f),
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                fontWeight = if (bold || i == 4) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    header -> MaterialTheme.colorScheme.onSurfaceVariant
                    i == cells.lastIndex && negative -> groupColor(Group.EXTRA)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TransactionFilters(state: ReportUiState, vm: ReportViewModel, visible: List<Transaction>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Movimenti del periodo", style = MaterialTheme.typography.titleMedium)
        Text("Tocca un movimento per spostarlo in un altro gruppo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.groupFilter == null, onClick = { vm.setGroupFilter(null) }, label = { Text("Tutti") })
            Group.entries.forEach { g ->
                FilterChip(selected = state.groupFilter == g, onClick = { vm.setGroupFilter(g) }, label = { Text(g.label) })
            }
        }
        if (state.sources.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.sourceFilter == null, onClick = { vm.setSourceFilter(null) }, label = { Text("Tutte le fonti") })
                state.sources.forEach { s ->
                    FilterChip(selected = state.sourceFilter == s, onClick = { vm.setSourceFilter(s) }, label = { Text(s.label) })
                }
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            label = { Text("Cerca (es. Lidl, affitto, Amazon)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${visible.size} movimenti · somma ${euro(visible.sumOf { it.amountCents })}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DAY = DateTimeFormatter.ofPattern("dd/MM/yy")

@Composable
private fun TransactionRow(t: Transaction, group: Group, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp)) { Dot(groupColor(group)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val moved = if (group != t.group) " · spostato in ${group.label}" else ""
            Text(
                "${t.date.format(DAY)} · ${t.category}" + (if (t.source != Source.BANCA) " · ${t.source.label}" else "") + moved,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            euro(t.amountCents),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (t.amountCents > 0) groupColor(Group.ENTRATE) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GroupDialog(t: Transaction, current: Group, onDismiss: () -> Unit, onPick: (Group) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        title = { Text(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("${euro(t.amountCents)} · ${t.date.format(DAY)}\n${t.description}", style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Group.entries.forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).selectable(selected = g == current, role = Role.RadioButton) { onPick(g) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = g == current, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(g.label + if (g == t.group) " (automatico)" else "")
                    }
                }
            }
        },
    )
}
