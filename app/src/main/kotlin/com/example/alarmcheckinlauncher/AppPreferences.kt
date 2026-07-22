package com.example.alarmcheckinlauncher

import android.content.Context
import androidx.core.content.edit

/**
 * 轻量 SharedPreferences 封装，保存用户配置：
 *  - 目标打卡 App 包名
 *  - 是否启用闹钟监听
 */
class AppPreferences private constructor(private val prefs: android.content.SharedPreferences) {

    var targetPackage: String
        get() = prefs.getString(KEY_TARGET_PKG, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TARGET_PKG, value.trim()) }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 启动权限引导是否已完成（避免每次启动都弹窗打扰用户） */
    var startupPermissionPrompted: Boolean
        get() = prefs.getBoolean(KEY_STARTUP_PERMISSION_PROMPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_STARTUP_PERMISSION_PROMPTED, value) }

    companion object {
        private const val PREFS_NAME = "alarm_checkin_prefs"
        private const val KEY_TARGET_PKG = "target_package"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STARTUP_PERMISSION_PROMPTED = "startup_permission_prompted"

        @Volatile private var instance: AppPreferences? = null

        fun get(context: Context): AppPreferences =
            instance ?: synchronized(this) {
                instance ?: AppPreferences(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
