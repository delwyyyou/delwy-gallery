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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long,    // мс, может быть 0
    val dateModified: Long, // секунды
    val relativePath: String,
    val expires: Long,      // секунды; для фото в корзине — когда удалится само
) {
    val canProcess get() = mime == "image/jpeg" || mime == "image/png"
    val isBig get() = canProcess && size > SIZE_LIMIT

    /** Дата для сортировки: съёмка, а если её нет — время изменения (мс) */
    val date: Long get() = if (dateTaken > 0) dateTaken else dateModified * 1000
}

enum class SortMode(val label: String) {
    DATE_DESC("Сначала новые"),
    DATE_ASC("Сначала старые"),
    SIZE_DESC("Сначала крупные"),
}

sealed interface Entry { val key: String }
data class Header(val label: String, val ids: List<Long>) : Entry {
    override val key: String get() = "h:$label"
}
data class Photo(val img: GalleryImage, val index: Int) : Entry {
    override val key: String get() = "p:${img.id}"
}

fun formatSize(b: Long): String =
    if (b >= 1_048_576) String.format("%.1f МБ", b / 1_048_576.0) else "${b / 1024} КБ"

fun dayLabel(ms: Long): String {
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "Сегодня"
        date == today.minusDays(1) -> "Вчера"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
        else -> date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
    }
}

fun buildEntries(list: List<GalleryImage>, grouped: Boolean): List<Entry> {
    if (!grouped) return list.mapIndexed { i, img -> Photo(img, i) }
    val out = mutableListOf<Entry>()
    list.withIndex().groupBy { dayLabel(it.value.date) }.forEach { (label, items) ->
        out += Header(label, items.map { it.value.id })
        items.forEach { out += Photo(it.value, it.index) }
    }
    return out
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    var images by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var trash by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var entries by mutableStateOf<List<Entry>>(emptyList()); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var showTrash by mutableStateOf(false); private set
    var sort by mutableStateOf(SortMode.DATE_DESC); private set
    var exifVersion by mutableIntStateOf(0); private set
    var message by mutableStateOf<String?>(null)

    /** Корзина / восстановление / удаление навсегда — после результата просто перезагружаем список */
    val request = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    /** Запрос на запись в файл (очистка EXIF) */
    val cleanRequest = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    private var rawImages = emptyList<GalleryImage>()
    private var rawTrash = emptyList<GalleryImage>()
    private var pendingClean: GalleryImage? = null
    private val cr: ContentResolver get() = getApplication<Application>().contentResolver

    val current: List<GalleryImage> get() = if (showTrash) trash else images

    // ---------- Загрузка и сортировка ----------

    fun load() {
        viewModelScope.launch {
            val (a, t) = withContext(Dispatchers.IO) { query(false) to query(true) }
            rawImages = a
            rawTrash = t
            rebuild()
            val ids = current.map { it.id }.toSet()
            selected = selected.filter { it in ids }.toSet()
        }
    }

    fun changeSort(mode: SortMode) {
        sort = mode
        rebuild()
    }

    private fun sorted(l: List<GalleryImage>) = when (sort) {
        SortMode.DATE_DESC -> l.sortedByDescending { it.date }
        SortMode.DATE_ASC -> l.sortedBy { it.date }
        SortMode.SIZE_DESC -> l.sortedByDescending { it.size }
    }

    private fun rebuild() {
        images = sorted(rawImages)
        trash = sorted(rawTrash)
        rebuildEntries()
    }

    private fun rebuildEntries() {
        entries = buildEntries(current, sort != SortMode.SIZE_DESC)
    }

    fun openTrash(open: Boolean) {
        showTrash = open
        selected = emptySet()
        rebuildEntries()
    }

    // ---------- Выбор ----------

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun toggleGroup(ids: List<Long>) {
        selected = if (selected.containsAll(ids)) selected - ids.toSet() else selected + ids
    }

    fun clearSelection() { selected = emptySet() }

    fun selectAll() { selected = current.map { it.id }.toSet() }

    private fun selectedUris(): List<Uri> = current.filter { it.id in selected }.map { it.uri }

    // ---------- Корзина ----------

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
                withContext(Dispatchers.IO) { ImageProcessor.cleanInPlace(cr, img) }
                "EXIF очищен"
            } catch (e: Throwable) {
                "Не удалось очистить EXIF"
            }
            exifVersion++
            load()
            // сканер MediaStore мог пересчитать дату уже после записи — возвращаем её ещё раз
            delay(1200)
            withContext(Dispatchers.IO) {
                ImageProcessor.restoreDates(cr, img.uri, img.date, img.dateModified)
            }
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
            MediaStore.Images.Media.DATE_MODIFIED,
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
            val iMod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
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
                    dateModified = c.getLong(iMod),
                    relativePath = c.getString(iPath) ?: "Pictures/",
                    expires = c.getLong(iExp),
                )
            }
        }
        return out
    }
}
