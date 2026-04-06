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

    // 🚨 核心修复 1：将 Gson 声明为单例
    // 避免频繁创建重量级对象导致的 CPU 浪费和系统 GC 卡顿
    private val gson = Gson()

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
        val json = gson.toJson(sources) // 复用单例
        getPrefs(context).edit().putString("api_sources", json).apply()
    }

    // 获取数据源列表（如果没保存过，就返回你要求的默认 5 个，且默认勾选 CWA）
    fun getSources(context: Context): List<ApiSource> {
        val json = getPrefs(context).getString("api_sources", null)
        if (json != null) {
            val type = object : TypeToken<List<ApiSource>>() {}.type
            return gson.fromJson(json, type) // 复用单例
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

    // 🚨 核心修复 2：添加 @Synchronized 并发锁
    // 防止多个 WebSocket 数据源同时接收到报文时，发生交叉写入导致记录丢失
    @Synchronized
    fun saveHistory(context: Context, eewData: EewData) {
        val history = getHistory(context).toMutableList()

        // 查找历史记录中是否已经存过这个 ID 的地震
        val existingIndex = history.indexOfFirst { it.id == eewData.id }

        if (existingIndex != -1) {
            // 如果找到了，且新报数更大，则覆盖更新
            if (eewData.reportNum > history[existingIndex].reportNum) {
                history[existingIndex] = eewData
            } else {
                // 如果是旧报文或重复报文，直接跳过，不执行保存
                return
            }
        } else {
            // 如果没找到，说明是一个全新的地震，插到列表最前面
            history.add(0, eewData)
            // 依然保持最多存 50 条记录
            if (history.size > 50) history.removeLast()
        }

        // 将更新后的列表保存回本地存储
        val json = gson.toJson(history) // 复用单例
        getPrefs(context).edit().putString("eew_history", json).apply()
    }

    fun getHistory(context: Context): List<EewData> {
        val json = getPrefs(context).getString("eew_history", null)
        return if (json != null) {
            val type = object : TypeToken<List<EewData>>() {}.type
            gson.fromJson(json, type) // 复用单例
        } else {
            emptyList()
        }
    }
}
