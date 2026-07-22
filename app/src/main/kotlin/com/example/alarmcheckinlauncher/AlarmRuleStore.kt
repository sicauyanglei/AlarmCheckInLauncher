package com.example.alarmcheckinlauncher

import android.content.Context
import androidx.core.content.edit
import com.example.alarmcheckinlauncher.AlarmRule.Companion.fromJsonArray
import com.example.alarmcheckinlauncher.AlarmRule.Companion.toJsonArrayString

/**
 * 闹钟规则持久化存储（永久保存，基于 SharedPreferences + JSON）。
 *
 * 存储结构：单个 key "rules" 保存 JSON 数组字符串。
 * 进程内用 @Volatile instance 单例 + 同步块保证线程安全。
 */
class AlarmRuleStore private constructor(private val prefs: android.content.SharedPreferences) {

    private var cache: List<AlarmRule>? = null

    @Synchronized
    fun all(): List<AlarmRule> {
        cache?.let { return it }
        val raw = prefs.getString(KEY_RULES, "") ?: ""
        val list = fromJsonArray(raw)
        cache = list
        return list
    }

    @Synchronized
    fun upsert(rule: AlarmRule) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        save(list)
    }

    @Synchronized
    fun delete(id: String) {
        val list = all().filterNot { it.id == id }
        save(list)
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val list = all().map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(list)
    }

    /** 找到与给定时间匹配且启用的规则，未找到返回 null */
    fun match(time: String): AlarmRule? =
        all().firstOrNull { it.enabled && it.time == time }

    private fun save(list: List<AlarmRule>) {
        cache = list
        prefs.edit { putString(KEY_RULES, list.toJsonArrayString()) }
    }

    companion object {
        private const val PREFS_NAME = "alarm_rules_prefs"
        private const val KEY_RULES = "rules"

        @Volatile private var instance: AlarmRuleStore? = null

        fun get(context: Context): AlarmRuleStore =
            instance ?: synchronized(this) {
                instance ?: AlarmRuleStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
