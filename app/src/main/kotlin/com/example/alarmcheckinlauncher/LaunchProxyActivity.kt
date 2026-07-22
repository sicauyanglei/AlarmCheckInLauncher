package com.example.alarmcheckinlauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * 全屏代理 Activity（最强方案）：
 *  - 闹钟响铃时由 [AlarmNotificationListener] 拉起
 *  - 唤醒屏幕、越过/解除锁屏
 *  - 全屏不透明覆盖闹钟界面和所有其他 App
 *  - 通过 ActivityManager 关闭其他 App 任务，确保目标 App 启动时栈最干净
 *  - 启动目标打卡 App，使其出现在最前面
 *  - finish() 后用户只看到目标 App
 *
 * 权限要求：
 *  - SYSTEM_ALERT_WINDOW：系统级覆盖窗口
 *  - REORDER_TASKS / MANAGE_TASKS：管理任务栈
 *  - WAKE_LOCK：唤醒屏幕
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
                    FileLogger.i("LaunchProxyActivity: 锁屏已解除")
                    launchTargetAfterDelay()
                }

                override fun onDismissError() {
                    FileLogger.w("LaunchProxyActivity: 解除锁屏失败，仍拉起目标")
                    launchTargetAfterDelay()
                }

                override fun onDismissCancelled() {
                    FileLogger.w("LaunchProxyActivity: 解除锁屏被取消，仍拉起目标")
                    launchTargetAfterDelay()
                }
            })
        } else {
            launchTargetAfterDelay()
        }
    }

    override fun onResume() {
        super.onResume()
        // decorView 在 onResume 时才保证初始化，在 onCreate 中调用会 NPE
        hideSystemUI()
    }

    /** 延迟 400ms 让本全屏界面稳定显示（覆盖闹钟），再清理任务+启动目标 */
    private fun launchTargetAfterDelay() {
        handler.postDelayed({
            closeOtherAppTasks()
            launchTarget()
        }, 400L)
    }

    /**
     * 关闭其他 App 的任务（如快手、闹钟全屏），让目标 App 启动时栈最干净。
     * 需要 REORDER_TASKS 权限（普通权限，安装即授予）。
     */
    private fun closeOtherAppTasks() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            // getRunningTasks 自 API 21 起只返回自己的任务，但尝试一下无妨
            val tasks = am.getRunningTasks(100)
            FileLogger.d("LaunchProxyActivity: 当前运行任务数=${tasks.size}")
            for (task in tasks) {
                val pkg = task.topActivity?.packageName
                if (pkg.isNullOrBlank()) continue
                // 不关闭自己（com.example.alarmcheckinlauncher）和目标 App
                if (pkg == packageName || pkg == targetPackage) continue
                FileLogger.d("LaunchProxyActivity: 尝试关闭任务 pkg=$pkg id=${task.id}")
                try {
                    am.moveTaskToFront(task.id, ActivityManager.MOVE_TASK_WITH_HOME)
                } catch (e: Exception) {
                    FileLogger.w("LaunchProxyActivity: moveTaskToFront 失败 pkg=$pkg", e)
                }
            }
        } catch (e: Exception) {
            FileLogger.w("LaunchProxyActivity: closeOtherAppTasks 失败（忽略）", e)
        }
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
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_TASK_ON_HOME
        )
        try {
            startActivity(launchIntent)
            FileLogger.i("LaunchProxyActivity: 目标 App 已启动: $targetPackage")
        } catch (e: Exception) {
            FileLogger.e("LaunchProxyActivity: 启动目标 App 失败: $targetPackage", e)
        }

        // 延迟 600ms finish，让目标 App 有时间稳定到前台
        handler.postDelayed({
            // 最后再尝试 moveTaskToFront 确保目标在最前
            tryMoveTargetToFront()
            finish()
        }, 600L)
    }

    /** 尝试通过 ActivityManager 把目标任务移到最前 */
    private fun tryMoveTargetToFront() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(100)
            val targetTask = tasks.firstOrNull { it.topActivity?.packageName == targetPackage }
            if (targetTask != null) {
                am.moveTaskToFront(targetTask.id, ActivityManager.MOVE_TASK_WITH_HOME)
                FileLogger.i("LaunchProxyActivity: 已将目标任务移到最前 id=${targetTask.id}")
            } else {
                FileLogger.w("LaunchProxyActivity: 未找到目标任务（可能仍在启动中）")
            }
        } catch (e: Exception) {
            FileLogger.w("LaunchProxyActivity: moveTaskToFront 失败（忽略）", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    /** 隐藏系统 UI（状态栏、导航栏），实现真全屏 */
    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    FileLogger.d("LaunchProxyActivity: 已隐藏系统UI")
                } else {
                    FileLogger.w("LaunchProxyActivity: insetsController 为 null，跳过隐藏系统UI")
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
                FileLogger.d("LaunchProxyActivity: 已隐藏系统UI")
            }
        } catch (e: Exception) {
            FileLogger.w("LaunchProxyActivity: 隐藏系统UI失败", e)
        }
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

    /** 设置越过锁屏 + 点亮屏幕的窗口标志（类似闹钟 App 的实现） */
    private fun showOverLockScreen() {
        // 所有版本都添加窗口标志，确保最大兼容性
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        // API 27+ 额外调用新方法（推荐方式）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
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

        /** 检查是否有悬浮窗权限（Android 6+ 需要运行时引导） */
        fun canDrawOverApps(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }

        /** 创建跳转悬浮窗权限设置页的 Intent */
        fun overlaySettingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            }
    }
}
