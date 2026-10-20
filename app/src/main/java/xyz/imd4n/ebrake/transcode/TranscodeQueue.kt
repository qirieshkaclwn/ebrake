package xyz.imd4n.ebrake.transcode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Очередь и лог живут здесь, а не во ViewModel: кодирование крутит
 * [TranscodeService], который переживает Activity, а ViewModel — нет.
 *
 * Сервис и ViewModel в одном процессе, поэтому вместо binder достаточно
 * обычного объекта. ViewModel только отображает состояние и шлёт команды
 * (добавить/старт/стоп) — источник правды ровно один.
 */
object TranscodeQueue {
    private val _queue = MutableStateFlow<List<TranscodeJob>>(emptyList())
    val queue: StateFlow<List<TranscodeJob>> = _queue.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // Лог начинается пустым: приветствие дописывает ViewModel из ресурсов при
    // старте. Ставить его прямо здесь нельзя — у объекта нет Context, а строка
    // в коде зафиксировала бы русский язык навсегда.
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /**
     * Выполненная очередь. Отдельный поток от [queue]: «Очистить» должен
     * убирать лишь ждущие и готовые задания, а история с экономией места —
     * оставаться до явного сброса.
     */
    private val _done = MutableStateFlow<List<DoneRecord>>(emptyList())
    val done: StateFlow<List<DoneRecord>> = _done.asStateFlow()

    /** Счётчик id заданий — здесь же, чтобы сервис и UI не выдали дубли. */
    private var seq = 0L

    @Synchronized
    fun nextId(): Long = ++seq

    fun add(job: TranscodeJob) {
        _queue.update { it + job }
    }

    fun update(id: Long, fn: (TranscodeJob) -> TranscodeJob) {
        _queue.update { list -> list.map { if (it.id == id) fn(it) else it } }
    }

    /** Удаляет неначатое задание из очереди. */
    fun remove(id: Long): Boolean {
        var removed = false
        _queue.update { list ->
            list.filterNot {
                if (it.id == id && it.status == JobStatus.QUEUED) {
                    removed = true
                    true
                } else {
                    false
                }
            }
        }
        return removed
    }

    /** Следующее задание к обработке (первое в статусе «В очереди»). */
    fun next(): TranscodeJob? = _queue.value.firstOrNull { it.status == JobStatus.QUEUED }

    fun hasQueued(): Boolean = next() != null

    /** Чистит только завершённые: «В очереди» и «Кодируется» остаются. */
    fun clearFinished() {
        _queue.update { list ->
            list.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
        }
    }

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    /** Задание закодировалось: переносим его в выполненные с экономией. */
    fun recordDone(record: DoneRecord) {
        _done.update { (listOf(record) + it).take(MAX_DONE) }
    }

    fun clearDone() {
        _done.value = emptyList()
    }

    fun appendLog(line: String) {
        _logs.update { (it + line).takeLast(300) }
    }

    /** История длинная не нужна: 100 записей — уже несколько экранов истории. */
    private const val MAX_DONE = 100
}
