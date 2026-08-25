package com.evan8686.eewreceiver

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson

data class ApiSource(
    val name: String,
    val url: String,
    var isSelected: Boolean,
    val isPredefined: Boolean = false, // 🚨 新增：用于识别是否为系统预置节点，不再依赖 index
    val isHidden: Boolean = false // 🚨 新增：用于标记是否隐藏节点
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

    // ========== 用户坐标相关 ==========

    /** 保存用户纬度，key: user_latitude */
    fun saveLatitude(context: Context, lat: Double) {
        getPrefs(context).edit().putFloat("user_latitude", lat.toFloat()).apply()
    }

    /** 读取用户纬度，默认值 Double.NaN 表示未设置 */
    fun getLatitude(context: Context): Double {
        val raw = getPrefs(context).getFloat("user_latitude", Float.NaN)
        return if (raw.isNaN()) Double.NaN else raw.toDouble()
    }

    /** 保存用户经度，key: user_longitude */
    fun saveLongitude(context: Context, lon: Double) {
        getPrefs(context).edit().putFloat("user_longitude", lon.toFloat()).apply()
    }

    /** 读取用户经度，默认值 Double.NaN 表示未设置 */
    fun getLongitude(context: Context): Double {
        val raw = getPrefs(context).getFloat("user_longitude", Float.NaN)
        return if (raw.isNaN()) Double.NaN else raw.toDouble()
    }

    // ========== FAN API 警告相关 ==========

    fun saveFanWarningDismissed(context: Context, dismissed: Boolean) {
        getPrefs(context).edit().putBoolean("fan_warning_dismissed", dismissed).apply()
    }

    fun isFanWarningDismissed(context: Context): Boolean =
        getPrefs(context).getBoolean("fan_warning_dismissed", false)

    // ========== 本地预估烈度阈值相关 ==========

    /**
     * 保存本地预估烈度触发阈值
     * 0 表示不启用（仍使用震级阈值逻辑），1-12 表示本地预估烈度达到该值时触发
     * key: local_intensity_threshold
     */
    fun saveLocalIntensityThreshold(context: Context, value: Int) {
        getPrefs(context).edit().putInt("local_intensity_threshold", value).apply()
    }

    /**
     * 读取本地预估烈度触发阈值
     * 默认值为 0（不启用）
     */
    fun getLocalIntensityThreshold(context: Context): Int =
        getPrefs(context).getInt("local_intensity_threshold", 0)

    // 🚨 提取默认源列表，并将 isPredefined 设为 true
    private fun getDefaultSources() = listOf(
        ApiSource("台湾地区中央气象署 (CWA, Wolfx API)", "wss://ws-api.wolfx.jp/cwa_eew", true, true),
        ApiSource("中国地震预警网 (CEA, Wolfx API)", "wss://ws-api.wolfx.jp/cenc_eew", false, true),
        ApiSource("台湾地区中央气象署 (CWA, FAN API)", "wss://ws.fanstudio.tech/cwa-eew", false, true, isHidden = true),
        ApiSource("中国地震预警网 (CEA, FAN API)", "wss://ws.fanstudio.tech/cea", false, true, isHidden = true),
        ApiSource("福建地震局 (FJ, Wolfx API)", "wss://ws-api.wolfx.jp/fj_eew", false, true),
        ApiSource("四川地震局 (SC, Wolfx API)", "wss://ws-api.wolfx.jp/sc_eew", false, true),
        ApiSource("日本気象庁 (JMA, Wolfx API)", "wss://ws-api.wolfx.jp/jma_eew", false, true),
        ApiSource("监控以上所有Wolfx节点 (勾选此项时建议取消上方选项)", "wss://ws-api.wolfx.jp/all_eew", false, true)
    )

    // 🚨 加上 @Synchronized 锁，防止多线程并发保存时文件损坏
    @Synchronized
    fun saveSources(context: Context, sources: List<ApiSource>) {
        val json = gson.toJson(sources)
        getPrefs(context).edit().putString("api_sources", json).apply()
    }

    /**
     * 核心重构：实现“代码骨架 + 存储记忆”的智能合并逻辑
     * 1. 以当前代码中定义的最新 getDefaultSources() 为准
     * 2. 如果存过相同的 URL，则继承用户之前的 isSelected 状态
     * 3. 自动保留用户添加的自定义源
     */
    @Synchronized
    fun getSources(context: Context): List<ApiSource> {
        val latestPredefined = getDefaultSources()
        
        return try {
            val json = getPrefs(context).getString("api_sources", null)
            if (json == null) {
                latestPredefined
            } else {
                val savedList = gson.fromJson(json, Array<ApiSource>::class.java).toList()
                
                // 1. 建立 URL -> isSelected 的快速映射表
                val selectionMap = savedList.associate { it.url to it.isSelected }
                
                // 2. 更新最新预置列表的状态（保留用户勾选，同时自动识别新节点和新名称）
                val mergedPredefined = latestPredefined.map { predefined ->
                    val wasSelected = selectionMap[predefined.url] ?: predefined.isSelected
                    // 🚨 核心逻辑：isHidden 必须以代码中最新的定义为准，强制覆盖存储中的旧状态
                    predefined.copy(isSelected = wasSelected)
                }
                
                // 3. 提取旧数据中的自定义节点（不属于预置 URL 的项）
                val predefinedUrls = latestPredefined.map { it.url }.toSet()
                val customSources = savedList.filter { it.url !in predefinedUrls && !it.isPredefined }
                
                // 4. 合并并返回
                mergedPredefined + customSources
            }
        } catch (e: Exception) {
            Log.e("EEW_Receiver", "解析订阅源失败，恢复默认值: ${e.message}")
            latestPredefined
        }
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
            // 🚨 核心修复：替换为兼容旧安卓版本的 removeAt 写法
            if (history.size > 50) history.removeAt(history.lastIndex)
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
                // 🚨 核心修复：历史记录同样改用 Array 接收解析，防止历史记录列表被误杀清空
                val array = gson.fromJson(json, Array<EewData>::class.java)
                array.toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            // 存储数据损坏时，记录日志并返回空列表，保证 App 不崩溃
            Log.e("EEW_Receiver", "历史记录解析失败，已重置为空: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun deleteHistoryItem(context: Context, id: String) {
        val history = getHistory(context).toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index != -1) {
            history.removeAt(index)
            val json = gson.toJson(history)
            getPrefs(context).edit().putString("eew_history", json).apply()
        }
    }
}