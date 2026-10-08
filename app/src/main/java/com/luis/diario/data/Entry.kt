package com.luis.diario.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class VideoRef(
    val titulo: String,
    val archivo: String,
    val miniatura: String? = null,
)

/**
 * Una entrada por día. Se guarda como `AAAA/MM/DD/entrada.json`.
 * `contenido` es Markdown; las imágenes se referencian como `![](media/foto-1.jpg)`.
 */
data class Entry(
    val fecha: LocalDate,
    val modificado: OffsetDateTime,
    val contenido: String,
    val videos: List<VideoRef> = emptyList(),
    val duracion: Int? = null,
    val tags: List<String> = emptyList(),
    val version: Int = SCHEMA_VERSION,
) {
    val isEmpty: Boolean
        get() = contenido.isBlank() && videos.isEmpty() && duracion == null && tags.isEmpty()

    /** Rutas `media/...` de las fotos citadas en el texto. */
    fun photoRefs(): List<String> =
        IMAGE_REGEX.findAll(contenido).map { it.groupValues[2].trim() }.toList()

    /** Todos los archivos de `media/` que esta entrada usa. */
    fun referencedMedia(): Set<String> = buildSet {
        photoRefs().forEach { add(it.removePrefix("media/")) }
        videos.forEach { v ->
            add(v.archivo.removePrefix("media/"))
            v.miniatura?.let { add(it.removePrefix("media/")) }
        }
    }

    /** Primera línea legible, sin símbolos de Markdown, para listados. */
    fun preview(max: Int = 90): String {
        val line = contenido.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !IMAGE_LINE.matches(it) && it != "---" }
            ?.replace(Regex("^#{1,6}\\s+"), "")
            ?.replace(Regex("^[-*]\\s+"), "")
            ?.replace("**", "")
            ?.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        val text = when {
            !line.isNullOrBlank() -> line
            videos.isNotEmpty() -> "${videos.size} vídeo(s)"
            photoRefs().isNotEmpty() -> "${photoRefs().size} foto(s)"
            else -> "(vacía)"
        }
        return if (text.length > max) text.take(max - 1) + "…" else text
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", version)
        o.put("fecha", fecha.toString())
        o.put("modificado", modificado.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        o.put("contenido", contenido)
        val vs = JSONArray()
        videos.forEach { v ->
            val vo = JSONObject()
            vo.put("titulo", v.titulo)
            vo.put("archivo", v.archivo)
            if (v.miniatura != null) vo.put("miniatura", v.miniatura)
            vs.put(vo)
        }
        o.put("videos", vs)
        if (duracion != null) o.put("duracion", duracion)
        o.put("tags", JSONArray(tags))
        // org.json escapa "/" como "\/"; lo deshacemos para que el archivo sea legible.
        return o.toString(2).replace("\\/", "/")
    }

    companion object {
        const val SCHEMA_VERSION = 1
        val IMAGE_REGEX = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
        val IMAGE_LINE = Regex("^!\\[([^\\]]*)\\]\\(([^)]+)\\)$")

        fun empty(date: LocalDate) = Entry(fecha = date, modificado = OffsetDateTime.now(), contenido = "")

        fun fromJson(text: String): Entry {
            val o = JSONObject(text)
            val videos = mutableListOf<VideoRef>()
            o.optJSONArray("videos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    videos += VideoRef(
                        titulo = v.optString("titulo", "Vídeo ${i + 1}"),
                        archivo = v.getString("archivo"),
                        miniatura = if (v.has("miniatura")) v.getString("miniatura") else null,
                    )
                }
            }
            val tags = mutableListOf<String>()
            o.optJSONArray("tags")?.let { arr -> for (i in 0 until arr.length()) tags += arr.getString(i) }
            return Entry(
                version = o.optInt("version", 1),
                fecha = LocalDate.parse(o.getString("fecha")),
                modificado = runCatching { OffsetDateTime.parse(o.getString("modificado")) }
                    .getOrElse { OffsetDateTime.now() },
                contenido = o.optString("contenido", ""),
                videos = videos,
                duracion = if (o.has("duracion") && !o.isNull("duracion")) o.getInt("duracion") else null,
                tags = tags,
            )
        }
    }
}
