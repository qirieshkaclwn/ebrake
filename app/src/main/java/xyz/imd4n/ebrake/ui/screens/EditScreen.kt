package xyz.imd4n.ebrake.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.CompareLevel
import xyz.imd4n.ebrake.transcode.FfmpegCommands
import xyz.imd4n.ebrake.transcode.TranscodeEngine
import xyz.imd4n.ebrake.transcode.TranscodeFormat
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.transcode.TranscodeUiState
import xyz.imd4n.ebrake.transcode.TranscodeViewModel
import xyz.imd4n.ebrake.ui.DockMetrics
import xyz.imd4n.ebrake.ui.FullscreenQualityPreview
import xyz.imd4n.ebrake.ui.PreviewPlayerHolder
import xyz.imd4n.ebrake.ui.PreviewPlayerSurface
import xyz.imd4n.ebrake.ui.QualityLevelSlider
import kotlin.math.roundToInt

/**
 * Пикер ролика для сравнения живёт в EbrakeApp рядом с остальными слоями:
 * стеклу нужен сиблинг источника. Сюда наружу только колбэк открытия.
 */
@Composable
fun EditScreen(vm: TranscodeViewModel, state: TranscodeUiState, onOpenPicker: () -> Unit) {
    val context = LocalContext.current

    // Один плеер на весь экран сравнения: качество переключается подменой MediaItem
    // с сохранением позиции, полноэкранный режим переиспользует тот же инстанс.
    val player = remember(context) { PreviewPlayerHolder(context) }
    DisposableEffect(player) { onDispose { player.release() } }

    // Уровни, для которых реально есть файл: если кодирование уровня упало,
    // слайдер не предлагает пустой вариант.
    val levels: List<CompareLevel> =
        CompareLevel.entries.filter { level -> state.previews.any { it.level == level } }
    val selected: CompareLevel? =
        state.preset.compareLevel.takeIf { it in levels } ?: levels.firstOrNull()
    val selectedPreview = state.previews.firstOrNull { it.level == selected }
    val currentPath = selectedPreview?.outputPath.orEmpty()

    var fullscreen by remember { mutableStateOf(false) }
    var qualityInfoOpen by remember { mutableStateOf(false) }
    // runId в ключе: после нового «Сравнить» путь тот же, а файл перезаписан.
    LaunchedEffect(currentPath, state.compareRunId) {
        player.load(currentPath, state.compareRunId)
    }
    // Детект при открытии: иначе меню движков не знает, что серить.
    LaunchedEffect(Unit) { vm.refreshEncoders() }

    val subtitle = if (selectedPreview == null) {
        stringResource(R.string.edit_compare_hint)
    } else {
        stringResource(
            R.string.edit_compare_meta,
            stringResource(selected!!.titleRes),
            formatBytes(context, selectedPreview.fileSizeBytes),
            TranscodeFormat.PREVIEW_SEC,
        )
    }

    LazyColumn(
        // Отступ снизу — высота дока: док рисуется поверх содержимого, и без
        // этого последняя кнопка уезжала бы под пилюлю намертво.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = DockMetrics.ContentBottom),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.tab_edit),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.edit_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(stringResource(R.string.edit_engine), style = MaterialTheme.typography.titleSmall)
            // Строка с выпадающим меню вместо сетки 2×N: движков уже пять,
            // дальше будет больше, а вертикаль экрана не резиновая.
            EngineDropdown(
                selected = state.preset.engine,
                // Та же проверка, что и у очереди: сборка + железо телефона.
                isAvailable = vm::engineAvailable,
                onSelect = vm::setEngine,
                enabled = !state.compareRunning && !state.queueRunning,
                strict = state.encodersDetected,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.encodersDetected) {
                Text(
                    stringResource(R.string.edit_engine_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.edit_quality),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // Что крутит слайдер: одной строкой-хинтом не объяснить.
                IconButton(onClick = { qualityInfoOpen = true }) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = stringResource(R.string.edit_quality_about),
                    )
                }
            }
            // Слайдер крутит Баланс, Высокое и Эко едут от него шагами.
            // Применяется на отпускании: иначе каждое движение сносило бы превью.
            QualityBaseSlider(
                quality = state.preset.quality,
                spread = state.preset.spread,
                engine = state.preset.engine,
                enabled = !state.compareRunning && !state.queueRunning,
                onFinished = vm::setQuality,
                onSpreadFinished = vm::setSpread,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.edit_quality_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // Пока кодирует очередь, движок занят: FFmpegKit.cancel() из
                // очереди погасил бы и сравнение, поэтому кнопку блокируем.
                Button(
                    onClick = onOpenPicker,
                    enabled = !state.compareRunning &&
                        !state.queueRunning &&
                        state.selection.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.edit_compare))
                }
                if (state.compareRunning) {
                    OutlinedButton(onClick = { vm.cancelCompare() }) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
            }
            if (state.selection.isEmpty() && !state.compareRunning) {
                Text(
                    stringResource(R.string.edit_compare_needs_selection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.compareRunning) {
                LinearProgressIndicator(progress = { state.compareProgress01 }, modifier = Modifier.fillMaxWidth())
                Text(state.compareStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.edit_compare_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(player.videoAspect)
                            // Скругляем и чёрную подложку: без clip углы кадра
                            // торчат острыми углами за пределы карточки.
                            .clip(RoundedCornerShape(PLAYER_CORNER))
                            .background(Color.Black),
                    ) {
                        if (currentPath.isNotEmpty()) {
                            // В фулскрине инлайн-поверхность не компонуется:
                            // два PlayerView на одном ExoPlayer Media3 не держит.
                            if (!fullscreen) {
                                PreviewPlayerSurface(
                                    holder = player,
                                    modifier = Modifier.fillMaxSize(),
                                    // Свернули карточку — замолкаем; фулскрин — не молчим.
                                    pauseOnRelease = !fullscreen,
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.edit_preview_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        IconButton(
                            onClick = { fullscreen = true },
                            enabled = currentPath.isNotEmpty(),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        ) {
                            Icon(
                                Icons.Filled.Fullscreen,
                                contentDescription = stringResource(R.string.cd_preview_fullscreen),
                                tint = Color.White,
                            )
                        }
                    }
                    QualityLevelSlider(
                        levels = levels,
                        selected = selected,
                        onSelect = vm::setCompareLevel,
                        enabled = currentPath.isNotEmpty(),
                    )
                    // Что именно уйдёт в очередь — одной строкой над кнопкой:
                    // держать это в подписи самой кнопки значит писать
                    // «Взять в очередь: Выбрано 3 ролика».
                    Text(
                        text = if (state.selection.isEmpty()) {
                            stringResource(R.string.edit_queue_empty)
                        } else {
                            pluralStringResource(
                                R.plurals.selected_count,
                                state.selection.size,
                                state.selection.size,
                            ) + " · " + if (state.selectedBytes > 0) {
                                stringResource(
                                    R.string.selected_total_size,
                                    formatBytes(context, state.selectedBytes),
                                )
                            } else {
                                stringResource(R.string.selected_size_unknown)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        // В очередь уходит вся отметка с «Ввода» с текущим
                        // уровнем слайдера, а не один ролик сравнения: кнопки
                        // импорта на «Вводе» больше нет, и брать оттуда нечего.
                        onClick = { vm.enqueueChecked() },
                        enabled = state.selection.isNotEmpty() && !state.queueRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_enqueue))
                    }
                    TextButton(
                        onClick = { vm.clearSelection() },
                        enabled = state.selection.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_clear_selection))
                    }
                }
            }
        }
    }

    if (fullscreen) {
        FullscreenQualityPreview(
            holder = player,
            levels = levels,
            selected = selected,
            subtitle = subtitle,
            onLevelChange = vm::setCompareLevel,
            onDismiss = { fullscreen = false },
        )
    }

    if (qualityInfoOpen) {
        AlertDialog(
            onDismissRequest = { qualityInfoOpen = false },
            confirmButton = {
                TextButton(onClick = { qualityInfoOpen = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            title = { Text(stringResource(R.string.edit_quality_about)) },
            text = { Text(stringResource(R.string.edit_quality_about_text)) },
        )
    }
}

/**
 * Движок одной строкой с выпадающим меню: короткое имя + характер, недоступные
 * в сборке помечены, но выбираются — при запуске их заменит доступный.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineDropdown(
    selected: TranscodeEngine,
    isAvailable: (TranscodeEngine) -> Boolean,
    onSelect: (TranscodeEngine) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** true = детект прошёл: отсутствующих не выбираем, а сереем. */
    strict: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val available = isAvailable(selected)
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { if (enabled) open = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = stringResource(selected.shortNameRes) +
                " · " + stringResource(
                    if (available) selected.hintRes else R.string.engine_not_in_build,
                ),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TranscodeEngine.entries.forEach { engine ->
                val ok = isAvailable(engine)
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(engine.shortNameRes) + " · " + stringResource(
                                if (ok) engine.hintRes else R.string.engine_not_in_build,
                            ),
                        )
                    },
                    // Нет в сборке или в железе — пункт серый и не жмётся.
                    // До детекта всё доступно: детект ещё не проходил.
                    enabled = ok || !strict,
                    onClick = {
                        open = false
                        onSelect(engine)
                    },
                )
            }
        }
    }
}

/**
 * База качества: слайдер — это Баланс, Высокое и Эко пересчитываются от него
 * на каждый шаг, значения стоят под своими рисками.
 *
 * Трек тонкий (4.dp), без точек: шаги и так целые (округление в onValueChange).
 * Риски таскаются пальцем: одна тянет вторую через общий разброс — Эко к базе,
 * и Высокое к базе на столько же. Применяется на отпускании.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityBaseSlider(
    quality: Int,
    spread: Float,
    engine: TranscodeEngine,
    onFinished: (Int) -> Unit,
    onSpreadFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var draft by remember(quality) { mutableIntStateOf(quality) }
    var dragSpread by remember(spread) { mutableFloatStateOf(spread) }
    val scheme = MaterialTheme.colorScheme
    val ecoOff = FfmpegCommands.levelOffset(engine, CompareLevel.ECO, dragSpread)
    val highOff = FfmpegCommands.levelOffset(engine, CompareLevel.HIGH, dragSpread)
    val range = FfmpegCommands.baseRange(engine, dragSpread)
    val ecoFrac = (draft + ecoOff).coerceIn(0, 100) / 100f
    val highFrac = (draft + highOff).coerceIn(0, 100) / 100f
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = draft.toFloat(),
            // База едет только между рисками: за Эко или за Высокое она
            // перестаёт быть «балансом».
            onValueChange = { draft = it.roundToInt().coerceIn(range) },
            onValueChangeFinished = { onFinished(draft) },
            valueRange = 0f..100f,
            steps = 0,
            enabled = enabled,
            thumb = {
                Box(
                    Modifier
                        .size(THUMB_SIZE)
                        .background(scheme.primary, CircleShape),
                )
            },
            track = {
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(TRACK_TOUCH),
                    contentAlignment = Alignment.Center,
                ) {
                    val wide = maxWidth
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(TRACK_HEIGHT)
                            .clip(RoundedCornerShape(TRACK_HEIGHT / 2))
                            .background(scheme.surfaceVariant),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(draft / 100f)
                            .align(Alignment.CenterStart)
                            .height(TRACK_HEIGHT)
                            .clip(RoundedCornerShape(TRACK_HEIGHT / 2))
                            .background(scheme.primary),
                    )
                    TickHitbox(
                        frac = ecoFrac,
                        width = wide,
                        enabled = enabled,
                        onDrag = { dragSpread = FfmpegCommands.dragSpread(engine, CompareLevel.ECO, dragSpread, it) },
                        onStop = { onSpreadFinished(dragSpread) },
                    )
                    TickHitbox(
                        frac = highFrac,
                        width = wide,
                        enabled = enabled,
                        onDrag = { dragSpread = FfmpegCommands.dragSpread(engine, CompareLevel.HIGH, dragSpread, it) },
                        onStop = { onSpreadFinished(dragSpread) },
                    )
                }
            },
        )
        // Значения под рисками: каждое стоит над своей точкой трека.
        BoxWithConstraints(Modifier.fillMaxWidth().height(LABELS_HEIGHT)) {
            val wide = maxWidth
            TickLabel(
                text = stringResource(CompareLevel.ECO.titleRes) + " " +
                    FfmpegCommands.qualityLabel(engine, CompareLevel.ECO, draft, dragSpread),
                frac = ecoFrac,
                width = wide,
            )
            TickLabel(
                text = stringResource(CompareLevel.BALANCED.titleRes) + " " +
                    FfmpegCommands.qualityLabel(engine, CompareLevel.BALANCED, draft, dragSpread),
                frac = draft / 100f,
                width = wide,
            )
            TickLabel(
                text = stringResource(CompareLevel.HIGH.titleRes) + " " +
                    FfmpegCommands.qualityLabel(engine, CompareLevel.HIGH, draft, dragSpread),
                frac = highFrac,
                width = wide,
            )
        }
    }
}

/**
 * Риска с зоной для пальца: видна тонкая черта, таскается широкая полоса.
 * Дельту переводим в пункты слайдера здесь же, наружу — готовые пункты.
 */
@Composable
private fun BoxScope.TickHitbox(
    frac: Float,
    width: Dp,
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onStop: () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .offset(x = (width * frac).coerceIn(HIT_W / 2, (width - HIT_W / 2).coerceAtLeast(HIT_W / 2)) - HIT_W / 2)
            .size(width = HIT_W, height = TRACK_TOUCH)
            .then(
                if (enabled) {
                    Modifier.draggable(
                        state = rememberDraggableState { deltaPx ->
                            val deltaPt = with(density) { deltaPx.toDp().value } * (100f / width.value)
                            onDrag(deltaPt)
                        },
                        orientation = Orientation.Horizontal,
                        onDragStopped = { onStop() },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = TICK_WIDTH, height = TICK_HEIGHT)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.onPrimary),
        )
    }
}

/** Подпись под риской, отцентрована на её точке и не уезжает за края. */
@Composable
private fun BoxScope.TickLabel(text: String, frac: Float, width: Dp) {
    Text(
        text = text,
        maxLines = 1,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = (width * frac - LABEL_W / 2).coerceIn(0.dp, (width - LABEL_W).coerceAtLeast(0.dp)))
            .width(LABEL_W),
    )
}

/** Тонкий трек слайдера и мелкий кружок вместо экспрессивной пилюли. */
private val TRACK_HEIGHT = 4.dp
private val TRACK_TOUCH = 20.dp
private val THUMB_SIZE = 14.dp
private val TICK_WIDTH = 4.dp
private val TICK_HEIGHT = 10.dp
/** Ширина пальцевой зоны риски и подписи под ней. */
private val HIT_W = 28.dp
private val LABEL_W = 88.dp
private val LABELS_HEIGHT = 20.dp

/** Радиус скругления кадра превью: совпадает с формой самой карточки. */
private val PLAYER_CORNER = 12.dp
