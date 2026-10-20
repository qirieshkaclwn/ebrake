package xyz.imd4n.ebrake.transcode

import android.app.Application
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.TranscodeFormat.PREVIEW_SEC
import xyz.imd4n.ebrake.transcode.TranscodeFormat.fixed
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatSec
import xyz.imd4n.ebrake.transcode.TranscodeFormat.fpsOrNull
import xyz.imd4n.ebrake.transcode.TranscodeFormat.lastLogLines
import xyz.imd4n.ebrake.transcode.TranscodeFormat.mbpsOrNull
import xyz.imd4n.ebrake.ui.gallery.VideoLibrary

data class TranscodeUiState(
    val inputUri: Uri? = null,
    /**
     * Пусто = файл не выбран. Раньше здесь стоял русский текст «Файл не
     * выбран», но состояние создаётся до того, как доступен Context: строка
     * зафиксировала бы язык навсегда. Экран сам берёт подпись из ресурсов,
     * когда имя пустое.
     */
    val inputName: String = "",
    val mediaInfo: String = "",
    val durationSec: Double = 0.0,
    /**
     * Отмеченные на «Вводе» ролики в порядке нажатий. Отметка значит одно:
     * «взять в обработку». Ролика для сравнения здесь нет — его выбирают отдельно
     * на «Правке», кнопкой «Сравнить 3 уровня».
     */
    val selection: List<SelectedInput> = emptyList(),

    /** Ролик, открытый на просмотр прямо из карточки отбора. Не обязательно отмечен. */
    val inspectUri: Uri? = null,
    val inspectName: String = "",
    val inspectInfo: String = "",
    val inspectLoading: Boolean = false,

    val preset: TranscodePreset = TranscodePreset(),
    val previews: List<PreviewResult> = emptyList(),
    /**
     * Счётчик запусков сравнения. Растёт на каждом `runCompare()`: плеер превью
     * по нему понимает, что файл с тем же путём перезаписан и его нужно
     * перезагрузить с нуля, а не продолжать со старой позиции.
     */
    val compareRunId: Int = 0,
    val compareRunning: Boolean = false,
    val compareProgress01: Float = 0f,
    val compareStatus: String = "",
    // Ниже — зеркало состояния, которым владеет TranscodeQueue/EncoderCatalog.
    // Сами очереди и лог живут там: кодирование крутит TranscodeService, он
    // переживает Activity, а ViewModel — нет. Экраны читают только ui.
    val queue: List<TranscodeJob> = emptyList(),
    val queueRunning: Boolean = false,
    /** Выполненные задания: список роликов и суммарная экономия места. */
    val done: List<DoneRecord> = emptyList(),
    val logs: List<String> = TranscodeQueue.logs.value,
    /** Куда пишет очередь: выбранная папка или app-specific dir. */
    val outputLabel: String = "",
    /** true = выбрана пользовательская папка (нужна кнопка «Сброс»). */
    val outputTreeChosen: Boolean = false,
    /** Откуда галерея берёт ролики: выбранная папка или весь MediaStore. */
    val inputFolderLabel: String = "",
    /** true = выбрана папка ввода (галерея лишена разрешения MediaStore). */
    val inputFolderChosen: Boolean = false,
    /** Дерево SAF выбранной папки; null = весь MediaStore. */
    val inputTreeUri: Uri? = null,
    /** Имена энкодеров из `-encoders`. Пусто = ещё не детектили. */
    val availableEncoders: Set<String> = emptySet(),
    val encodersDetected: Boolean = false,
) {
    /**
     * Суммарный размер отмеченных роликов. Карточка отбора показывает его
     * одной строкой, поэтому он считается здесь, а не копируется в состояние:
     * иначе пришлось бы обновлять его в каждом месте, где меняется отметка.
     */
    val selectedBytes: Long get() = selection.sumOf { it.sizeBytes }
}

class TranscodeViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(TranscodeUiState())
    val ui: StateFlow<TranscodeUiState> = _ui.asStateFlow()

    private val context: Application get() = getApplication()

    /**
     * Форма из ресурсов с правильной формой слова. Количественное «с ошибкой»,
     * «задание» и т. п. отличаются по числу, и склейка через `str` напечатала
     * «с ошибкой 5».
     */
    private fun plural(
        @PluralsRes id: Int,
        quantity: Int,
        vararg args: Any?,
    ): String = context.resources.getQuantityString(id, quantity, *args)

    /**
     * Строка из ресурсов. Логи и подписи состояния живут вне Compose, поэтому
     * `stringResource` там недоступен — но язык здесь всё равно правильный:
     * берём его у текущей конфигурации, а не у системы.
     *
     * Аргументы nullable и правда nullable (например `e.message` у исключения):
     * `getString` сам печатает «null», и это честнее, чем падать на ровном месте.
     */
    private fun str(@StringRes id: Int, vararg args: Any?): String =
        context.getString(id, *args)

    /** Многоразовый SAF-URL входа (ffkitsaf://), освобождается при смене файла. */
    private var inputSafUrl: String? = null

    init {
        // Одноразовые SAF-URL (дефолт библиотеки) не открываются в executeAsync:
        // на устройстве падало с "Failed to open SAF id N: callback returned 0".
        // Делаем все URL многоразовыми и освобождаем вручную после каждого задания.
        // (TranscodeService ставит то же самое — сервис может стартовать первым.)
        FFmpegKitConfig.setSafUrlsReusable(true)

        // Приветствие в лог: из ресурсов, иначе строка осталась бы русской.
        // ViewModel переживает поворот экрана, а TranscodeQueue — процесс,
        // поэтому дубликат не появляется.
        TranscodeQueue.appendLog(str(R.string.log_engine_ready))

        // Детект движков сразу при старте: экран онбординга показывает плашку
        // «что умеет этот телефон», а меню на «Правке» — серые пункты.
        // Однократный (см. EncoderCatalog.ensureDetected), поэтому дешёво.
        refreshEncoders()

        // Зеркалим состояние очереди/лога/энкодеров в ui одним потоком,
        // чтобы экраны не знали про TranscodeQueue и EncoderCatalog.
        viewModelScope.launch {
            TranscodeQueue.queue.collect { q -> _ui.update { it.copy(queue = q) } }
        }
        viewModelScope.launch {
            TranscodeQueue.running.collect { r -> _ui.update { it.copy(queueRunning = r) } }
        }
        viewModelScope.launch {
            TranscodeQueue.logs.collect { l -> _ui.update { it.copy(logs = l) } }
        }
        viewModelScope.launch {
            TranscodeQueue.done.collect { d ->
                _ui.update { state ->
                    val finished = d.map { it.inputUri }.toSet()
                    state.copy(
                        done = d,
                        // Отметка снимается после успешного кодирования: держать
                        // «взять в обработку» у уже обработанного ролика бессмысленно,
                        // а снять её вручную пользователь забывает.
                        selection = state.selection.filterNot { it.uri in finished },
                    )
                }
            }
        }
        viewModelScope.launch {
            EncoderCatalog.state.collect { s ->
                _ui.update {
                    it.copy(availableEncoders = s.names, encodersDetected = s.detected)
                }
            }
        }
        val context = getApplication<Application>()
        _ui.update {
            it.copy(
                outputLabel = OutputTarget.label(context),
                outputTreeChosen = OutputTarget.hasTree(context),
                inputFolderLabel = InputTarget.label(context),
                inputFolderChosen = InputTarget.hasTree(context),
                inputTreeUri = InputTarget.treeUri(context),
            )
        }
    }

    /**
     * Папка вывода от системного пикера. null — вернуться к папке приложения.
     * Выбор сохраняется в SharedPreferences: сервис переживает Activity.
     */
    fun setOutputTree(uri: Uri?) {
        val context = getApplication<Application>()
        OutputTarget.setTree(context, uri)
        _ui.update {
            it.copy(
                outputLabel = OutputTarget.label(context),
                outputTreeChosen = OutputTarget.hasTree(context),
            )
        }
        appendLog(str(R.string.log_output_folder, OutputTarget.label(context)))
    }

    /**
     * Папка ввода от системного пикера: галерея покажет ровно её содержимое
     * (например, только Downloads) и без разрешения на MediaStore. null —
     * вернуться ко всем роликам телефона.
     */
    fun setInputFolder(uri: Uri?) {
        val context = getApplication<Application>()
        InputTarget.setTree(context, uri)
        _ui.update {
            it.copy(
                inputFolderLabel = InputTarget.label(context),
                inputFolderChosen = InputTarget.hasTree(context),
                inputTreeUri = InputTarget.treeUri(context),
            )
        }
        appendLog(str(R.string.log_input_folder, InputTarget.label(context)))
    }

    /**
     * Отметка ролика в галерее. Ничего больше она не трогает: вход сравнения
     * выбирается отдельно, на «Правке», иначе снятие галочки с первого ролика
     * молча подменяло бы файл, который пользователь уже сравнивает.
     */
    fun toggleSelection(uri: Uri, name: String, sizeBytes: Long) {
        val key = uri.toString()
        _ui.update { state ->
            val next = if (state.selection.any { it.uri.toString() == key }) {
                state.selection.filterNot { it.uri.toString() == key }
            } else {
                state.selection + SelectedInput(uri, name, sizeBytes)
            }
            state.copy(selection = next)
        }
    }

    /**
     * Файлы из ручного пикера. Размер каждого доказывается отдельным запросом:
     * из галереи тут нечего взять, а карточке отбора нужна сумма.
     * Обновление состояния батчится один раз для всего списка.
     */
    fun addToSelection(items: List<Pair<Uri, String>>) {
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentUris = _ui.value.selection.map { it.uri }.toSet()
            val newItems = items
                .filterNot { (uri, _) -> uri in currentUris }
                .map { (uri, name) ->
                    val size = VideoLibrary.size(getApplication(), uri)
                    SelectedInput(uri, name, size)
                }
            if (newItems.isNotEmpty()) {
                _ui.update { it.copy(selection = it.selection + newItems) }
            }
        }
    }

    /** Удаление одного задания из очереди. */
    fun removeJob(id: Long) {
        TranscodeQueue.remove(id)
    }

    /** Сброс отметок. Вход сравнения не трогаем — он выбран на «Правке». */
    fun clearSelection() {
        _ui.update { it.copy(selection = emptyList()) }
    }

    /** Ролик, открытый на просмотр из карточки отбора: снимаем, если он уже не тот. */
    fun closeInspect() {
        _ui.update { it.copy(inspectUri = null, inspectName = "", inspectInfo = "", inspectLoading = false) }
    }

    /**
     * Просмотр ролика прямо из карточки: метаданные читаются тем же ffprobe,
     * что и для входа сравнения, но в отдельное состояние — иначе открытый
     * для просмотра ролик подменял бы файл на «Правке».
     */
    fun inspect(uri: Uri, name: String) {
        if (_ui.value.inspectUri == uri) return
        _ui.update {
            it.copy(inspectUri = uri, inspectName = name, inspectInfo = str(R.string.input_reading), inspectLoading = true)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching {
                val ctx = getApplication<Application>()
                val saf = FFmpegKitConfig.getSafParameterForRead(ctx, uri, false)
                // Освобождаем URL только после чтения. unregisterSafProtocolUrl
                // убирает запись из карты ffmpeg-kit, и следующий же open() не
                // находит id — «Failed to open SAF id N: callback returned 0».
                // Регистрация копится, но чистится здесь же, на каждый просмотр.
                val session = try {
                    FFprobeKit.getMediaInformation(saf)
                } finally {
                    runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(saf) }
                }
                val info = session.getMediaInformation()
                if (info == null) {
                    str(R.string.meta_no_media_information) + "\n" +
                        lastLogLines(ctx, session.getOutput(), 5)
                } else {
                    val locale = TranscodeFormat.currentLocale(ctx)
                    val dur = info.getDuration()?.toDoubleOrNull() ?: 0.0
                    formatMediaSummary(info, dur, ctx, locale)
                }
            }.getOrElse { e -> str(R.string.meta_read_error, e.javaClass.name + ": " + e.message) }
            _ui.update { it.copy(inspectInfo = text, inspectLoading = false) }
        }
    }

    private fun clearInput() {
        _ui.update {
            it.copy(
                inputUri = null,
                inputName = "",
                mediaInfo = "",
                durationSec = 0.0,
                previews = emptyList(),
            )
        }
    }

    /**
     * Вход для сравнения трёх уровней. Выбирается на «Правке» из отмеченного,
     * поэтому сам по себе в отметку не попадает и порядок отметок не трогает.
     */
    fun setInput(uri: Uri, name: String) {
        applyInput(uri, name)
    }

    private fun applyInput(uri: Uri, name: String) {
        inputSafUrl?.let { runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(it) } }
        inputSafUrl = null
        _ui.update {
            it.copy(
                inputUri = uri,
                inputName = name,
                mediaInfo = str(R.string.input_reading),
                durationSec = 0.0,
                previews = emptyList(),
            )
        }
        appendLog(str(R.string.log_input, name))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val saf = FFmpegKitConfig.getSafParameterForRead(ctx, uri, true)
                inputSafUrl = saf
                // Флаг не трогаем: сравнение стартует после ffprobe, когда
                // durationSec уже свежий, иначе seek считался от нуля.
                val session = FFprobeKit.getMediaInformation(saf)
                val info = session.getMediaInformation()
                if (info == null) {
                    _ui.update {
                        it.copy(
                            mediaInfo = str(R.string.meta_no_media_information) +
                                "\n" + lastLogLines(context, session.getOutput(), 5),
                        )
                    }
                    appendLog(str(R.string.log_ffprobe_none))
                    if (comparePending) {
                        comparePending = false
                        runCompare()
                    }
                    return@launch
                }
                val dur = info.getDuration()?.toDoubleOrNull() ?: 0.0
                val locale = TranscodeFormat.currentLocale(ctx)
                _ui.update {
                    it.copy(
                        durationSec = dur,
                        mediaInfo = formatMediaSummary(info, dur, ctx, locale),
                    )
                }
                appendLog(
                    str(
                        R.string.log_ffprobe_summary,
                        info.getFormat(),
                        formatSec(dur, locale),
                    ),
                )
                if (comparePending) {
                    comparePending = false
                    runCompare()
                }
            } catch (e: Exception) {
                comparePending = false
                // Сообщение самого исключения у Android почти бесполезно: на
                // реальном файле прилетало «d != java.lang.String» — ни класса,
                // ни стека, и понять, чей это каст, было нечем. Поэтому в
                // подпись и в лог идёт класс исключения, а в лог — ещё и
                // верхушка стектрейса.
                val what = e.javaClass.name + ": " + e.message
                _ui.update { it.copy(mediaInfo = str(R.string.meta_read_error, what)) }
                appendLog(str(R.string.log_ffprobe_failed, what))
                appendLog(
                    e.stackTrace.take(12).joinToString("\n") { "\tat $it" }
                        .ifEmpty { str(R.string.log_empty) },
                )
            }
        }
    }

    /**
     * Ролик, выбранный на «Правке» для сравнения трёх уровней.
     *
     * Сравнение запускается не сразу: вход меняется здесь же, а ffprobe-шаг
     * [applyInput] ещё должен выдать SAF-URL и длительность для seek в середину.
     * Поэтому просьба ставится флагом и обрабатывается в конце applyInput — иначе
     * сравнение стартовало бы с пустым входом и молча уходило бы в лог.
     */
    fun pickForCompare(uri: Uri, name: String) {
        comparePending = true
        applyInput(uri, name)
    }

    private var comparePending = false

    fun setEngine(engine: TranscodeEngine) {
        _ui.update { it.copy(preset = it.preset.copy(engine = engine), previews = emptyList()) }
        appendLog(str(R.string.log_engine_set, str(engine.titleRes)))
    }

    fun setCompareLevel(level: CompareLevel) {
        _ui.update { it.copy(preset = it.preset.copy(compareLevel = level)) }
    }

    /**
     * База качества со слайдера (0..100). Превью сбрасываем, как при смене
     * движка: старые файлы посчитаны другой базой. База не выходит за риски.
     */
    fun setQuality(quality: Int) {
        val q = quality.coerceIn(FfmpegCommands.baseRange(_ui.value.preset.engine, _ui.value.preset.spread))
        if (_ui.value.preset.quality == q) return
        _ui.update { it.copy(preset = it.preset.copy(quality = q), previews = emptyList()) }
    }

    /** Разброс Эко/Высокого от базы (таскание рисок). Тоже сбрасывает превью. */
    fun setSpread(spread: Float) {
        val s = spread.coerceIn(0.2f, 2.5f)
        val preset = _ui.value.preset
        if (preset.spread == s) return
        // Риски сдвинулись — база обязана остаться между ними.
        val q = preset.quality.coerceIn(FfmpegCommands.baseRange(preset.engine, s))
        _ui.update { it.copy(preset = preset.copy(spread = s, quality = q), previews = emptyList()) }
    }

    /**
     * Детект энкодеров при открытии «Правки»: иначе меню не знает, что серить,
     * пока не запустят сравнение или очередь. Однократный (см. ensureDetected).
     */
    fun refreshEncoders() {
        viewModelScope.launch(Dispatchers.IO) {
            EncoderCatalog.ensureDetected(getApplication())
        }
    }

    /**
     * Реальное сравнение: 3 кодирования отрезка 10 сек (середина файла)
     * синхронными execute() в IO-потоке, последовательно. Прогресс грубый —
     * по числу готовых уровней; отмена через FFmpegKit.cancel().
     */
    fun runCompare() {
        val state = _ui.value
        val saf = inputSafUrl
        if (state.inputUri == null || saf == null) {
            appendLog(str(R.string.log_compare_no_file))
            return
        }
        if (state.compareRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    compareRunning = true,
                    compareProgress01 = 0f,
                    previews = emptyList(),
                    compareRunId = it.compareRunId + 1,
                )
            }
            EncoderCatalog.ensureDetected(getApplication())
            val wanted = state.preset.engine
            val engine = EncoderCatalog.effective(wanted)
            if (engine != wanted) {
                appendLog(
                    str(
                        R.string.log_engine_missing,
                        wanted.ffmpegVideoCodec,
                        engine.ffmpegVideoCodec,
                    ),
                )
            }
            val seek = ((state.durationSec - PREVIEW_SEC) / 2).coerceAtLeast(0.0).toInt()
            appendLog(
                str(R.string.log_compare_start, seek, str(engine.titleRes)),
            )
            val results = mutableListOf<PreviewResult>()
            CompareLevel.entries.forEachIndexed { idx, level ->
                if (!ui.value.compareRunning) return@forEachIndexed // отменили
                val outFile = File(getApplication<Application>().cacheDir, "preview_${level.name.lowercase()}.mp4")
                val cmd = FfmpegCommands.previewArgs(
                    inputSafUrl = saf,
                    outputPath = outFile.absolutePath,
                    preset = state.preset.copy(engine = engine, compareLevel = level),
                    seekSec = seek,
                    durationSec = PREVIEW_SEC,
                )
                _ui.update {
                    it.copy(
                        compareStatus = "${str(level.titleRes)} (${idx + 1}/3)…",
                    )
                }
                // Команду в лог не пишем: она длинная и перебивает ход событий,
                // а по ошибке ffmpeg всё равно отдаёт свой хвост вывода.
                appendLog(str(R.string.log_compare_level, str(level.titleRes), idx + 1))
                val session = FFmpegKit.execute(cmd)
                val rc = session.getReturnCode()
                if (ReturnCode.isSuccess(rc) && outFile.exists()) {
                    results += PreviewResult(level, outFile.length(), cmd, outFile.absolutePath)
                    appendLog(
                        str(
                            R.string.log_level_done,
                            str(level.titleRes),
                            formatBytes(getApplication<Application>(), outFile.length()),
                        ),
                    )
                } else {
                    appendLog(
                        str(R.string.log_level_failed, str(level.titleRes), rc?.value) +
                            "\n" + lastLogLines(context, session.getOutput(), 8),
                    )
                }
                _ui.update { it.copy(compareProgress01 = (idx + 1) / 3f, previews = results.toList()) }
            }
            val done = ui.value.compareRunning
            _ui.update { it.copy(compareRunning = false, compareStatus = "") }
            appendLog(
                if (done) str(R.string.log_compare_done) else str(R.string.log_compare_cancelled),
            )
        }
    }

    fun cancelCompare() {
        if (!_ui.value.compareRunning) return
        FFmpegKit.cancel()
        _ui.update { it.copy(compareRunning = false, compareStatus = "") }
        appendLog(str(R.string.log_compare_cancel_requested))
    }

    /**
     * В очередь уходят все отмеченные на «Вводе» — без исключений: отдельной
     * кнопки «Импорт» больше нет, отметка снимается сама после успешного
     * кодирования (см. зеркало [TranscodeQueue.done] в `init`).
     */
    fun enqueueChecked() {
        val state = _ui.value
        if (state.selection.isEmpty()) {
            appendLog(str(R.string.log_queue_nothing))
            return
        }
        var added = 0
        state.selection.forEach { picked ->
            if (addJob(picked.uri, picked.name, state.preset)) added++
        }
        appendLog(
            plural(
                R.plurals.log_import_done,
                added,
                added,
                state.selection.size,
            ),
        )
    }

    /**
     * Добавляет задание. false, если такой ролик уже ждёт в очереди: повторное
     * нажатие «В очередь» иначе набивал бы очередь копиями одного и того же файла.
     */
    private fun addJob(uri: Uri, name: String, preset: TranscodePreset): Boolean {
        val pending = TranscodeQueue.queue.value
            .any { it.inputUri == uri && it.status in PENDING_STATUSES }
        if (pending) {
            appendLog(str(R.string.log_already_queued, name))
            return false
        }
        val job = TranscodeJob(
            id = TranscodeQueue.nextId(),
            inputUri = uri,
            inputName = name,
            displayName = name,
            preset = preset,
        )
        TranscodeQueue.add(job)
        appendLog(
            str(
                R.string.log_enqueued,
                job.displayName,
                str(job.preset.engine.shortNameRes),
            ),
        )
        return true
    }

    fun clearDone() {
        TranscodeQueue.clearDone()
        appendLog(str(R.string.log_done_cleared))
    }

    fun clearFinished() {
        if (_ui.value.queueRunning) {
            appendLog(str(R.string.log_clear_while_running))
            return
        }
        TranscodeQueue.clearFinished()
    }

    /**
     * Старт очереди: кодирование уезжает в [TranscodeService], ViewModel только
     * поднимает сервис. Само задание (в SAF-URL, ffmpeg-команда, прогресс) —
     * в сервисе, см. TranscodeService.runOneJob.
     */
    fun startQueue() {
        if (_ui.value.queueRunning) return
        if (!TranscodeQueue.hasQueued()) {
            appendLog(str(R.string.log_start_empty))
            return
        }
        TranscodeService.start(getApplication())
    }

    fun cancelQueue() {
        if (!_ui.value.queueRunning) return
        TranscodeService.cancel(getApplication())
    }

    fun appendLog(line: String) {
        TranscodeQueue.appendLog(line)
    }

    /**
     * Расширенная диагностика: версия, есть ли mediacodec/libx264/libx265,
     * счётчики энкодеров/декодеров, полные -h обоих HW-энкодеров.
     */
    fun runEngineDiagnostics() {
        appendLog(str(R.string.log_diagnostics_start))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val version = FFmpegKit.execute("-version")
                val firstLine = version.getOutput()?.lineSequence()?.firstOrNull() ?: "?"
                appendLog(
                    str(
                        R.string.log_diagnostics_ffmpeg,
                        firstLine,
                        version.getReturnCode()?.value,
                    ),
                )

                val enc = FFmpegKit.execute("-hide_banner -encoders").getOutput().orEmpty()
                val encLines = enc.lineSequence().toList()
                val videoEnc = encLines.filter { it.startsWith(" V") }
                val names = encLines.map { it.trim().split(Regex("\\s+")).getOrNull(1).orEmpty() }
                    .filter { it.isNotEmpty() && it != "----" }.toSet()
                EncoderCatalog.publish(names)
                appendLog(
                    str(
                        R.string.log_diagnostics_encoder_count,
                        encLines.size,
                        videoEnc.size,
                    ),
                )
                appendLog(str(R.string.log_diagnostics_video_encoders) + "\n" + videoEnc.joinToString("\n"))
                listOf("h264_mediacodec", "hevc_mediacodec", "libx264", "libx265", " aac ").forEach { key ->
                    appendLog("has[$key] = ${encLines.any { it.contains(key) }}")
                }

                val dec = FFmpegKit.execute("-hide_banner -decoders").getOutput().orEmpty()
                val decLines = dec.lineSequence().toList()
                appendLog(
                    str(
                        R.string.log_diagnostics_decoder_count,
                        decLines.size,
                        decLines.count { it.contains("mediacodec") },
                    ),
                )

                listOf("h264_mediacodec", "hevc_mediacodec").forEach { name ->
                    val h = FFmpegKit.execute("-hide_banner -h encoder=$name").getOutput().orEmpty()
                    val kept = h.lineSequence()
                        .filter { l ->
                            l.contains("bitrate_mode") || l.contains(" -cq") ||
                                l.contains("qp_") || l.contains("Encoder ") || l.contains("not found")
                        }
                        .take(25)
                        .toList()
                    appendLog(
                        "$name:\n" +
                            kept.joinToString("\n").ifEmpty { str(R.string.log_empty) },
                    )
                }
            } catch (e: Exception) {
                appendLog(str(R.string.log_diagnostics_failed, e.message))
            }
        }
    }

    override fun onCleared() {
        inputSafUrl?.let { runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(it) } }
        super.onCleared()
    }

    fun engineAvailable(engine: TranscodeEngine): Boolean = EncoderCatalog.available(engine)

    /** Форматирует сводку ffprobe по контейнеру, битрейту, длительности и дорожкам. */
    private fun formatMediaSummary(
        info: com.arthenica.ffmpegkit.MediaInformation,
        durationSec: Double,
        ctx: Application,
        locale: java.util.Locale,
    ): String = buildString {
        append(str(R.string.meta_format, info.getFormat(), info.getLongFormat()))
        append("\n")
        append(
            str(
                R.string.meta_duration,
                formatSec(durationSec, locale),
                formatBytes(ctx, info.getSize()?.toLongOrNull() ?: 0L),
            ),
        )
        append("\n")
        append(str(R.string.meta_bitrate, prettyBitrate(info.getBitrate(), locale)))
        append("\n")
        append(prettyStreams(info, locale))
    }

    /** Битрейт в Мбит/с с сотыми; неизвестно — «?», единицу дописывает строка ресурсов. */
    private fun prettyBitrate(raw: String?, locale: java.util.Locale): String =
        mbpsOrNull(raw)?.let { fixed(it, locale) } ?: "?"

    private fun prettyStreams(info: com.arthenica.ffmpegkit.MediaInformation, locale: java.util.Locale): String =
        info.getStreams()?.joinToString("\n") { s ->
            when (s.getType()) {
                "video" -> {
                    // avg_frame_rate приходит дробью «1818000/61007» — человеку
                    // нужно число с сотыми. Не разобрали — показываем как есть.
                    val fps = fpsOrNull(s.getAverageFrameRate())
                        ?.let { fixed(it, locale) }
                        ?: s.getAverageFrameRate()
                    str(
                        R.string.stream_video,
                        s.getCodec(),
                        s.getWidth(),
                        s.getHeight(),
                        fps,
                    )
                }
                // Частота дискретизации у ffmpeg-kit приходит строкой (в отличие
                // от ширины и высоты — это Long), поэтому в строке ресурсов стоит
                // %s, а не %d. Раньше здесь было %d, и любой ролик со звуком
                // падал с IllegalFormatConversionException.
                "audio" -> str(R.string.stream_audio, s.getCodec(), s.getSampleRate() ?: "?")
                else -> str(R.string.stream_other, s.getType(), s.getCodec())
            }
        }.orEmpty()

    private companion object {
        /** Статусы, при которых задание ещё можно увидеть в очереди как ждущее. */
        val PENDING_STATUSES = setOf(JobStatus.QUEUED, JobStatus.RUNNING)
    }
}
