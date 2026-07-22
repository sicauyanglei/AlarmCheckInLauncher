package com.example.alarmcheckinlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
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
 *
 * 日志策略：
 *  - 每条来自时钟 App 的通知、判断结果、拉起动作都写入 [FileLogger]（持久化）+ logcat（实时）。
 *  - 拉起成功/失败时额外弹一条本地通知，便于用户直观看到「是否触发」。
 */
class AlarmNotificationListener : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.i("AlarmNotificationListener onCreate")
        ensureLogChannel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        FileLogger.init(this)
        FileLogger.i("NotificationListener 已连接，开始监听通知")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        FileLogger.w("NotificationListener 已断开（可能被系统/用户关闭授权）")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val pkg = sbn.packageName ?: return

        val n = sbn.notification
        val category = n?.category
        val extras = n?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val ticker = n?.tickerText?.toString().orEmpty()

        // CATEGORY_ALARM 类通知即使不在已知时钟列表也记录日志（方便发现新厂商时钟包名）
        val isAlarmCategory = category == Notification.CATEGORY_ALARM
        if (isClockApp(pkg) || isAlarmCategory) {
            FileLogger.d("收到通知 pkg=$pkg category=$category title=\"$title\" text=\"$text\" ticker=\"$ticker\"")
        }

        // 非时钟 App 且非 CATEGORY_ALARM 直接忽略
        if (!isClockApp(pkg) && !isAlarmCategory) return

        val prefs = AppPreferences.get(this)
        if (!prefs.enabled) {
            FileLogger.d("已暂停监听（enabled=false），跳过")
            return
        }

        if (n == null || !isAlarmRinging(n, pkg)) {
            FileLogger.d("非闹钟响铃通知，忽略 pkg=$pkg")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < DEBOUNCE_MS) {
            FileLogger.d("闹钟事件去抖（${now - lastTriggerMs}ms < ${DEBOUNCE_MS}ms），跳过拉起")
            return
        }
        lastTriggerMs = now

        FileLogger.i(">>> 检测到闹钟响铃 pkg=$pkg title=\"$title\"")
        // 解析通知文本中的时间，匹配规则；无规则或未命中则拉起默认目标 App（com.byd.moaais）
        val time = parseAlarmTime(title, text, ticker)
        FileLogger.i("解析时间=$time 文本 title=\"$title\" text=\"$text\"")
        val rules = AlarmRuleStore.get(this).all()
        val rule = time?.let { t -> rules.firstOrNull { it.enabled && it.time == t } }
        val target = rule?.targetPackage ?: prefs.targetPackage
        val targetLabel = rule?.appLabel ?: target
        if (rule != null) {
            FileLogger.i("命中规则 time=${rule.time} pkg=${rule.targetPackage}")
        } else if (rules.isEmpty()) {
            FileLogger.i("未设置任何闹钟规则，使用默认目标 pkg=$target")
        } else {
            FileLogger.i("未命中规则（共 ${rules.size} 条），使用默认目标 pkg=$target")
        }
        pushLocalNotification("闹钟响铃(${time ?: "未知时间"}) → 拉起 $targetLabel")

        // 关键：先取消闹钟通知，关闭闹钟全屏 Activity
        // 荣耀闹钟全屏 Activity 由通知的 fullscreen intent 触发，
        // 取消通知后系统会关闭全屏 Activity，目标 App 才能显示到最前面
        try {
            val key = sbn.key
            cancelNotification(key)
            FileLogger.i("已取消闹钟通知（关闭全屏界面）key=$key")
        } catch (e: Exception) {
            FileLogger.w("取消闹钟通知失败（忽略，继续拉起）", e)
        }

        // 延迟 800ms 让闹钟全屏 Activity 完全关闭，再启动目标 App
        // 用 LaunchProxyActivity：能设置锁屏标志、主动解除锁屏、全屏覆盖
        mainHandler.postDelayed({
            launchTarget(target)
        }, 800L)
    }

    /**
     * 从通知文本里解析闹钟时间，返回 "HH:mm"（24 小时制），解析失败返回 null。
     * 兼容常见格式：「07:00」「7:00」「7:00 AM」「07:00 闹钟」「闹钟 上午 7:00」等。
     */
    private fun parseAlarmTime(vararg texts: String?): String? {
        val combined = texts.joinToString(" ") { it.orEmpty() }
        // 1) 优先匹配带 AM/PM 或 上午/下午 的 12 小时制
        val ampmMatch = Regex("(?i)(\\d{1,2}):(\\d{2})\\s*(AM|PM|上午|下午)").find(combined)
        if (ampmMatch != null) {
            val h = ampmMatch.groupValues[1].toIntOrNull() ?: return null
            val m = ampmMatch.groupValues[2].toIntOrNull() ?: return null
            val suffix = ampmMatch.groupValues[3].lowercase()
            var hour = h
            val isPm = suffix == "pm" || suffix == "下午"
            when {
                h == 12 && !isPm -> hour = 0
                h != 12 && isPm -> hour = h + 12
            }
            return String.format("%02d:%02d", hour, m)
        }
        // 2) 匹配 24 小时制 HH:mm
        val match = Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(combined) ?: return null
        val h = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].toIntOrNull() ?: return null
        if (h > 23 || m > 59) return null
        return String.format("%02d:%02d", h, m)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 闹钟通知移除（用户关闭闹钟）时不做处理
    }

    private fun launchTarget(targetPackage: String) {
        if (TextUtils.isEmpty(targetPackage)) {
            FileLogger.w("目标包名为空，跳过拉起（请在 App 内配置打卡 App 包名）")
            pushLocalNotification("未拉起：目标包名为空，请在「提醒打卡」内配置")
            return
        }
        // 先检查目标 App 是否已安装
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            FileLogger.w("目标 App 未安装或无启动入口: $targetPackage")
            pushLocalNotification("未拉起：$targetPackage 未安装或无启动入口")
            return
        }

        // 通过 LaunchProxyActivity 拉起目标 App：
        //  - Activity 能设置锁屏标志（FLAG_SHOW_WHEN_LOCKED 等），覆盖在锁屏之上
        //  - 主动 requestDismissKeyguard 解除锁屏
        //  - 全屏不透明覆盖任何残留界面
        //  - 有 SYSTEM_ALERT_WINDOW 权限可从后台启动 Activity
        if (!LaunchProxyActivity.canDrawOverApps(this)) {
            FileLogger.w("悬浮窗权限未开启，目标 App 可能无法到前台！请在 App 设置中开启悬浮窗权限")
        }
        try {
            val proxyIntent = LaunchProxyActivity.createIntent(this, targetPackage)
            startActivity(proxyIntent)
            FileLogger.i("已通过 LaunchProxyActivity 拉起: $targetPackage")
            pushLocalNotification("已拉起 $targetPackage")
        } catch (e: Exception) {
            FileLogger.e("拉起失败: $targetPackage", e)
            pushLocalNotification("拉起失败: ${e.javaClass.simpleName}: ${e.message}")
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
            FileLogger.d("匹配规则: CATEGORY_ALARM")
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
        val matched = texts.firstOrNull { !it.isNullOrBlank() && ALARM_PATTERN.containsMatchIn(it) }
        if (matched != null) {
            FileLogger.d("匹配规则: 文本命中=\"$matched\"")
            return true
        }
        return false
    }

    // ---------- 本地通知（让用户直观看到触发结果） ----------

    private fun ensureLogChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "触发日志",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "显示闹钟检测与拉起结果" }
                )
            }
        }
    }

    private fun pushLocalNotification(msg: String) {
        FileLogger.i("[Notify] $msg")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("提醒打卡")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, notif)
        } catch (e: Exception) {
            FileLogger.w("弹本地通知失败", e)
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 10_000L
        private const val CHANNEL_ID = "trigger_log"
        private const val NOTIF_ID = 1001

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
            "com.huawei.clock",
            "com.hihonor.clock",
            "com.hihonor.deskclock"
        )

        @Volatile private var lastTriggerMs: Long = 0L

        @JvmStatic
        fun resetDebounceForTest() {
            lastTriggerMs = 0L
        }
    }
}
