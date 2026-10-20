package xyz.imd4n.ebrake.transcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.imd4n.ebrake.MainActivity
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatDelta
import xyz.imd4n.ebrake.transcode.TranscodeFormat.lastLogLines

/**
 * Фоновое перекодирование очереди.
 *
 * Логика намеренно живёт здесь, а не во ViewModel: раньше цикл крутился в
 * `viewModelScope`, и сворачивание приложения (или пересоздание Activity) могло
 * оборвать кодирование на полпути. Сервис живёт дольше UI, а уведомление даёт
 * прогресс и кнопку «Стоп» без открытия приложения.
 *
 * Состояние — [TranscodeQueue], это единственный источник правды: UI его
 * читает, сервис его пишет. Очередь живёт в памяти процесса, поэтому сервис
 * объявлен START_NOT_STICKY — перезапуск после смерти процесса не восстановил бы
 * задания и просто поднял бы пустое уведомление.
 */
class TranscodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var inForeground = false

    @Volatile
    private var cancelRequested = false

    override fun onCreate() {
        super.onCreate()
        // Тот же SAF-фикс, что и во ViewModel: одноразовые ffkitsaf:// не
        // открываются в executeAsync. Идемпотентно, но сервис может стартовать
        // раньше ViewModel — подстрахуемся.
        FFmpegKitConfig.setSafUrlsReusable(true)
        createChannel()
        // Уведомление — проекция состояния очереди, отдельного источника нет.
        scope.launch {
            combine(TranscodeQueue.queue, TranscodeQueue.running) { q, r -> q to r }
                .collect { (queue, running) -> pushNotification(queue, running) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                requestCancel(fromNotification = true)
                return START_NOT_STICKY
            }
            else -> {
                // startForegroundService обязан показать уведомление за 5 секунд,
                // поэтому сначала форground, потом (если есть что) обработка.
                promoteToForeground()
                if (TranscodeQueue.hasQueued()) startWorker() else stopQuietly()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (cancelRequested || inForeground) {
            runCatching { FFmpegKit.cancel() }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ───────────────────────── цикл кодирования ─────────────────────────

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            cancelRequested = false
            TranscodeQueue.setRunning(true)
            var processed = 0
            try {
                EncoderCatalog.ensureDetected(this@TranscodeService)
                while (!cancelRequested) {
                    val job = TranscodeQueue.next() ?: break
                    // false = очередь отменили, дальше не идём
                    if (!runOneJob(job)) break
                    processed++
                }
            } finally {
                TranscodeQueue.setRunning(false)
                TranscodeQueue.appendLog(getString(R.string.log_queue_finished))
                val failed = TranscodeQueue.queue.value.count { it.status == JobStatus.FAILED }
                finish(
                    when {
                        cancelRequested -> getString(R.string.notif_result_cancelled, processed)
                        failed > 0 -> resources.getQuantityString(
                            R.plurals.notif_result_failed,
                            failed,
                            processed,
                            failed,
                        )
                        else -> getString(R.string.notif_result_done, processed)
                    },
                )
            }
        }
    }

    /**
     * Одно задание целиком. Возвращает false, если очередь отменили.
     * Логика и ловушки ffmpeg-kit перенесены из ViewModel как есть.
     */
    private fun runOneJob(job: TranscodeJob): Boolean {
        val engine = EncoderCatalog.effective(job.preset.engine)
        if (engine != job.preset.engine) {
            TranscodeQueue.appendLog(
                getString(
                    R.string.log_engine_missing,
                    job.preset.engine.ffmpegVideoCodec,
                    engine.ffmpegVideoCodec,
                ),
            )
        }
        TranscodeQueue.update(job.id) {
            it.copy(
                status = JobStatus.RUNNING,
                progress01 = 0f,
                preset = job.preset.copy(engine = engine),
            )
        }
        // Вход: свежий многоразовый SAF-URL под это задание (uri хранится в задании).
        // Флаг reusable обязателен: одноразовые URL в executeAsync не открываются
        // (на устройстве: "Failed to open SAF id N: callback returned 0").
        val saf = try {
            FFmpegKitConfig.getSafParameterForRead(applicationContext, job.inputUri, true)
        } catch (e: Exception) {
            TranscodeQueue.appendLog(
                getString(R.string.log_input_open_failed, job.id, e.message),
            )
            TranscodeQueue.update(job.id) { it.copy(status = JobStatus.FAILED) }
            return true
        }
        // Длительность для прогресса: пробуем FFprobe, иначе 0 (прогресс по размеру).
        val durationMs = runCatching {
            FFprobeKit.getMediaInformation(saf)
                .getMediaInformation()?.getDuration()?.toDoubleOrNull()?.times(1000)?.toLong()
        }.getOrNull() ?: 0L
        // Размер исходника — база для экономии в выполненной очереди.
        val inputBytes = InputTarget.sizeOf(applicationContext, job.inputUri)
        TranscodeQueue.update(job.id) { it.copy(inputSizeBytes = inputBytes) }
        // Приёмник: папка пользователя либо Movies/eBrake по умолчанию.
        val out = OutputTarget.prepare(
            applicationContext,
            "ebrake_${job.id}_${engine.ffmpegVideoCodec}_${job.preset.compareLevel.name.lowercase()}.mp4",
        )
        val cmd = FfmpegCommands.fullArgs(
            saf,
            out.ffmpegArg,
            job.preset.copy(engine = engine),
        )
        // Сырую команду в лог не пишем: она в три строки и заслоняет смысл,
        // а по ошибке ffmpeg всё равно отдаёт свой хвост вывода.
        TranscodeQueue.appendLog(
            getString(R.string.log_job_starting, job.id, job.displayName),
        )
        val done = CompletableFuture<Boolean>()
        FFmpegKit.executeAsync(
            cmd,
            { session ->
                val rc = session.getReturnCode()
                // Для SAF «файл существует» — это stat по документу, а не File.exists().
                val size = if (ReturnCode.isSuccess(rc)) out.size(applicationContext) else 0L
                if (ReturnCode.isSuccess(rc) && size > 0) {
                    OutputTarget.complete(applicationContext, out, true)
                    val finished = job.copy(
                        status = JobStatus.DONE,
                        progress01 = 1f,
                        outputPath = out.display,
                        outputSizeBytes = size,
                        inputSizeBytes = inputBytes,
                        preset = job.preset.copy(engine = engine),
                    )
                    TranscodeQueue.update(job.id) { finished }
                    TranscodeQueue.recordDone(
                        DoneRecord(
                            id = finished.id,
                            name = finished.inputName.ifEmpty { finished.displayName },
                            inputUri = finished.inputUri,
                            inputBytes = inputBytes,
                            outputBytes = size,
                            engine = finished.preset.engine,
                            level = finished.preset.compareLevel,
                            outputPath = out.display,
                        ),
                    )
                    TranscodeQueue.appendLog(
                        getString(
                            R.string.log_job_done,
                            job.id,
                            formatBytes(this, size),
                            formatBytes(this, inputBytes),
                            formatDelta(this, inputBytes - size),
                            out.display,
                        ),
                    )
                } else if (ReturnCode.isCancel(rc)) {
                    // Недописанный MediaStore-файл удаляем, чтобы не копились
                    // пустые «Movies/eBrake/ebrake_*». Задание — обратно в очередь.
                    OutputTarget.complete(applicationContext, out, false)
                    // Возвращаем в «В очереди», чтобы можно было перезапустить.
                    TranscodeQueue.update(job.id) { it.copy(status = JobStatus.QUEUED, progress01 = 0f) }
                    TranscodeQueue.appendLog(getString(R.string.log_job_cancelled, job.id))
                } else {
                    OutputTarget.complete(applicationContext, out, false)
                    TranscodeQueue.update(job.id) { it.copy(status = JobStatus.FAILED) }
                    TranscodeQueue.appendLog(
                        getString(
                            R.string.log_job_failed,
                            job.id,
                            rc?.value,
                            lastLogLines(this, session.getOutput(), 8),
                        ),
                    )
                }
                runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(saf) }
                if (out.isSaf) runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(out.ffmpegArg) }
                done.complete(true)
            },
            { _ -> /* logCallback: шум ffmpeg не дублируем, хватает хвоста при ошибке */ },
            { stats ->
                // Statistics — Kotlin-класс со свойствами (stats.time), а не с
                // функциями getTime(): явный вызов геттера не компилируется.
                if (durationMs > 0) {
                    val p = (stats.time.toFloat() / durationMs).coerceIn(0f, 1f)
                    TranscodeQueue.update(job.id) { it.copy(progress01 = p) }
                }
            },
        )
        return try {
            done.get()
            // false = очередь отменили, дальше не идём
            !cancelRequested && TranscodeQueue.running.value
        } catch (_: Exception) {
            false
        }
    }

    private fun requestCancel(fromNotification: Boolean) {
        if (TranscodeQueue.running.value) {
            cancelRequested = true
            TranscodeQueue.setRunning(false)
            // FFmpegKit.cancel() глушит ВСЕ сессии движка, включая compare —
            // поэтому UI не даёт запустить одно с другим (см. Edit/OutputScreen).
            FFmpegKit.cancel()
            TranscodeQueue.appendLog(getString(R.string.log_queue_cancel_requested))
        } else if (!fromNotification) {
            return
        }
        // Повторный тап по «Стоп» (или тап по устаревшему уведомлению) сюда
        // попадает с running=false: глушить движок уже нечего, только сняться.
        if (!inForeground) stopQuietly()
    }

    // ───────────────────────── уведомление ─────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
                description = getString(R.string.notif_channel_description)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun promoteToForeground() {
        val notification = buildProgress(TranscodeQueue.queue.value, TranscodeQueue.running.value)
        // mediaProcessing — ровно наш случай (перекодирование), и у него нет
        // суточного лимита, в отличие от dataSync. Тип появился в API 35;
        // ниже передаём 0 — там тип берётся из манифеста.
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            // Страховка от неожиданностей платформы: уведомление важнее типа.
            .onFailure { runCatching { startForeground(NOTIFICATION_ID, notification) } }
        inForeground = true
    }

    private fun pushNotification(queue: List<TranscodeJob>, running: Boolean) {
        if (!inForeground) return
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildProgress(queue, running))
        }
    }

    private fun buildProgress(queue: List<TranscodeJob>, running: Boolean): Notification {
        val current = queue.firstOrNull { it.status == JobStatus.RUNNING }
        val position = current?.let { job ->
            queue.indexOfFirst { it.id == job.id } + 1
        } ?: 0
        val percent = ((current?.progress01 ?: 0f) * 100).toInt()
        val text = if (current == null) {
            if (running) getString(R.string.notif_preparing) else getString(R.string.notif_idle)
        } else {
            getString(R.string.notif_task, position, queue.size, percent)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ebrake)
            .setContentTitle(current?.displayName ?: getString(R.string.notif_title))
            .setContentText(text)
            .setProgress(100, percent, current == null || current.progress01 <= 0f)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .apply { if (running) addAction(0, getString(R.string.action_stop), stopIntent()) }
            .build()
    }

    /** Итоговое уведомление переживает сервис: STOP_FOREGROUND_DETACH его не снимает. */
    private fun finish(text: String) {
        inForeground = false
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_ebrake)
                    .setContentTitle(getString(R.string.notif_title_done))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build(),
            )
        }
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        stopSelf()
    }

    /** Нечего кодировать — снимаемся без следа. */
    private fun stopQuietly() {
        inForeground = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, TranscodeService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_CANCEL = "xyz.imd4n.ebrake.action.CANCEL_QUEUE"

        private const val CHANNEL_ID = "transcode_progress"
        private const val NOTIFICATION_ID = 4711

        /** Старт очереди из UI. Сервис сам разберётся, есть ли что кодировать. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscodeService::class.java),
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, TranscodeService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}
