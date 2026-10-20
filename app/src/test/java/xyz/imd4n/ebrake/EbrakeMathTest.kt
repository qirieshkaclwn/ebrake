package xyz.imd4n.ebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.imd4n.ebrake.transcode.CompareLevel
import xyz.imd4n.ebrake.transcode.FfmpegCommands
import xyz.imd4n.ebrake.transcode.Savings
import xyz.imd4n.ebrake.transcode.TranscodeEngine
import xyz.imd4n.ebrake.transcode.TranscodeFormat
import xyz.imd4n.ebrake.ui.gallery.formatDuration
import java.util.Locale

/**
 * Тесты чистой логики: расчёт экономии в выполненной очереди и разбор размера
 * на число с единицей. Оба показываются пользователю, поэтому ошибка видна
 * сразу («сэкономлено 0 МБ» вместо «−132,0 МБ»).
 *
 * Тестируется [Savings] и [TranscodeFormat.sizeParts], а не [DoneRecord] и не
 * `formatBytes`: у записи есть android.net.Uri (Uri в юнит-тестах без
 * Robolectric кидает исключение на статическом инициализаторе), а
 * `formatBytes` нужен Context за единицей измерения.
 *
 * Формат чисел проверяется явно по локали — именно на этом уже ломался CI:
 * `"%.1f".format(...)` без локали печатал «31.3» на английской машине и
 * «31,3» на русской, и падал ровно один из двух прогонов.
 */
class EbrakeMathTest {

    private val ru = Locale.forLanguageTag("ru")
    private val en = Locale.US

    @Test
    fun `экономия это разница размеров`() {
        // 320 МБ → 188 МБ из реального прогона 28.09.2026.
        assertEquals(132_000_000L, Savings.savedBytes(320_000_000L, 188_000_000L))
        assertEquals(41, Savings.savedPercent(320_000_000L, 188_000_000L))
    }

    @Test
    fun `файл вырос — экономия ноль а не минус`() {
        // Реальный случай: на логах/битрейте потрачено больше, чем сэкономлено.
        assertEquals(0L, Savings.savedBytes(100_000_000L, 104_000_000L))
        assertEquals(0, Savings.savedPercent(100_000_000L, 104_000_000L))
    }

    @Test
    fun `неизвестный размер исходника не даёт ложной экономии`() {
        // content:// без stat: делить на ноль нельзя — 0, а не -1%.
        assertEquals(0, Savings.savedPercent(0L, 50_000_000L))
        assertEquals(0L, Savings.savedBytes(0L, 50_000_000L))
    }

    @Test
    fun `суммарная экономия по нескольким роликам не отрицательная`() {
        val pairs = listOf(320_000_000L to 188_000_000L, 126_000_000L to 126_000_000L)
        val saved = pairs.sumOf { (input, output) -> Savings.savedBytes(input, output) }
        assertEquals(132_000_000L, saved)
        assertTrue(pairs.all { (input, output) -> Savings.savedBytes(input, output) >= 0L })
    }

    @Test
    fun `размер делится на ту единицу, которая читается`() {
        assertEquals(
            TranscodeFormat.SizeUnit.BYTES,
            TranscodeFormat.sizeParts(500L).second,
        )
        assertEquals(
            TranscodeFormat.SizeUnit.KILOBYTES,
            TranscodeFormat.sizeParts(1_500L).second,
        )
        assertEquals(
            TranscodeFormat.SizeUnit.MEGABYTES,
            TranscodeFormat.sizeParts(31_300_000L).second,
        )
        assertEquals(
            TranscodeFormat.SizeUnit.GIGABYTES,
            TranscodeFormat.sizeParts(1_000_000_000L).second,
        )
    }

    @Test
    fun `число печатается разделителем своей локали`() {
        // Ровно тот случай, что ломал CI: одна строка, два ответа.
        assertEquals("31,3", TranscodeFormat.number(31.3, ru))
        assertEquals("31.3", TranscodeFormat.number(31.3, en))
        // КБ округляются до целого — одна десятая там бессмысленна.
        assertEquals("2", TranscodeFormat.number(1.5, ru, decimals = 0))
        assertEquals("2", TranscodeFormat.number(1.5, en, decimals = 0))
    }

    @Test
    fun `размер в байтах печатается целиком`() {
        assertEquals("500", TranscodeFormat.number(500.0, ru, decimals = 0))
        assertEquals("0", TranscodeFormat.number(0.0, en, decimals = 0))
    }

    @Test
    fun `таймкод одинаков в обеих локалях`() {
        assertEquals("2:05", TranscodeFormat.formatSec(125.0, ru))
        assertEquals("2:05", TranscodeFormat.formatSec(125.0, en))
    }

    @Test
    fun `битрейт всегда в мегабитах с сотыми`() {
        // Тот самый ролик со скрина: 19976343 bps → 19,98 Мбит/с.
        assertEquals(19.976343, TranscodeFormat.mbpsOrNull("19976343")!!, 1e-9)
        assertEquals("19,98", TranscodeFormat.fixed(19.976343, ru))
        assertEquals("19.98", TranscodeFormat.fixed(19.976343, en))
        // Пусто и мусор — null, вызывающий покажет «?».
        assertEquals(null, TranscodeFormat.mbpsOrNull(null))
        assertEquals(null, TranscodeFormat.mbpsOrNull("N/A"))
    }

    @Test
    fun `fps дробью превращается в число с сотыми`() {
        // avg_frame_rate со скрина: 1818000/61007 ≈ 29,80.
        assertEquals(29.8, TranscodeFormat.fpsOrNull("1818000/61007")!!, 0.005)
        assertEquals("29,80", TranscodeFormat.fixed(29.79986, ru))
        assertEquals("29.80", TranscodeFormat.fixed(29.79986, en))
        // Голое число — знаменатель 1, нулевой знаменатель — тоже 1.
        assertEquals(30.0, TranscodeFormat.fpsOrNull("30")!!, 1e-9)
        assertEquals(0.0, TranscodeFormat.fpsOrNull("0/0")!!, 1e-9)
        // Мусор и пусто — null, вызывающий покажет строку как есть.
        assertEquals(null, TranscodeFormat.fpsOrNull(null))
        assertEquals(null, TranscodeFormat.fpsOrNull("N/A"))
    }

    @Test
    fun `база 50 это старая таблица уровней`() {
        // Лестница 28.09.2026: H.264 = 8/3/1.2М, x264 CRF 20/26/32, mpeg4 q 2/4/8.
        assertEquals("8M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.HIGH, 50))
        assertEquals("3M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.BALANCED, 50))
        assertEquals("1.2M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_HEVC, CompareLevel.ECO, 50))
        assertEquals("8M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_AV1, CompareLevel.HIGH, 50))
        assertEquals("CRF 20", FfmpegCommands.qualityLabel(TranscodeEngine.SW_X264, CompareLevel.HIGH, 50))
        assertEquals("CRF 26", FfmpegCommands.qualityLabel(TranscodeEngine.SW_X264, CompareLevel.BALANCED, 50))
        assertEquals("CRF 32", FfmpegCommands.qualityLabel(TranscodeEngine.SW_X264, CompareLevel.ECO, 50))
        assertEquals("q2", FfmpegCommands.qualityLabel(TranscodeEngine.SW_MPEG4, CompareLevel.HIGH, 50))
        assertEquals("q4", FfmpegCommands.qualityLabel(TranscodeEngine.SW_MPEG4, CompareLevel.BALANCED, 50))
        assertEquals("q8", FfmpegCommands.qualityLabel(TranscodeEngine.SW_MPEG4, CompareLevel.ECO, 50))
    }

    @Test
    fun `слайдер двигает все три уровня`() {
        // База 100: всё вдвое-вчетверо выше, порядок лестницы держится.
        assertEquals("12M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.BALANCED, 100))
        assertEquals("24M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.HIGH, 100))
        assertEquals("4.8M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.ECO, 100))
        assertEquals("CRF 18", FfmpegCommands.qualityLabel(TranscodeEngine.SW_X264, CompareLevel.BALANCED, 100))
        // База 0: эко упирается в низ битрейта, но не в ноль.
        assertEquals("0.3M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.ECO, 0))
    }

    @Test
    fun `риски стоят по разные стороны от баланса`() {
        // Высокое — плюс, Эко — минус, у каждого движка своё расстояние.
        TranscodeEngine.entries.forEach { engine ->
            assertTrue(FfmpegCommands.levelOffset(engine, CompareLevel.HIGH) > 0)
            assertEquals(0, FfmpegCommands.levelOffset(engine, CompareLevel.BALANCED))
            assertTrue(FfmpegCommands.levelOffset(engine, CompareLevel.ECO) < 0)
        }
        assertEquals(35, FfmpegCommands.levelOffset(TranscodeEngine.HW_H264, CompareLevel.HIGH))
        assertEquals(-33, FfmpegCommands.levelOffset(TranscodeEngine.HW_H264, CompareLevel.ECO))
    }

    @Test
    fun `база не выходит за риски`() {
        // База зажата между Эко и Высоким: сжали разброс — сжался и диапазон.
        val r1 = FfmpegCommands.baseRange(TranscodeEngine.HW_H264, 1f)
        assertEquals(17, r1.first)
        assertEquals(85, r1.last)
        val r2 = FfmpegCommands.baseRange(TranscodeEngine.HW_H264, 0.2f)
        assertEquals(43, r2.first)
        assertEquals(57, r2.last)
        // Развёрнутый разброс на 0..100 телефоне тоже упирается в края.
        val r3 = FfmpegCommands.baseRange(TranscodeEngine.HW_H264, 2.5f)
        assertEquals(0, r3.first)
        assertEquals(100, r3.last)
    }

    @Test
    fun `риски тянутся парой через разброс`() {
        // Эко к базе на 10 пунктов — разброс 23/33, Высокое едет тем же множителем.
        val s = FfmpegCommands.dragSpread(TranscodeEngine.HW_H264, CompareLevel.ECO, 1f, 10f)
        assertEquals(23f / 33f, s, 0.01f)
        assertEquals(24, FfmpegCommands.levelOffset(TranscodeEngine.HW_H264, CompareLevel.HIGH, s))
        // Разброс не уходит в ноль и не улетает: три одинаковых кодирования бессмысленны.
        assertEquals(0.2f, FfmpegCommands.dragSpread(TranscodeEngine.HW_H264, CompareLevel.ECO, 1f, 100f), 0.001f)
        assertEquals(2.5f, FfmpegCommands.dragSpread(TranscodeEngine.HW_H264, CompareLevel.HIGH, 1f, 100f), 0.001f)
    }

    @Test
    fun `таймкод форматируется правильно для коротких и длинных роликов`() {
        assertEquals("0:45", formatDuration(45_000L, en))
        assertEquals("3:25", formatDuration(205_000L, en))
        assertEquals("1:05:02", formatDuration(3902_000L, en))
    }

    @Test
    fun `formatSec работает корректно`() {
        assertEquals("0:00", TranscodeFormat.formatSec(0.0, en))
        assertEquals("1:40", TranscodeFormat.formatSec(100.0, en))
    }

    @Test
    fun `fixed форматирует с нужным числом знаков`() {
        assertEquals("12,345", TranscodeFormat.fixed(12.3454, ru, 3))
        assertEquals("12.345", TranscodeFormat.fixed(12.3454, en, 3))
    }

    @Test
    fun `fpsOrNull парсит строковые значения fps ffprobe`() {
        assertEquals(30.0, TranscodeFormat.fpsOrNull("30") ?: 0.0, 0.001)
        assertEquals(24.0, TranscodeFormat.fpsOrNull("24/1") ?: 0.0, 0.001)
        assertEquals(29.97, TranscodeFormat.fpsOrNull("30000/1001") ?: 0.0, 0.01)
        assertEquals(59.94, TranscodeFormat.fpsOrNull("60000/1001") ?: 0.0, 0.01)
        // Деление на ноль защищено
        assertEquals(30.0, TranscodeFormat.fpsOrNull("30/0") ?: 0.0, 0.001)

        // Невалидный ввод
        org.junit.Assert.assertNull(TranscodeFormat.fpsOrNull(null))
        org.junit.Assert.assertNull(TranscodeFormat.fpsOrNull(""))
        org.junit.Assert.assertNull(TranscodeFormat.fpsOrNull("   "))
        org.junit.Assert.assertNull(TranscodeFormat.fpsOrNull("abc"))
        org.junit.Assert.assertNull(TranscodeFormat.fpsOrNull("30/xyz"))
    }

    @Test
    fun `mbpsOrNull переводит bps в Мбит в секунду`() {
        assertEquals(5.0, TranscodeFormat.mbpsOrNull("5000000") ?: 0.0, 0.0001)
        assertEquals(0.128, TranscodeFormat.mbpsOrNull("128000") ?: 0.0, 0.0001)
        assertEquals(0.0, TranscodeFormat.mbpsOrNull("0") ?: -1.0, 0.0001)

        org.junit.Assert.assertNull(TranscodeFormat.mbpsOrNull(null))
        org.junit.Assert.assertNull(TranscodeFormat.mbpsOrNull(""))
        org.junit.Assert.assertNull(TranscodeFormat.mbpsOrNull("invalid"))
    }
}