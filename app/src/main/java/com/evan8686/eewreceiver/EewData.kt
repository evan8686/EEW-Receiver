package com.evan8686.eewreceiver

import com.google.gson.annotations.SerializedName

data class EewData(
    // 发报 ID (兼容中国/台湾/福建/四川的 ID，以及日本的 EventID)
    // 注意：为了兼容有些源可能带有字母的 ID，这里将类型从 Long 改为了 String
    @SerializedName(value = "ID", alternate = ["EventID"])
    val id: String? = null,

    // EEW 发报数 (兼容 ReportNum 和 日本的 Serial)
    @SerializedName(value = "ReportNum", alternate = ["Serial"])
    val reportNum: Int = 0,

    // 发报时间 (兼容 ReportTime 和 日本的 AnnouncedTime)
    @SerializedName(value = "ReportTime", alternate = ["AnnouncedTime"])
    val reportTime: String? = "",

    // 发震时间 (拼写一致)
    @SerializedName("OriginTime")
    val originTime: String? = "",

    // 震源地 (拼写一致)
    @SerializedName("HypoCenter")
    val hypoCenter: String? = "",

    // 纬度 (拼写一致)
    @SerializedName("Latitude")
    val latitude: Double = 0.0,

    // 经度 (拼写一致)
    @SerializedName("Longitude")
    val longitude: Double = 0.0,

    // 震级 (完美兼容 Magnitude 和 拼写错误的 Magunitude)
    @SerializedName(value = "Magnitude", alternate = ["Magunitude"])
    val magnitude: Double = 0.0,

    // 震源深度 (福建源没有此字段，所以加上 ? 允许为空)
    @SerializedName("depth")
    val depth: Int? = null,

    // 最大预估烈度 (福建源没有此字段，所以加上 ? 允许为空)
    @SerializedName("MaxIntensity")
    val maxIntensity: String? = null
) {
    // 将数据转换为通知栏显示的直观文本
    fun toReadableText(): String {
        // 如果福建源没有深度和烈度，我们显示为“未知”
        val depthText = if (depth != null) "${depth}km" else "未知"
        val intensityText = maxIntensity ?: "未知"

        return "【地震预警】第 ${reportNum} 报\n" +
                "发震时间：$originTime\n" +
                "震源地：$hypoCenter\n" +
                "震级：${magnitude} 级\n" +
                "最大震度：$intensityText\n" +
                "深度：$depthText"
    }
}