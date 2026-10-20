package xyz.imd4n.ebrake.ui.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Один ролик из системной галереи. */
data class VideoItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    /**
     * Размер в байтах из `MediaStore.Media.SIZE` (или `COLUMN_SIZE` для SAF).
     * Нужен карточке отбора, чтобы показать «общий размер X МБ» без опроса
     * каждого файла через ffprobe. Бывает 0 — часть SAF-провайдеров (карта,
     * OTG) размер не отдаёт.
     */
    val sizeBytes: Long,
)

/**
 * Чтение видео + кэш обложек.
 *
 * Два источника: MediaStore (все ролики телефона, нужен доступ к медиа) и
 * выбранная папка SAF (ровно её содержимое, доступ не нужен — удобно, чтобы
 * смотреть только Downloads). SAF-пикер остаётся третьим путём, когда ролика
 * нет ни там, ни там.
 */
object VideoLibrary {

    /**
     * Обложки галереи. Лимитируем **байтами**, а не штуками: сетка 4×N на
     * экране — это уже около 24 плиток, и кэш на 24 картинки вытеснял сам
     * себя: каждая плитка декодировалась заново по кругу. 12 МБ — это несколько
     * сотен плиток шириной 90dp, то есть весь экран с запасом.
     */
    private val covers = object : LruCache<String, Bitmap>(COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Ключи, для которых обложку достать не удалось. Отдельный кэш, потому
     * что [covers] не принимает null, а молча пробыть «ещё разок» при каждой
     * перерисовке сетки — значит заново дёргать провайдер на каждый файл.
     */
    private val coverMisses = LruCache<String, Boolean>(512)

    /**
     * Заблаговременная загрузка обложки. Отдельный метод нужен, чтобы вызов
     * читался как «подгрузи сейчас, результат мне не нужен» — иначе в коде
     * префетча валяются пустые `cover(...)` с присваиванием в никуда.
     */
    suspend fun prefetch(context: Context, uri: Uri, widthPx: Int) {
        cover(context, uri, widthPx)
    }

    /** Ролики для галереи: из выбранной папки, иначе из всего MediaStore. */
    suspend fun videos(context: Context, tree: Uri? = null): List<VideoItem> =
        withContext(Dispatchers.IO) {
            if (tree != null) treeVideos(context, tree) else mediaStoreVideos(context)
        }

    private fun mediaStoreVideos(context: Context): List<VideoItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
        )
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Всё внешнее хранилище, а не только основной том.
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val out = mutableListOf<VideoItem>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                // SIZE необязателен: на части прошивок колонки нет, и жёсткий
                // индекс обнулил бы весь список роликов.
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    out += VideoItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameCol) ?: "video",
                        durationMs = cursor.getLong(durCol),
                        dateAddedSec = cursor.getLong(dateCol),
                        sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    )
                }
            }
        }
        return out
    }

    /**
     * Ролики из выбранной папки SAF. Обход одного уровня (как показывает
     * системный пикер): вложенные подпапки не разворачиваем — иначе Downloads
     * с подпапками превратится в каталог всего телефона.
     *
     * Длительность у таких файлов неизвестна (в SAF её нет, а декодировать
     * каждый файл ради цифры в углу плитки — дорого), поэтому остаётся 0 и
     * бейдж не рисуется.
     */
    private fun treeVideos(context: Context, tree: Uri): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                // mime и дата — необязательные: часть провайдеров (карта, OTG)
                // их не отдаёт, и жёсткий getColumnIndexOrThrow обнулял бы весь список.
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol).orEmpty()
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    // Часть провайдеров не отдаёт mime — сверяемся по расширению.
                    if (!mime.startsWith("video/") && !name.hasVideoExtension()) continue
                    out += VideoItem(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idCol)),
                        name = name.ifEmpty { "video" },
                        durationMs = 0L,
                        dateAddedSec = if (modCol >= 0) cursor.getLong(modCol) / 1000 else 0L,
                        sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    )
                }
            }
        }
        return out.sortedByDescending { it.dateAddedSec }
    }

    /**
     * Размер одного файла, выбранного SAF-пикером: там нет галереи, из которой
     * можно было бы взять [VideoItem.sizeBytes], а карточке отбора нужна сумма.
     * 0, если провайдер размер не отдаёт.
     */
    suspend fun size(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                if (col >= 0 && cursor.moveToFirst()) cursor.getLong(col) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun String.hasVideoExtension(): Boolean =
        substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /**
     * Обложка ролика под ширину плитки; null, если декодировать нечем.
     *
     * Сначала системная обложка [android.content.ContentResolver.loadThumbnail]:
     * она быстрая и не трогает сам файл. Но на SAF-документах (папка Telegram,
     * Google Drive, ОТГ) она либо бросает исключение, либо молча отдаёт null —
     * поэтому null — это сигнал «попробовать иначе», а не конец. Запасной путь
     * [decodeFrame] декодирует кадр через MediaMetadataRetriever.
     *
     * Неудачи тоже кэшируются: иначе каждый скролл заново дёргал бы провайдер
     * для каждого файла, и сетка с папки оставалась бы серой и «дышащей».
     */
    suspend fun cover(context: Context, uri: Uri, widthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$uri@$widthPx"
            // Провал запоминаем отдельно: LruCache не принимает null, а пустой
            // Bitmap означал бы «не декодировать этот файл никогда».
            if (coverMisses.get(key) != null) return@withContext null
            covers.get(key)?.let { return@withContext it }
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(widthPx, widthPx), null)
                } else {
                    null
                }
            }.getOrNull() ?: decodeFrame(context, uri, widthPx)
            if (bitmap != null) covers.put(key, bitmap) else coverMisses.put(key, true)
            bitmap
        }

    /**
     * Кадр-заставка через MediaMetadataRetriever — запасной путь для SAF.
     *
     * Ключевое здесь — [MediaMetadataRetriever.getScaledFrameAtTime]: без него
     * декодируется кадр **полного разрешения**, и для 4K это ~8 МБ Bitmap на
     * каждую плитку 90dp. Масштабированный запрос отдаёт сразу нужный размер.
     * Высота -1 оставляет пропорции: плитка квадратная и кадр кропается, но
     * растягивать исходник нельзя. Не все провайдеры умеют масштабировать —
     * тогда метод бросает исключение, и мы откатываемся к полному кадру.
     */
    private fun decodeFrame(context: Context, uri: Uri, widthPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // Середина ролика: обычно это уже не чёрный кадр вступления.
            val atUs = durMs * 1000 / 3
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching {
                    retriever.getScaledFrameAtTime(
                        atUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        widthPx,
                        -1,
                    )
                }.getOrNull()
            } else {
                null
            } ?: retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleDown(it, widthPx) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0 || (w <= maxDim && h <= maxDim)) return src
        val aspect = w.toFloat() / h.toFloat()
        val (targetW, targetH) = if (w >= h) {
            maxDim to (maxDim / aspect).toInt().coerceAtLeast(1)
        } else {
            (maxDim * aspect).toInt().coerceAtLeast(1) to maxDim
        }
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        if (scaled !== src) {
            src.recycle()
        }
        return scaled
    }
}

/** Расширения для провайдеров SAF, которые не отдают mime-тип. */
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "ts")

/** Предел кэша обложек, байт. См. [VideoLibrary.covers]. */
private const val COVER_CACHE_BYTES = 12 * 1024 * 1024
