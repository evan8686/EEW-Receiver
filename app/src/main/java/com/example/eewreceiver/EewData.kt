package com.example.eewreceiver

import com.google.gson.annotations.SerializedName

// 使用 @SerializedName 将 JSON 里的奇葩拼写映射为我们代码里正常的拼写
data class EewData(
    @SerializedName("ID") val id: Long,
    @SerializedName("ReportTime") val reportTime: String,
    @SerializedName("ReportNum") val reportNum: Int,
    @SerializedName("OriginTime") val originTime: String,
    @SerializedName("HypoCenter") val hypoCenter: String,
    @SerializedName("Latitude") val latitude: Double,
    @SerializedName("Longitude") val longitude: Double,
    // 注意：这里原样对应 API 的拼写错误 "Magunitude"，但在我们的代码里叫 magnitude
    @SerializedName("Magunitude") val magnitude: Double,
    @SerializedName("Depth") val depth: Int,
    @SerializedName("MaxIntensity") val maxIntensity: String
) {
    // 这是一个辅助函数：收到数据后，一键生成方便普通人阅读的中文文本
    fun toReadableText(): String {
        return "【地震预警】第 $reportNum 报\n" +
                "发震时间：$originTime\n" +
                "震源地：$hypoCenter\n" +
                "震级：$magnitude 级\n" +
                "最大震度：$maxIntensity\n" +
                "深度：${depth}km"
    }
}
