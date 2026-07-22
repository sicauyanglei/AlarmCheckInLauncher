package com.example.alarmcheckinlauncher

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量文件日志器：把关键事件（闹钟检测、拉起结果）写入应用私有目录的日志文件，
 * 便于在「闹钟响了但没拉起」时事后定位。
 *
 * - 文件位置：/data/data/<pkg>/files/alarm_checkin.log（应用私有，无需额外权限）
 * - 同时输出到 logcat，便于实时调试
 * - 自动保留最近 MAX_LINES 行，避免无限增长
 */
object FileLogger {

    private const val TAG = "AlarmCheckIn"
    private const val FILE_NAME = "alarm_checkin.log"
    private const val MAX_LINES = 2000

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Volatile private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        // 启动时记录一条分界，便于区分多次运行
        val bootMsg = buildString {
            appendLine("==============================")
            appendLine("App 进程启动 / Build=${Build.VERSION.SDK_INT} / ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("==============================")
        }
        write(bootMsg)
    }

    fun d(msg: String) = log("D", msg)
    fun i(msg: String) = log("I", msg)
    fun w(msg: String, t: Throwable? = null) = log("W", msg, t)
    fun e(msg: String, t: Throwable? = null) = log("E", msg, t)

    private fun log(level: String, msg: String, t: Throwable? = null) {
        val time = timeFmt.format(Date())
        val line = "$time $level $msg"
        Log.println(priority(level), TAG, line)
        t?.let { Log.println(priority(level), TAG, Log.getStackTraceString(it)) }

        val sb = StringBuilder(line)
        if (t != null) {
            sb.append('\n').append(Log.getStackTraceString(t))
        }
        sb.append('\n')
        write(sb.toString())
    }

    private fun write(text: String) {
        val d = dir ?: return
        try {
            val file = File(d, FILE_NAME)
            file.appendText(text)
            // 超长时截断保留尾部（最近事件最有价值）
            if (file.length() > MAX_LINES * 120) {
                trimFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "write log failed", e)
        }
    }

    private fun trimFile(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size <= MAX_LINES) return
            val tail = lines.takeLast(MAX_LINES)
            file.writeText(tail.joinToString("\n"))
        } catch (_: Exception) { /* ignore */ }
    }

    private fun priority(level: String): Int = when (level) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        "E" -> Log.ERROR
        else -> Log.INFO
    }

    /** 读取当前日志全文，供 UI 展示/分享 */
    fun read(): String = try {
        val d = dir ?: return "(未初始化)"
        File(d, FILE_NAME).takeIf { it.exists() }?.readText() ?: "(无日志)"
    } catch (e: Exception) {
        "(读取日志失败: ${e.message})"
    }

    /** 清空日志 */
    fun clear() {
        try {
            val d = dir ?: return
            File(d, FILE_NAME).takeIf { it.exists() }?.delete()
        } catch (_: Exception) { /* ignore */ }
    }
}
