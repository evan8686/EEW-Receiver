package com.evan8686.eewreceiver

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson

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

    // 🚨 提取默认源列表为私有方法，防止重复代码，方便容灾重置时统一调用
    private fun getDefaultSources() = listOf(
        ApiSource("台湾中央气象署 (CWA)", "wss://ws-api.wolfx.jp/cwa_eew", true),
        ApiSource("中国地震台网 (CENC)", "wss://ws-api.wolfx.jp/cenc_eew", false),
        ApiSource("福建地震局 (FJ)", "wss://ws-api.wolfx.jp/fj_eew", false),
        ApiSource("四川地震局 (SC)", "wss://ws-api.wolfx.jp/sc_eew", false),
        ApiSource("东亚地区 (ALL)", "wss://ws-api.wolfx.jp/all_eew", false)
    )

    // 🚨 加上 @Synchronized 锁，防止多线程并发保存时文件损坏
    @Synchronized
    fun saveSources(context: Context, sources: List<ApiSource>) {
        val json = gson.toJson(sources)
        getPrefs(context).edit().putString("api_sources", json).apply()
    }

    // 🚨 加上锁和 try-catch 容灾兜底：哪怕本地 JSON 损坏、混淆被擦除，也绝不闪退！
    @Synchronized
    fun getSources(context: Context): List<ApiSource> {
        return try {
            val json = getPrefs(context).getString("api_sources", null)
            if (json != null) {
                // 🚨 核心修复：降维打击，直接用实体数组 Array 接收解析，彻底无视泛型擦除
                val array = gson.fromJson(json, Array<ApiSource>::class.java)
                array.toList() // 顺手转回 List 喂给外部调用者
            } else {
                getDefaultSources()
            }
        } catch (e: Exception) {
            // 存储数据损坏时（如混淆导致字段错乱），清空"毒数据"，自动重置为默认值，App 强行续命活下来
            Log.e("EEW_Receiver", "订阅源数据解析失败，已重置为默认值: ${e.message}")
            getPrefs(context).edit().remove("api_sources").apply()
            getDefaultSources()
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
}