package com.luis.diario.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.luis.diario.data.VideoRef
import java.time.format.DateTimeFormatter

private val TITLE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM yyyy", ES)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: DiaryViewModel) {
    val e = vm.editor ?: return
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }

    BackHandler { vm.closeEditor() }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::addPhoto)
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::addVideo)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::closeEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                title = {
                    Column {
                        Text(
                            e.date.format(TITLE_FORMAT).replaceFirstChar { it.titlecase(ES) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (e.dirty) "Guardando…" else "Guardado",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = vm::toggleViewMode) { Text(if (e.viewMode) "Editar" else "Ver") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (e.viewMode) {
                if (e.text.text.isBlank()) {
                    Text("Sin texto. Pulsa «Editar» para escribir.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MarkdownView(e.text.text, e.mediaUris, Modifier.fillMaxWidth())
                }
            } else {
                FormatBar(
                    onHeading = { vm.updateText(prefixLine(e.text, "## ")) },
                    onBold = { vm.updateText(wrap(e.text, "**", "**")) },
                    onItalic = { vm.updateText(wrap(e.text, "_", "_")) },
                    onList = { vm.updateText(prefixLine(e.text, "- ")) },
                    onLink = { vm.updateText(wrap(e.text, "[", "](https://)")) },
                    onPhoto = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                OutlinedTextField(
                    value = e.text,
                    onValueChange = vm::updateText,
                    placeholder = { Text("¿Qué has entrenado hoy?") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = fieldColors(),
                )
                if (e.mediaUris.keys.any { it.startsWith("foto-") }) {
                    Text(
                        "Las fotos aparecen como ![](media/…). Pulsa «Ver» para verlas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionLabel("VÍDEOS")
            e.videos.forEachIndexed { i, v ->
                VideoRow(
                    video = v,
                    thumb = v.miniatura?.let { e.mediaUris[it.removePrefix("media/")] },
                    onPlay = {
                        val uri = e.mediaUris[v.archivo.removePrefix("media/")]
                        if (uri == null) {
                            Toast.makeText(context, "No se encuentra el archivo del vídeo", Toast.LENGTH_SHORT).show()
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "video/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(intent)
                            } catch (ex: ActivityNotFoundException) {
                                Toast.makeText(context, "No hay reproductor de vídeo", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onRename = { renaming = i },
                    onDelete = { deleting = i },
                )
            }
            OutlinedButton(onClick = {
                pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }) { Text("+ Añadir vídeo") }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionLabel("DETALLES")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = e.duracion,
                    onValueChange = vm::updateDuration,
                    label = { Text("Minutos") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                    colors = fieldColors(),
                )
                OutlinedTextField(
                    value = e.tags,
                    onValueChange = vm::updateTags,
                    label = { Text("Etiquetas") },
                    placeholder = { Text("forma, sanda") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = fieldColors(),
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    renaming?.let { idx ->
        val current = e.videos.getOrNull(idx)
        if (current == null) {
            renaming = null
        } else {
            var title by remember(idx) { mutableStateOf(current.titulo) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Título del vídeo") },
                text = {
                    OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, colors = fieldColors())
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.renameVideo(idx, title.trim().ifEmpty { current.titulo })
                        renaming = null
                    }) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
            )
        }
    }

    deleting?.let { idx ->
        val current = e.videos.getOrNull(idx)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar vídeo?") },
            text = { Text("Se eliminará «${current?.titulo ?: ""}» y su archivo.") },
            confirmButton = {
                TextButton(onClick = { vm.removeVideo(idx); deleting = null }) {
                    Text("Borrar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FormatBar(
    onHeading: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onList: () -> Unit,
    onLink: () -> Unit,
    onPhoto: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormatChip("Título", onHeading)
        FormatChip("B", onBold, bold = true)
        FormatChip("I", onItalic, italic = true)
        FormatChip("• Lista", onList)
        FormatChip("Enlace", onLink)
        FormatChip("+ Foto", onPhoto, accent = true)
    }
}

@Composable
private fun FormatChip(label: String, onClick: () -> Unit, bold: Boolean = false, italic: Boolean = false, accent: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
            color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun VideoRow(video: VideoRef, thumb: android.net.Uri?, onPlay: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(width = 112.dp, height = 64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Icon(Icons.Filled.PlayArrow, "Reproducir", tint = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            video.titulo,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).clickable(onClick = onRename),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, "Renombrar") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Borrar") }
    }
}

// ---------- ayudas de formato sobre el texto ----------

private fun wrap(v: TextFieldValue, before: String, after: String): TextFieldValue {
    val t = v.text
    val start = v.selection.min.coerceIn(0, t.length)
    val end = v.selection.max.coerceIn(0, t.length)
    val selected = t.substring(start, end)
    val newText = t.substring(0, start) + before + selected + after + t.substring(end)
    val sel = if (selected.isEmpty()) TextRange(start + before.length)
    else TextRange(start + before.length, start + before.length + selected.length)
    return TextFieldValue(newText, sel)
}

private fun prefixLine(v: TextFieldValue, prefix: String): TextFieldValue {
    val t = v.text
    val pos = v.selection.min.coerceIn(0, t.length)
    val lineStart = t.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0 || pos == 0) 0 else it + 1 }
    if (t.startsWith(prefix, lineStart)) {
        // Si ya lo tiene, se quita (alternar).
        val newText = t.removeRange(lineStart, lineStart + prefix.length)
        return TextFieldValue(newText, TextRange((pos - prefix.length).coerceAtLeast(lineStart)))
    }
    val newText = t.substring(0, lineStart) + prefix + t.substring(lineStart)
    return TextFieldValue(newText, TextRange(pos + prefix.length))
}
