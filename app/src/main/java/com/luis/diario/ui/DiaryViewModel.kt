package com.luis.diario.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luis.diario.data.Conflict
import com.luis.diario.data.Decision
import com.luis.diario.data.DiaryRepository
import com.luis.diario.data.Entry
import com.luis.diario.data.ImportPlan
import com.luis.diario.data.MediaImporter
import com.luis.diario.data.VideoRef
import com.luis.diario.data.ZipBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

data class MonthItem(val date: LocalDate, val preview: String, val videos: Int, val duracion: Int?)

data class UiState(
    val rootUri: Uri? = null,
    val month: YearMonth = YearMonth.now(),
    val daysWithEntries: Set<Int> = emptySet(),
    val monthItems: List<MonthItem> = emptyList(),
    val busy: String? = null,
    val message: String? = null,
    val lastExport: Instant? = null,
    val hasEntries: Boolean = false,
    val import: ImportUi? = null,
) {
    val backupOverdue: Boolean
        get() = rootUri != null && hasEntries &&
            (lastExport == null || Duration.between(lastExport, Instant.now()).toDays() >= 30)
}

data class ImportUi(
    val plan: ImportPlan,
    val showingConflicts: Boolean = false,
    val index: Int = 0,
    val decisions: Map<LocalDate, Decision> = emptyMap(),
) {
    val current: Conflict? get() = plan.conflicts.getOrNull(index)
}

data class EditorState(
    val date: LocalDate,
    val text: TextFieldValue,
    val videos: List<VideoRef>,
    val duracion: String,
    val tags: String,
    val mediaUris: Map<String, Uri>,
    val viewMode: Boolean,
    val rev: Int = 0,
    val savedRev: Int = 0,
) {
    val dirty get() = rev != savedRev

    fun toEntry() = Entry(
        fecha = date,
        modificado = OffsetDateTime.now(),
        contenido = text.text,
        videos = videos,
        duracion = duracion.toIntOrNull(),
        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )
}

class DiaryViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("diario", Context.MODE_PRIVATE)

    var state by mutableStateOf(UiState())
        private set
    var editor by mutableStateOf<EditorState?>(null)
        private set

    private var repo: DiaryRepository? = null
    private var importer: MediaImporter? = null
    private var backup: ZipBackup? = null
    private var saveJob: Job? = null
    private val saveMutex = Mutex()

    init {
        val last = prefs.getLong(KEY_LAST_EXPORT, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        state = state.copy(lastExport = last)
        val saved = prefs.getString(KEY_ROOT, null)?.let(Uri::parse)
        if (saved != null &&
            ctx.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }
        ) {
            useRoot(saved)
        }
    }

    // ---------------- carpeta raíz ----------------

    fun setRoot(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
        prefs.edit().putString(KEY_ROOT, uri.toString()).apply()
        useRoot(uri)
    }

    private fun useRoot(uri: Uri) {
        val r = runCatching { DiaryRepository(ctx, uri) }.getOrNull()
        if (r == null) {
            state = state.copy(rootUri = null, message = "No se puede acceder a la carpeta. Elige otra.")
            return
        }
        repo = r
        importer = MediaImporter(ctx, r)
        backup = ZipBackup(ctx, r)
        state = state.copy(rootUri = uri)
        refreshMonth()
    }

    // ---------------- calendario ----------------

    fun changeMonth(delta: Long) {
        state = state.copy(month = state.month.plusMonths(delta), daysWithEntries = emptySet(), monthItems = emptyList())
        refreshMonth()
    }

    fun goToToday() {
        if (state.month != YearMonth.now()) {
            state = state.copy(month = YearMonth.now())
            refreshMonth()
        }
    }

    fun refreshMonth() {
        val r = repo ?: return
        val month = state.month
        viewModelScope.launch {
            val (days, items) = withContext(Dispatchers.IO) {
                val d = r.daysWithEntries(month)
                val list = d.sorted().mapNotNull { day ->
                    val date = month.atDay(day)
                    r.readEntry(date)?.let { MonthItem(date, it.preview(), it.videos.size, it.duracion) }
                }
                d to list
            }
            val any = state.hasEntries || days.isNotEmpty() ||
                withContext(Dispatchers.IO) { r.allDays().isNotEmpty() }
            state = state.copy(hasEntries = any)
            if (state.month == month) state = state.copy(daysWithEntries = days, monthItems = items)
        }
    }

    // ---------------- editor ----------------

    fun openDay(date: LocalDate) {
        val r = repo ?: return
        viewModelScope.launch {
            val (entry, media) = withContext(Dispatchers.IO) {
                (r.readEntry(date) ?: Entry.empty(date)) to r.mediaFiles(date)
            }
            editor = EditorState(
                date = date,
                text = TextFieldValue(entry.contenido),
                videos = entry.videos,
                duracion = entry.duracion?.toString() ?: "",
                tags = entry.tags.joinToString(", "),
                mediaUris = media,
                viewMode = entry.contenido.isNotBlank(),
            )
        }
    }

    fun toggleViewMode() {
        editor = editor?.let { it.copy(viewMode = !it.viewMode) }
    }

    private fun change(transform: (EditorState) -> EditorState) {
        val e = editor ?: return
        editor = transform(e).let { it.copy(rev = e.rev + 1) }
        scheduleSave()
    }

    fun updateText(v: TextFieldValue) {
        val e = editor ?: return
        if (v.text == e.text.text) {
            editor = e.copy(text = v) // solo cambia el cursor o la selección
        } else {
            change { it.copy(text = v) }
        }
    }

    fun updateDuration(s: String) = change { it.copy(duracion = s.filter(Char::isDigit).take(4)) }
    fun updateTags(s: String) = change { it.copy(tags = s) }

    fun renameVideo(index: Int, title: String) = change { e ->
        e.copy(videos = e.videos.mapIndexed { i, v -> if (i == index) v.copy(titulo = title) else v })
    }

    fun removeVideo(index: Int) {
        val e = editor ?: return
        val v = e.videos.getOrNull(index) ?: return
        change { it.copy(videos = it.videos.filterIndexed { i, _ -> i != index }) }
        val r = repo ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                r.deleteMedia(e.date, v.archivo.removePrefix("media/"))
                v.miniatura?.let { r.deleteMedia(e.date, it.removePrefix("media/")) }
            }
            refreshMedia(e.date)
        }
    }

    private suspend fun refreshMedia(date: LocalDate) {
        val r = repo ?: return
        val media = withContext(Dispatchers.IO) { r.mediaFiles(date) }
        editor = editor?.takeIf { it.date == date }?.copy(mediaUris = media) ?: editor
    }

    fun addPhoto(uri: Uri) {
        val e = editor ?: return
        val imp = importer ?: return
        viewModelScope.launch {
            state = state.copy(busy = "Añadiendo foto…")
            try {
                val path = imp.importPhoto(e.date, uri)
                refreshMedia(e.date)
                change { cur ->
                    val t = cur.text.text
                    val at = cur.text.selection.end.coerceIn(0, t.length)
                    val before = if (at > 0 && t[at - 1] != '\n') "\n" else ""
                    val insert = "$before![]($path)\n"
                    cur.copy(
                        text = TextFieldValue(
                            t.substring(0, at) + insert + t.substring(at),
                            TextRange(at + insert.length),
                        ),
                    )
                }
            } catch (ex: Exception) {
                state = state.copy(message = "No se pudo añadir la foto: ${ex.message}")
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun addVideo(uri: Uri) {
        val e = editor ?: return
        val imp = importer ?: return
        viewModelScope.launch {
            state = state.copy(busy = "Comprimiendo vídeo… puede tardar un poco")
            try {
                val ref = imp.importVideo(e.date, uri, "Vídeo ${e.videos.size + 1}")
                refreshMedia(e.date)
                change { it.copy(videos = it.videos + ref) }
            } catch (ex: Exception) {
                state = state.copy(message = "No se pudo añadir el vídeo: ${ex.message}")
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1200)
            editor?.let { persist(it) }
        }
    }

    /** Guardado inmediato (al salir de la app o del editor). */
    fun saveNow() {
        saveJob?.cancel()
        val e = editor ?: return
        viewModelScope.launch { persist(e) }
    }

    private suspend fun persist(e: EditorState) {
        if (!e.dirty) return
        val r = repo ?: return
        val entry = e.toEntry()
        try {
            saveMutex.withLock {
                withContext(Dispatchers.IO) {
                    // No creamos archivos para un día que sigue vacío.
                    if (!(entry.isEmpty && r.readEntry(e.date) == null)) r.writeEntry(entry)
                }
            }
            editor = editor?.let { cur -> if (cur.date == e.date) cur.copy(savedRev = e.rev) else cur }
        } catch (ex: Exception) {
            state = state.copy(message = "Error al guardar: ${ex.message}")
        }
    }

    fun closeEditor() {
        val e = editor ?: return
        saveJob?.cancel()
        editor = null
        val r = repo ?: return
        viewModelScope.launch {
            persist(e)
            // Borra fotos que ya no aparecen en el texto.
            withContext(Dispatchers.IO) {
                runCatching {
                    val used = e.toEntry().referencedMedia()
                    r.mediaFiles(e.date).keys
                        .filter { it.startsWith("foto-") && it !in used }
                        .forEach { r.deleteMedia(e.date, it) }
                }
            }
            refreshMonth()
        }
    }

    // ---------------- exportar ----------------

    fun export(target: Uri, sinceLast: Boolean) {
        val b = backup ?: return
        val since = if (sinceLast) state.lastExport else null
        viewModelScope.launch {
            state = state.copy(busy = "Exportando…")
            try {
                val n = withContext(Dispatchers.IO) {
                    b.export(target, since) { i, total ->
                        withContext(Dispatchers.Main) { state = state.copy(busy = "Exportando $i de $total días…") }
                    }
                }
                val now = Instant.now()
                prefs.edit().putLong(KEY_LAST_EXPORT, now.toEpochMilli()).apply()
                state = state.copy(lastExport = now, message = "Copia creada con $n día(s).")
            } catch (ex: Exception) {
                state = state.copy(message = "Error al exportar: ${ex.message}")
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    // ---------------- importar ----------------

    fun startImport(src: Uri) {
        val b = backup ?: return
        viewModelScope.launch {
            state = state.copy(busy = "Leyendo copia…")
            try {
                val plan = withContext(Dispatchers.IO) { b.prepareImport(src) }
                if (plan.newDays.isEmpty() && plan.conflicts.isEmpty()) {
                    plan.tempDir.deleteRecursively()
                    state = state.copy(message = "La copia no contiene entradas.")
                } else {
                    state = state.copy(import = ImportUi(plan))
                }
            } catch (ex: Exception) {
                state = state.copy(message = ex.message ?: "No se pudo leer la copia")
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun confirmImportSummary() {
        val imp = state.import ?: return
        if (imp.plan.conflicts.isEmpty()) applyImport(imp)
        else state = state.copy(import = imp.copy(showingConflicts = true))
    }

    fun resolveConflict(decision: Decision, applyToRest: Boolean) {
        val imp = state.import ?: return
        val conflicts = imp.plan.conflicts
        val decisions = imp.decisions.toMutableMap()
        val range = if (applyToRest) imp.index until conflicts.size else imp.index..imp.index
        range.forEach { decisions[conflicts[it].date] = decision }
        val next = range.last + 1
        val updated = imp.copy(index = next, decisions = decisions)
        if (next >= conflicts.size) applyImport(updated) else state = state.copy(import = updated)
    }

    fun cancelImport() {
        state.import?.plan?.tempDir?.deleteRecursively()
        state = state.copy(import = null)
    }

    private fun applyImport(imp: ImportUi) {
        val b = backup ?: return
        state = state.copy(import = null, busy = "Importando…")
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    b.applyImport(imp.plan, imp.decisions) { i, total ->
                        withContext(Dispatchers.Main) { state = state.copy(busy = "Importando $i de $total…") }
                    }
                }
                state = state.copy(
                    message = buildString {
                        append("Importación terminada.\n")
                        append("Nuevas: ${res.added}\n")
                        if (res.replaced > 0) append("Reemplazadas: ${res.replaced}\n")
                        if (res.merged > 0) append("Combinadas: ${res.merged}\n")
                        if (res.kept > 0) append("Sin cambios: ${res.kept}")
                    }.trim(),
                )
            } catch (ex: Exception) {
                state = state.copy(message = "Error al importar: ${ex.message}")
            } finally {
                withContext(Dispatchers.IO) { imp.plan.tempDir.deleteRecursively() }
                state = state.copy(busy = null)
                refreshMonth()
            }
        }
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    companion object {
        private const val KEY_ROOT = "root_uri"
        private const val KEY_LAST_EXPORT = "last_export"
    }
}
