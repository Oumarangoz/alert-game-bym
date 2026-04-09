package com.alertgamebym

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

object AppLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeQueue = ArrayBlockingQueue<String>(2000)

    @Volatile private var logDir: File? = null
    @Volatile private var writerStarted = false
    private val writerLock = Any()

    fun bind(context: Context) {
        val dir = context.applicationContext.filesDir
        dir.mkdirs()
        logDir = dir
        startWriter()
    }

    private fun startWriter() {
        synchronized(writerLock) {
            if (writerStarted) return
            writerStarted = true
        }
        ioScope.launch {
            val dir = logDir ?: return@launch
            val file = File(dir, "log.txt")
            while (true) {
                try {
                    val line = writeQueue.poll(200, TimeUnit.MILLISECONDS)
                    if (line != null) {
                        val sb = StringBuilder(line).append("\n")
                        var next = writeQueue.poll()
                        while (next != null) {
                            sb.append(next).append("\n")
                            next = writeQueue.poll()
                        }
                        runCatching { file.appendText(sb.toString()) }
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    // Tüm çağrı yerleriyle uyumlu: add(message: String)
    fun add(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $message"
        _lines.value = (_lines.value + line).takeLast(500)
        writeQueue.offer(line)
    }

    fun logFile(context: Context): File =
        File(context.applicationContext.filesDir, "log.txt")

    // Log kaydet butonu: picker açılmadan önce çağrılır
    fun flushBlocking(timeoutMs: Long = 2500L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (writeQueue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    // Log kaydet callback: picker kapandıktan sonra çağrılır
    suspend fun snapshotText(context: Context, flushTimeoutMs: Long = 2500L): String {
        runCatching {
            withTimeout(flushTimeoutMs) {
                while (writeQueue.isNotEmpty()) delay(50)
            }
        }
        val lf = logFile(context)
        if (lf.exists() && lf.length() > 0) return lf.readText(Charsets.UTF_8)
        return lines.value.joinToString("\n")
    }

    fun clear(context: Context) {
        _lines.value = emptyList()
        writeQueue.clear()
        runCatching { logFile(context).delete() }
    }
}
