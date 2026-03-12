package com.evan8686.eewreceiver

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// 【新增】：用于表示单个数据源的数据结构
data class ApiSource(
    val name: String,
    val url: String,
    var isSelected: Boolean
)

object DataManager {
    private const val PREF_NAME = "eew_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ========== 配置相关 ==========

    // 阈值保存
    fun saveThreshold(context: Context, threshold: Float) {
        getPrefs(context).edit().putFloat("alert_threshold", threshold).apply()
    }
    fun getThreshold(context: Context): Float = getPrefs(context).getFloat("alert_threshold", 3.0f)

    // 数据源保存 (支持多选列表)
    fun saveSources(context: Context, sources: List<ApiSource>) {
        val json = Gson().toJson(sources)
        getPrefs(context).edit().putString("api_sources", json).apply()
    }

    // 获取数据源列表（如果没保存过，就返回你要求的默认 5 个，且默认勾选 CWA）
    fun getSources(context: Context): List<ApiSource> {
        val json = getPrefs(context).getString("api_sources", null)
        if (json != null) {
            val type = object : TypeToken<List<ApiSource>>() {}.type
            return Gson().fromJson(json, type)
        }
        return listOf(
            ApiSource("台湾中央气象署 (CWA)", "wss://ws-api.wolfx.jp/cwa_eew", true),
            ApiSource("中国地震台网 (CENC)", "wss://ws-api.wolfx.jp/cenc_eew", false),
            ApiSource("福建地震局 (FJ)", "wss://ws-api.wolfx.jp/fj_eew", false),
            ApiSource("四川地震局 (SC)", "wss://ws-api.wolfx.jp/sc_eew", false),
            ApiSource("东亚地区 (ALL)", "wss://ws-api.wolfx.jp/all_eew", false)
        )
    }

    // ========== 历史记录相关 ==========
    fun saveHistory(context: Context, eewData: EewData) {
        val history = getHistory(context).toMutableList()
        if (history.none { it.id == eewData.id }) {
            history.add(0, eewData)
            if (history.size > 50) history.removeLast()

            val json = Gson().toJson(history)
            getPrefs(context).edit().putString("eew_history", json).apply()
        }
    }

    fun getHistory(context: Context): List<EewData> {
        val json = getPrefs(context).getString("eew_history", null)
        return if (json != null) {
            val type = object : TypeToken<List<EewData>>() {}.type
            Gson().fromJson(json, type)
        } else {
            emptyList()
        }
    }
}