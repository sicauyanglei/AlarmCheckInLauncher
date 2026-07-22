package com.example.alarmcheckinlauncher

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager

/**
 * 透明代理 Activity：
 *  - 闹钟响铃时由 [AlarmNotificationListener] 拉起
 *  - 唤醒屏幕（即使锁屏也能点亮）
 *  - 越过/解除锁屏（setShowWhenLocked / requestDismissKeyguard）
 *  - 启动目标打卡 App 并强制覆盖在闹钟全屏界面之上
 *  - 自身 finish()，用户只看到目标 App 界面
 *
 * 为什么需要代理 Activity？
 *  - Android 10+ 限制后台 Service 直接 startActivity 到前台，
 *    但 Activity startActivity 不受此限制。
 *  - 闹钟响铃时系统时钟 App 的全屏闹钟 Activity 在最顶层，
 *    且屏幕可能锁屏，需要 Activity 级别窗口标志来唤醒和解锁。
 */
class LaunchProxyActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var targetPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        if (targetPackage.isEmpty()) {
            FileLogger.w("LaunchProxyActivity: 目标包名为空，直接退出")
            finish()
            return
        }

        wakeUpScreen()
        showOverLockScreen()

        FileLogger.i("LaunchProxyActivity: 准备拉起 $targetPackage")

        // 若处于锁屏状态，先请求解除锁屏，再拉起目标 App
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            km.isKeyguardLocked
        } else {
            @Suppress("DEPRECATION")
            km.inKeyguardRestrictedInputMode()
        }

        if (isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+：请求系统解除锁屏（需 App 在前台，本代理 Activity 已在前台）
            FileLogger.d("LaunchProxyActivity: 检测到锁屏，请求解除锁屏")
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    FileLogger.i("LaunchProxyActivity: 锁屏已解除，开始拉起目标")
                    launchTargetAndBringToFront()
                }

                override fun onDismissError() {
                    FileLogger.w("LaunchProxyActivity: 解除锁屏失败，仍尝试拉起目标")
                    launchTargetAndBringToFront()
                }

                override fun onDismissCancelled() {
                    FileLogger.w("LaunchProxyActivity: 解除锁屏被取消，仍尝试拉起目标")
                    launchTargetAndBringToFront()
                }
            })
        } else {
            // 未锁屏或低版本，直接拉起
            launchTargetAndBringToFront()
        }
    }

    /** 启动目标 App，延迟后再次启动确保覆盖在闹钟全屏界面之上 */
    private fun launchTargetAndBringToFront() {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            FileLogger.w("LaunchProxyActivity: 目标 App 未安装或无启动入口: $targetPackage")
            finish()
            return
        }

        // 第一次启动：新任务，clear top
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_TASK_ON_HOME
        )
        try {
            startActivity(launchIntent)
            FileLogger.i("LaunchProxyActivity: 首次 startActivity 成功 $targetPackage")
        } catch (e: Exception) {
            FileLogger.e("LaunchProxyActivity: 首次 startActivity 失败 $targetPackage", e)
            finish()
            return
        }

        // 延迟 600ms 后再次启动，确保目标 App 覆盖在闹钟全屏界面之上
        // 目标 App 首次启动可能较慢，第二次启动会命中已存在的任务并带到前台
        handler.postDelayed({
            relaunchToFront()
        }, RELAUNCH_DELAY_MS)
    }

    /** 第二次启动目标 App，强制带到最前 */
    private fun relaunchToFront() {
        try {
            val relaunch = packageManager.getLaunchIntentForPackage(targetPackage)
            if (relaunch != null) {
                relaunch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                )
                startActivity(relaunch)
                FileLogger.i("LaunchProxyActivity: 二次拉起（REORDER_TO_FRONT）成功 $targetPackage")
            }
        } catch (e: Exception) {
            FileLogger.w("LaunchProxyActivity: 二次拉起失败（忽略，首次已启动）", e)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
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
        /** 首次启动后等待目标 App 初始化，再二次拉起确保到前台 */
        private const val RELAUNCH_DELAY_MS = 600L

        /** 创建启动 LaunchProxyActivity 的 Intent */
        fun createIntent(context: Context, targetPackage: String): Intent =
            Intent(context, LaunchProxyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            }
    }
}
