package xyz.imd4n.ebrake.transcode

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.imd4n.ebrake.R

/** Что удалось вытащить из `-encoders`. Пустое detected = детект ещё не проходил. */
data class EncoderState(
    val names: Set<String> = emptySet(),
    val detected: Boolean = false,
)

/**
 * Каталог энкодеров сборки: нужен и сравнению (ViewModel), и очереди (сервис).
 * Раньше обе стороны держали своё состояние в [TranscodeUiState] и могли
 * разойтись по мнению о доступности чипов.
 */
object EncoderCatalog {
    private val _state = MutableStateFlow(EncoderState())
    val state: StateFlow<EncoderState> = _state.asStateFlow()

    /**
     * Чем реально открылся энкодер. null = ещё не проверяли.
     *
     * Заявки двух источников врут: `-encoders` показывает скомпилированное, а
     * MediaCodecList — то, что чип отдаёт. На телефоне без аппаратного HEVC он всё
     * равно перечисляет `video/hevc`, и `hevc_mediacodec` падает уже при open
     * («Could not open encoder before EOF», -22). Единственная честная проверка —
     * один кадр через сам энкодер.
     */
    private val _verified = MutableStateFlow<Set<TranscodeEngine>?>(null)
    val verified: StateFlow<Set<TranscodeEngine>?> = _verified.asStateFlow()

    /**
     * Однократный разбор `-encoders` плюс проверка HW-кодеков пробой. Вызывать из
     * IO-потока: `execute()` блокирует. synchronized — сравнение и очередь могут
     * дёрнуть одновременно, а ffmpeg-kit не любит параллельные сессии.
     */
    @Synchronized
    fun ensureDetected(context: Context) {
        if (_state.value.detected) return
        val names = runCatching {
            FFmpegKit.execute("-hide_banner -encoders").getOutput().orEmpty()
        }.getOrDefault("")
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")).getOrNull(1).orEmpty() }
            .filter { it.isNotEmpty() && it != "----" }
            .toSet()
        _state.value = EncoderState(names, true)
        val ok = verifyHardware(names)
        TranscodeQueue.appendLog(
            context.resources.getQuantityString(
                R.plurals.log_encoder_detected,
                names.size,
                names.size,
            ),
        )
        TranscodeQueue.appendLog(
            if (ok.isEmpty()) {
                context.getString(R.string.log_engine_hw_none)
            } else {
                ok.joinToString(" · ") { context.getString(it.shortNameRes) }
            },
        )
    }

    /**
     * По одному кадру на каждый HW-кодек, который прошёл список прошивки: с
     * синтетическим входом (testsrc), в никуда. Кодек, который не открылся,
     * отмечается недоступным и в меню, и в fallback.
     */
    private fun verifyHardware(names: Set<String>): List<TranscodeEngine> {
        val ok = TranscodeEngine.entries.filter { engine ->
            deviceMime(engine) != null &&
                engine.ffmpegVideoCodec in names &&
                (deviceEncoders.isEmpty() || deviceMime(engine) in deviceEncoders) &&
                opens(engine)
        }
        _verified.value = ok.toSet()
        return ok
    }

    /**
     * Реально ли открывается кодек: один кадр через сам энкодер.
     * Аргументы минимальные (-b:v без qp-лимитов): проба проверяет сам факт
     * открытия, а не поддержку опций качества. Полная лестница (vbr +
     * qp-лимиты) и кадр 64x64 давали ложные отказы на рабочих чипах: часть
     * HEVC отвергает мелкие кадры и часть qp-опций, хотя реальный файл
     * тем же энкодером кодирует. Отмена (пользователь жал «Стоп» в этот
     * момент) — не приговор кодеку.
     */
    private fun opens(engine: TranscodeEngine): Boolean = runCatching {
        val session = FFmpegKit.execute(
            "-f lavfi -i testsrc=size=320x240:rate=1:duration=0.2 " +
                "-c:v ${engine.ffmpegVideoCodec} -b:v 1M -f null -",
        )
        val rc = session.getReturnCode()
        ReturnCode.isSuccess(rc) || ReturnCode.isCancel(rc)
    }.getOrDefault(false)

    /** Для ручного детекта из диагностики — результат не логируем повторно. */
    fun publish(names: Set<String>) {
        _state.value = EncoderState(names, true)
    }

    fun available(engine: TranscodeEngine): Boolean {
        val s = _state.value
        if (!s.detected) return true
        // Проба важнее списков: именно она ловит «заявлен, но не открывается».
        _verified.value?.let { verifiedHw ->
            val mime = deviceMime(engine)
            return if (mime != null) {
                engine in verifiedHw
            } else {
                engine.ffmpegVideoCodec in s.names
            }
        }
        if (engine.ffmpegVideoCodec !in s.names) return false
        // Список пустой = прочитать не удалось (старый API, обрезанные права).
        // Тогда доверяем сборке: иначе посыпались бы все HW разом.
        val mime = deviceMime(engine) ?: return true
        if (deviceEncoders.isEmpty()) return true
        return mime in deviceEncoders
    }

    /** Цепочка fallback: HW H.264 → HW HEVC → HW AV1 → x264 → MPEG-4 (есть всегда). */
    fun effective(wanted: TranscodeEngine): TranscodeEngine {
        val have = _state.value.names
        if (have.isEmpty() || available(wanted)) return wanted
        return FALLBACK_CHAIN.firstOrNull { available(it) } ?: TranscodeEngine.SW_MPEG4
    }

    private val FALLBACK_CHAIN = listOf(
        TranscodeEngine.HW_H264,
        TranscodeEngine.HW_HEVC,
        TranscodeEngine.HW_AV1,
        TranscodeEngine.SW_X264,
        TranscodeEngine.SW_MPEG4,
    )

    private fun deviceMime(engine: TranscodeEngine): String? = when (engine) {
        TranscodeEngine.HW_H264 -> "video/avc"
        TranscodeEngine.HW_HEVC -> "video/hevc"
        TranscodeEngine.HW_AV1 -> "video/av01"
        else -> null
    }

    /**
     * MIME всех кодировщиков железа; читается один раз, Context не нужен.
     *
     * Сначала REGULAR_CODECS, потом ALL_CODECS (29+): у AV1 и HEVC-10 битовые
     * профили часто лежат только во втором списке. Если оба пусты — значит
     * список недоступен, и тогда мы доверяем сборке (см. [available]).
     */
    private val deviceEncoders: Set<String> by lazy {
        val lists = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MediaCodecList.REGULAR_CODECS, MediaCodecList.ALL_CODECS)
        } else {
            listOf(MediaCodecList.REGULAR_CODECS)
        }
        buildSet {
            lists.forEach { kind ->
                runCatching {
                    MediaCodecList(kind).codecInfos
                        .filter { it.isEncoder }
                        .forEach { info -> addAll(info.supportedTypes.map { t -> t.lowercase() }) }
                }
            }
        }
    }
}
