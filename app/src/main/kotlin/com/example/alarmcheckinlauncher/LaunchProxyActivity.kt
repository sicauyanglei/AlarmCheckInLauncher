package com.example.alarmcheckinlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager

/**
 * 透明代理 Activity：
 *  - 闹钟响铃时由 [AlarmNotificationListener] 拉起
 *  - 唤醒屏幕（即使锁屏也能点亮）
 *  - 越过锁屏显示（setShowWhenLocked / FLAG_SHOW_WHEN_LOCKED）
 *  - 启动目标打卡 App 到前台
 *  - 自身立即 finish()，用户只看到目标 App 界面
 *
 * 为什么需要代理 Activity？
 *  - Android 10+ 限制后台 Service 直接 startActivity 到前台，
 *    但 Activity startActivity 不受此限制。
 *  - 闹钟响铃时屏幕可能熄灭/锁屏，需要 Activity 级别的窗口标志来唤醒。
 */
class LaunchProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        if (targetPackage.isEmpty()) {
            FileLogger.w("LaunchProxyActivity: 目标包名为空，直接退出")
            finish()
            return
        }

        wakeUpScreen()
        showOverLockScreen()

        FileLogger.i("LaunchProxyActivity: 准备拉起 $targetPackage")
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            FileLogger.w("LaunchProxyActivity: 目标 App 未安装或无启动入口: $targetPackage")
            finish()
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try {
            startActivity(launchIntent)
            FileLogger.i("LaunchProxyActivity: 拉起成功 $targetPackage")
        } catch (e: Exception) {
            FileLogger.e("LaunchProxyActivity: 拉起失败 $targetPackage", e)
        }

        // 立即消失，不留下透明界面
        finish()
    }

    /** 点亮屏幕（闹钟响铃时屏幕可能熄灭） */
    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(flags, "AlarmCheckIn:LaunchProxy")
            wakeLock.acquire(5_000L) // 5 秒后自动释放
            FileLogger.d("LaunchProxyActivity: 已唤醒屏幕")
        } catch (e: Exception) {
            FileLogger.w("LaunchProxyActivity: 唤醒屏幕失败", e)
        }
    }

    /** 设置越过锁屏 + 点亮屏幕的窗口标志 */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+ 推荐方式
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // API < 27 回退到 window flags
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    companion object {
        private const val EXTRA_TARGET_PACKAGE = "target_package"

        /** 创建启动 LaunchProxyActivity 的 Intent */
        fun createIntent(context: Context, targetPackage: String): Intent =
            Intent(context, LaunchProxyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            }
    }
}
