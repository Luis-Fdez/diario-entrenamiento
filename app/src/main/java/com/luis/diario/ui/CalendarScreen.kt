package com.luis.diario.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val ES: Locale = Locale.forLanguageTag("es-ES")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: DiaryViewModel, onChangeFolder: () -> Unit) {
    val s = vm.state
    var menu by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var sinceLast by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { vm.export(it, sinceLast) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::startImport) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Entrenamiento", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menú") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Exportar copia (ZIP)") }, onClick = {
                            menu = false; exportDialog = true
                        })
                        DropdownMenuItem(text = { Text("Importar copia (ZIP)") }, onClick = {
                            menu = false
                            importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        })
                        DropdownMenuItem(text = { Text("Cambiar carpeta del diario") }, onClick = {
                            menu = false; onChangeFolder()
                        })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.openDay(LocalDate.now()) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Hoy") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.backupOverdue) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (s.lastExport == null) "Aún no has hecho ninguna copia de seguridad."
                                else "Hace más de 30 días de tu última copia.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { exportDialog = true }) { Text("Exportar") }
                        }
                    }
                }
            }
            item { MonthHeader(s.month.atDay(1), onPrev = { vm.changeMonth(-1) }, onNext = { vm.changeMonth(1) }, onToday = vm::goToToday) }
            item { MonthGrid(s.month.atDay(1), s.daysWithEntries, onDay = vm::openDay) }
            item {
                Text(
                    if (s.monthItems.isEmpty()) "Sin entradas este mes" else "ENTRADAS DEL MES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(s.monthItems.sortedByDescending { it.date }, key = { it.date.toString() }) { item ->
                EntryRow(item) { vm.openDay(item.date) }
            }
        }
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("Exportar copia") },
            text = {
                Column {
                    ChoiceRow("Todo el diario", selected = !sinceLast) { sinceLast = false }
                    ChoiceRow(
                        "Desde la última exportación",
                        selected = sinceLast,
                        enabled = s.lastExport != null,
                    ) { sinceLast = true }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Los vídeos ocupan lo mismo dentro del ZIP. Guárdalo fuera del móvil.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportDialog = false
                    exportLauncher.launch("entrenamiento-backup-${LocalDate.now()}.zip")
                }) { Text("Elegir destino") }
            },
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun MonthHeader(first: LocalDate, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val name = first.month.getDisplayName(TextStyle.FULL_STANDALONE, ES).replaceFirstChar { it.titlecase(ES) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mes anterior") }
        Text(
            "$name ${first.year}",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).clickable(onClick = onToday).padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mes siguiente") }
    }
}

@Composable
private fun MonthGrid(first: LocalDate, withEntries: Set<Int>, onDay: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val offset = first.dayOfWeek.value - 1 // lunes = 0
    val days = first.lengthOfMonth()
    val cells = offset + days
    val rows = (cells + 6) / 7
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("L", "M", "X", "J", "V", "S", "D").forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..days) {
                            val date = first.withDayOfMonth(dayNum)
                            val has = dayNum in withEntries
                            val isToday = date == today
                            val shape = CircleShape
                            var m = Modifier.fillMaxSize().clip(shape)
                            m = if (has) m.background(MaterialTheme.colorScheme.primary) else m
                            m = if (isToday && !has) m.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else m
                            Box(m.clickable { onDay(date) }, contentAlignment = Alignment.Center) {
                                Text(
                                    "$dayNum",
                                    color = when {
                                        has -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (has || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d", ES)

@Composable
private fun EntryRow(item: MonthItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.date.format(DAY_FORMAT).replaceFirstChar { it.titlecase(ES) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                val extra = buildList {
                    item.duracion?.let { add("$it min") }
                    if (item.videos > 0) add("${item.videos} vídeo" + if (item.videos > 1) "s" else "")
                }.joinToString(" · ")
                if (extra.isNotEmpty()) {
                    Text(extra, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                item.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
