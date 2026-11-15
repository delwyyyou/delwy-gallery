package org.delwy.webpgallery

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.util.Locale

object ExifReader {

    fun read(cr: ContentResolver, uri: Uri): List<Pair<String, String>> {
        return try {
            val original = try {
                cr.openInputStream(MediaStore.setRequireOriginal(uri))
            } catch (e: Exception) {
                null
            }
            val stream = original ?: cr.openInputStream(uri) ?: return emptyList()
            stream.use { s ->
                val e = ExifInterface(s)
                fun t(tag: String) = e.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }

                val rows = mutableListOf<Pair<String, String>>()
                fun add(label: String, v: String?) { if (v != null) rows += label to v }

                add("Дата съёмки", t(ExifInterface.TAG_DATETIME_ORIGINAL) ?: t(ExifInterface.TAG_DATETIME))
                val camera = listOfNotNull(t(ExifInterface.TAG_MAKE), t(ExifInterface.TAG_MODEL)).joinToString(" ")
                add("Камера", camera.ifEmpty { null })
                add("Объектив", t(ExifInterface.TAG_LENS_MODEL))
                val w = t(ExifInterface.TAG_IMAGE_WIDTH)
                val h = t(ExifInterface.TAG_IMAGE_LENGTH)
                add("Размер (EXIF)", if (w != null && h != null) "$w × $h" else null)
                add("Выдержка", t(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
                    if (it in 0.0..0.99) "1/${Math.round(1 / it)} с" else "$it с"
                })
                add("Диафрагма", t(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" })
                add("ISO", t(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
                add("Фокусное", e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                    .takeIf { it > 0 }?.let { "${it.toInt()} мм" })
                add("ПО", t(ExifInterface.TAG_SOFTWARE))
                add("GPS", e.latLong?.let { String.format(Locale.US, "%.5f, %.5f", it[0], it[1]) })
                rows
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
