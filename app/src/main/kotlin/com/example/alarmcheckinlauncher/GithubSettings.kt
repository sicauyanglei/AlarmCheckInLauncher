package com.example.alarmcheckinlauncher

import android.content.Context
import androidx.core.content.edit

/**
 * GitHub 上传配置（永久保存在应用私有 SharedPreferences，不进仓库）。
 *
 * - token:  具有 repo 写权限的 Personal Access Token
 * - owner:  仓库所有者（用户名）
 * - repo:   仓库名
 * - branch: 目标分支
 * - path:   日志文件在仓库内的路径（如 logs/alarm_checkin.log）
 */
class GithubSettings private constructor(
    private val prefs: android.content.SharedPreferences
) {
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit { putString(KEY_TOKEN, v.trim()) }

    var owner: String
        get() = prefs.getString(KEY_OWNER, "") ?: ""
        set(v) = prefs.edit { putString(KEY_OWNER, v.trim()) }

    var repo: String
        get() = prefs.getString(KEY_REPO, "") ?: ""
        set(v) = prefs.edit { putString(KEY_REPO, v.trim()) }

    var branch: String
        get() = prefs.getString(KEY_BRANCH, "main") ?: "main"
        set(v) = prefs.edit { putString(KEY_BRANCH, v.trim().ifEmpty { "main" }) }

    var path: String
        get() = prefs.getString(KEY_PATH, "logs/alarm_checkin.log") ?: "logs/alarm_checkin.log"
        set(v) = prefs.edit { putString(KEY_PATH, v.trim().ifEmpty { "logs/alarm_checkin.log" }) }

    /** 关键字段是否都已配置 */
    fun isComplete(): Boolean =
        token.isNotEmpty() && owner.isNotEmpty() && repo.isNotEmpty()

    companion object {
        private const val PREFS_NAME = "github_settings"
        private const val KEY_TOKEN = "token"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        private const val KEY_PATH = "path"

        @Volatile private var instance: GithubSettings? = null

        fun get(context: Context): GithubSettings =
            instance ?: synchronized(this) {
                instance ?: GithubSettings(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
