package org.delwy.webpgallery

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
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
    val dateTaken: Long,
    val dateModified: Long,
    val relativePath: String,
    val expires: Long,
    val duration: Long,
) {
    val isVideo get() = mime.startsWith("video/")
    val canProcess get() = mime == "image/jpeg" || mime == "image/png"
    val canTranscode get() = isVideo && mime != "video/webm"
    val isBig get() = canProcess && size > SIZE_LIMIT

    val date: Long get() = if (dateTaken > 0) dateTaken else dateModified * 1000
}

data class Album(val path: String, val name: String, val count: Int, val cover: GalleryImage)

enum class NavTab { GALLERY, ALBUMS, TRASH }

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

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

fun albumName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { "Корень" }

fun buildAlbums(list: List<GalleryImage>): List<Album> =
    list.groupBy { it.relativePath }
        .map { (path, items) -> Album(path, albumName(path), items.size, items.maxBy { it.date }) }
        .sortedByDescending { it.cover.date }

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

    private enum class Op { TRASH, RESTORE, DELETE }

    var images by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var trash by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var albums by mutableStateOf<List<Album>>(emptyList()); private set

    var current by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var entries by mutableStateOf<List<Entry>>(emptyList()); private set

    var tab by mutableStateOf(NavTab.GALLERY); private set
    var album by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var subProgress by mutableFloatStateOf(0f); private set
    var sort by mutableStateOf(SortMode.DATE_DESC); private set
    var refreshing by mutableStateOf(false); private set
    var exifVersion by mutableIntStateOf(0); private set
    var message by mutableStateOf<String?>(null)

    val showTrash: Boolean get() = tab == NavTab.TRASH

    val request = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    val cleanRequest = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    private var rawImages = emptyList<GalleryImage>()
    private var rawTrash = emptyList<GalleryImage>()
    private var pendingClean: GalleryImage? = null
    private var writeWaiter: CompletableDeferred<Boolean>? = null

    @Volatile
    private var cancelFlag = false

    private val queue = ArrayDeque<Pair<Op, List<Uri>>>()
    private var inFlight = false

    private val cr: ContentResolver get() = getApplication<Application>().contentResolver


    private suspend fun reload() {
        val (a, t) = withContext(Dispatchers.IO) { query(false) to query(true) }
        rawImages = a
        rawTrash = t
        rebuild()
        val ids = current.map { it.id }.toSet()
        selected = selected.filter { it in ids }.toSet()
    }

    fun load() {
        viewModelScope.launch { reload() }
    }

    fun refresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                reload()
                delay(400)
            } finally {
                refreshing = false
            }
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
        albums = buildAlbums(rawImages)
        if (album != null && albums.none { it.path == album }) album = null
        rebuildEntries()
    }

    private fun rebuildEntries() {
        val a = album
        current = when (tab) {
            NavTab.GALLERY -> images
            NavTab.TRASH -> trash
            NavTab.ALBUMS -> if (a == null) emptyList() else images.filter { it.relativePath == a }
        }
        entries = buildEntries(current, sort != SortMode.SIZE_DESC)
    }
 

    fun openTab(t: NavTab) {
        tab = t
        album = null
        selected = emptySet()
        rebuildEntries()
    }

    fun openAlbum(path: String) {
        album = path
        selected = emptySet()
        rebuildEntries()
    }

    fun closeAlbum() {
        album = null
        selected = emptySet()
        rebuildEntries()
    }


    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun toggleGroup(ids: List<Long>) {
        selected = if (selected.containsAll(ids)) selected - ids.toSet() else selected + ids
    }

    fun clearSelection() { selected = emptySet() }

    fun selectAll() { selected = current.map { it.id }.toSet() }

    private fun selectedUris(): List<Uri> = current.filter { it.id in selected }.map { it.uri }

    private fun enqueue(op: Op, uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.chunked(500).forEach { queue.add(op to it) }
        if (!inFlight) next()
    }

    private fun next() {
        val item = queue.removeFirstOrNull()
        if (item == null) {
            inFlight = false
            return
        }
        inFlight = true
        val (op, batch) = item
        viewModelScope.launch {
            val pi = when (op) {
                Op.TRASH -> MediaStore.createTrashRequest(cr, batch, true)
                Op.RESTORE -> MediaStore.createTrashRequest(cr, batch, false)
                Op.DELETE -> MediaStore.createDeleteRequest(cr, batch)
            }
            request.emit(pi.intentSender)
        }
    }

    fun onSystemResult(ok: Boolean) {
        inFlight = false
        if (ok) {
            selected = emptySet()
            next()
        } else {
            queue.clear()
        }
        load()
    }

    fun moveSelected(toTrash: Boolean) {
        enqueue(if (toTrash) Op.TRASH else Op.RESTORE, selectedUris())
    }

    fun deleteSelectedForever() {
        enqueue(Op.DELETE, selectedUris())
    }

    private suspend fun requestWrite(uris: List<Uri>): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        writeWaiter = waiter
        cleanRequest.emit(MediaStore.createWriteRequest(cr, uris).intentSender)
        return waiter.await()
    }

    fun requestClean(img: GalleryImage) {
        pendingClean = img
        viewModelScope.launch {
            cleanRequest.emit(MediaStore.createWriteRequest(cr, listOf(img.uri)).intentSender)
        }
    }

    fun onCleanResult(ok: Boolean) {
        // Если это ответ на запрос «замены на месте» из run() — просто передаём его туда
        val waiter = writeWaiter
        if (waiter != null) {
            writeWaiter = null
            waiter.complete(ok)
            return
        }
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

    // ---------- Сжатие фото и видео ----------

    fun cancelRun() {
        cancelFlag = true
    }

    /**
     * all = true — все JPEG/PNG в галерее (и видео, если vopt.include),
     * иначе только выбранные фото и видео.
     */
    fun run(opt: Options, vopt: VideoOptions, all: Boolean = false) {
        if (progress != null) return
        val imgs = images.filter { it.canProcess && (all || it.id in selected) }
        var vids = images.filter { it.canTranscode && (if (all) vopt.include else it.id in selected) }
        var note: String? = null
        if (vids.isNotEmpty() && !VideoTranscoder.isSupported()) {
            vids = emptyList()
            note = "нет VP9-кодера, видео пропущены"
        }
        val list = imgs + vids
        if (list.isEmpty()) {
            message = note?.let { "Нечего обрабатывать: $it" } ?: "Нет файлов для обработки"
            return
        }
        cancelFlag = false
        progress = 0 to list.size
        subProgress = 0f
        val app = getApplication<Application>()
        viewModelScope.launch {
            var saved = 0L
            var ok = 0
            var skipped = 0
            var noAccess = 0
            var fail = 0
            var canceled = false
            var firstError: String? = null
            val processed = mutableListOf<Uri>()

            // Фото вне DCIM/Pictures заменяем на месте (рядом с оригиналом новый файл не создать).
            // Только если оригиналы всё равно должны уйти; иначе — копия в Pictures/WebP Gallery.
            val inPlace = if (opt.trashOriginals) imgs.filter { needsInPlace(it) } else emptyList()
            val inPlaceIds = inPlace.map { it.id }.toSet()
            val granted = mutableSetOf<Long>()
            for (chunk in inPlace.chunked(500)) {
                if (!requestWrite(chunk.map { it.uri })) break
                granted += chunk.map { it.id }
            }

            for ((idx, img) in list.withIndex()) {
                if (cancelFlag) { canceled = true; break }
                progress = idx to list.size
                subProgress = 0f
                if (img.id in inPlaceIds && img.id !in granted) {
                    noAccess++
                    continue
                }
                try {
                    val r = withContext(Dispatchers.IO) {
                        when {
                            img.isVideo ->
                                VideoTranscoder.process(app, img, vopt, { subProgress = it }, { cancelFlag })
                            img.id in inPlaceIds -> ImageProcessor.processInPlace(cr, img, opt)
                            else -> ImageProcessor.process(cr, img, opt)
                        }
                    }
                    if (r.skipped) {
                        skipped++
                    } else {
                        saved += r.before - r.after
                        ok++
                        // замена на месте: сам файл уже и есть результат, в корзину его не нужно
                        if (img.id !in inPlaceIds) processed += img.uri
                    }
                } catch (e: TranscodeCanceled) {
                    canceled = true
                    break
                } catch (e: Throwable) {
                    fail++
                    if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                }
            }
            progress = null
            subProgress = 0f
            selected = emptySet()
            message = buildString {
                append(if (canceled) "Отменено. Готово: $ok" else "Готово: $ok")
                if (skipped > 0) append(", пропущено: $skipped")
                if (noAccess > 0) append(", нет доступа: $noAccess")
                if (fail > 0) append(", ошибок: $fail (${firstError ?: "?"})")
                if (note != null) append(", $note")
                append(". Освобождено ${formatSize(saved)}")
            }
            load()
            if (opt.trashOriginals) enqueue(Op.TRASH, processed)
        }
    }

    // ---------- MediaStore ----------

    private fun query(trashed: Boolean): List<GalleryImage> {
        val coll = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val imageBase = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val videoBase = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.DURATION,
        )
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                )
            )
            putInt(
                MediaStore.QUERY_ARG_MATCH_TRASHED,
                if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE
            )
        }
        val out = mutableListOf<GalleryImage>()
        cr.query(coll, proj, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iExp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
            val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val mime = c.getString(iMime) ?: "image/jpeg"
                val base = if (mime.startsWith("video/")) videoBase else imageBase
                out += GalleryImage(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    name = c.getString(iName) ?: "file",
                    mime = mime,
                    size = c.getLong(iSize),
                    dateTaken = c.getLong(iTaken),
                    dateModified = c.getLong(iMod),
                    relativePath = c.getString(iPath) ?: "",
                    expires = c.getLong(iExp),
                    duration = c.getLong(iDur),
                )
            }
        }
        return out
    }
}
