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
 * 全屏代理 Activity：
 *  - 闹钟响铃时由 [AlarmNotificationListener] 拉起
 *  - 唤醒屏幕（即使锁屏也能点亮）
 *  - 越过/解除锁屏
 *  - 以全屏不透明界面覆盖闹钟全屏 Activity 和其他 App（如快手）
 *  - 从全屏界面启动目标打卡 App，目标 App 出现在本界面之上 = 最前面
 *  - 启动目标后延迟 finish()，让目标 App 稳定到前台
 *
 * 为什么用全屏不透明而非透明？
 *  - 荣耀等厂商闹钟全屏 Activity 有系统级高优先级，透明 Activity 无法覆盖
 *  - 全屏不透明 Activity 配合最高窗口标志，能可靠覆盖闹钟界面
 *  - 目标 App 从本界面启动，必然在本界面之上 = 真正的最前面
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
        hideSystemUI()

        FileLogger.i("LaunchProxyActivity: 全屏覆盖启动，准备拉起 $targetPackage")

        // 检测锁屏状态，先解锁再拉起
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            km.isKeyguardLocked
        } else {
            @Suppress("DEPRECATION")
            km.inKeyguardRestrictedInputMode()
        }

        if (isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            FileLogger.d("LaunchProxyActivity: 检测到锁屏，请求解除锁屏")
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    FileLogger.i("LaunchProxyActivity: 锁屏已解除，开始拉起目标")
                    launchTargetAfterDelay()
                }

                override fun onDismissError() {
                    FileLogger.w("LaunchProxyActivity: 解除锁屏失败，仍尝试拉起目标")
                    launchTargetAfterDelay()
                }

                override fun onDismissCancelled() {
                    FileLogger.w("LaunchProxyActivity: 解除锁屏被取消，仍尝试拉起目标")
                    launchTargetAfterDelay()
                }
            })
        } else {
            launchTargetAfterDelay()
        }
    }

    /** 延迟 300ms 让本全屏界面稳定显示（覆盖闹钟），再启动目标 App */
    private fun launchTargetAfterDelay() {
        handler.postDelayed({
            launchTarget()
        }, 300L)
    }

    /** 启动目标 App，使其出现在本全屏界面之上 = 最前面 */
    private fun launchTarget() {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            FileLogger.w("LaunchProxyActivity: 目标 App 未安装或无启动入口: $targetPackage")
            finish()
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        try {
            startActivity(launchIntent)
            FileLogger.i("LaunchProxyActivity: 目标 App 已启动到前台: $targetPackage")
        } catch (e: Exception) {
            FileLogger.e("LaunchProxyActivity: 启动目标 App 失败: $targetPackage", e)
        }

        // 延迟 finish，让目标 App 有时间稳定到前台
        handler.postDelayed({
            finish()
        }, 500L)
    }

    /** 隐藏系统 UI（状态栏、导航栏），实现真全屏 */
    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
            }
            FileLogger.d("LaunchProxyActivity: 已隐藏系统UI，全屏显示")
        } catch (e: Exception) {
            FileLogger.w("LaunchProxyActivity: 隐藏系统UI失败", e)
        }
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
            wakeLock.acquire(5_000L)
            FileLogger.d("LaunchProxyActivity: 已唤醒屏幕")
        } catch (e: Exception) {
            FileLogger.w("LaunchProxyActivity: 唤醒屏幕失败", e)
        }
    }

    /** 设置越过锁屏 + 点亮屏幕的窗口标志 */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
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
