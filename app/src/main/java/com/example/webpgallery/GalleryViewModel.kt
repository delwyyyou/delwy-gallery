package com.example.webpgallery

import android.app.Application
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
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
) {
    val canProcess get() = mime == "image/jpeg" || mime == "image/png"
    val isBig get() = canProcess && size > SIZE_LIMIT
}

fun formatSize(b: Long): String =
    if (b >= 1_048_576) String.format("%.1f МБ", b / 1_048_576.0) else "${b / 1024} КБ"

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    var images by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var onlyBig by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    val deleteRequest = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    val visible: List<GalleryImage>
        get() = if (onlyBig) images.filter { it.isBig } else images

    fun load() {
        viewModelScope.launch {
            images = withContext(Dispatchers.IO) { query() }
            val ids = images.map { it.id }.toSet()
            selected = selected.filter { it in ids }.toSet()
        }
    }

    fun toggle(id: Long) {
        if (images.none { it.id == id && it.canProcess }) return
        selected = if (id in selected) selected - id else selected + id
    }

    fun clearSelection() { selected = emptySet() }

    fun selectBig() { selected = images.filter { it.isBig }.map { it.id }.toSet() }

    fun run(opt: Options) {
        val list = images.filter { it.id in selected && it.canProcess }
        if (list.isEmpty() || progress != null) return
        viewModelScope.launch {
            val cr = getApplication<Application>().contentResolver
            var saved = 0L
            var ok = 0
            var fail = 0
            val toDelete = mutableListOf<Uri>()
            list.forEachIndexed { idx, img ->
                progress = idx to list.size
                try {
                    val r = withContext(Dispatchers.IO) { ImageProcessor.process(cr, img, opt) }
                    saved += r.before - r.after
                    ok++
                    toDelete += img.uri
                } catch (e: Throwable) {
                    fail++
                }
            }
            progress = null
            selected = emptySet()
            message = "Готово: $ok, ошибок: $fail. Освобождено ${formatSize(saved)}"
            load()
            if (opt.deleteOriginals && toDelete.isNotEmpty()) {
                // Системный диалог подтверждения удаления оригиналов
                deleteRequest.emit(MediaStore.createDeleteRequest(cr, toDelete).intentSender)
            }
        }
    }

    private fun query(): List<GalleryImage> {
        val cr = getApplication<Application>().contentResolver
        val coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val sel = "${MediaStore.Images.Media.MIME_TYPE} IN (?,?,?)"
        val args = arrayOf("image/jpeg", "image/png", "image/webp")
        val out = mutableListOf<GalleryImage>()
        cr.query(coll, proj, sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
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
                )
            }
        }
        return out
    }
}
