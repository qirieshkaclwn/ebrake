package xyz.imd4n.ebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.imd4n.ebrake.ui.gallery.buildDateSections
import java.util.Calendar
import java.util.Locale

/**
 * Тестирование группировки видео по секциям дат в галерее:
 * - Ролики за сегодня попадают в секцию «Сегодня»
 * - Ролики за вчера попадают в секцию «Вчера»
 * - Ролики текущего года группируются без года («d MMMM»)
 * - Ролики прошлых лет группируются с годом («d MMMM yyyy»)
 */
class VideoGalleryDateSectionsTest {

    private data class DummyVideo(val id: Long, val name: String, val dateAddedSec: Long)

    @Test
    fun `группировка корректно распределяет ролики по дням`() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val midnightToday = cal.timeInMillis / 1000
        val addedToday = midnightToday + 3600 // Сегодня в 1:00 ночи
        val addedYesterday = midnightToday - 3600 // Вчера в 23:00

        // Ролик двухлетней давности
        cal.add(Calendar.YEAR, -2)
        val addedPastYear = cal.timeInMillis / 1000

        val items = listOf(
            DummyVideo(id = 1, name = "today.mp4", dateAddedSec = addedToday),
            DummyVideo(id = 2, name = "yesterday.mp4", dateAddedSec = addedYesterday),
            DummyVideo(id = 3, name = "past.mp4", dateAddedSec = addedPastYear),
        )

        val ruLocale = Locale.forLanguageTag("ru")
        val sections = buildDateSections(
            items = items,
            today = "Сегодня",
            yesterday = "Вчера",
            locale = ruLocale,
            dateAddedSec = { it.dateAddedSec },
        )

        assertEquals(3, sections.size)
        assertEquals("Сегодня", sections[0].first)
        assertEquals(1, sections[0].second.size)
        assertEquals(1L, sections[0].second[0].id)

        assertEquals("Вчера", sections[1].first)
        assertEquals(1, sections[1].second.size)
        assertEquals(2L, sections[1].second[0].id)

        // Прошлый год должен содержать 4 цифры года
        val pastYearSection = sections[2].first
        assertTrue(pastYearSection.matches(Regex(".+\\d{4}$")))
    }
}
