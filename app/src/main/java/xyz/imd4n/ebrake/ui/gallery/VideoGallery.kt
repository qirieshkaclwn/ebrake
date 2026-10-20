package xyz.imd4n.ebrake.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.TranscodeFormat
import xyz.imd4n.ebrake.ui.DockMetrics

/**
 * Галерея роликов: сетка обложек во весь экран, четыре плитки в ряд, без полей
 * и без скруглений. Между плитками — тонкий зазор в [GUTTER], сквозь который
 * виден фон сетки: это и есть разделители, отдельная рамка на каждой плитке
 * давала бы двойную линию.
 *
 * Под плитками подписей нет: в превью важен только кадр, а имя съедало бы
 * высоту и в четыре колонки поместилось бы три ряда вместо шести.
 *
 * Мультивыбор: тап ставит и снимает галочку в углу. Смысла «первый идёт на
 * сравнение» больше нет — ролик для сравнения выбирается отдельно, на «Правке».
 *
 * [denseGrid] переключает раскладку: true — ровная сетка, false — с разбивкой
 * по датам («Сегодня», «Вчера», «12 сентября»). Заголовок даты в плотной сетке
 * стоил бы целого ряда, поэтому в ней он убран.
 *
 * Источник роликов: выбранная папка SAF, иначе MediaStore. Без доступа к медиа
 * показываем пустое состояние с кнопкой «Выбрать вручную» — путь, который
 * работает вообще без разрешений.
 */
@Composable
fun VideoGallery(
    /** Отмеченные ролики как строки Uri — в таком виде их держит состояние. */
    selection: List<String>,
    /** Выбранная папка SAF; null = весь MediaStore. */
    treeUri: Uri?,
    hasPermission: Boolean,
    denseGrid: Boolean,
    onToggle: (VideoItem) -> Unit,
    onRequestPermission: () -> Unit,
    onPickManually: () -> Unit,
    onShowAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Отступ сверху под плавающую шапку экрана. */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<VideoItem>?>(null) }
    val gridState = rememberLazyGridState()
    // null = грузим, пустой список = загрузили и ничего не нашли.
    val loadingKey = listOf(hasPermission, treeUri)
    LaunchedEffect(loadingKey) {
        items = if (treeUri != null || hasPermission) {
            VideoLibrary.videos(context, treeUri)
        } else {
            emptyList()
        }
    }

    val marked = remember(selection) { selection.toSet() }

    val loaded = items
    when {
        loaded == null -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        !hasPermission && treeUri == null -> GalleryMessage(
            title = stringResource(R.string.gallery_need_access_title),
            text = stringResource(R.string.gallery_need_access_text),
            actions = listOf(
                stringResource(R.string.action_allow) to onRequestPermission,
                stringResource(R.string.action_pick_manually) to onPickManually,
            ),
            modifier = modifier,
        )

        loaded.isEmpty() -> GalleryMessage(
            title = stringResource(R.string.gallery_empty_title),
            text = if (treeUri != null) {
                stringResource(R.string.gallery_empty_folder)
            } else {
                stringResource(R.string.gallery_empty_all)
            },
            actions = buildList {
                add(stringResource(R.string.action_pick_manually) to onPickManually)
                if (onShowAll != null) add(stringResource(R.string.action_all_videos) to onShowAll)
            },
            modifier = modifier,
        )

        else -> {
            val locale = TranscodeFormat.currentLocale(context)
            val today = stringResource(R.string.date_today)
            val yesterday = stringResource(R.string.date_yesterday)
            val epochDay = System.currentTimeMillis() / 86_400_000L
            // Секции кэшируем, чтобы группировка сотен файлов не крутилась на каждом кадре скролла.
            // epochDay инвалидирует кэш при переходе через полночь.
            val sections = remember(loaded, denseGrid, today, yesterday, locale, epochDay) {
                if (denseGrid || loaded.isEmpty()) emptyList() else buildDateSections(loaded, today, yesterday, locale)
            }
            // Порядок элементов ровно такой, как в сетке, заголовками дат
            // включительно (они помечены null). Нужен предзагрузке, чтобы
            // «две строки вперёд» считались по индексам сетки, а не по
            // отдельному списку, который рано или поздно разошёлся бы с ней.
            val gridOrder: List<VideoItem?> = if (denseGrid) {
                loaded.map { it as VideoItem? }
            } else {
                buildList {
                    sections.forEach { (_, section) ->
                        add(null)
                        addAll(section)
                    }
                }
            }
            val gridModifier = modifier
                .fillMaxSize()
                // Фон сетки виден в зазорах — это и есть тонкие разделители.
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(top = topInset)

            BoxWithConstraints(modifier = gridModifier) {
                // Ширина плитки считается один раз здесь и передаётся вниз:
                // ключ кэша обложки включает ширину, и если плитка запросит
                // «немного другую», предзагрузка окажется впустую.
                val coverPx = with(LocalDensity.current) {
                    (maxWidth / GALLERY_COLUMNS).toPx()
                }.toInt().coerceIn(MIN_COVER_PX, MAX_COVER_PX)

                CoverPrefetch(gridState, gridOrder, coverPx)

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(GALLERY_COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(0.dp),
                    horizontalArrangement = Arrangement.spacedBy(GUTTER),
                    verticalArrangement = Arrangement.spacedBy(GUTTER),
                ) {
                if (denseGrid) {
                    items(loaded, key = { it.uri.toString() }) { item ->
                        GalleryTile(
                            item = item,
                            coverPx = coverPx,
                            marked = item.uri.toString() in marked,
                            onClick = { onToggle(item) },
                        )
                    }
                } else {
                    sections.forEach { (label, section) ->
                        item(key = "section_$label", span = { GridItemSpan(maxLineSpan) }) {
                            // Зазор слева/справа сохраняем, иначе заголовок
                            // прилипает к краю экрана, а сетка — нет.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                        items(section, key = { it.uri.toString() }) { item ->
                            GalleryTile(
                                item = item,
                                coverPx = coverPx,
                                marked = item.uri.toString() in marked,
                                onClick = { onToggle(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun GalleryTile(
    item: VideoItem,
    /** Ширина обложки в px — считается один раз на всю сетку, не на плитку. */
    coverPx: Int,
    marked: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val locale = TranscodeFormat.currentLocale(context)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Углы острые: скругление здесь означало бы ещё и чёрные углы
            // подложки, и в четырёх рядах сетка выглядела бы рваной.
            .clickable(onClick = onClick),
    ) {
        val cover by produceState<Bitmap?>(
            initialValue = null,
            key1 = item.uri,
            key2 = coverPx,
        ) {
            value = VideoLibrary.cover(context, item.uri, coverPx)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Квадрат под кадр: обложка кропается по центру, поэтому
                // вертикальные ролики не превращают плитку в колонку.
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val bitmap = cover
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Обложка ещё декодируется — заглушка, чтобы не мигало.
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
            if (marked) {
                // Галочка, а не порядковый номер: номер был нужен только
                // чтобы различать «сравнение» и «очередь», а теперь
                // отметка одна на всех.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(CHECK_SIZE)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(CHECK_SIZE * 0.6f),
                    )
                }
            }
            if (item.durationMs > 0) {
                Text(
                    text = formatDuration(item.durationMs, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * Заблаговременная подгрузка обложек вокруг видимой области.
 *
 * Без неё каждая плитка грузится только в момент появления, и на любом
 * замере, дольше чтения одного кадра, сетка «дышит» — телефону видно, как он
 * то подгружает, то выгружает превью.
 *
 * Два свойства:
 *  - **пауза на скролле.** Пока идёт прокрутка, декодирование не запускается
 *    вовсе: [collectLatest] отменяет текущую загрузку при каждом новом
 *    состоянии и ждёт, пока сетка остановится. Догонять кадры на ходу —
 *    значит гарантированно не успевать и дёргать список;
 *  - **пауза нужна и во время медленного перетаскивания**, поэтому
 *    [SETTLE_MS] откладывает старт: палец «дёрнулся — отпустил — потянул
 *    снова» не должен запускать декодирование каждый раз.
 *
 * Назад подгружаем на [PREFETCH_ROWS_BACK] строк, вперёд — на
 * [PREFETCH_ROWS_AHEAD]: при возврате вверх попадаем в кэш, а не в пропасть.
 */
@Composable
private fun CoverPrefetch(
    gridState: LazyGridState,
    order: List<VideoItem?>,
    coverPx: Int,
) {
    val context = LocalContext.current
    LaunchedEffect(gridState, order, coverPx) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            Triple(first, last, gridState.isScrollInProgress)
        }.collectLatest { (first, last, scrolling) ->
            if (scrolling) {
                // Дали сетке встать, но дальше грузим: иначе последний флинг
                // уходил в return и предзагрузка не шла до следующего скролла.
                delay(SETTLE_MS)
                if (gridState.isScrollInProgress) return@collectLatest
            }
            val from = (first - PREFETCH_ROWS_BACK * GALLERY_COLUMNS).coerceAtLeast(0)
            val to = (last + PREFETCH_ROWS_AHEAD * GALLERY_COLUMNS).coerceAtMost(order.lastIndex)
            for (i in from..to) {
                val item = order.getOrNull(i) ?: continue
                VideoLibrary.prefetch(context, item.uri, coverPx)
            }
        }
    }
}

@Composable
private fun GalleryMessage(
    title: String,
    text: String,
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    Column(
        // Отступ снизу — высота дока: док парит поверх всего, и без этого
        // «Разрешить» и «Выбрать вручную» оказывались ровно под пилюлёй.
        modifier = modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = DockMetrics.ContentBottom),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        actions.forEachIndexed { index, (label, action) ->
            if (index == 0) {
                Button(onClick = action) { Text(label) }
            } else {
                TextButton(onClick = action) { Text(label) }
            }
        }
    }
}

/** Ролики, сгруппированные по дням: сегодня / вчера / дата. */
internal fun <T> buildDateSections(
    items: List<T>,
    today: String,
    yesterday: String,
    locale: Locale,
    dateAddedSec: (T) -> Long,
): List<Pair<String, List<T>>> {
    val calendar = Calendar.getInstance()
    val thisYear = calendar.get(Calendar.YEAR)
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val midnightToday = calendar.timeInMillis / 1000
    val midnightYesterday = midnightToday - 86_400L

    val dayFormat = SimpleDateFormat("d MMMM", locale)
    val dateFormat = SimpleDateFormat("d MMMM yyyy", locale)
    val itemCal = Calendar.getInstance()

    return items
        .groupBy { item ->
            val added = dateAddedSec(item)
            when {
                added >= midnightToday -> today
                added >= midnightYesterday -> yesterday
                else -> {
                    val date = Date(added * 1000)
                    itemCal.time = date
                    val year = itemCal.get(Calendar.YEAR)
                    if (year == thisYear) dayFormat.format(date) else dateFormat.format(date)
                }
            }
        }
        // Порядок групп задаёт самый свежий ролик в них.
        .toList()
        .sortedByDescending { (_, groupItems) -> groupItems.maxOf { dateAddedSec(it) } }
}

internal fun buildDateSections(
    items: List<VideoItem>,
    today: String,
    yesterday: String,
    locale: Locale,
): List<Pair<String, List<VideoItem>>> =
    buildDateSections(items, today, yesterday, locale) { it.dateAddedSec }

/**
 * Таймкод ролика: `м:сс` или `ч:мм:сс`.
 *
 * Локаль передаётся, а не берётся из системы: %d на некоторых локалях
 * (например арабской) подставляет восточно-арабские цифры, и таймкод рядом
 * с латиницей выглядит чужеродно. Язык приложения указывает и на разделитель.
 */
internal fun formatDuration(ms: Long, locale: Locale): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(locale, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(locale, "%d:%02d", m, s)
    }
}

/** Границы декодируемой обложки, px: снизу — чтобы не декодировать 20px, сверху — кэш. */
private const val MIN_COVER_PX = 200
private const val MAX_COVER_PX = 720

/** Плиток в ряду. Четыре — компромисс: на 360dp это 90dp, кадр ещё узнаваем. */
const val GALLERY_COLUMNS = 4

/** Зазор между плитками: сквозь него виден фон сетки — это и разделители. */
val GUTTER = 1.dp

/** Галочка отметки в углу плитки. */
private val CHECK_SIZE = 20.dp

/** Строк вперёд грузим до того, как пользователь до них доедет. */
private const val PREFETCH_ROWS_AHEAD = 3

/** Строк назад: возврат вверх должен попадать в кэш, а не декодироваться заново. */
private const val PREFETCH_ROWS_BACK = 1

/**
 * Пауза после остановки сетки перед стартом догрузки. Без неё «дёрнул —
 * отпустил — потянул» запускал бы декодирование на каждом микросдвиге.
 */
private const val SETTLE_MS = 120L
