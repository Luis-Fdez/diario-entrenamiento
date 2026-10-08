package com.luis.diario.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.YearMonth

/**
 * Acceso al diario dentro de la carpeta elegida por el usuario (Storage Access Framework).
 *
 * Estructura:
 *   <raíz>/AAAA/MM/DD/entrada.json
 *   <raíz>/AAAA/MM/DD/media/foto-1.jpg, video-1.mp4, video-1.jpg
 */
class DiaryRepository(private val context: Context, rootUri: Uri) {

    private val resolver = context.contentResolver
    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, rootUri) ?: error("No se puede acceder a la carpeta")

    fun canWrite(): Boolean = root.canWrite()

    // ---------- carpetas ----------

    private fun childDir(parent: DocumentFile, name: String, create: Boolean): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        return if (create) parent.createDirectory(name) else null
    }

    fun dayDir(date: LocalDate, create: Boolean): DocumentFile? {
        val y = childDir(root, "%04d".format(date.year), create) ?: return null
        val m = childDir(y, "%02d".format(date.monthValue), create) ?: return null
        return childDir(m, "%02d".format(date.dayOfMonth), create)
    }

    fun mediaDir(date: LocalDate, create: Boolean): DocumentFile? =
        dayDir(date, create)?.let { childDir(it, MEDIA, create) }

    /** Días del mes que tienen carpeta con entrada. */
    fun daysWithEntries(month: YearMonth): Set<Int> {
        val y = childDir(root, "%04d".format(month.year), false) ?: return emptySet()
        val m = childDir(y, "%02d".format(month.monthValue), false) ?: return emptySet()
        return m.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { it.name?.toIntOrNull() }
            .filter { it in 1..month.lengthOfMonth() }
            .toSet()
    }

    /** Todas las fechas con carpeta, en orden. */
    fun allDays(): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        root.listFiles().filter { it.isDirectory }.forEach { y ->
            val year = y.name?.toIntOrNull() ?: return@forEach
            y.listFiles().filter { it.isDirectory }.forEach { m ->
                val month = m.name?.toIntOrNull() ?: return@forEach
                m.listFiles().filter { it.isDirectory }.forEach { d ->
                    val day = d.name?.toIntOrNull() ?: return@forEach
                    runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let(out::add)
                }
            }
        }
        return out.sorted()
    }

    // ---------- entradas ----------

    fun readEntry(date: LocalDate): Entry? {
        val dir = dayDir(date, false) ?: return null
        return readEntryIn(dir)
    }

    fun readEntryIn(dir: DocumentFile): Entry? {
        val files = dir.listFiles()
        // Si un guardado se interrumpió entre borrar y renombrar, recuperamos el .tmp.
        val file = files.firstOrNull { it.name == ENTRY } ?: files.firstOrNull { it.name == "$ENTRY.tmp" }
        ?: return null
        return runCatching { Entry.fromJson(readText(file.uri)) }.getOrNull()
    }

    fun writeEntry(entry: Entry) {
        val dir = dayDir(entry.fecha, true) ?: error("No se pudo crear la carpeta del día")
        writeAtomic(dir, ENTRY, entry.toJson())
    }

    /** Escribe en `nombre.tmp` y renombra. Un cierre a mitad no deja la entrada corrupta. */
    private fun writeAtomic(dir: DocumentFile, name: String, text: String) {
        dir.findFile("$name.tmp")?.delete()
        val tmp = dir.createFile(OCTET, "$name.tmp") ?: error("No se pudo escribir $name")
        resolver.openOutputStream(tmp.uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        dir.findFile(name)?.delete()
        if (!tmp.renameTo(name)) {
            // Algunos proveedores no permiten renombrar: escritura directa como alternativa.
            val f = dir.createFile(OCTET, name) ?: error("No se pudo escribir $name")
            resolver.openOutputStream(f.uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            tmp.delete()
        }
    }

    fun deleteDay(date: LocalDate) {
        dayDir(date, false)?.delete()
    }

    // ---------- medios ----------

    fun mediaFiles(date: LocalDate): Map<String, Uri> =
        mediaDir(date, false)?.listFiles()
            ?.filter { it.isFile && it.name != null }
            ?.associate { it.name!! to it.uri }
            ?: emptyMap()

    fun nextMediaName(media: DocumentFile, prefix: String, ext: String): String {
        val re = Regex("^${Regex.escape(prefix)}-(\\d+)\\.")
        val max = media.listFiles().mapNotNull { f ->
            f.name?.let { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        }.maxOrNull() ?: 0
        return "$prefix-${max + 1}.$ext"
    }

    fun createFile(dir: DocumentFile, name: String): DocumentFile {
        dir.findFile(name)?.delete()
        return dir.createFile(mimeFor(name), name) ?: error("No se pudo crear $name")
    }

    fun copyIn(input: InputStream, dir: DocumentFile, name: String) {
        val f = createFile(dir, name)
        input.use { src -> resolver.openOutputStream(f.uri, "w")!!.use { src.copyTo(it, BUFFER) } }
    }

    fun copyIn(src: Uri, dir: DocumentFile, name: String) =
        copyIn(resolver.openInputStream(src) ?: error("No se pudo leer el archivo"), dir, name)

    fun copyIn(src: File, dir: DocumentFile, name: String) = copyIn(src.inputStream(), dir, name)

    fun deleteMedia(date: LocalDate, name: String) {
        mediaDir(date, false)?.findFile(name)?.delete()
    }

    fun readText(uri: Uri): String =
        resolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        const val ENTRY = "entrada.json"
        const val MEDIA = "media"
        const val OCTET = "application/octet-stream"
        const val BUFFER = 64 * 1024

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            else -> OCTET
        }
    }
}
