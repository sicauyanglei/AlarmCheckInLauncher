package com.example.alarmcheckinlauncher

import android.content.Context
import androidx.core.content.edit

/**
 * GitHub 上传配置（永久保存在应用私有 SharedPreferences，不进仓库）。
 *
 * 简化模式：只需保存 Token，其余（owner/repo/branch/path）由 App 自动推断。
 *  - token:  具有 repo 写权限的 Personal Access Token
 *  - owner:  通过 /user API 用 Token 自动获取并缓存
 *  - repo:   固定为 AlarmCheckInLauncher（本项目仓库）
 *  - branch: main
 *  - path:   logs/alarm_checkin.log
 */
class GithubSettings private constructor(
    private val prefs: android.content.SharedPreferences
) {
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit { putString(KEY_TOKEN, v.trim()) }

    /** 通过 Token 自动获取并缓存的用户名；未获取过为空 */
    var owner: String
        get() = prefs.getString(KEY_OWNER, "") ?: ""
        set(v) = prefs.edit { putString(KEY_OWNER, v.trim()) }

    val repo: String get() = "AlarmCheckInLauncher"
    val branch: String get() = "main"
    val path: String get() = "logs/alarm_checkin.log"

    /** 是否已具备上传条件（Token 已填且 owner 已自动获取） */
    fun isComplete(): Boolean = token.isNotEmpty() && owner.isNotEmpty()

    companion object {
        private const val PREFS_NAME = "github_settings"
        private const val KEY_TOKEN = "token"
        private const val KEY_OWNER = "owner"

        @Volatile private var instance: GithubSettings? = null

        fun get(context: Context): GithubSettings =
            instance ?: synchronized(this) {
                instance ?: GithubSettings(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
