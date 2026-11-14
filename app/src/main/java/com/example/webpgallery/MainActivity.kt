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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
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
    MaterialTheme(colorScheme = scheme, content = content)
}

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
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.clearSelection()
        vm.load()
    }
    val cleanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> vm.onCleanResult(r.resultCode == Activity.RESULT_OK) }

    val snackbar = remember { SnackbarHostState() }
    var showOptions by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }

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
            topBar = {
                TopAppBar(
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
                            IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, null) }
                        } else if (vm.showTrash) {
                            IconButton(onClick = { vm.openTrash(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            }
                        }
                    },
                    actions = {
                        if (vm.selected.isNotEmpty()) {
                            TextButton(onClick = vm::selectAll) { Text("Все") }
                        } else if (!vm.showTrash) {
                            TextButton(onClick = { vm.openTrash(true) }) {
                                Text(if (vm.trash.isEmpty()) "Корзина" else "Корзина (${vm.trash.size})")
                            }
                        }
                    },
                )
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
                    val list = vm.current
                    if (list.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (vm.showTrash) "Корзина пуста" else "Фото не найдены",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(8.dp, 4.dp, 8.dp, 140.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(list, key = { _, it -> it.id }) { index, img ->
                            Tile(
                                img = img,
                                trashMode = vm.showTrash,
                                selected = img.id in vm.selected,
                                onClick = {
                                    if (vm.selected.isNotEmpty() || vm.showTrash) vm.toggle(img.id)
                                    else viewer = index
                                },
                                onLongClick = { vm.toggle(img.id) },
                            )
                        }
                    }
                }

                // Нижняя панель действий
                AnimatedVisibility(
                    visible = vm.selected.isNotEmpty() || vm.progress != null,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy)) { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                    ) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                            val p = vm.progress
                            if (p != null) {
                                Text("Обработка ${p.first + 1} из ${p.second}")
                                LinearProgressIndicator(
                                    progress = { (p.first + 1f) / p.second },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            } else {
                                val total = vm.current.filter { it.id in vm.selected }.sumOf { it.size }
                                Text(
                                    "${vm.selected.size} шт · ${formatSize(total)}",
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (vm.showTrash) {
                                        FilledTonalButton(
                                            onClick = { vm.moveSelected(false) },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Восстановить") }
                                        Button(
                                            onClick = vm::deleteSelectedForever,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError,
                                            ),
                                        ) { Text("Удалить") }
                                    } else {
                                        FilledTonalButton(
                                            onClick = { vm.moveSelected(true) },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("В корзину") }
                                        Button(
                                            onClick = { showOptions = true },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Сжать") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        viewer?.let { Viewer(vm, it) { viewer = null } }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 8.dp),
        )
    }

    if (showOptions) {
        var quality by remember { mutableFloatStateOf(80f) }
        var lossless by remember { mutableStateOf(false) }
        var trashOrig by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("Параметры") },
            text = {
                Column {
                    Text("Качество WebP: ${quality.toInt()}")
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f)
                    SwitchRow("PNG без потерь (lossless)", lossless) { lossless = it }
                    SwitchRow("Оригиналы — в корзину", trashOrig) { trashOrig = it }
                    Text(
                        "Файлы до 300 КБ не пересжимаются — из них только удаляется EXIF.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showOptions = false
                    vm.run(Options(quality.toInt(), lossless, trashOrig))
                }) { Text("Начать") }
            },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("Отмена") } },
        )
    }
}

// ---------------- Просмотрщик ----------------

@Composable
fun Viewer(vm: GalleryViewModel, start: Int, onClose: () -> Unit) {
    val list = vm.images
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.lastIndex)) { list.size }
    var showInfo by remember { mutableStateOf(false) }
    val cur = list.getOrNull(pager.currentPage)

    BackHandler { if (showInfo) showInfo = false else onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(showInfo) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { total = 0f },
                    onDragEnd = {
                        when {
                            total < -100f -> showInfo = true            // вверх — показать EXIF
                            total > 100f -> if (showInfo) showInfo = false else onClose()
                        }
                    },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = list[page].uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp),
        ) { Icon(Icons.Default.Close, null, tint = Color.White) }

        if (cur != null && !showInfo) {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${cur.name} · ${formatSize(cur.size)}", color = Color.White)
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
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
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
            ) { Text("Очистить EXIF") }
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

// ---------------- Мелкие компоненты ----------------

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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        if (selected) 0.86f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "scale",
    )
    val corner by animateDpAsState(if (selected) 32.dp else 20.dp, label = "corner")
    val cs = MaterialTheme.colorScheme

    val daysLeft = if (trashMode && img.expires > 0)
        ((img.expires * 1000 - System.currentTimeMillis()) / 86_400_000L + 1).coerceAtLeast(0)
    else -1L

    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(cs.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = img.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val highlight = img.isBig && !trashMode
        Text(
            text = when {
                daysLeft >= 0 -> "$daysLeft дн."
                img.mime == "image/webp" -> "WEBP"
                else -> formatSize(img.size)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) cs.onTertiaryContainer else cs.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(CircleShape)
                .background(if (highlight) cs.tertiaryContainer else cs.surface.copy(alpha = 0.75f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(cs.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp)) }
        }
    }
}
