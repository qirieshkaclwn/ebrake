package xyz.imd4n.ebrake.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.TranscodeUiState
import xyz.imd4n.ebrake.transcode.TranscodeViewModel
import xyz.imd4n.ebrake.ui.gallery.VideoGallery
import xyz.imd4n.ebrake.ui.gallery.VideoItem

/**
 * Ввод: сетка роликов во весь экран.
 *
 * Плавающая карточка отбора и её полноэкранный слой живут в EbrakeApp, рядом
 * с доком: стеклу нужен сиблинг источника, а не потомок внутри пейджера.
 *
 * Отдельной кнопки «Импорт» больше нет. Отметка — это список «что взять в
 * обработку»: ролики уходят на «Правку», снимаются сами после успешного
 * кодирования или кнопкой «Сбросить отметки» в карточке. Ролик для сравнения
 * трёх уровней выбирается отдельно, на «Правке».
 *
 * Рядом с сеткой остались два ручных пути — SAF-пикер (без разрешений, выручает
 * с файлами, которых нет в MediaStore) и выбор папки: тогда сетка показывает
 * только её содержимое, например Downloads.
 */
@Composable
fun InputScreen(vm: TranscodeViewModel, state: TranscodeUiState) {
    val context = LocalContext.current

    // MediaStore читается только с разрешением; на 13+ это READ_MEDIA_VIDEO,
    // ниже — старое READ_EXTERNAL_STORAGE. Выбранная папка идёт через SAF и
    // разрешения не требует, поэтому проверка нужна только для MediaStore.
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, mediaPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Возврат из системных настроек: перепроверяем, иначе экран так и покажет
    // «нет доступа» после выданного разрешения.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(context, mediaPermission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // Ручной выбор: работает всегда, даже без разрешения на галерею.
    val manualPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        // Долгосрочный доступ: задания очереди открывают файлы позже,
        // уже после возврата пикера.
        val picked = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            uri to displayName(context, uri)
        }
        vm.addToSelection(picked)
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { vm.setInputFolder(it) } }

    // Ровная сетка или с разбивкой по датам. rememberSaveable, а не remember:
    // переключение вкладок не должно сбрасывать выбор вида.
    var denseGrid by rememberSaveable { mutableStateOf(true) }
    var gridMenuOpen by remember { mutableStateOf(false) }

    // Шапка плавает поверх сетки, а сетка начинается под ней — иначе верхний
    // ряд плиток оказался бы под заголовком. Высоту меряем, а не задаём
    // константой: строки переводятся и на других языках длиннее.
    var headerSize by remember { mutableStateOf(IntSize.Zero) }
    val headerHeight = with(LocalDensity.current) { headerSize.height.toDp() }

    Box(modifier = Modifier.fillMaxSize()) {
        VideoGallery(
            selection = state.selection.map { it.uri.toString() },
            treeUri = state.inputTreeUri,
            hasPermission = hasPermission,
            denseGrid = denseGrid,
            onToggle = { item: VideoItem ->
                vm.toggleSelection(item.uri, item.name, item.sizeBytes)
            },
            onRequestPermission = { permissionLauncher.launch(mediaPermission) },
            onPickManually = { manualPicker.launch(arrayOf("video/*")) },
            onShowAll = { vm.setInputFolder(null) },
            modifier = Modifier.fillMaxSize(),
            topInset = headerHeight,
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { headerSize = it },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tab_input),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.action_folder))
                }
                Box {
                    IconButton(onClick = { gridMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.input_grid_mode),
                        )
                    }
                    GridModeMenu(
                        expanded = gridMenuOpen,
                        dense = denseGrid,
                        onSelect = { denseGrid = it },
                        onDismiss = { gridMenuOpen = false },
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.input_source, state.inputFolderLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.inputFolderChosen) {
                    // Вернуться ко всем роликам, не заставляя искать папку заново.
                    TextButton(onClick = { vm.setInputFolder(null) }) {
                        Text(stringResource(R.string.action_all_videos))
                    }
                }
            }
        }
    }
}

/** Меню шестерёнки: ровная сетка или сетка с разбивкой по датам. */
@Composable
private fun GridModeMenu(
    expanded: Boolean,
    dense: Boolean,
    onSelect: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.input_grid_mode)) },
            onClick = {},
            enabled = false,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.input_grid_dense)) },
            leadingIcon = { RadioButton(selected = dense, onClick = null) },
            onClick = { onSelect(true) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.input_grid_dated)) },
            leadingIcon = { RadioButton(selected = !dense, onClick = null) },
            onClick = { onSelect(false) },
        )
    }
}

/** Имя файла из SAF-пикера; без провайдера — просто video. */
private fun displayName(context: android.content.Context, uri: Uri): String {
    var name = context.getString(R.string.input_fallback_name)
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
    }
    return name
}
