package xyz.imd4n.ebrake.transcode

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.edit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.File
import xyz.imd4n.ebrake.R

/**
 * Куда складывать результат очереди.
 *
 * По умолчанию — публичная `Movies/eBrake` (sd/Movies/eBrake): ролики видны
 * в галерее и файловом менеджере без SAF-пикера. Пишем туда через MediaStore
 * (RELATIVE_PATH), а не прямым File-путём: на 29+ scoped storage прямой путь
 * в shared storage даёт EACCES, а своя запись MediaStore — нет.
 *
 * Если пользователь выбрал папку системным пикером, пишем туда же через
 * SAF-протокол ffmpeg-kit. Если и MediaStore, и публичная папка недоступны
 * (например, отозван доступ) — тихо откатываемся на app-specific dir, чтобы
 * задание всё-таки выполнилось.
 */
object OutputTarget {
    private const val PREFS = "ebrake_output"
    private const val KEY_TREE = "tree_uri"
    private const val MIME_MP4 = "video/mp4"

    /** Относительный путь по умолчанию внутри shared storage. */
    const val DEFAULT_RELATIVE_PATH = "Movies/eBrake"

    /** Куда физически попадёт очередной файл. */
    sealed interface Target {
        /** То, что передаётся в ffmpeg: путь или SAF-URL. */
        val ffmpegArg: String

        /** Что показать пользователю в карточке задания. */
        val display: String

        /** Размер готового файла (у SAF — stat по дескриптору). */
        fun size(context: Context): Long

        /** Должен ли адрес уйти в unregisterSafProtocolUrl. */
        val isSaf: Boolean get() = false

        data class Local(val file: File) : Target {
            override val ffmpegArg: String get() = file.absolutePath
            override val display: String get() = file.absolutePath
            override fun size(context: Context): Long = file.length()
        }

        data class Saf(
            val uri: Uri,
            val safUrl: String,
            override val display: String,
            /**
             * true = запись создана нами в MediaStore как IS_PENDING и требует
             * финализации через [OutputTarget.complete] (снять pending при
             * успехе, удалить при провале). У пользовательской SAF-папки —
             * false, там документ живёт сам по себе.
             */
            val managedMedia: Boolean = false,
        ) : Target {
            override val ffmpegArg: String get() = safUrl
            override fun size(context: Context): Long = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
            override val isSaf: Boolean get() = true
        }
    }

    fun treeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    /** null = вернуться к папке по умолчанию (Movies/eBrake). */
    fun setTree(context: Context, uri: Uri?) {
        prefs(context).edit {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
        }
        if (uri == null) return
        // Долгий доступ: очередь пишет в папку позже, уже после возврата пикера.
        // Мультизапись обязательна — без неё createDocument откажется.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun hasTree(context: Context): Boolean = treeUri(context) != null

    /**
     * Человеческий путь папки для UI. Без выбранного дерева — абсолютный путь
     * публичной Movies/eBrake (тот самый sd/Movies/eBrake), даже если физическая
     * запись идёт через MediaStore: пользователю важен путь в менеджере файлов.
     */
    fun label(context: Context): String {
        val tree = treeUri(context) ?: return publicDir().absolutePath
        val name = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name ?: ""
    }

    /**
     * Готовит приёмник для одного файла очереди. Порядок:
     * 1) пользовательская SAF-папка (если есть);
     * 2) MediaStore Movies/eBrake (29+);
     * 3) прямой File в публичной Movies/eBrake (до 28, нужен WRITE);
     * 4) app-specific dir как последняя страховка.
     */
    fun prepare(context: Context, fileName: String): Target {
        val tree = treeUri(context)
        if (tree != null) {
            val safTarget = runCatching { createDocument(context, tree, fileName) }.getOrNull()
            if (safTarget != null) return safTarget
            // Выбор папки не трогаем: разовый сбой не должен стирать настройку.
            TranscodeQueue.appendLog(context.getString(R.string.log_output_fallback))
        }
        runCatching { createMediaStoreTarget(context, fileName) }.getOrNull()?.let { return it }
        runCatching { createPublicFileTarget(fileName) }.getOrNull()?.let { return it }
        return Target.Local(File(appDir(context), fileName))
    }

    /**
     * Финализация MediaStore-записи после кодирования: снять IS_PENDING при
     * успехе (файл становится виден галерее), удалить недописанный файл при
     * провале/отмене. Для SAF-папки и Local — no-op.
     */
    fun complete(context: Context, target: Target, success: Boolean) {
        val saf = target as? Target.Saf ?: return
        if (!saf.managedMedia) return
        runCatching {
            if (success) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(saf.uri, values, null, null)
                }
            } else {
                context.contentResolver.delete(saf.uri, null, null)
            }
        }
    }

    private fun createDocument(context: Context, tree: Uri, fileName: String): Target.Saf? {
        // createDocument ждёт документ-родителя, а не корень дерева.
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val doc = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            MIME_MP4,
            fileName,
        ) ?: return null
        // Reusable — тот же SAF-фикс, что и на чтении входа: одноразовые URL
        // не открываются в executeAsync (см. ловушку в NOTES.md).
        val safUrl = FFmpegKitConfig.getSafParameterForWrite(context, doc, true)
        val folder = label(context)
        val display = if (folder.isNotEmpty() && !folder.startsWith("content:")) "$folder/$fileName" else fileName
        return Target.Saf(doc, safUrl, display)
    }

    /**
     * Запись по умолчанию через MediaStore: файл сразу виден как
     * Movies/eBrake/<name>. IS_PENDING=1 прячет недописанный файл из галереи
     * до вызова [complete].
     */
    private fun createMediaStoreTarget(context: Context, fileName: String): Target.Saf? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_MP4)
            put(MediaStore.Video.Media.RELATIVE_PATH, DEFAULT_RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return null
        return runCatching {
            val safUrl = FFmpegKitConfig.getSafParameterForWrite(context, uri, true)
            Target.Saf(uri, safUrl, "$DEFAULT_RELATIVE_PATH/$fileName", managedMedia = true)
        }.getOrElse {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw it
        }
    }

    /**
     * Прямой File в публичной папке. На 29+ почти всегда упирается в scoped
     * storage (EACCES при записи), поэтому это лишь ступень для старых API —
     * на новых primary-путь это MediaStore выше.
     */
    private fun createPublicFileTarget(fileName: String): Target.Local? {
        val dir = publicDir()
        // mkdirs=false на 29+ без прав — не врём, что папка есть.
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.canWrite()) return null
        return Target.Local(File(dir, fileName))
    }

    @Suppress("DEPRECATION")
    fun publicDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "eBrake",
        )

    private fun appDir(context: Context): File =
        File(context.getExternalFilesDir(null), "ebrake").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
