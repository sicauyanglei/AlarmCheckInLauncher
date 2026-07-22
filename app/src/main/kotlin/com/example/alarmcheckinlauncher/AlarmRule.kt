package com.example.alarmcheckinlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一条闹钟规则：指定时间点响铃时拉起哪个 App。
 *
 * @param id    规则唯一 id（UUID）
 * @param time  闹钟时间，24 小时制 "HH:mm"，用于与响铃通知文本里解析出的时间匹配
 * @param targetPackage 目标打卡 App 包名
 * @param appLabel 目标 App 显示名（仅用于 UI 展示，不参与匹配）
 * @param enabled 是否启用
 */
data class AlarmRule(
    val id: String = UUID.randomUUID().toString(),
    val time: String,
    val targetPackage: String,
    val appLabel: String = targetPackage,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("time", time)
        put("pkg", targetPackage)
        put("label", appLabel)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): AlarmRule = AlarmRule(
            id = o.optString("id", UUID.randomUUID().toString()),
            time = o.optString("time"),
            targetPackage = o.optString("pkg"),
            appLabel = o.optString("label", o.optString("pkg")),
            enabled = o.optBoolean("enabled", true)
        )

        fun fromJsonArray(s: String): List<AlarmRule> = try {
            if (s.isBlank()) emptyList()
            else JSONArray(s).let { arr -> (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) } }
        } catch (e: Exception) {
            emptyList()
        }

        fun List<AlarmRule>.toJsonArrayString(): String =
            JSONArray().also { arr -> forEach { arr.put(it.toJson()) } }.toString()
    }
}
