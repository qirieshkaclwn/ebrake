package xyz.imd4n.ebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.imd4n.ebrake.transcode.EncoderCatalog
import xyz.imd4n.ebrake.transcode.TranscodeEngine

/**
 * Тестирование каталога кодеков и механизма выбора эффективного энкодера:
 * - Доступность кодеков по заявленным именам в сборке ffmpeg
 * - Цепочка fallback при отсутствии запрашиваемого энкодера
 */
class EncoderCatalogTest {

    @Test
    fun `available возвращает true для присутствующих в списке кодеков`() {
        EncoderCatalog.publish(setOf("h264_mediacodec", "libx264", "mpeg4"))

        assertTrue(EncoderCatalog.available(TranscodeEngine.HW_H264))
        assertTrue(EncoderCatalog.available(TranscodeEngine.SW_X264))
        assertTrue(EncoderCatalog.available(TranscodeEngine.SW_MPEG4))
        assertFalse(EncoderCatalog.available(TranscodeEngine.HW_HEVC))
        assertFalse(EncoderCatalog.available(TranscodeEngine.HW_AV1))
    }

    @Test
    fun `effective возвращает запрашиваемый кодек если он доступен`() {
        EncoderCatalog.publish(setOf("h264_mediacodec", "libx264", "mpeg4"))

        assertEquals(TranscodeEngine.HW_H264, EncoderCatalog.effective(TranscodeEngine.HW_H264))
        assertEquals(TranscodeEngine.SW_X264, EncoderCatalog.effective(TranscodeEngine.SW_X264))
    }

    @Test
    fun `effective переключается по цепочке fallback если запрашиваемый недоступен`() {
        // Доступны только libx264 и mpeg4 (аппаратных нет)
        EncoderCatalog.publish(setOf("libx264", "mpeg4"))

        // Запрашиваем HW_HEVC -> первый в fallback это HW_H264 (нет) -> HW_HEVC (нет) -> HW_AV1 (нет) -> SW_X264 (есть!)
        assertEquals(TranscodeEngine.SW_X264, EncoderCatalog.effective(TranscodeEngine.HW_HEVC))

        // Запрашиваем HW_AV1 -> тоже должен выбрать SW_X264
        assertEquals(TranscodeEngine.SW_X264, EncoderCatalog.effective(TranscodeEngine.HW_AV1))
    }

    @Test
    fun `effective откатывается на SW_MPEG4 если другие кодеки отсутствуют`() {
        // Доступен только базовый mpeg4
        EncoderCatalog.publish(setOf("mpeg4"))

        assertEquals(TranscodeEngine.SW_MPEG4, EncoderCatalog.effective(TranscodeEngine.HW_H264))
        assertEquals(TranscodeEngine.SW_MPEG4, EncoderCatalog.effective(TranscodeEngine.SW_X264))
    }
}
