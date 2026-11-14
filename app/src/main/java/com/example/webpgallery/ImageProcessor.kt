package com.example.webpgallery

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

const val SIZE_LIMIT = 300L * 1024

data class Options(
    val quality: Int = 80,
    val pngLossless: Boolean = false,
    val deleteOriginals: Boolean = true,
)

data class Result(val before: Long, val after: Long)

object ImageProcessor {

    fun process(cr: ContentResolver, img: GalleryImage, opt: Options): Result {
        val bytes = cr.openInputStream(img.uri)!!.use { it.readBytes() }
        val isPng = img.mime == "image/png"
        val base = img.name.substringBeforeLast('.')
        val origExt = img.name.substringAfterLast('.', if (isPng) "png" else "jpg")

        var data: ByteArray? = null
        var ext = origExt
        var outMime = img.mime

        // Больше 300 КБ — конвертируем в WebP (EXIF при перекодировании исчезает сам)
        if (bytes.size > SIZE_LIMIT) {
            val webp = toWebp(cr, img.uri, isPng, opt)
            if (webp.size < bytes.size) {
                data = webp
                ext = "webp"
                outMime = "image/webp"
            }
        }
        // Маленькие файлы (или если WebP вышел не меньше) — без пересжатия, только чистим метаданные
        if (data == null) {
            data = if (isPng) stripPng(bytes) else stripJpeg(cr, img.uri, bytes)
        }

        val dir = if (img.relativePath.startsWith("DCIM") || img.relativePath.startsWith("Pictures"))
            img.relativePath else "Pictures/WebP Gallery"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$base.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, outMime)
            put(MediaStore.Images.Media.RELATIVE_PATH, dir)
            if (img.dateTaken > 0) put(MediaStore.Images.Media.DATE_TAKEN, img.dateTaken)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val newUri = cr.insert(collection, values) ?: error("insert failed")
        try {
            cr.openOutputStream(newUri)!!.use { it.write(data) }
            cr.update(newUri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } catch (e: Throwable) {
            cr.delete(newUri, null, null)
            throw e
        }
        return Result(bytes.size.toLong(), data.size.toLong())
    }

    // ---------- WebP ----------

    private fun toWebp(cr: ContentResolver, uri: Uri, isPng: Boolean, opt: Options): ByteArray {
        val bmp = decode(cr, uri)
        try {
            val (fmt, q) =
                if (isPng && opt.pngLossless) Bitmap.CompressFormat.WEBP_LOSSLESS to 100
                else Bitmap.CompressFormat.WEBP_LOSSY to opt.quality
            return ByteArrayOutputStream().also { bmp.compress(fmt, q, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    // ImageDecoder сам применяет EXIF-поворот, поэтому картинка не «ляжет на бок»
    private fun decode(cr: ContentResolver, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, _, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    // ---------- JPEG: удаляем метаданные без перекодирования ----------

    private fun stripJpeg(cr: ContentResolver, uri: Uri, src: ByteArray): ByteArray {
        val orientation = ExifInterface(ByteArrayInputStream(src))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        if (orientation != ExifInterface.ORIENTATION_NORMAL &&
            orientation != ExifInterface.ORIENTATION_UNDEFINED
        ) {
            // Тег поворота исчезнет вместе с EXIF, поэтому физически поворачиваем пиксели
            val bmp = decode(cr, uri)
            try {
                return ByteArrayOutputStream()
                    .also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    .toByteArray()
            } finally {
                bmp.recycle()
            }
        }
        return stripJpegSegments(src)
    }

    private fun stripJpegSegments(src: ByteArray): ByteArray {
        if (src.size < 4 || src[0] != 0xFF.toByte() || src[1] != 0xD8.toByte()) return src
        val out = ByteArrayOutputStream(src.size)
        out.write(src, 0, 2)
        var i = 2
        while (i + 4 <= src.size) {
            if (src[i] != 0xFF.toByte()) break
            val marker = src[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xDA) break // SOS — дальше данные изображения
            val len = ((src[i + 2].toInt() and 0xFF) shl 8) or (src[i + 3].toInt() and 0xFF)
            val end = i + 2 + len
            if (end > src.size) break
            val isMpf = marker == 0xE2 && end - i >= 8 &&
                src[i + 4] == 'M'.code.toByte() && src[i + 5] == 'P'.code.toByte() &&
                src[i + 6] == 'F'.code.toByte()
            // APP1 (EXIF/XMP), APP13 (Photoshop/IPTC), COM, MPF — удаляем; ICC (APP2) и JFIF оставляем
            val drop = marker == 0xE1 || marker == 0xED || marker == 0xFE || isMpf
            if (!drop) out.write(src, i, end - i)
            i = end
        }
        // Копируем сами данные до первого EOI, всё, что после (видео motion photo и т.п.), отбрасываем
        var j = i
        while (j + 1 < src.size && !(src[j] == 0xFF.toByte() && src[j + 1] == 0xD9.toByte())) j++
        val tail = if (j + 1 < src.size) j + 2 else src.size
        out.write(src, i, tail - i)
        return out.toByteArray()
    }

    // ---------- PNG: выкидываем текстовые чанки и eXIf ----------

    private val pngDrop = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")

    private fun stripPng(src: ByteArray): ByteArray {
        if (src.size < 8) return src
        val out = ByteArrayOutputStream(src.size)
        out.write(src, 0, 8)
        var i = 8
        while (i + 12 <= src.size) {
            val len = ByteBuffer.wrap(src, i, 4).int
            val type = String(src, i + 4, 4, Charsets.ISO_8859_1)
            val end = i + 12 + len
            if (len < 0 || end > src.size) {
                out.write(src, i, src.size - i)
                break
            }
            if (type !in pngDrop) out.write(src, i, 12 + len)
            i = end
        }
        return out.toByteArray()
    }
}
