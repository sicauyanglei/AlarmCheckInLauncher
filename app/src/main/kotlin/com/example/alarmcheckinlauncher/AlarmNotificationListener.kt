package com.example.alarmcheckinlauncher

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 监听系统闹钟 App 的通知，当检测到「闹钟正在响铃」类通知时拉起用户配置的打卡 App。
 *
 * 实现说明：
 *  - Android 没有公开的「闹钟响铃」广播，最可靠的跨厂商方案是 NotificationListenerService：
 *    系统时钟 App 在闹钟响铃时会发布一条 CATEGORY_ALARM 通知。
 *  - 当通知 category 为 [Notification.CATEGORY_ALARM] 或来自已知时钟 App 且文本含「闹钟/Alarm」时
 *    视为响铃事件。
 *  - 通过包名 [Intent] 直接拉起目标 App；若目标 App 未安装则静默忽略并打日志。
 *  - 同一次响铃可能多次 onNotificationPosted，使用最近触发时间做去抖（10 秒窗口）。
 */
class AlarmNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val prefs = AppPreferences.get(this)
        if (!prefs.enabled) return

        val pkg = sbn.packageName ?: return
        if (!isClockApp(pkg)) return

        val n = sbn.notification ?: return
        if (!isAlarmRinging(n, pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < DEBOUNCE_MS) {
            Log.d(TAG, "Alarm event debounced, skip launch")
            return
        }
        lastTriggerMs = now

        Log.i(TAG, "Alarm ringing detected from $pkg, launching target app")
        launchTarget(prefs.targetPackage)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 闹钟通知移除（用户关闭闹钟）时不做处理
    }

    private fun launchTarget(targetPackage: String) {
        if (TextUtils.isEmpty(targetPackage)) {
            Log.w(TAG, "Target package is empty, skip launch")
            return
        }
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            Log.w(TAG, "Target app not installed or has no launchable activity: $targetPackage")
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        try {
            startActivity(launchIntent)
            Log.i(TAG, "Launched $targetPackage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $targetPackage", e)
        }
    }

    /** 是否为系统/厂商时钟 App 包名 */
    private fun isClockApp(pkg: String): Boolean {
        if (KNOWN_CLOCK_APPS.contains(pkg)) return true
        val lower = pkg.lowercase()
        return lower.contains("clock") || lower.contains("alarm") || lower.contains("deskclock")
    }

    /**
     * 判断该通知是否表示「闹钟正在响铃」：
     * 1. category == CATEGORY_ALARM（API 21+），最可靠
     * 2. 否则回退到文本匹配：「闹钟」「alarm」
     */
    private fun isAlarmRinging(n: Notification, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            Notification.CATEGORY_ALARM == n.category
        ) {
            return true
        }
        val extras = n.extras ?: return false
        val texts = buildList {
            add(n.tickerText?.toString())
            add(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        }
        return texts.any { !it.isNullOrBlank() && ALARM_PATTERN.containsMatchIn(it) }
    }

    companion object {
        private const val TAG = "AlarmCheckInListener"
        private const val DEBOUNCE_MS = 10_000L

        private val ALARM_PATTERN = Regex("(?i)(闹钟|alarm|(?<![a-z])alert(?![a-z]))")

        private val KNOWN_CLOCK_APPS = setOf(
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.samsung.android.clockpackage",
            "com.miui.clock",
            "com.xiaomi.clock",
            "com.android.alarmclock",
            "com.htc.android.worldclock",
            "com.sonyericsson.organizer",
            "com.sonymobile.organizer",
            "com.lge.clock",
            "com.android.bbk.clock",
            "com.coloros.clock",
            "com.oppo.clock",
            "com.vivo.clock",
            "com.meizu.flyme.alarmclock",
            "com.huawei.clock"
        )

        @Volatile private var lastTriggerMs: Long = 0L

        @JvmStatic
        fun resetDebounceForTest() {
            lastTriggerMs = 0L
        }
    }
}
