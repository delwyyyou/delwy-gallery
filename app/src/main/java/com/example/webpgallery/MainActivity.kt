@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.webpgallery

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Coil умеет доставать кадры из видео для превью
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(VideoFrameDecoder.Factory()) }
                .build()
        )
        enableEdgeToEdge()
        setContent { GalleryTheme { GalleryApp() } }
    }
}

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        ),
        content = content,
    )
}

// ---------------- Щипок: количество фото в ряду ----------------

@Composable
fun Modifier.pinchColumns(columns: Int, onChange: (Int) -> Unit): Modifier {
    val latest by rememberUpdatedState(columns)
    val callback by rememberUpdatedState(onChange)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var last = 0f
            var acc = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val dist = (pressed[0].position - pressed[1].position).getDistance()
                    if (last > 0f) {
                        acc *= dist / last
                        if (acc > 1.35f && latest > 1) {          // разводим пальцы — крупнее
                            callback(latest - 1); acc = 1f
                        } else if (acc < 0.74f && latest < 8) {    // сводим — мельче, больше в ряду
                            callback(latest + 1); acc = 1f
                        }
                    }
                    last = dist
                    event.changes.forEach { it.consume() }
                } else {
                    last = 0f
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

// ---------------- Главный экран ----------------

@Composable
fun GalleryApp(vm: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    val permImages = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val permList = remember {
        buildList {
            add(permImages)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }.toTypedArray()
    }
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(has(permImages)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = has(permImages)
        if (granted) vm.load()
    }

    val systemLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> vm.onSystemResult(r.resultCode == Activity.RESULT_OK) }
    val cleanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> vm.onCleanResult(r.resultCode == Activity.RESULT_OK) }

    val snackbar = remember { SnackbarHostState() }
    var showOptions by remember { mutableStateOf(false) }
    var optimizeAll by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    var viewerOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    val galleryGrid = rememberLazyGridState()
    val trashGrid = rememberLazyGridState()
    val albumGrid = rememberLazyGridState()
    val albumsListGrid = rememberLazyGridState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val inAlbumsList = vm.tab == NavTab.ALBUMS && vm.album == null
    val inAlbum = vm.tab == NavTab.ALBUMS && vm.album != null

    val fabExpanded by remember { derivedStateOf { galleryGrid.firstVisibleItemIndex == 0 } }
    val showFab = granted && vm.tab == NavTab.GALLERY && vm.selected.isEmpty() &&
        vm.progress == null && vm.images.any { it.canProcess || it.canTranscode }

    // Пока идёт обработка, экран не гаснет
    val view = LocalView.current
    val busy = vm.progress != null
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        if (permList.any { !has(it) }) permLauncher.launch(permList)
    }
    LaunchedEffect(granted) { if (granted) vm.load() }
    LaunchedEffect(vm.album) { albumGrid.scrollToItem(0) }
    LaunchedEffect(Unit) {
        vm.request.collect { systemLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    LaunchedEffect(Unit) {
        vm.cleanRequest.collect { cleanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    // «Назад»: сначала снять выбор, потом выйти из альбома, потом на вкладку «Галерея»
    BackHandler(vm.selected.isNotEmpty()) { vm.clearSelection() }
    BackHandler(vm.selected.isEmpty() && inAlbum) { vm.closeAlbum() }
    BackHandler(vm.selected.isEmpty() && !inAlbum && vm.tab != NavTab.GALLERY) {
        vm.openTab(NavTab.GALLERY)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Text(
                            when {
                                vm.selected.isNotEmpty() -> "Выбрано: ${vm.selected.size}"
                                vm.tab == NavTab.TRASH -> "Корзина"
                                vm.tab == NavTab.ALBUMS -> vm.album?.let { albumName(it) } ?: "Альбомы"
                                else -> "Галерея"
                            },
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (vm.selected.isNotEmpty()) {
                            IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, null) }
                        } else if (inAlbum) {
                            IconButton(onClick = vm::closeAlbum) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            }
                        }
                    },
                    actions = {
                        if (vm.selected.isNotEmpty()) {
                            IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, null) }
                        } else if (!inAlbumsList) {
                            SortMenu(vm)
                        }
                    },
                    scrollBehavior = scroll,
                )
            },
            bottomBar = {
                val actionMode = vm.selected.isNotEmpty() || vm.progress != null
                AnimatedContent(targetState = actionMode, label = "bottom") { mode ->
                    if (mode) {
                        ActionBar(vm) { optimizeAll = false; showOptions = true }
                    } else {
                        NavBar(vm)
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(visible = showFab, enter = scaleIn(), exit = scaleOut()) {
                    ExtendedFloatingActionButton(
                        onClick = { optimizeAll = true; showOptions = true },
                        expanded = fabExpanded,
                        icon = { Icon(Icons.Filled.Compress, null) },
                        text = { Text("Оптимизировать все") },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!granted) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { permLauncher.launch(permList) }) {
                            Text("Разрешить доступ к фото и видео")
                        }
                    }
                } else {
                    // Потяните вниз от верха списка — галерея обновится
                    PullToRefreshBox(
                        isRefreshing = vm.refreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (inAlbumsList) {
                            AlbumsGrid(vm, albumsListGrid)
                        } else {
                            val entries = vm.entries
                            if (entries.isEmpty()) {
                                if (vm.showTrash) EmptyState(Icons.Outlined.Delete, "Корзина пуста")
                                else EmptyState(Icons.Outlined.PhotoLibrary, "Фото не найдены")
                            } else {
                                val dense = columns >= 5
                                val gap = if (dense) 2.dp else 4.dp
                                val selectionMode = vm.selected.isNotEmpty()
                                val gridState = when {
                                    vm.tab == NavTab.TRASH -> trashGrid
                                    vm.tab == NavTab.ALBUMS -> albumGrid
                                    else -> galleryGrid
                                }
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(columns),
                                    state = gridState,
                                    modifier = Modifier.fillMaxSize().pinchColumns(columns) { columns = it },
                                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 88.dp),
                                    horizontalArrangement = Arrangement.spacedBy(gap),
                                    verticalArrangement = Arrangement.spacedBy(gap),
                                ) {
                                    items(
                                        entries,
                                        key = { it.key },
                                        span = { e -> if (e is Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                                    ) { e ->
                                        when (e) {
                                            is Header -> DayHeader(
                                                label = e.label,
                                                selectionMode = selectionMode,
                                                allSelected = vm.selected.containsAll(e.ids),
                                                modifier = Modifier.animateItem(),
                                                onClick = { vm.toggleGroup(e.ids) },
                                            )
                                            is Photo -> Tile(
                                                img = e.img,
                                                trashMode = vm.showTrash,
                                                selected = e.img.id in vm.selected,
                                                selectionMode = selectionMode,
                                                dense = dense,
                                                modifier = Modifier.animateItem(),
                                                onClick = { center ->
                                                    if (selectionMode || vm.showTrash) {
                                                        vm.toggle(e.img.id)
                                                    } else {
                                                        viewerOrigin = center
                                                        viewer = e.index
                                                    }
                                                },
                                                onLongClick = { vm.toggle(e.img.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        viewer?.let { Viewer(vm, it, viewerOrigin) { viewer = null } }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 8.dp),
        )
    }

    if (showOptions) {
        var quality by remember { mutableFloatStateOf(80f) }
        var lossless by remember { mutableStateOf(false) }
        var trashOrig by remember { mutableStateOf(true) }
        var includeVideos by remember { mutableStateOf(false) }
        var vQuality by remember { mutableIntStateOf(1) }
        var vSide by remember { mutableIntStateOf(0) }

        val allImages = vm.images.filter { it.canProcess }
        val allVideos = vm.images.filter { it.canTranscode }
        val selImages = vm.images.count { it.id in vm.selected && it.canProcess }
        val selVideos = vm.images.count { it.id in vm.selected && it.canTranscode }
        val photosOn = optimizeAll || selImages > 0
        val videoCount = if (optimizeAll) allVideos.size else selVideos
        val videosOn = videoCount > 0 && (!optimizeAll || includeVideos)

        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text(if (optimizeAll) "Оптимизировать все" else "Параметры") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (optimizeAll) {
                        Text(
                            "Фото: ${allImages.size} (${formatSize(allImages.sumOf { it.size })})",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (photosOn) {
                        Text("Качество WebP: ${quality.toInt()}")
                        Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f)
                        SwitchRow("PNG без потерь (lossless)", lossless) { lossless = it }
                    }
                    if (optimizeAll && allVideos.isNotEmpty()) {
                        SwitchRow(
                            "И видео (${allVideos.size} шт., ${formatSize(allVideos.sumOf { it.size })})",
                            includeVideos,
                        ) { includeVideos = it }
                    }
                    if (videosOn) {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Text("Видео → WebM (VP9 + Opus)", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Качество", style = MaterialTheme.typography.labelLarge)
                        Segmented(listOf("Мало", "Баланс", "Макс."), vQuality) { vQuality = it }
                        Spacer(Modifier.height(8.dp))
                        Text("Разрешение", style = MaterialTheme.typography.labelLarge)
                        Segmented(listOf("Исходное", "1080p", "720p"), vSide) { vSide = it }
                        Text(
                            "Кодирование программное и медленное: минуты на минуту видео, " +
                                "быстрее при 720p. Не сворачивайте приложение.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    SwitchRow("Оригиналы — в корзину", trashOrig) { trashOrig = it }
                    Text(
                        "Фото до 300 КБ не пересжимаются — из них только удаляется EXIF. " +
                            "Уже чистые файлы и файлы, которые не стали меньше, пропускаются. " +
                            "Дата съёмки сохраняется.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showOptions = false
                    vm.run(
                        Options(quality.toInt(), lossless, trashOrig),
                        VideoOptions(vQuality, listOf(0, 1080, 720)[vSide], includeVideos),
                        all = optimizeAll,
                    )
                }) { Text("Начать") }
            },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = selected == i,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

// ---------------- Шапка, нижние панели ----------------

@Composable
fun SortMenu(vm: GalleryViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, null) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = { vm.changeSort(mode); open = false },
                    leadingIcon = {
                        if (vm.sort == mode) Icon(Icons.Filled.Check, null)
                        else Spacer(Modifier.size(24.dp))
                    },
                )
            }
        }
    }
}

@Composable
fun NavBar(vm: GalleryViewModel) {
    NavigationBar {
        NavigationBarItem(
            selected = vm.tab == NavTab.GALLERY,
            onClick = { vm.openTab(NavTab.GALLERY) },
            icon = {
                Icon(
                    if (vm.tab == NavTab.GALLERY) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary,
                    null,
                )
            },
            label = { Text("Галерея") },
        )
        NavigationBarItem(
            selected = vm.tab == NavTab.ALBUMS,
            onClick = { vm.openTab(NavTab.ALBUMS) },
            icon = {
                Icon(
                    if (vm.tab == NavTab.ALBUMS) Icons.Filled.Folder else Icons.Outlined.Folder,
                    null,
                )
            },
            label = { Text("Альбомы") },
        )
        NavigationBarItem(
            selected = vm.tab == NavTab.TRASH,
            onClick = { vm.openTab(NavTab.TRASH) },
            icon = {
                BadgedBox(badge = {
                    if (vm.trash.isNotEmpty()) Badge { Text("${vm.trash.size}") }
                }) {
                    Icon(
                        if (vm.tab == NavTab.TRASH) Icons.Filled.Delete else Icons.Outlined.Delete,
                        null,
                    )
                }
            },
            label = { Text("Корзина") },
        )
    }
}

@Composable
fun BtnLabel(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, maxLines = 1)
}

@Composable
fun ActionBar(vm: GalleryViewModel, onCompress: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
            val p = vm.progress
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Обработка ${p.first + 1} из ${p.second}",
                        Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = vm::cancelRun) { Text("Отмена") }
                }
                LinearProgressIndicator(
                    progress = { ((p.first + vm.subProgress) / p.second).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else {
                val total = vm.current.filter { it.id in vm.selected }.sumOf { it.size }
                Text("${vm.selected.size} шт · ${formatSize(total)}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.showTrash) {
                        FilledTonalButton(
                            onClick = { vm.moveSelected(false) },
                            modifier = Modifier.weight(1f),
                        ) { BtnLabel(Icons.Filled.RestoreFromTrash, "Восстановить") }
                        Button(
                            onClick = vm::deleteSelectedForever,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cs.error,
                                contentColor = cs.onError,
                            ),
                        ) { BtnLabel(Icons.Filled.DeleteForever, "Удалить") }
                    } else {
                        FilledTonalButton(
                            onClick = { vm.moveSelected(true) },
                            modifier = Modifier.weight(1f),
                        ) { BtnLabel(Icons.Filled.Delete, "В корзину") }
                        Button(
                            onClick = onCompress,
                            modifier = Modifier.weight(1f),
                        ) { BtnLabel(Icons.Filled.Compress, "Сжать") }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    val cs = MaterialTheme.colorScheme
    // Прокручиваемый, чтобы «потяни для обновления» работало и на пустом экране
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(72.dp), tint = cs.outline)
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
    }
}

// ---------------- Альбомы ----------------

@Composable
fun AlbumsGrid(vm: GalleryViewModel, state: LazyGridState) {
    val albums = vm.albums
    if (albums.isEmpty()) {
        EmptyState(Icons.Outlined.Folder, "Альбомов нет")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums, key = { it.path }) { a ->
            AlbumCard(a, Modifier.animateItem()) { vm.openAlbum(a.path) }
        }
    }
}

@Composable
fun AlbumCard(album: Album, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surfaceContainerHigh)
        ) {
            AsyncImage(
                model = album.cover.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            album.name,
            Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${album.count}",
            Modifier.padding(start = 6.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

// ---------------- Просмотрщик ----------------

@Composable
fun Viewer(vm: GalleryViewModel, start: Int, origin: Offset, onClose: () -> Unit) {
    val list = vm.current
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.lastIndex)) { list.size }
    val cur = list.getOrNull(pager.currentPage)

    // Открытие: фото «вырастает» из плитки
    LaunchedEffect(Unit) {
        progress.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow))
    }
    // Новая страница всегда открывается без зума
    LaunchedEffect(pager.currentPage) { zoomed = false }

    fun close() {
        if (closing) return
        closing = true
        showInfo = false
        scope.launch {
            progress.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
            onClose()
        }
    }

    fun resetDrag() {
        scope.launch { animate(dragY, 0f) { v, _ -> dragY = v } }
    }

    BackHandler { if (showInfo) showInfo = false else close() }

    val dragFrac = if (showInfo) 0f else (dragY / 900f).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Color.Black,
                    alpha = progress.value.coerceIn(0f, 1f) * (1f - 0.7f * dragFrac),
                )
            }
            .pointerInput(showInfo) {
                detectVerticalDragGestures(
                    onDragStart = { dragY = 0f },
                    onDragCancel = { dragY = 0f },
                    onDragEnd = {
                        val total = dragY
                        when {
                            !showInfo && total > 220f -> close()
                            !showInfo && total < -100f -> { showInfo = true; resetDrag() }
                            showInfo && total > 100f -> { showInfo = false; resetDrag() }
                            else -> resetDrag()
                        }
                    },
                    onVerticalDrag = { _, dy -> dragY += dy },
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1.2f)
                    val s = lerp(0.3f, 1f, p) * (1f - 0.3f * dragFrac)
                    scaleX = s
                    scaleY = s
                    translationY = if (showInfo) 0f else dragY.coerceAtLeast(0f)
                    alpha = p.coerceIn(0f, 1f)
                    transformOrigin = if (origin.isSpecified)
                        TransformOrigin(origin.x / size.width, origin.y / size.height)
                    else TransformOrigin.Center
                    clip = true
                    shape = RoundedCornerShape(lerp(160f, 0f, p.coerceIn(0f, 1f)))
                }
        ) {
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                pageSpacing = 16.dp,
                userScrollEnabled = !zoomed,
            ) { page ->
                val item = list[page]
                val onZoom: (Boolean) -> Unit = { if (page == pager.currentPage) zoomed = it }
                if (item.isVideo) {
                    VideoPage(item, active = page == pager.currentPage && !closing, onZoomChange = onZoom)
                } else {
                    ZoomableImage(model = item.uri, onZoomChange = onZoom)
                }
            }

            val overlay = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
            )
            Row(
                Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = { showInfo = !showInfo }, colors = overlay) {
                    Icon(Icons.Filled.Info, null)
                }
                FilledTonalIconButton(onClick = { close() }, colors = overlay) {
                    Icon(Icons.Filled.Close, null)
                }
            }

            // Подпись и подсказка — только для фото (у видео внизу своё управление)
            AnimatedVisibility(
                visible = cur != null && !showInfo && cur.isVideo.not(),
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    Modifier.navigationBarsPadding().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.5f)) {
                        Text(
                            "${cur?.name.orEmpty()} · ${formatSize(cur?.size ?: 0)}",
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Потяните вверх — информация и EXIF",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            AnimatedVisibility(
                visible = showInfo && cur != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                if (cur != null) InfoPanel(cur, vm)
            }
        }
    }
}

// ---------------- Видео ----------------

/** Страница видео: постер, а плеер поднимается только на активной странице */
@Composable
fun VideoPage(img: GalleryImage, active: Boolean, onZoomChange: (Boolean) -> Unit) {
    if (active) {
        ActiveVideo(img, onZoomChange)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = img.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun ActiveVideo(img: GalleryImage, onZoomChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(img.uri) {
        ExoPlayer.Builder(ctx)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(img.uri))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    var playing by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(img.duration) }
    var seeking by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.duration > 0) dur = player.duration
                ended = playbackState == Player.STATE_ENDED
                if (ended) controls = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!seeking) pos = player.currentPosition
            delay(250)
        }
    }
    // Управление прячется само через 3 секунды воспроизведения
    LaunchedEffect(controls, playing) {
        if (controls && playing) {
            delay(3000)
            controls = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Зум действует на видео вместе с постером; тап показывает/прячет управление
        ZoomBox(onZoomChange = onZoomChange, onTap = { controls = !controls }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = img.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                AndroidView(
                    factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.aspectRatio(ratio),
                )
            }
        }

        AnimatedVisibility(
            visible = controls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        when {
                            ended -> { player.seekTo(0); player.play() }
                            playing -> player.pause()
                            else -> player.play()
                        }
                    }) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            null,
                            tint = Color.White,
                        )
                    }
                    Text(
                        formatDuration(pos),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = pos.coerceIn(0L, dur.coerceAtLeast(1L)).toFloat(),
                        onValueChange = { seeking = true; pos = it.toLong() },
                        onValueChangeFinished = { player.seekTo(pos); seeking = false },
                        valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        formatDuration(dur),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

// ---------------- Зум (фото и видео) ----------------

/**
 * Контейнер с зумом: щипок (до 5×), двойной тап (2.5× и обратно), перемещение пальцем.
 * При масштабе 1× одиночные касания не перехватываются — работают листание и свайпы вверх/вниз.
 */
@Composable
fun ZoomBox(
    onZoomChange: (Boolean) -> Unit,
    onTap: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tapCb by rememberUpdatedState(onTap)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val zoomed by remember { derivedStateOf { scale > 1.01f } }

    LaunchedEffect(zoomed) { onZoomChange(zoomed) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = (viewSize.width * (s - 1f) / 2f).coerceAtLeast(0f)
        val my = (viewSize.height * (s - 1f) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tapCb?.invoke() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            val startScale = scale
                            val startOffset = offset
                            val target = if (startScale > 1.05f) 1f else 2.5f
                            val focus = tap - Offset(viewSize.width / 2f, viewSize.height / 2f)
                            animate(0f, 1f, animationSpec = tween(280)) { t, _ ->
                                val s = lerp(startScale, target, t).coerceIn(1f, 5f)
                                val z = s / startScale
                                scale = s
                                offset = clamp(focus * (1f - z) + startOffset * z, s)
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val pointers = event.changes.count { it.pressed }
                            if (pointers >= 2 || scale > 1.01f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid(useCurrent = false)
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                val z = newScale / scale
                                val mid = Offset(viewSize.width / 2f, viewSize.height / 2f)
                                val focus = if (c.isSpecified) c - mid else Offset.Zero
                                offset = clamp(focus * (1f - z) + offset * z + pan, newScale)
                                scale = newScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        content = content,
    )
}

@Composable
fun ZoomableImage(model: Any, onZoomChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    // Запрашиваем картинку с запасом по размеру, чтобы в зуме она не была мыльной
    val request = remember(model) {
        ImageRequest.Builder(ctx).data(model).size(3000, 3000).build()
    }
    ZoomBox(onZoomChange = onZoomChange) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun InfoPanel(img: GalleryImage, vm: GalleryViewModel) {
    val cr = LocalContext.current.contentResolver
    val exif by produceState<List<Pair<String, String>>?>(null, img.uri, vm.exifVersion) {
        if (img.isVideo) {
            value = emptyList()
            return@produceState
        }
        value = null
        value = withContext(Dispatchers.IO) { ExifReader.read(cr, img.uri) }
    }
    val data = exif
    val cs = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = cs.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(20.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(40.dp, 4.dp)
                    .clip(CircleShape)
                    .background(cs.onSurfaceVariant.copy(alpha = 0.4f))
            )
            Spacer(Modifier.height(12.dp))
            Text("Информация", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)

            if (data?.any { it.first == "GPS" } == true) {
                AssistChip(
                    onClick = {},
                    label = { Text("Есть геометка") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = cs.errorContainer,
                        labelColor = cs.onErrorContainer,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp)
            ) {
                InfoRow("Файл", img.name)
                InfoRow("Размер", formatSize(img.size))
                InfoRow("Тип", img.mime)
                InfoRow("Папка", img.relativePath.ifEmpty { "—" })
                if (img.isVideo) {
                    InfoRow("Длительность", formatDuration(img.duration))
                } else {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("EXIF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    when {
                        data == null -> Text("Загрузка…", color = cs.onSurfaceVariant)
                        data.isEmpty() -> Text("Основных тегов EXIF не найдено", color = cs.onSurfaceVariant)
                        else -> data.forEach { (k, v) -> InfoRow(k, v) }
                    }
                }
            }

            if (!img.isVideo) {
                Button(
                    onClick = { vm.requestClean(img) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { BtnLabel(Icons.Filled.CleaningServices, "Очистить EXIF") }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            Modifier.weight(0.38f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(value, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------- Сетка ----------------

@Composable
fun DayHeader(
    label: String,
    selectionMode: Boolean,
    allSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (selectionMode) {
            Icon(
                if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                null,
                tint = if (allSelected) cs.primary else cs.outline,
            )
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Tile(
    img: GalleryImage,
    trashMode: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    dense: Boolean,
    modifier: Modifier,
    onClick: (Offset) -> Unit,
    onLongClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        if (selected) 0.86f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "scale",
    )
    val corner by animateDpAsState(
        when {
            selected -> if (dense) 14.dp else 32.dp
            else -> if (dense) 6.dp else 20.dp
        },
        label = "corner",
    )
    val cs = MaterialTheme.colorScheme
    var center by remember { mutableStateOf(Offset.Zero) }

    val daysLeft = if (trashMode && img.expires > 0)
        ((img.expires * 1000 - System.currentTimeMillis()) / 86_400_000L + 1).coerceAtLeast(0)
    else -1L

    Box(
        modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(cs.surfaceContainerHigh)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .combinedClickable(onClick = { onClick(center) }, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = img.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // В корзине — срок до автоудаления
        if (daysLeft >= 0 && !dense) {
            Text(
                text = "$daysLeft дн.",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(cs.surface.copy(alpha = 0.75f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // Видео: значок ▶ и длительность
        if (img.isVideo) {
            if (dense) {
                Icon(
                    Icons.Filled.PlayArrow,
                    null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(14.dp),
                )
            } else {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(
                        formatDuration(img.duration),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        val mark = if (dense) 16.dp else 24.dp
        val markPad = if (dense) 4.dp else 8.dp
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(markPad)
                    .size(mark)
                    .clip(CircleShape)
                    .background(cs.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(mark - 8.dp))
            }
        } else if (selectionMode) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(markPad)
                    .size(mark)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            )
        }
    }
}
