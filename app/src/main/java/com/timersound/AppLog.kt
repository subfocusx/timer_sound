package com.timersound

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Б5: единый логгер модуля. Пишет в android.util.Log, если доступен рантайм;
 * в чистых JVM-тестах (android.jar-заглушка) падает назад на System.out,
 * чтобы юнит-тесты машины состояний не требовали Robolectric ради логов.
 *
 * Файловый лог диагностики: внутреннее хранилище filesDir/logs/, ротация 48ч.
 * Ноль разрешений (своя папка без permission), ноль UI. Снятие: adb pull / run-as.
 * Писатель — один фоновый поток, очередь до 2000 строк, дальше сбрасывает
 * переполнение (лог не должен ронять сервис).
 */
object AppLog {
    const val TAG = "TimerSound"
    private const val LOG_DIR = "logs"
    private const val RETENTION_MS = 48L * 60 * 60 * 1000
    private const val MAX_QUEUE = 2000

    private val androidAvailable: Boolean by lazy {
        runCatching {
            Class.forName("android.util.Log")
            Class.forName("android.os.SystemClock") // триггер реального рантайма
            true
        }.getOrDefault(false) && !isUnitTestStub()
    }

    private fun isUnitTestStub(): Boolean = runCatching {
        android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)
        false
    }.getOrDefault(true)

    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE)
    private val writerStarted = AtomicBoolean(false)
    @Volatile private var logDir: File? = null
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Вызвать один раз из Service.onCreate. Повторные — no-op. */
    fun init(filesDir: File) {
        val dir = File(filesDir, LOG_DIR)
        runCatching { dir.mkdirs() }
        logDir = dir
        pruneOld()
        if (writerStarted.compareAndSet(false, true)) {
            Thread({ drainLoop() }, "AppLogWriter").apply { isDaemon = true }.start()
        }
    }

    /** Удалить файлы старше 48ч. Вызывается при init. */
    fun pruneOld(nowMs: Long = System.currentTimeMillis()) {
        val dir = logDir ?: return
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (nowMs - f.lastModified() > RETENTION_MS) runCatching { f.delete() }
            }
        }
    }

    fun i(message: String) {
        if (androidAvailable) android.util.Log.i(TAG, message)
        else println("I/$TAG: $message")
        toFile("I", message)
    }

    fun w(message: String) {
        if (androidAvailable) android.util.Log.w(TAG, message)
        else println("W/$TAG: $message")
        toFile("W", message)
    }

    fun e(message: String) {
        if (androidAvailable) android.util.Log.e(TAG, message)
        else println("E/$TAG: $message")
        toFile("E", message)
    }

    /** Структурированное событие срабатывания: план vs факт vs дрейф. */
    fun evt(
        channelId: Int,
        channelName: String,
        plannedElapsedMs: Long,
        actualElapsedMs: Long,
        sound: String,
        extra: String = "",
    ) {
        val drift = actualElapsedMs - plannedElapsedMs
        i(
            "EVT fire ch=$channelId name=\"$channelName\" planElapsed=$plannedElapsedMs " +
                "actualElapsed=$actualElapsedMs driftMs=$drift sound=$sound " +
                "wall=${tsFormat.format(Date())} $extra".trim(),
        )
    }

    private fun toFile(level: String, message: String) {
        val dir = logDir ?: return
        runCatching { dir.mkdirs() }
        queue.offer("${tsFormat.format(Date())} $level/$TAG: $message")
    }

    private fun drainLoop() {
        var writer: BufferedWriter? = null
        var writerDay = ""
        try {
            while (true) {
                val line = queue.take()
                val dir = logDir ?: continue
                val day = dayFormat.format(Date())
                if (writer == null || day != writerDay) {
                    runCatching { writer?.close() }
                    val file = File(dir, "timersound-$day.log")
                    writer = runCatching { BufferedWriter(FileWriter(file, true)) }.getOrNull()
                        ?: continue
                    writerDay = day
                }
                runCatching {
                    val w = writer
                    w?.write(line)
                    w?.newLine()
                    if (queue.isEmpty()) w?.flush()
                }
            }
        } catch (_: InterruptedException) {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
        }
    }
}
