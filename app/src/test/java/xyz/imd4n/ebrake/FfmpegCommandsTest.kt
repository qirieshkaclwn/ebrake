package xyz.imd4n.ebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.imd4n.ebrake.transcode.CompareLevel
import xyz.imd4n.ebrake.transcode.FfmpegCommands
import xyz.imd4n.ebrake.transcode.TranscodeEngine
import xyz.imd4n.ebrake.transcode.TranscodePreset

/**
 * Тестирование генерации команд ffmpeg и формул расчёта параметров кодирования:
 * - Формирование аргументов кодеков (-c:v, битрейт, CRF, пресеты)
 * - Формирование команд полного кодирования и превью
 * - Метки качества для интерфейса
 * - Расчёт смещений уровней и диапазонов слайдера
 */
class FfmpegCommandsTest {

    @Test
    fun `videoArgs для HW кодеков генерирует корректный медиакодек и VBR параметры`() {
        val presetH264 = TranscodePreset(TranscodeEngine.HW_H264, CompareLevel.BALANCED, quality = 50, spread = 1f)
        val argsH264 = FfmpegCommands.videoArgs(presetH264)
        assertTrue(argsH264.contains("-c:v h264_mediacodec"))
        assertTrue(argsH264.contains("-bitrate_mode vbr"))
        assertTrue(argsH264.contains("-b:v 3M"))

        val presetHevc = TranscodePreset(TranscodeEngine.HW_HEVC, CompareLevel.HIGH, quality = 50, spread = 1f)
        val argsHevc = FfmpegCommands.videoArgs(presetHevc)
        assertTrue(argsHevc.contains("-c:v hevc_mediacodec"))
        assertTrue(argsHevc.contains("-bitrate_mode vbr"))
        assertTrue(argsHevc.contains("-b:v 8M"))

        val presetAv1 = TranscodePreset(TranscodeEngine.HW_AV1, CompareLevel.ECO, quality = 50, spread = 1f)
        val argsAv1 = FfmpegCommands.videoArgs(presetAv1)
        assertTrue(argsAv1.contains("-c:v av1_mediacodec"))
        assertTrue(argsAv1.contains("-bitrate_mode vbr"))
        assertTrue(argsAv1.contains("-b:v 1.2M"))
    }

    @Test
    fun `videoArgs для SW x264 учитывает флаг ultrafast`() {
        val preset = TranscodePreset(TranscodeEngine.SW_X264, CompareLevel.BALANCED, quality = 50, spread = 1f)
        val normalArgs = FfmpegCommands.videoArgs(preset, ultrafast = false)
        assertTrue(normalArgs.contains("-c:v libx264"))
        assertTrue(normalArgs.contains("-crf 26"))
        assertTrue(normalArgs.contains("-preset veryfast"))

        val fastArgs = FfmpegCommands.videoArgs(preset, ultrafast = true)
        assertTrue(fastArgs.contains("-c:v libx264"))
        assertTrue(fastArgs.contains("-crf 26"))
        assertTrue(fastArgs.contains("-preset ultrafast"))
    }

    @Test
    fun `videoArgs для SW MPEG4 использует квантователь qscale`() {
        val presetBalanced = TranscodePreset(TranscodeEngine.SW_MPEG4, CompareLevel.BALANCED, quality = 50, spread = 1f)
        val argsBalanced = FfmpegCommands.videoArgs(presetBalanced)
        assertTrue(argsBalanced.contains("-c:v mpeg4 -q:v 4"))

        val presetHigh = TranscodePreset(TranscodeEngine.SW_MPEG4, CompareLevel.HIGH, quality = 50, spread = 1f)
        val argsHigh = FfmpegCommands.videoArgs(presetHigh)
        assertTrue(argsHigh.contains("-c:v mpeg4 -q:v 2"))

        val presetEco = TranscodePreset(TranscodeEngine.SW_MPEG4, CompareLevel.ECO, quality = 50, spread = 1f)
        val argsEco = FfmpegCommands.videoArgs(presetEco)
        assertTrue(argsEco.contains("-c:v mpeg4 -q:v 8"))
    }

    @Test
    fun `previewArgs формирует команду с таймкодом и ограничением длительности`() {
        val preset = TranscodePreset(TranscodeEngine.HW_H264, CompareLevel.BALANCED, quality = 50, spread = 1f)
        val cmd = FfmpegCommands.previewArgs(
            inputSafUrl = "saf://input",
            outputPath = "/tmp/preview.mp4",
            preset = preset,
            seekSec = 15,
            durationSec = 5,
        )
        assertTrue(cmd.startsWith("-ss 15 -t 5 -i saf://input"))
        assertTrue(cmd.contains("-c:a aac -b:a 128k -y /tmp/preview.mp4"))
    }

    @Test
    fun `fullArgs формирует корректную строку полного перекодирования`() {
        val preset = TranscodePreset(TranscodeEngine.SW_X264, CompareLevel.BALANCED, quality = 50, spread = 1f)
        val cmd = FfmpegCommands.fullArgs(
            inputSafUrl = "saf://in",
            outputSafUrl = "saf://out",
            preset = preset,
        )
        assertTrue(cmd.startsWith("-i saf://in"))
        assertTrue(cmd.contains("-c:v libx264"))
        assertTrue(cmd.contains("-c:a aac -b:a 128k -y saf://out"))
    }

    @Test
    fun `qualityLabel возвращает техническую подпись уровня качества`() {
        assertEquals("8M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.HIGH, quality = 50))
        assertEquals("3M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.BALANCED, quality = 50))
        assertEquals("1.2M", FfmpegCommands.qualityLabel(TranscodeEngine.HW_H264, CompareLevel.ECO, quality = 50))

        assertEquals("CRF 20", FfmpegCommands.qualityLabel(TranscodeEngine.SW_X264, CompareLevel.HIGH, quality = 50))
        assertEquals("CRF 26", FfmpegCommands.qualityLabel(TranscodeEngine.SW_X264, CompareLevel.BALANCED, quality = 50))
        assertEquals("CRF 32", FfmpegCommands.qualityLabel(TranscodeEngine.SW_X264, CompareLevel.ECO, quality = 50))

        assertEquals("q2", FfmpegCommands.qualityLabel(TranscodeEngine.SW_MPEG4, CompareLevel.HIGH, quality = 50))
        assertEquals("q4", FfmpegCommands.qualityLabel(TranscodeEngine.SW_MPEG4, CompareLevel.BALANCED, quality = 50))
        assertEquals("q8", FfmpegCommands.qualityLabel(TranscodeEngine.SW_MPEG4, CompareLevel.ECO, quality = 50))
    }

    @Test
    fun `levelOffset рассчитывает корректные сдвиги уровней`() {
        assertEquals(35, FfmpegCommands.levelOffset(TranscodeEngine.HW_H264, CompareLevel.HIGH, 1f))
        assertEquals(0, FfmpegCommands.levelOffset(TranscodeEngine.HW_H264, CompareLevel.BALANCED, 1f))
        assertEquals(-33, FfmpegCommands.levelOffset(TranscodeEngine.HW_H264, CompareLevel.ECO, 1f))

        assertEquals(38, FfmpegCommands.levelOffset(TranscodeEngine.SW_X264, CompareLevel.HIGH, 1f))
        assertEquals(0, FfmpegCommands.levelOffset(TranscodeEngine.SW_X264, CompareLevel.BALANCED, 1f))
        assertEquals(-38, FfmpegCommands.levelOffset(TranscodeEngine.SW_X264, CompareLevel.ECO, 1f))

        assertEquals(25, FfmpegCommands.levelOffset(TranscodeEngine.SW_MPEG4, CompareLevel.HIGH, 1f))
        assertEquals(0, FfmpegCommands.levelOffset(TranscodeEngine.SW_MPEG4, CompareLevel.BALANCED, 1f))
        assertEquals(-50, FfmpegCommands.levelOffset(TranscodeEngine.SW_MPEG4, CompareLevel.ECO, 1f))
    }

    @Test
    fun `baseRange ограничивает диапазон слайдера в пределах 0-100`() {
        val range = FfmpegCommands.baseRange(TranscodeEngine.HW_H264, spread = 1f)
        assertEquals(17..85, range)

        val wideRange = FfmpegCommands.baseRange(TranscodeEngine.HW_H264, spread = 2.5f)
        assertTrue(wideRange.first >= 0)
        assertTrue(wideRange.last <= 100)
    }

    @Test
    fun `dragSpread для BALANCED не меняет разброс`() {
        val spread = 1.2f
        val newSpread = FfmpegCommands.dragSpread(TranscodeEngine.HW_H264, CompareLevel.BALANCED, spread, deltaPt = 10f)
        assertEquals(spread, newSpread, 0.0001f)
    }
}
