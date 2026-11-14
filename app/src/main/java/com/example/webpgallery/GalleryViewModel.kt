package com.example.webpgallery

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long,
    val relativePath: String,
    val expires: Long, // секунды; для фото в корзине — когда удалится само
) {
    val canProcess get() = mime == "image/jpeg" || mime == "image/png"
    val isBig get() = canProcess && size > SIZE_LIMIT
}

fun formatSize(b: Long): String =
    if (b >= 1_048_576) String.format("%.1f МБ", b / 1_048_576.0) else "${b / 1024} КБ"

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    var images by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var trash by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var showTrash by mutableStateOf(false); private set
    var exifVersion by mutableIntStateOf(0); private set
    var message by mutableStateOf<String?>(null)

    /** Корзина / восстановление / удаление навсегда — после результата просто перезагружаем список */
    val request = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    /** Запрос на запись в файл (очистка EXIF) */
    val cleanRequest = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    private var pendingClean: GalleryImage? = null
    private val cr: ContentResolver get() = getApplication<Application>().contentResolver

    val current: List<GalleryImage> get() = if (showTrash) trash else images

    fun load() {
        viewModelScope.launch {
            val (a, t) = withContext(Dispatchers.IO) { query(false) to query(true) }
            images = a
            trash = t
            val ids = current.map { it.id }.toSet()
            selected = selected.filter { it in ids }.toSet()
        }
    }

    fun openTrash(open: Boolean) {
        showTrash = open
        selected = emptySet()
    }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun clearSelection() { selected = emptySet() }

    fun selectAll() { selected = current.map { it.id }.toSet() }

    private fun selectedUris(): List<Uri> = current.filter { it.id in selected }.map { it.uri }

    /** true — в корзину, false — восстановить */
    fun moveSelected(toTrash: Boolean) {
        val uris = selectedUris()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            request.emit(MediaStore.createTrashRequest(cr, uris, toTrash).intentSender)
        }
    }

    fun deleteSelectedForever() {
        val uris = selectedUris()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            request.emit(MediaStore.createDeleteRequest(cr, uris).intentSender)
        }
    }

    // ---------- Очистка EXIF прямо в файле ----------

    fun requestClean(img: GalleryImage) {
        pendingClean = img
        viewModelScope.launch {
            cleanRequest.emit(MediaStore.createWriteRequest(cr, listOf(img.uri)).intentSender)
        }
    }

    fun onCleanResult(ok: Boolean) {
        val img = pendingClean ?: return
        pendingClean = null
        if (!ok) return
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) { ImageProcessor.cleanInPlace(cr, img.uri, img.mime) }
                "EXIF очищен"
            } catch (e: Throwable) {
                "Не удалось очистить EXIF"
            }
            exifVersion++
            load()
        }
    }

    // ---------- Сжатие ----------

    fun run(opt: Options) {
        val list = images.filter { it.id in selected && it.canProcess }
        if (progress != null) return
        if (list.isEmpty()) {
            message = "Среди выбранных нет JPEG/PNG"
            return
        }
        viewModelScope.launch {
            var saved = 0L
            var ok = 0
            var fail = 0
            val processed = mutableListOf<Uri>()
            list.forEachIndexed { idx, img ->
                progress = idx to list.size
                try {
                    val r = withContext(Dispatchers.IO) { ImageProcessor.process(cr, img, opt) }
                    saved += r.before - r.after
                    ok++
                    processed += img.uri
                } catch (e: Throwable) {
                    fail++
                }
            }
            progress = null
            selected = emptySet()
            message = "Готово: $ok, ошибок: $fail. Освобождено ${formatSize(saved)}"
            load()
            if (opt.trashOriginals && processed.isNotEmpty()) {
                request.emit(MediaStore.createTrashRequest(cr, processed, true).intentSender)
            }
        }
    }

    // ---------- MediaStore ----------

    private fun query(trashed: Boolean): List<GalleryImage> {
        val coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.MIME_TYPE} IN (?,?,?)"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("image/jpeg", "image/png", "image/webp")
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )
            putInt(
                MediaStore.QUERY_ARG_MATCH_TRASHED,
                if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE
            )
        }
        val out = mutableListOf<GalleryImage>()
        cr.query(coll, proj, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val iExp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += GalleryImage(
                    id = id,
                    uri = ContentUris.withAppendedId(coll, id),
                    name = c.getString(iName) ?: "image",
                    mime = c.getString(iMime) ?: "image/jpeg",
                    size = c.getLong(iSize),
                    dateTaken = c.getLong(iTaken),
                    relativePath = c.getString(iPath) ?: "Pictures/",
                    expires = c.getLong(iExp),
                )
            }
        }
        return out
    }
}
