@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.webpgallery

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val perm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val loc = Manifest.permission.ACCESS_MEDIA_LOCATION
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(has(perm)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = has(perm) }

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
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val fabExpanded by remember { derivedStateOf { galleryGrid.firstVisibleItemIndex == 0 } }
    val showFab = granted && !vm.showTrash && vm.selected.isEmpty() &&
        vm.progress == null && vm.images.any { it.canProcess }

    LaunchedEffect(Unit) {
        if (!has(perm) || !has(loc)) permLauncher.launch(arrayOf(perm, loc))
    }
    LaunchedEffect(granted) { if (granted) vm.load() }
    LaunchedEffect(Unit) {
        vm.request.collect { systemLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    LaunchedEffect(Unit) {
        vm.cleanRequest.collect { cleanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    BackHandler(vm.selected.isNotEmpty()) { vm.clearSelection() }
    BackHandler(vm.showTrash && vm.selected.isEmpty()) { vm.openTrash(false) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Text(
                            when {
                                vm.selected.isNotEmpty() -> "Выбрано: ${vm.selected.size}"
                                vm.showTrash -> "Корзина"
                                else -> "Галерея"
                            },
                            fontWeight = FontWeight.ExtraBold,
                        )
                    },
                    navigationIcon = {
                        if (vm.selected.isNotEmpty()) {
                            IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, null) }
                        }
                    },
                    actions = {
                        if (vm.selected.isNotEmpty()) {
                            IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, null) }
                        } else {
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
                        Button(onClick = { permLauncher.launch(arrayOf(perm, loc)) }) {
                            Text("Разрешить доступ к фото")
                        }
                    }
                } else {
                    // Потяните вниз от верха списка — галерея обновится
                    PullToRefreshBox(
                        isRefreshing = vm.refreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val entries = vm.entries
                        if (entries.isEmpty()) {
                            EmptyState(vm.showTrash)
                        } else {
                            val dense = columns >= 5
                            val gap = if (dense) 2.dp else 4.dp
                            val selectionMode = vm.selected.isNotEmpty()
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = if (vm.showTrash) trashGrid else galleryGrid,
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
        val all = vm.images.filter { it.canProcess }
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text(if (optimizeAll) "Оптимизировать все" else "Параметры") },
            text = {
                Column {
                    if (optimizeAll) {
                        Text(
                            "Будет обработано: ${all.size} фото (${formatSize(all.sumOf { it.size })})",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    Text("Качество WebP: ${quality.toInt()}")
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f)
                    SwitchRow("PNG без потерь (lossless)", lossless) { lossless = it }
                    SwitchRow("Оригиналы — в корзину", trashOrig) { trashOrig = it }
                    Text(
                        "Файлы до 300 КБ не пересжимаются — из них только удаляется EXIF. " +
                            "Уже чистые файлы пропускаются. Дата съёмки сохраняется.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showOptions = false
                    vm.run(Options(quality.toInt(), lossless, trashOrig), all = optimizeAll)
                }) { Text("Начать") }
            },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("Отмена") } },
        )
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
            selected = !vm.showTrash,
            onClick = { vm.openTrash(false) },
            icon = {
                Icon(if (!vm.showTrash) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary, null)
            },
            label = { Text("Галерея") },
        )
        NavigationBarItem(
            selected = vm.showTrash,
            onClick = { vm.openTrash(true) },
            icon = {
                BadgedBox(badge = {
                    if (vm.trash.isNotEmpty()) Badge { Text("${vm.trash.size}") }
                }) {
                    Icon(if (vm.showTrash) Icons.Filled.Delete else Icons.Outlined.Delete, null)
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
                Text("Обработка ${p.first + 1} из ${p.second}", fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { (p.first + 1f) / p.second },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
fun EmptyState(trash: Boolean) {
    val cs = MaterialTheme.colorScheme
    // Прокручиваемый, чтобы «потяни для обновления» работало и на пустом экране
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (trash) Icons.Outlined.Delete else Icons.Outlined.PhotoLibrary,
            null,
            Modifier.size(72.dp),
            tint = cs.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (trash) "Корзина пуста" else "Фото не найдены",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant,
        )
    }
}

// ---------------- Просмотрщик ----------------

@Composable
fun Viewer(vm: GalleryViewModel, start: Int, origin: Offset, onClose: () -> Unit) {
    val list = vm.images
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
                ZoomableImage(
                    model = list[page].uri,
                    onZoomChange = { if (page == pager.currentPage) zoomed = it },
                )
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

            AnimatedVisibility(
                visible = cur != null && !showInfo,
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

/**
 * Зум щипком (до 5×), двойной тап (2.5× и обратно), перемещение пальцем в приближении.
 * При масштабе 1× одиночные касания не перехватываются — работают листание и свайпы вверх/вниз.
 */
@Composable
fun ZoomableImage(model: Any, onZoomChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
                detectTapGestures(onDoubleTap = { tap ->
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
                })
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
            }
    ) {
        if (viewSize.width > 0) {
            // Запрашиваем картинку с запасом по размеру, чтобы в зуме она не была мыльной
            val request = remember(model, viewSize) {
                ImageRequest.Builder(ctx)
                    .data(model)
                    .size(viewSize.width * 3, viewSize.height * 3)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun InfoPanel(img: GalleryImage, vm: GalleryViewModel) {
    val cr = LocalContext.current.contentResolver
    val exif by produceState<List<Pair<String, String>>?>(null, img.uri, vm.exifVersion) {
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
                InfoRow("Папка", img.relativePath)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("EXIF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                when {
                    data == null -> Text("Загрузка…", color = cs.onSurfaceVariant)
                    data.isEmpty() -> Text("Основных тегов EXIF не найдено", color = cs.onSurfaceVariant)
                    else -> data.forEach { (k, v) -> InfoRow(k, v) }
                }
            }

            Button(
                onClick = { vm.requestClean(img) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { BtnLabel(Icons.Filled.CleaningServices, "Очистить EXIF") }
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

        // В галерее на плитках нет веса и WEBP; в корзине — только срок до автоудаления
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
