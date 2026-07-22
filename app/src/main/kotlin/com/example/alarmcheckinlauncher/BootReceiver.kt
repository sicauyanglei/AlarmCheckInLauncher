package com.example.alarmcheckinlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启：开机后启动 [GuardService]（前台保活服务）。
 *
 * 说明：
 *  - NotificationListenerService 本身由系统在用户授权后绑定，无法通过 startService 拉起。
 *    但可以通过 [GuardService] 间接保活：前台服务持续运行，定时调用
 *    NotificationListenerService.requestRebind() 触发系统重新绑定本应用的监听服务。
 *  - 需要 RECEIVE_BOOT_COMPLETED 权限，且 App 已被用户打开过至少一次（Android 限制）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent?.action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        FileLogger.init(context)
        FileLogger.i("BootReceiver 收到开机广播 action=${intent.action}")

        GuardService.start(context)
    }
}
