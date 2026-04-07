package com.evan8686.eewreceiver

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ApiSource(
    val name: String,
    val url: String,
    var isSelected: Boolean
)

object DataManager {
    private const val PREF_NAME = "eew_prefs"

    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ========== 配置相关 ==========

    fun saveThreshold(context: Context, threshold: Float) {
        getPrefs(context).edit().putFloat("alert_threshold", threshold).apply()
    }

    fun getThreshold(context: Context): Float =
        getPrefs(context).getFloat("alert_threshold", 3.0f)

    fun saveSources(context: Context, sources: List<ApiSource>) {
        val json = gson.toJson(sources)
        getPrefs(context).edit().putString("api_sources", json).apply()
    }

    fun getSources(context: Context): List<ApiSource> {
        val json = getPrefs(context).getString("api_sources", null)
        if (json != null) {
            val type = object : TypeToken<List<ApiSource>>() {}.type
            return gson.fromJson(json, type)
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

    @Synchronized
    fun saveHistory(context: Context, eewData: EewData) {
        val history = getHistory(context).toMutableList()

        val existingIndex = history.indexOfFirst { it.id == eewData.id }

        if (existingIndex != -1) {
            if (eewData.reportNum > history[existingIndex].reportNum) {
                history[existingIndex] = eewData
            } else {
                return
            }
        } else {
            history.add(0, eewData)
            if (history.size > 50) history.removeLast()
        }

        val json = gson.toJson(history)
        getPrefs(context).edit().putString("eew_history", json).apply()
    }

    // 🔑 修复：加 @Synchronized，防止写入过程中被同时读取导致数据错乱
    // 🔑 修复：加 try-catch，防止存储文件损坏时直接崩溃，降级返回空列表
    @Synchronized
    fun getHistory(context: Context): List<EewData> {
        return try {
            val json = getPrefs(context).getString("eew_history", null)
            if (json != null) {
                val type = object : TypeToken<List<EewData>>() {}.type
                gson.fromJson(json, type)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            // 存储数据损坏时，记录日志并返回空列表，保证 App 不崩溃
            Log.e("EEW_Receiver", "历史记录解析失败，已重置为空: ${e.message}")
            emptyList()
        }
    }
}
