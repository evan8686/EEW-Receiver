package com.evan8686.eewreceiver

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

/**
 * 地震相关核心计算工具类
 * 所有方法在数据缺失或异常时返回 null，不抛出异常
 */
object EarthquakeCalculator {

    private const val TAG = "EarthquakeCalculator"

    // 用于解析发震时间字符串的格式（兼容常见格式）
    private val TIME_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss"
    )

    // ================================================================
    // 3a. 用户与震中距离计算（Haversine 公式）
    // ================================================================

    /**
     * 使用 Haversine 公式计算用户与震中之间的大圆距离
     * @param userLat 用户纬度（度）
     * @param userLon 用户经度（度）
     * @param epicenterLat 震中纬度（度）
     * @param epicenterLon 震中经度（度）
     * @return 距离（公里，保留 1 位小数），数据异常时返回 null
     */
    fun calculateDistance(
        userLat: Double,
        userLon: Double,
        epicenterLat: Double,
        epicenterLon: Double
    ): Double? {
        return try {
            // 输入有效性检查
            if (userLat.isNaN() || userLon.isNaN() ||
                epicenterLat.isNaN() || epicenterLon.isNaN()) {
                Log.w(TAG, "距离计算：输入坐标含 NaN，跳过计算")
                return null
            }

            val earthRadiusKm = 6371.0 // 地球平均半径（公里）

            // 将角度转换为弧度
            val dLat = Math.toRadians(epicenterLat - userLat)
            val dLon = Math.toRadians(epicenterLon - userLon)
            val lat1Rad = Math.toRadians(userLat)
            val lat2Rad = Math.toRadians(epicenterLat)

            // Haversine 公式核心
            val a = sin(dLat / 2).pow(2.0) +
                    cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2.0)
            val c = 2 * asin(sqrt(a))
            val distanceKm = earthRadiusKm * c

            // 保留 1 位小数返回
            val result = (distanceKm * 10).roundToInt() / 10.0
            Log.d(TAG, "距离计算结果：${result} km")
            result
        } catch (e: Exception) {
            Log.e(TAG, "距离计算异常：${e.message}")
            null
        }
    }

    // ================================================================
    // 3b. 本地预估烈度计算
    // ================================================================

    /**
     * 根据震级和震中距估算用户所在地的地震烈度
     * 公式采用"全国平均长轴烈度"与"全国平均短轴烈度"的等效圆均值
     *
     * @param magnitude 震级 M
     * @param distanceKm 震中距 R（公里）
     * @return 预估烈度整数（范围 1~12），数值不足 1 时返回 1
     */
    fun calculateLocalIntensity(
        magnitude: Double,
        distanceKm: Double
    ): Double {
        return try {
            // 全国平均长轴烈度（自然对数 ln）
            val ia = 5.845 + 1.509 * magnitude - 2.095 * ln(distanceKm + 25.0)

            // 全国平均短轴烈度（自然对数 ln）
            val ib = 2.779 + 1.399 * magnitude - 1.468 * ln(distanceKm + 7.0)

            // 等效圆均值取平均，四舍五入并限制在 [1, 12]
            val avg = (ia + ib) / 2.0
            val rounded = avg.roundToInt()
            val clamped = rounded.coerceIn(1, 12)

            Log.d(TAG, "烈度计算：M=$magnitude R=${distanceKm}km → Ia=${"%.2f".format(ia)} Ib=${"%.2f".format(ib)} → 预估烈度=$clamped")
            clamped.toDouble()
        } catch (e: Exception) {
            Log.e(TAG, "烈度计算异常：${e.message}")
            // 发生异常时兜底返回 1（最小烈度）
            1.0
        }
    }

    // ================================================================
    // 3c. P 波到达倒计时计算
    // ================================================================

    /**
     * 计算 P 波到达用户所在地的剩余秒数
     * 公式：到达时刻 = 发震时刻 + 距离 / P波速度
     * 倒计时 = 到达时刻 - 当前时刻（取整秒，最小为 0）
     *
     * @param distanceKm 震中距（公里）
     * @param originTimeMillis 发震时刻（毫秒时间戳）
     * @param pWaveSpeedKmPerSec P 波速度（默认 6.0 km/s）
     * @return 剩余秒数（最小 0），数据异常时返回 null
     */
    fun calculateCountdown(
        distanceKm: Double,
        originTimeMillis: Long,
        pWaveSpeedKmPerSec: Double = 6.0
    ): Int? {
        return try {
            if (distanceKm.isNaN() || distanceKm < 0 ||
                originTimeMillis <= 0 || pWaveSpeedKmPerSec <= 0) {
                Log.w(TAG, "倒计时计算：参数无效，跳过计算")
                return null
            }

            // P 波到达时刻（毫秒）= 发震时刻 + 传播时间
            val travelTimeSec = distanceKm / pWaveSpeedKmPerSec
            val arrivalTimeMillis = originTimeMillis + (travelTimeSec * 1000).toLong()

            // 剩余时间（毫秒），最小为 0
            val remainingMs = arrivalTimeMillis - System.currentTimeMillis()
            val countdownSec = maxOf(0, (remainingMs / 1000).toInt())

            Log.d(TAG, "倒计时计算：距离=${distanceKm}km，传播时间=${"%.1f".format(travelTimeSec)}s，剩余=${countdownSec}s")
            countdownSec
        } catch (e: Exception) {
            Log.e(TAG, "倒计时计算异常：${e.message}")
            null
        }
    }

    // ================================================================
    // 辅助：将发震时间字符串解析为毫秒时间戳
    // ================================================================

    /**
     * 将 EewData.originTime 字符串（如 "2026-04-28 00:11:30"）解析为毫秒时间戳
     * 🚨 2.0 升级：引入 type 字段进行强时区绑定，彻底解决跨时区倒计时错乱问题
     * * @param originTimeStr 发震时间字符串
     * @param type 数据源类型（用于判断所属时区）
     */
    fun parseOriginTimeMillis(originTimeStr: String?, type: String?): Long? {
        if (originTimeStr.isNullOrBlank() || originTimeStr == "null") return null

        // 根据报文 type 强制绑定对应的物理时区
        val targetTimeZone = if (type == "jma_eew") {
            TimeZone.getTimeZone("GMT+09:00") // 日本气象厅：强制 UTC+9
        } else {
            // 包括 cenc_eew, cwa_eew, fj_eew, sc_eew, cq_eew 以及未知源，默认全部按照大中华区时区处理
            TimeZone.getTimeZone("GMT+08:00") // 默认：强制 UTC+8 (北京/台北时间)
        }

        for (fmt in TIME_FORMATS) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.getDefault())
                sdf.timeZone = targetTimeZone // 赋予时区滤镜
                sdf.isLenient = false
                val date = sdf.parse(originTimeStr.trim())
                if (date != null) return date.time
            } catch (_: Exception) {
                // 继续尝试下一种格式
            }
        }
        Log.w(TAG, "发震时间解析失败，所有格式均不匹配：$originTimeStr")
        return null
    }
}