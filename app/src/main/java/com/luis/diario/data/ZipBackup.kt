package com.luis.diario.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class Decision { KEEP, REPLACE, MERGE }

data class Conflict(val date: LocalDate, val local: Entry, val imported: Entry)

data class ImportPlan(
    val tempDir: File,
    val newDays: List<LocalDate>,
    val conflicts: List<Conflict>,
)

data class ImportResult(val added: Int, val replaced: Int, val merged: Int, val kept: Int)

/**
 * Copias de seguridad en ZIP. El ZIP contiene la misma estructura de carpetas + manifest.json.
 * JSON comprimido; fotos y vídeos sin comprimir (STORED), porque ya vienen comprimidos.
 */
class ZipBackup(private val context: Context, private val repo: DiaryRepository) {

    private val resolver = context.contentResolver

    /** Funciones bloqueantes: llamar desde Dispatchers.IO. Devuelve el número de días exportados. */
    suspend fun export(target: Uri, since: Instant?, onProgress: suspend (Int, Int) -> Unit): Int {
        val days = repo.allDays().filter { day ->
            if (since == null) true
            else repo.readEntry(day)?.modificado?.toInstant()?.isAfter(since) == true
        }
        val manifest = JSONObject().apply {
            put("app", APP_ID)
            put("version", Entry.SCHEMA_VERSION)
            put("exportado", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            if (days.isNotEmpty()) {
                put("desde", days.first().toString())
                put("hasta", days.last().toString())
            }
            put("dias", days.size)
        }

        val out = resolver.openOutputStream(target, "w") ?: error("No se pudo crear el archivo")
        ZipOutputStream(BufferedOutputStream(out, DiaryRepository.BUFFER)).use { zip ->
            putDeflated(zip, MANIFEST, manifest.toString(2).toByteArray())
            days.forEachIndexed { i, day ->
                val dir = repo.dayDir(day, false)
                if (dir != null) {
                    val prefix = dayPath(day)
                    val files = dir.listFiles()
                    files.firstOrNull { it.name == DiaryRepository.ENTRY }?.let { f ->
                        val bytes = resolver.openInputStream(f.uri)!!.use { it.readBytes() }
                        putDeflated(zip, prefix + DiaryRepository.ENTRY, bytes)
                    }
                    files.firstOrNull { it.name == DiaryRepository.MEDIA && it.isDirectory }
                        ?.listFiles()
                        ?.filter { it.isFile && it.name != null }
                        ?.forEach { f -> putStored(zip, prefix + "media/" + f.name, f.uri) }
                }
                onProgress(i + 1, days.size)
            }
        }
        return days.size
    }

    private fun putDeflated(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.DEFLATED
        zip.putNextEntry(e)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** STORED exige conocer CRC y tamaño antes: primera pasada para calcularlos, segunda para copiar. */
    private fun putStored(zip: ZipOutputStream, name: String, uri: Uri) {
        val crc = CRC32()
        var size = 0L
        val buf = ByteArray(DiaryRepository.BUFFER)
        resolver.openInputStream(uri)!!.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
                size += n
            }
        }
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = size
        e.compressedSize = size
        e.crc = crc.value
        zip.putNextEntry(e)
        resolver.openInputStream(uri)!!.use { it.copyTo(zip, DiaryRepository.BUFFER) }
        zip.closeEntry()
    }

    // ---------------- importar ----------------

    /** Extrae a una carpeta temporal, valida el manifiesto y detecta conflictos. */
    fun prepareImport(src: Uri): ImportPlan {
        val temp = File(context.cacheDir, "import-${System.currentTimeMillis()}")
        temp.mkdirs()
        try {
            extract(src, temp)
            val manifestFile = File(temp, MANIFEST)
            if (!manifestFile.exists()) error("Este ZIP no es una copia del diario (falta manifest.json)")
            val manifest = JSONObject(manifestFile.readText())
            if (manifest.optString("app") != APP_ID) error("Este ZIP no es una copia del diario")
            val v = manifest.optInt("version", 0)
            if (v > Entry.SCHEMA_VERSION) {
                error("La copia es de una versión más nueva de la app (formato $v). Actualiza la app.")
            }

            val newDays = mutableListOf<LocalDate>()
            val conflicts = mutableListOf<Conflict>()
            importedDays(temp).forEach { (date, dir) ->
                val imported = runCatching {
                    Entry.fromJson(File(dir, DiaryRepository.ENTRY).readText())
                }.getOrNull() ?: return@forEach
                val local = repo.readEntry(date)
                if (local == null) newDays += date else conflicts += Conflict(date, local, imported)
            }
            return ImportPlan(temp, newDays.sorted(), conflicts.sortedBy { it.date })
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw e
        }
    }

    private fun extract(src: Uri, dest: File) {
        val base = dest.canonicalPath + File.separator
        val input = resolver.openInputStream(src) ?: error("No se pudo abrir el ZIP")
        ZipInputStream(input.buffered(DiaryRepository.BUFFER)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dest, e.name)
                // Protección contra rutas maliciosas ("../").
                if (!out.canonicalPath.startsWith(base)) error("ZIP no válido")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it, DiaryRepository.BUFFER) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun importedDays(temp: File): List<Pair<LocalDate, File>> {
        val out = mutableListOf<Pair<LocalDate, File>>()
        temp.listFiles()?.filter { it.isDirectory }?.forEach { y ->
            y.listFiles()?.filter { it.isDirectory }?.forEach { m ->
                m.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                    val date = runCatching {
                        LocalDate.of(y.name.toInt(), m.name.toInt(), d.name.toInt())
                    }.getOrNull()
                    if (date != null && File(d, DiaryRepository.ENTRY).exists()) out += date to d
                }
            }
        }
        return out
    }

    private fun importedDir(plan: ImportPlan, date: LocalDate) = File(plan.tempDir, dayPath(date))

    suspend fun applyImport(
        plan: ImportPlan,
        decisions: Map<LocalDate, Decision>,
        onProgress: suspend (Int, Int) -> Unit,
    ): ImportResult {
        var added = 0; var replaced = 0; var merged = 0; var kept = 0
        val total = plan.newDays.size + plan.conflicts.size
        var done = 0

        plan.newDays.forEach { date ->
            copyDay(importedDir(plan, date), date)
            added++
            onProgress(++done, total)
        }
        plan.conflicts.forEach { c ->
            when (decisions[c.date] ?: Decision.KEEP) {
                Decision.KEEP -> kept++
                Decision.REPLACE -> {
                    repo.deleteDay(c.date)
                    copyDay(importedDir(plan, c.date), c.date)
                    replaced++
                }
                Decision.MERGE -> {
                    mergeDay(importedDir(plan, c.date), c)
                    merged++
                }
            }
            onProgress(++done, total)
        }
        return ImportResult(added, replaced, merged, kept)
    }

    private fun copyDay(src: File, date: LocalDate) {
        val entry = Entry.fromJson(File(src, DiaryRepository.ENTRY).readText())
        val mediaFiles = File(src, DiaryRepository.MEDIA).listFiles()?.filter { it.isFile }.orEmpty()
        if (mediaFiles.isNotEmpty()) {
            val media = repo.mediaDir(date, true) ?: error("No se pudo crear la carpeta de medios")
            mediaFiles.forEach { repo.copyIn(it, media, it.name) }
        }
        repo.writeEntry(entry.copy(fecha = date))
    }

    /**
     * Combinar: texto importado al final, vídeos y etiquetas unidos, duraciones sumadas.
     * Los medios con nombre repetido se renombran (`foto-1-importado.jpg`) y se actualizan sus referencias.
     */
    private fun mergeDay(src: File, c: Conflict) {
        val localNames = repo.mediaFiles(c.date).keys
        val importedFiles = File(src, DiaryRepository.MEDIA).listFiles()?.filter { it.isFile }.orEmpty()
        val taken = (localNames + importedFiles.map { it.name }).toMutableSet()
        val renames = mutableMapOf<String, String>()
        importedFiles.forEach { f ->
            if (f.name in localNames) {
                val base = f.name.substringBeforeLast('.')
                val ext = f.name.substringAfterLast('.', "")
                var n = 1
                var candidate: String
                do {
                    val suffix = if (n == 1) "-importado" else "-importado-$n"
                    candidate = if (ext.isEmpty()) "$base$suffix" else "$base$suffix.$ext"
                    n++
                } while (candidate in taken)
                taken += candidate
                renames[f.name] = candidate
            }
        }

        if (importedFiles.isNotEmpty()) {
            val media = repo.mediaDir(c.date, true) ?: error("No se pudo crear la carpeta de medios")
            importedFiles.forEach { repo.copyIn(it, media, renames[it.name] ?: it.name) }
        }

        fun remap(path: String): String {
            val name = path.removePrefix("media/")
            return renames[name]?.let { "media/$it" } ?: path
        }

        val imp = c.imported
        val content = MEDIA_REF.replace(imp.contenido) { m -> remap(m.value) }
        val videos = imp.videos.map { v -> v.copy(archivo = remap(v.archivo), miniatura = v.miniatura?.let(::remap)) }
        val local = c.local
        val mergedEntry = local.copy(
            modificado = OffsetDateTime.now(),
            contenido = listOf(local.contenido.trimEnd(), content.trimStart())
                .filter { it.isNotBlank() }
                .joinToString("\n\n---\n\n"),
            videos = local.videos + videos,
            duracion = if (local.duracion == null && imp.duracion == null) null
            else (local.duracion ?: 0) + (imp.duracion ?: 0),
            tags = (local.tags + imp.tags).distinct(),
        )
        repo.writeEntry(mergedEntry)
    }

    companion object {
        const val APP_ID = "diario-entrenamiento"
        const val MANIFEST = "manifest.json"
        private val MEDIA_REF = Regex("media/[^)\\s\"]+")

        fun dayPath(d: LocalDate) = "%04d/%02d/%02d/".format(d.year, d.monthValue, d.dayOfMonth)
    }
}
