package xyz.imd4n.ebrake.transcode

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import xyz.imd4n.ebrake.R

/**
 * Движок кодирования для одного задания.
 *
 * Подписи — не строки, а id ресурсов: иначе они останутся русскими у
 * англоязычного пользователя. Экраны берут текст через `stringResource`,
 * логи и уведомления — через `getString`.
 *
 * [titleRes] — длинная подпись для логов и уведомлений, [shortNameRes] +
 * [hintRes] — разложение для сетки чипов на «Правке», где ширина ячейки мала.
 */
enum class TranscodeEngine(
    @param:StringRes val titleRes: Int,
    val ffmpegVideoCodec: String,
    @param:StringRes val shortNameRes: Int,
    @param:StringRes val hintRes: Int,
) {
    HW_H264(
        R.string.engine_hw_h264_title, "h264_mediacodec",
        R.string.engine_hw_h264_short, R.string.engine_hw_h264_hint,
    ),
    HW_HEVC(
        R.string.engine_hw_hevc_title, "hevc_mediacodec",
        R.string.engine_hw_hevc_short, R.string.engine_hw_hevc_hint,
    ),
    /**
     * HW AV1 через тот же MediaCodec-враппер. Отдельной сборки не надо: если
     * `av1_mediacodec` есть в `-encoders`, движок оживает сам, иначе висит
     * «нет в сборке» и молча заменяется доступным. SW AV1 (libaom/svt) сюда
     * не добавляем: его нет в AAR, а на телефоне он считал бы минутами.
     */
    HW_AV1(
        R.string.engine_hw_av1_title, "av1_mediacodec",
        R.string.engine_hw_av1_short, R.string.engine_hw_av1_hint,
    ),
    SW_X264(
        R.string.engine_sw_x264_title, "libx264",
        R.string.engine_sw_x264_short, R.string.engine_sw_x264_hint,
    ),
    SW_MPEG4(
        R.string.engine_sw_mpeg4_title, "mpeg4",
        R.string.engine_sw_mpeg4_short, R.string.engine_sw_mpeg4_hint,
    ),
    ;

    /** Короткое имя для логов и файлов: язык тут ни при чём, имя кодера. */
    override fun toString(): String = ffmpegVideoCodec
}

/** Три уровня сжатия для экрана сравнения. */
enum class CompareLevel(@param:StringRes val titleRes: Int) {
    HIGH(R.string.level_high),
    BALANCED(R.string.level_balanced),
    ECO(R.string.level_eco),
}

/** Параметры кодирования, общие для превью и финальной очереди. */
data class TranscodePreset(
    val engine: TranscodeEngine = TranscodeEngine.HW_H264,
    val compareLevel: CompareLevel = CompareLevel.BALANCED,
    /**
     * База качества 0..100, 50 = старая таблица. Крутит её слайдер на «Правке»:
     * Баланс едет за слайдером, Высокое и Эко — фиксированные шаги от базы.
     */
    val quality: Int = 50,
    /**
     * Разброс уровней 1 = как было. Риски Эко/Высокого на слайдере таскаются:
     * одна тянет вторую (общий множитель), 0.2 = почти рядом, 2.5 = широко.
     */
    val spread: Float = 1f,
)

/** Одно задание в очереди вывода. */
data class TranscodeJob(
    val id: Long,
    val inputUri: Uri,
    val displayName: String,
    val preset: TranscodePreset,
    val status: JobStatus = JobStatus.QUEUED,
    val progress01: Float = 0f,
    val outputPath: String = "",
    val outputSizeBytes: Long = 0L,
    /** Размер исходника: из него считается экономия в выполненной очереди. */
    val inputSizeBytes: Long = 0L,
    /** Имя файла без суффикса уровня — для списка выполненных. */
    val inputName: String = "",
)

/**
 * Ролик, отмеченный на «Вводе».
 *
 * Отметка — это просто «взять в обработку», без первого/второго: ролик для
 * сравнения выбирается отдельно, на «Правке». Порядок в списке сохраняется
 * только затем, чтобы карусель в карточке шла в том порядке, в каком отмечали.
 */
data class SelectedInput(
    val uri: Uri,
    val name: String,
    /** Размер исходника в байтах; 0, если провайдер размер не отдал. */
    val sizeBytes: Long = 0L,
)

/**
 * Запись выполненной очереди: что перекодировали и сколько места сэкономили
 * относительно исходника. Живёт отдельно от [TranscodeJob], чтобы «очередь»
 * можно было очистить, а историю — нет.
 */
data class DoneRecord(
    val id: Long,
    val name: String,
    val inputUri: Uri,
    val inputBytes: Long,
    val outputBytes: Long,
    /**
     * Движок и уровень хранятся перечислениями, а не строками: запись живёт
     * дольше, чем может смениться язык телефона, и сохранённая подпись осталась
     * бы на старом языке. Экран берёт текст из ресурсов сам.
     */
    val engine: TranscodeEngine,
    val level: CompareLevel,
    val outputPath: String,
) {
    val savedBytes: Long get() = Savings.savedBytes(inputBytes, outputBytes)

    val savedPercent: Int get() = Savings.savedPercent(inputBytes, outputBytes)
}

/**
 * Арифметика экономии, вынесенная отдельно от [DoneRecord]: чистые функции
 * без Uri и Android-типов, поэтому покрываются обычными юнит-тестами
 * (`./gradlew :app:testDebugUnitTest`) без Robolectric.
 */
object Savings {
    /** Сэкономлено на диске; отрицательная дельта (файл вырос) — ноль. */
    fun savedBytes(inputBytes: Long, outputBytes: Long): Long =
        (inputBytes - outputBytes).coerceAtLeast(0L)

    /**
     * Доля экономии в процентах. Нулевой размер исходника означает «размер
     * неизвестен» (stat не дал) — делить на ноль нельзя, показываем 0, а не
     * правдоподобное отрицательное число.
     */
    fun savedPercent(inputBytes: Long, outputBytes: Long): Int {
        if (inputBytes <= 0L) return 0
        return (savedBytes(inputBytes, outputBytes) * 100 / inputBytes).toInt()
    }
}

/** Результат превью-сравнения (10-секундный отрезок, реальный файл в cacheDir). */
data class PreviewResult(
    val level: CompareLevel,
    val fileSizeBytes: Long,
    val note: String,
    val outputPath: String = "",
)

enum class JobStatus(@param:StringRes val titleRes: Int) {
    QUEUED(R.string.status_queued),
    RUNNING(R.string.status_running),
    DONE(R.string.status_done),
    FAILED(R.string.status_failed),
}

/**
 * Построение ffmpeg-команды для заданного движка и уровня.
 *
 * HW идёт через constant-quality / VBR (без фиксированного CBR):
 *  - bitrate_mode cq/vbr + ограничения qp/qmin/qmax.
 *    Опции существуют, только если в сборке есть mediacodec-обёртка!
 * SW x264 — классический CRF (нужен --enable-gpl при сборке).
 * SW mpeg4 — -q:v, фиксированный квантователь = constant quality,
 *    есть в любой сборке ffmpeg. Шкала 1 (лучше) — 31.
 *
 * Возвращает команду одной строкой для FFmpegKit.executeAsync().
 */
object FfmpegCommands {
    fun previewArgs(
        inputSafUrl: String,
        outputPath: String,
        preset: TranscodePreset,
        seekSec: Int = 30,
        durationSec: Int = 10,
    ): String {
        val video = videoArgs(preset, ultrafast = true)
        return "-ss $seekSec -t $durationSec -i $inputSafUrl $video " +
            "-c:a aac -b:a 128k -y $outputPath"
    }

    fun fullArgs(
        inputSafUrl: String,
        outputSafUrl: String,
        preset: TranscodePreset,
    ): String {
        val video = videoArgs(preset, ultrafast = false)
        return "-i $inputSafUrl $video -c:a aac -b:a 128k -y $outputSafUrl"
    }

    /** Видео-аргументы под движок: HW идут одной веткой, разница лишь в имени кодека. */
    fun videoArgs(preset: TranscodePreset, ultrafast: Boolean = false): String =
        when (preset.engine) {
        TranscodeEngine.HW_H264, TranscodeEngine.HW_HEVC, TranscodeEngine.HW_AV1 ->
            "-c:v ${preset.engine.ffmpegVideoCodec} ${hwArgs(hwQuality(preset.compareLevel, preset.quality, preset.spread))}"
        TranscodeEngine.SW_X264 ->
            "-c:v libx264 -crf ${swCrf(preset.compareLevel, preset.quality, preset.spread)} " +
                "-preset ${if (ultrafast) "ultrafast" else "veryfast"}"
        TranscodeEngine.SW_MPEG4 ->
            "-c:v mpeg4 -q:v ${mpeg4q(preset.compareLevel, preset.quality, preset.spread)}"
    }

    /**
     * Короткая техподпись уровня для слайдера («8M», «CRF 26», «q4»).
     * Чистая, без Context: единицы кодера не переводятся.
     */
    fun qualityLabel(engine: TranscodeEngine, level: CompareLevel, quality: Int, spread: Float = 1f): String =
        when (engine) {
            TranscodeEngine.HW_H264, TranscodeEngine.HW_HEVC, TranscodeEngine.HW_AV1 ->
                hwQuality(level, quality, spread).bitrate
            TranscodeEngine.SW_X264 ->
                "CRF ${swCrf(level, quality, spread)}"
            TranscodeEngine.SW_MPEG4 ->
                "q${mpeg4q(level, quality, spread)}"
        }

    /**
     * Сдвиг уровня от Баланса в пунктах слайдера: плюс — Высокое, минус — Эко.
     * Обратная сторона лестницы выше: точка, где Баланс дал бы тот же битрейт.
     * Нужно рискам на слайдере, чтобы было видно расстояние от базы.
     */
    fun levelOffset(engine: TranscodeEngine, level: CompareLevel, spread: Float = 1f): Int {
        val s = spread.coerceIn(0.2f, 2.5f)
        val base = when (engine) {
            TranscodeEngine.HW_H264, TranscodeEngine.HW_HEVC, TranscodeEngine.HW_AV1 -> when (level) {
                CompareLevel.HIGH -> 35 // 8/3 ≈ 2^1.4 → 1.4 × 25
                CompareLevel.BALANCED -> 0
                CompareLevel.ECO -> -33 // 0.4 ≈ 2^-1.3 → −1.3 × 25
            }
            TranscodeEngine.SW_X264 -> when (level) {
                CompareLevel.HIGH -> 38 // 6 CRF / 0.16 на пункт
                CompareLevel.BALANCED -> 0
                CompareLevel.ECO -> -38
            }
            TranscodeEngine.SW_MPEG4 -> when (level) {
                CompareLevel.HIGH -> 25 // 2 q / 0.08 на пункт
                CompareLevel.BALANCED -> 0
                CompareLevel.ECO -> -50 // 4 q / 0.08 на пункт
            }
        }
        return (base * s).roundToInt()
    }

    /**
     * Базу нельзя увести за риски: иначе «Баланс» выйдет хуже Эко или лучше
     * Высокого и лестница потеряет смысл. Диапазон сам сжимается вместе с рисками.
     */
    fun baseRange(engine: TranscodeEngine, spread: Float): IntRange {
        val low = levelOffset(engine, CompareLevel.ECO, spread)
        val high = levelOffset(engine, CompareLevel.HIGH, spread)
        return (50 + low).coerceAtLeast(0)..(50 + high).coerceAtMost(100)
    }

    /**
     * Новый разброс после перетаскивания риски на deltaPt пунктов слайдера.
     * Плюс — от базы, минус — к базе. Разброс общий: Эко тянут к базе —
     * Высокое едет к базе тем же множителем, и наоборот.
     */
    fun dragSpread(engine: TranscodeEngine, level: CompareLevel, spread: Float, deltaPt: Float): Float {
        val def = when (level) {
            CompareLevel.HIGH -> levelOffset(engine, CompareLevel.HIGH).toFloat()
            CompareLevel.ECO -> -levelOffset(engine, CompareLevel.ECO).toFloat()
            CompareLevel.BALANCED -> return spread
        }
        if (def <= 0f) return spread
        val dist = def * spread.coerceIn(0.2f, 2.5f) + if (level == CompareLevel.HIGH) deltaPt else -deltaPt
        return (dist / def).coerceIn(0.2f, 2.5f)
    }

    /**
     * Уровень качества HW.
     *
     * Уроки с устройства (28.09.2026, диагностика):
     * - `bitrate_mode` — int-опция; значение `cq` чип отвергает
     *   («Could not open encoder before EOF», -22). Везде используем `vbr`.
     * - Одни qp-границы без целевого битрейта чип игнорирует: Баланс (q26)
     *   и Эко (q32) дали одинаковый размер. Поэтому лестница задаётся через
     *   VBR-таргет -b:v/-maxrate (это НЕ фиксированный CBR — битрейт плавает
     *   по содержимому), а qp-границы остаются страховкой от развалов качества.
     *
     * База слайдера удваивается каждые 25 пунктов: 50 = старая таблица
     * (8/3/1.2M), 75 = вдвое больше, 25 = вдвое меньше.
     */
    private data class HwQuality(val bitrate: String, val maxrate: String, val qMin: Int, val qMax: Int)

    private fun hwQuality(level: CompareLevel, quality: Int, spread: Float): HwQuality {
        val q = quality.coerceIn(0, 100)
        val s = spread.coerceIn(0.2f, 2.5f)
        val baseM = 3.0 * 2.0.pow((q - 50) / 25.0)
        // Отношение уровней в степени разброса: 1 = старая таблица (8/3/1.2M).
        val ratio = when (level) {
            CompareLevel.HIGH -> 8.0 / 3.0
            CompareLevel.BALANCED -> 1.0
            CompareLevel.ECO -> 1.2 / 3.0
        }.pow(s.toDouble())
        val m = (baseM * ratio).coerceIn(0.3, 24.0)
        val maxM = (m * 1.5).coerceIn(0.3, 32.0)
        val (qMin, qMax) = when (level) {
            CompareLevel.HIGH -> 18 to 25
            CompareLevel.BALANCED -> 22 to 30
            CompareLevel.ECO -> 26 to 34
        }
        return HwQuality(fmtM(m), fmtM(maxM), qMin, qMax)
    }

    /** «8M», а не «8.0M»: дробь только ниже 10M. */
    private fun fmtM(m: Double): String {
        val r = (m * 10).roundToInt() / 10.0
        return if (r >= 10) "${r.toInt()}M" else "${r}M".replace(".0M", "M")
    }

    private fun hwArgs(hw: HwQuality) =
        "-bitrate_mode vbr -b:v ${hw.bitrate} -maxrate ${hw.maxrate} " +
            "-bufsize ${hw.maxrate} -qmin ${hw.qMin} -qmax ${hw.qMax} " +
            "-qp_i_max ${hw.qMax} -qp_p_max ${hw.qMax}"

    /** CRF x264: 50 = старая таблица (20/26/32), шаг ±6 между уровнями. */
    private fun swCrf(level: CompareLevel, quality: Int, spread: Float): Int {
        val base = (26 - (quality.coerceIn(0, 100) - 50) * 0.16).roundToInt().coerceIn(16, 36)
        val step = (6 * spread.coerceIn(0.2f, 2.5f)).roundToInt()
        return when (level) {
            CompareLevel.HIGH -> base - step
            CompareLevel.BALANCED -> base
            CompareLevel.ECO -> base + step
        }.coerceIn(10, 40)
    }

    /** mpeg4 -q:v: меньше = лучше, шкала 1–31. 50 = старая таблица (2/4/8). */
    private fun mpeg4q(level: CompareLevel, quality: Int, spread: Float): Int {
        val base = (4 + (50 - quality.coerceIn(0, 100)) * 0.08).roundToInt().coerceIn(2, 10)
        val s = spread.coerceIn(0.2f, 2.5f)
        return when (level) {
            CompareLevel.HIGH -> base - (2 * s).roundToInt()
            CompareLevel.BALANCED -> base
            CompareLevel.ECO -> base + (4 * s).roundToInt()
        }.coerceIn(1, 16)
    }
}

/**
 * Форматирование размеров/времени/логов. Лежит рядом с моделями, а не во
 * ViewModel, потому что пользуются и UI, и фоновый сервис (уведомление).
 *
 * Локаль передаётся явно, а не берётся из `Locale.getDefault()`. Иначе
 * получается ровно то, что уже случалось: число печатается разделителем
 * системы («31.3»), а единица — из ресурсов («МБ»). Дальше это расходится по
 * логам, уведомлениям и сохранённой истории, где язык один, а локаль — нет.
 *
 * Само форматирование чисел остаётся чистым: ресурсы нужны только за единицами
 * измерения, поэтому их подставляет вызывающий через [formatBytes] в UI или
 * через `getString` в сервисе. Тесты бьют по чистой части — см. [sizeParts].
 */
object TranscodeFormat {
    /** Длина превью-отрезка для сравнения качества, сек. */
    const val PREVIEW_SEC = 10

    /** Единица измерения размера. */
    enum class SizeUnit(@param:StringRes val labelRes: Int) {
        BYTES(R.string.unit_b),
        KILOBYTES(R.string.unit_kb),
        MEGABYTES(R.string.unit_mb),
        GIGABYTES(R.string.unit_gb),
    }

    /**
     * Разбор размера на число и единицу — без строк и ресурсов, чтобы
     * покрывалось обычным юнит-тестом.
     */
    fun sizeParts(bytes: Long): Pair<Double, SizeUnit> = when {
        bytes >= 1_000_000_000 -> bytes / 1_000_000_000.0 to SizeUnit.GIGABYTES
        bytes >= 1_000_000 -> bytes / 1_000_000.0 to SizeUnit.MEGABYTES
        bytes >= 1_000 -> bytes / 1_000.0 to SizeUnit.KILOBYTES
        else -> bytes.toDouble() to SizeUnit.BYTES
    }

    /** Число в локали пользователя; цифры и разделитель — как у него в системе. */
    fun number(value: Double, locale: Locale, decimals: Int = 1): String =
        if (decimals == 0) {
            String.format(locale, "%.0f", value)
        } else {
            String.format(locale, "%.1f", value)
        }

    /**
     * Число с фиксированными знаками после запятой, разделитель — по локали.
     * Для статистики ffprobe: битрейт в Мбит/с и fps всегда с сотыми.
     */
    fun fixed(value: Double, locale: Locale, decimals: Int = 2): String =
        String.format(locale, "%.${decimals}f", value)

    /**
     * Битрейт ffprobe в Мбит/с: строка приходит в bps. null = ffprobe не отдал
     * число (пусто или «N/A»), тогда вызывающий показывает «?».
     */
    fun mbpsOrNull(raw: String?): Double? = raw?.toDoubleOrNull()?.div(1_000_000.0)

    /**
     * fps из avg_frame_rate вида «1818000/61007». Голое «30» тоже ест
     * (знаменатель 1), нулевой знаменатель считает единицей. null = мусор,
     * тогда вызывающий показывает строку как есть.
     */
    fun fpsOrNull(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("/")
        val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val den = if (parts.size > 1) {
            val d = parts[1].toDoubleOrNull() ?: return null
            if (d == 0.0) 1.0 else d
        } else {
            1.0
        }
        return num / den
    }

    /** Размер с единицей: [context] — источник перевода «МБ»/«MB». */
    fun formatBytes(context: Context, bytes: Long, locale: Locale = currentLocale(context)): String {
        val (value, unit) = sizeParts(bytes)
        val decimals = if (unit == SizeUnit.BYTES || unit == SizeUnit.KILOBYTES) 0 else 1
        return context.getString(unit.labelRes, number(value, locale, decimals))
    }

    /** Локаль приложения: с учётом выбора языка в настройках (13+). */
    fun currentLocale(context: Context): Locale =
        context.resources.configuration.locales[0]

    fun formatSec(d: Double, locale: Locale): String {
        val s = d.toInt()
        return String.format(locale, "%d:%02d", s / 60, s % 60)
    }

    /** Дельта размера со знаком: «−132,0 МБ» / «+4,1 МБ». */
    fun formatDelta(context: Context, bytes: Long, locale: Locale = currentLocale(context)): String {
        // Минус — U+2212, а не дефис: в строке «−132,0 МБ» он длиннее
        // и не слипается с цифрой. Сам знак не переводится.
        val sign = if (bytes < 0) "−" else "+"
        return context.getString(
            R.string.delta_signed,
            sign,
            formatBytes(context, kotlin.math.abs(bytes), locale),
        )
    }

    fun lastLogLines(context: Context, output: String?, n: Int): String =
        output?.lineSequence()?.toList()?.takeLast(n)?.joinToString("\n")
            ?: context.getString(R.string.log_no_output)
}
