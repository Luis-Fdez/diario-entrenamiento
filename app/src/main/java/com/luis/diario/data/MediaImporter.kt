package com.luis.diario.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Copia fotos y vídeos a la carpeta `media/` del día, reduciendo su tamaño. */
class MediaImporter(private val context: Context, private val repo: DiaryRepository) {

    /** Devuelve la ruta relativa `media/foto-N.jpg`. */
    suspend fun importPhoto(date: LocalDate, src: Uri): String = withContext(Dispatchers.IO) {
        val media = repo.mediaDir(date, true) ?: error("No se pudo crear la carpeta de medios")
        val name = repo.nextMediaName(media, "foto", "jpg")
        val scaled = runCatching { downscalePhoto(src) }.getOrNull()
        if (scaled != null) {
            repo.copyIn(ByteArrayInputStream(scaled), media, name)
        } else {
            repo.copyIn(src, media, name)
        }
        "media/$name"
    }

    /** Lado mayor a 2048 px, JPEG al 85 %. Respeta la orientación EXIF (ImageDecoder, Android 9+). */
    private fun downscalePhoto(src: Uri): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val source = ImageDecoder.createSource(context.contentResolver, src)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > MAX_PHOTO) {
                val s = MAX_PHOTO.toFloat() / longest
                decoder.setTargetSize((w * s).roundToInt(), (h * s).roundToInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    suspend fun importVideo(date: LocalDate, src: Uri, title: String): VideoRef {
        val tmp = File(context.cacheDir, "import-${System.currentTimeMillis()}.mp4")
        try {
            val target = withContext(Dispatchers.IO) { targetSize(src) }
            val compressed = target != null && runCatching { compress(src, tmp, target) }.isSuccess &&
                tmp.length() > 0
            return withContext(Dispatchers.IO) {
                val media = repo.mediaDir(date, true) ?: error("No se pudo crear la carpeta de medios")
                val name = repo.nextMediaName(media, "video", "mp4")
                if (compressed) repo.copyIn(tmp, media, name) else repo.copyIn(src, media, name)

                val thumbName = name.removeSuffix(".mp4") + ".jpg"
                val thumb = runCatching { thumbnail(src) }.getOrNull()
                if (thumb != null) repo.copyIn(ByteArrayInputStream(thumb), media, thumbName)

                VideoRef(
                    titulo = title,
                    archivo = "media/$name",
                    miniatura = if (thumb != null) "media/$thumbName" else null,
                )
            }
        } finally {
            tmp.delete()
        }
    }

    /** Tamaño de salida con el lado corto a 720 px, o null si el vídeo ya es pequeño. */
    private fun targetSize(src: Uri): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, src)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val shortest = min(w, h)
            if (shortest <= TARGET_SHORT_SIDE) return null
            val s = TARGET_SHORT_SIDE.toFloat() / shortest
            even(w * s) to even(h * s)
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private fun even(v: Float): Int = (v.roundToInt() / 2) * 2

    @OptIn(UnstableApi::class)
    private suspend fun compress(src: Uri, out: File, size: Pair<Int, Int>) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(src))
                    .setEffects(
                        Effects(
                            listOf(),
                            listOf(
                                Presentation.createForWidthAndHeight(
                                    size.first, size.second, Presentation.LAYOUT_SCALE_TO_FIT,
                                ),
                            ),
                        ),
                    )
                    .build()
                transformer.start(item, out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }

    private fun thumbnail(src: Uri): ByteArray? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, src)
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.frameAtTime ?: return null
            val s = THUMB_WIDTH.toFloat() / frame.width
            val scaled = if (s < 1f) {
                Bitmap.createScaledBitmap(frame, THUMB_WIDTH, (frame.height * s).roundToInt(), true)
            } else frame
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return out.toByteArray()
        } finally {
            r.release()
        }
    }

    companion object {
        const val MAX_PHOTO = 2048
        const val TARGET_SHORT_SIDE = 720
        const val THUMB_WIDTH = 480
    }
}
