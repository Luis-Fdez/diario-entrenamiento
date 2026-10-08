package com.luis.diario.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.luis.diario.data.Decision
import com.luis.diario.data.Entry
import java.time.format.DateTimeFormatter

@Composable
fun BusyDialog(text: String) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun MessageDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Vale") } },
    )
}

private val DATE = DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", ES)
private val STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", ES)

@Composable
fun ImportDialogs(vm: DiaryViewModel, imp: ImportUi) {
    if (!imp.showingConflicts) {
        val n = imp.plan.newDays.size
        val c = imp.plan.conflicts.size
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("Importar copia") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$n entrada(s) nueva(s) se añadirán.")
                    if (c > 0) Text("$c ya existe(n) en tu diario: te preguntaré qué hacer con cada una.")
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmImportSummary) { Text(if (c > 0) "Continuar" else "Importar") } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancelar") } },
        )
        return
    }

    val conflict = imp.current ?: return
    var applyToRest by remember { mutableStateOf(false) }
    val total = imp.plan.conflicts.size
    val remaining = total - imp.index - 1
    val localNewer = conflict.local.modificado.isAfter(conflict.imported.modificado)

    Dialog(onDismissRequest = vm::cancelImport, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Conflicto ${imp.index + 1} de $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(conflict.date.format(DATE), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EntryCard("Actual", conflict.local, newer = localNewer, modifier = Modifier.weight(1f))
                    EntryCard("Importada", conflict.imported, newer = !localNewer, modifier = Modifier.weight(1f))
                }
                if (remaining > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clickable { applyToRest = !applyToRest },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = applyToRest, onCheckedChange = { applyToRest = it })
                        Text("Aplicar a los $remaining restantes")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.resolveConflict(Decision.KEEP, applyToRest) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Mantener la actual")
                    }
                    OutlinedButton(onClick = { vm.resolveConflict(Decision.REPLACE, applyToRest) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Usar la importada")
                    }
                    Button(onClick = { vm.resolveConflict(Decision.MERGE, applyToRest) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Combinar las dos")
                    }
                    TextButton(onClick = vm::cancelImport, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Cancelar importación")
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(label: String, entry: Entry, newer: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            if (newer) 1.5.dp else 1.dp,
            if (newer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            if (newer) Text("Más reciente", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                entry.preview(140),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.photoRefs().size} foto(s) · ${entry.videos.size} vídeo(s)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Modificada ${entry.modificado.format(STAMP)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
