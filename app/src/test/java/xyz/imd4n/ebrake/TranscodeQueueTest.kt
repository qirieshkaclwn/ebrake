package xyz.imd4n.ebrake

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.imd4n.ebrake.transcode.CompareLevel
import xyz.imd4n.ebrake.transcode.DoneRecord
import xyz.imd4n.ebrake.transcode.JobStatus
import xyz.imd4n.ebrake.transcode.TranscodeEngine
import xyz.imd4n.ebrake.transcode.TranscodeJob
import xyz.imd4n.ebrake.transcode.TranscodePreset
import xyz.imd4n.ebrake.transcode.TranscodeQueue

/**
 * Тестирование логики управления очередью заданий [TranscodeQueue]:
 * - Генерация уникальных монотонных ID
 * - Добавление, поиск и извлечение следующего задания
 * - Обновление прогресса и статуса задания
 * - Удаление заданий из очереди (только в статусе QUEUED)
 * - Очистка завершённых заданий с сохранением активных и ожидающих
 * - Ведение истории выполненных заданий и ограничение скользящего окна (до 100)
 * - Кольцевой буфер строк лога (до 300 строк)
 */
class TranscodeQueueTest {

    private val dummyUri: Uri = android.net.FakeUri()

    private fun createJob(
        id: Long = TranscodeQueue.nextId(),
        status: JobStatus = JobStatus.QUEUED,
    ): TranscodeJob = TranscodeJob(
        id = id,
        inputUri = dummyUri,
        displayName = "clip_$id.mp4",
        preset = TranscodePreset(TranscodeEngine.SW_X264, CompareLevel.BALANCED, 50, 1f),
        status = status,
        progress01 = 0f,
        outputPath = "/tmp/out_$id.mp4",
        outputSizeBytes = 0L,
        inputSizeBytes = 10_000_000L,
        inputName = "clip_$id.mp4",
    )

    @Before
    fun setUp() {
        TranscodeQueue.clearFinished()
        TranscodeQueue.clearDone()
        TranscodeQueue.queue.value.forEach { TranscodeQueue.remove(it.id) }
    }

    @Test
    fun `nextId выдает строго возрастающие идентификаторы`() {
        val id1 = TranscodeQueue.nextId()
        val id2 = TranscodeQueue.nextId()
        val id3 = TranscodeQueue.nextId()
        assertTrue(id2 > id1)
        assertTrue(id3 > id2)
    }

    @Test
    fun `добавление и получение следующего задания next`() {
        val job1 = createJob(id = 101, status = JobStatus.QUEUED)
        val job2 = createJob(id = 102, status = JobStatus.QUEUED)

        TranscodeQueue.add(job1)
        TranscodeQueue.add(job2)

        assertTrue(TranscodeQueue.hasQueued())
        val nextJob = TranscodeQueue.next()
        assertNotNull(nextJob)
        assertEquals(101L, nextJob?.id)
    }

    @Test
    fun `update обновляет статус и параметры задания в очереди`() {
        val job = createJob(id = 201, status = JobStatus.QUEUED)
        TranscodeQueue.add(job)

        TranscodeQueue.update(201L) { it.copy(status = JobStatus.RUNNING, progress01 = 0.5f) }

        val updated = TranscodeQueue.queue.value.first { it.id == 201L }
        assertEquals(JobStatus.RUNNING, updated.status)
        assertEquals(0.5f, updated.progress01, 0.001f)
    }

    @Test
    fun `remove удаляет только задание со статусом QUEUED`() {
        val queuedJob = createJob(id = 301, status = JobStatus.QUEUED)
        val runningJob = createJob(id = 302, status = JobStatus.RUNNING)

        TranscodeQueue.add(queuedJob)
        TranscodeQueue.add(runningJob)

        // Попытка удалить активное задание должна вернуть false
        val removedRunning = TranscodeQueue.remove(302L)
        assertFalse(removedRunning)
        assertTrue(TranscodeQueue.queue.value.any { it.id == 302L })

        // Удаление неначатого задания должно вернуть true
        val removedQueued = TranscodeQueue.remove(301L)
        assertTrue(removedQueued)
        assertFalse(TranscodeQueue.queue.value.any { it.id == 301L })

        // Повторное удаление несуществующего задания
        val removedAgain = TranscodeQueue.remove(301L)
        assertFalse(removedAgain)
    }

    @Test
    fun `clearFinished удаляет только завершенные и упавшие задания`() {
        val queuedJob = createJob(id = 401, status = JobStatus.QUEUED)
        val runningJob = createJob(id = 402, status = JobStatus.RUNNING)
        val doneJob = createJob(id = 403, status = JobStatus.DONE)
        val failedJob = createJob(id = 404, status = JobStatus.FAILED)

        TranscodeQueue.add(queuedJob)
        TranscodeQueue.add(runningJob)
        TranscodeQueue.add(doneJob)
        TranscodeQueue.add(failedJob)

        TranscodeQueue.clearFinished()

        val remainingIds = TranscodeQueue.queue.value.map { it.id }.toSet()
        assertTrue(remainingIds.contains(401L))
        assertTrue(remainingIds.contains(402L))
        assertFalse(remainingIds.contains(403L))
        assertFalse(remainingIds.contains(404L))
    }

    @Test
    fun `recordDone сохраняет историю и ограничивает размер до 100 записей`() {
        for (i in 1..120) {
            val record = DoneRecord(
                id = i.toLong(),
                name = "input_$i.mp4",
                inputUri = dummyUri,
                inputBytes = 20_000_000L,
                outputBytes = 10_000_000L,
                engine = TranscodeEngine.SW_X264,
                level = CompareLevel.BALANCED,
                outputPath = "/tmp/out_$i.mp4",
            )
            TranscodeQueue.recordDone(record)
        }

        val doneList = TranscodeQueue.done.value
        assertEquals(100, doneList.size)
        // Новые записи добавляются в начало
        assertEquals(120L, doneList.first().id)
        assertEquals(21L, doneList.last().id)

        TranscodeQueue.clearDone()
        assertTrue(TranscodeQueue.done.value.isEmpty())
    }

    @Test
    fun `appendLog ограничивает количество строк до 300`() {
        for (i in 1..350) {
            TranscodeQueue.appendLog("Log message $i")
        }

        val logs = TranscodeQueue.logs.value
        assertEquals(300, logs.size)
        assertEquals("Log message 51", logs.first())
        assertEquals("Log message 350", logs.last())
    }
}
