package com.example.webpgallery

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp(vm: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.load() }
    val snackbar = remember { SnackbarHostState() }
    var showOptions by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perm) }
    LaunchedEffect(granted) { if (granted) vm.load() }
    LaunchedEffect(Unit) {
        vm.deleteRequest.collect { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    BackHandler(vm.selected.isNotEmpty()) { vm.clearSelection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (vm.selected.isEmpty()) "Галерея" else "Выбрано: ${vm.selected.size}",
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    if (vm.selected.isNotEmpty()) {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, null) }
                    }
                },
                actions = { TextButton(onClick = vm::selectBig) { Text("Выбрать >300 КБ") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = !vm.onlyBig, onClick = { vm.onlyBig = false }, label = { Text("Все") })
                    FilterChip(selected = vm.onlyBig, onClick = { vm.onlyBig = true }, label = { Text("Больше 300 КБ") })
                }

                if (!granted) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { permLauncher.launch(perm) }) { Text("Разрешить доступ к фото") }
                    }
                } else {
                    val list = vm.visible
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(8.dp, 4.dp, 8.dp, 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(list, key = { _, it -> it.id }) { index, img ->
                            Tile(
                                img = img,
                                selected = img.id in vm.selected,
                                onClick = {
                                    if (vm.selected.isNotEmpty()) vm.toggle(img.id) else viewer = index
                                },
                                onLongClick = { vm.toggle(img.id) },
                            )
                        }
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
                            val total = vm.images.filter { it.id in vm.selected }.sumOf { it.size }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${vm.selected.size} шт · ${formatSize(total)}",
                                    Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                )
                                Button(onClick = { showOptions = true }) { Text("Оптимизировать") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOptions) {
        var quality by remember { mutableFloatStateOf(80f) }
        var lossless by remember { mutableStateOf(false) }
        var delete by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("Параметры") },
            text = {
                Column {
                    Text("Качество WebP: ${quality.toInt()}")
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f)
                    SwitchRow("PNG без потерь (lossless)", lossless) { lossless = it }
                    SwitchRow("Удалить оригиналы", delete) { delete = it }
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
                    vm.run(Options(quality.toInt(), lossless, delete))
                }) { Text("Начать") }
            },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("Отмена") } },
        )
    }

    viewer?.let { start ->
        val list = vm.visible
        if (list.isNotEmpty()) {
            Dialog(
                onDismissRequest = { viewer = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                val pager = rememberPagerState(initialPage = start.coerceIn(0, list.lastIndex)) { list.size }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                        AsyncImage(
                            model = list[page].uri,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    list.getOrNull(pager.currentPage)?.let {
                        Text(
                            "${it.name} · ${formatSize(it.size)}",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                        )
                    }
                    IconButton(
                        onClick = { viewer = null },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    ) { Icon(Icons.Default.Close, null, tint = Color.White) }
                }
            }
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
fun Tile(img: GalleryImage, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val scale by animateFloatAsState(
        if (selected) 0.86f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "scale",
    )
    val corner by animateDpAsState(if (selected) 32.dp else 20.dp, label = "corner")
    val cs = MaterialTheme.colorScheme

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
        Text(
            text = if (img.mime == "image/webp") "WEBP" else formatSize(img.size),
            style = MaterialTheme.typography.labelSmall,
            color = if (img.isBig) cs.onTertiaryContainer else cs.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(CircleShape)
                .background(if (img.isBig) cs.tertiaryContainer else cs.surface.copy(alpha = 0.75f))
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
