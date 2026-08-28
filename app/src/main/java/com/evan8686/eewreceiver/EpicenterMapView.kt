package com.evan8686.eewreceiver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase

/**
 * 任务6：震中地图组件
 * 使用 OSMDroid 框架，并配置高德地图国内瓦片源，实现国内网络秒开且带中文地名
 *
 * @param epicenterLat  震中纬度
 * @param epicenterLon  震中经度
 * @param modifier      Compose Modifier
 */
@Composable
fun EpicenterMapView(
    epicenterLat: Double,
    epicenterLon: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 初始化 OSMDroid 配置（userAgent + 磁盘缓存目录）
    // 在 Compose 中用 remember 确保只初始化一次
    remember(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles")
        }
    }

    // 预先创建红色圆点 Bitmap，避免在 View 构建期间重复分配
    val redDotDrawable = remember(context) {
        BitmapDrawable(context.resources, createRedDotBitmap(72))
    }

    // 用 AndroidView 将传统 View 嵌入 Compose
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                // 👇 核心修复：自定义瓦片源，专门处理高德的 x=...&y=...&z=... URL 格式
                val gaodeTileSource = object : OnlineTileSourceBase(
                    "GaodeMap",
                    1, 20, 256, ".png",
                    arrayOf(
                        "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&",
                        "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&",
                        "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&",
                        "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&"
                    )
                ) {
                    override fun getTileURLString(pMapTileIndex: Long): String {
                        val z = MapTileIndex.getZoom(pMapTileIndex)
                        val x = MapTileIndex.getX(pMapTileIndex)
                        val y = MapTileIndex.getY(pMapTileIndex)
                        // 严格按照高德所需的参数格式拼接
                        return baseUrl + "x=$x&y=$y&z=$z"
                    }
                }
                setTileSource(gaodeTileSource)
                // 👆 修复结束

                // 禁用用户交互（警报界面只做展示，不需要可拖动/缩放）
                setMultiTouchControls(false)
                isClickable = false
                isFocusable = false

                // 以震中为中心，缩放 7 级（可清晰看到区域范围）
                controller.setZoom(7.0)
                controller.setCenter(GeoPoint(epicenterLat, epicenterLon))

                // 添加震中红点标记
                val marker = Marker(this).apply {
                    position = GeoPoint(epicenterLat, epicenterLon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = redDotDrawable
                    // 不显示默认的信息气泡
                    title = null
                    snippet = null
                    setInfoWindow(null)
                }
                overlays.add(marker)
            }
        },
        // 当 Activity 离开时触发 onDetach 自动回收地图资源
        onRelease = { mapView ->
            mapView.onDetach()
        },
        modifier = modifier
    )
}

/**
 * 用 Android Canvas API 绘制红色填充圆形 Bitmap，用作震中标记图标
 * @param sizePx 图标像素尺寸（正方形）
 */
private fun createRedDotBitmap(sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 红色填充圆（带轻微透明度让它更自然）
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(230, 220, 30, 30)
        style = Paint.Style.FILL
    }
    // 白色描边，让红点在深色地图上更突出
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.07f
    }

    val margin = strokePaint.strokeWidth + 2f
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val radius = sizePx / 2f - margin

    canvas.drawCircle(cx, cy, radius, fillPaint)
    canvas.drawCircle(cx, cy, radius, strokePaint)

    return bitmap
}

/**
 * 震中地图兜底组件：当经纬度数据缺失时显示占位文字
 */
@Composable
fun EpicenterMapPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFF1A0D0D), shape = RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "未解析到数据",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}