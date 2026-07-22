package com.example.alarmcheckinlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * 前台保活服务：
 *  - 启动后以低优先级前台通知常驻（Android 8+ 后台服务会被杀，前台服务存活率高得多）
 *  - 每 [CHECK_INTERVAL_MS] 检查一次 NotificationListenerService 是否已授权但未连接，
 *    若是则调用 requestRebind() 触发系统重新绑定监听服务（API 24+）
 *  - 开机后由 [BootReceiver] 拉起；App 内 MainActivity 启动时也会拉起
 *
 * 说明：Android 系统对前台服务存活率较高，但极端省电模式下仍可能被杀。
 * 用户需把本 App 加入电池优化白名单（「不受电池优化限制」）才能最大化存活。
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            ensureListenerRebind()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.i("GuardService onCreate")
        ensureChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.i("GuardService onStartCommand")
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, INIT_DELAY_MS)
        // START_STICKY：被杀后系统会尝试重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        FileLogger.w("GuardService onDestroy（可能被系统杀死）")
        handler.removeCallbacks(checkRunnable)
    }

    /** 检查监听服务授权状态，授权了但未连接时请求重绑 */
    private fun ensureListenerRebind() {
        try {
            val cn = ComponentName(this, AlarmNotificationListener::class.java)
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
            val authorized = flat.split(":").any { it == cn.flattenToString() }
            if (!authorized) {
                FileLogger.d("GuardService: 监听服务未授权，跳过重绑")
                return
            }
            // 已授权但可能未连接，请求系统重新绑定。
            // NotificationListenerService.requestRebind(ComponentName) 是 @SystemApi 隐藏方法，
            // 公开 SDK 中不可见，需通过反射调用（在浅灰名单中，允许反射）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val clazz = Class.forName("android.service.notification.NotificationListenerService")
                    val method = clazz.getMethod("requestRebind", ComponentName::class.java)
                    method.invoke(null, cn)
                    FileLogger.d("GuardService: 已通过反射调用 requestRebind 请求重连监听服务")
                } catch (e: NoSuchMethodException) {
                    FileLogger.w("GuardService: requestRebind 方法不存在（ROM 可能精简），跳过重绑")
                }
            }
        } catch (e: Exception) {
            FileLogger.w("GuardService 重绑检查失败", e)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "保活服务",
                        NotificationManager.IMPORTANCE_MIN
                    ).apply { description = "保持闹钟监听在后台运行" }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat() {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("提醒打卡正在后台运行")
            .setContentText("闹钟响铃时会自动拉起打卡 App")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "guard_keep_alive"
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L  // 每 5 分钟检查一次
        private const val INIT_DELAY_MS = 10 * 1000L          // 启动后 10 秒首次检查

        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                FileLogger.i("GuardService 已请求启动")
            } catch (e: Exception) {
                FileLogger.e("GuardService 启动失败", e)
            }
        }
    }
}
