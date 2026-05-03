package com.evan8686.eewreceiver

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// =====================================================================
// 数据模型
// =====================================================================

/** 将所有零散字段打包成一个标准数据类（高内聚） */
data class AlertUiData(
    val magnitude: String = "0.0",
    val intensity: String = "未知",
    val hypoCenter: String = "未知",
    val depth: String = "未知",
    val time: String = "未知",
    val reportNum: String = "1",
    val triggerTime: Long = 0L,        // 用于触发超时自动关闭
    // 任务3 新增：计算结果字段（null 表示无法计算，UI 显示兜底文字）
    val distanceKm: Double? = null,    // 震中距（公里）
    val localIntensity: Int? = null,   // 本地预估烈度（1~12）
    val countdown: Int? = null,        // P 波到达倒计时（秒）
    // 任务6 地图需要的震中经纬度
    val epicenterLat: Double? = null,
    val epicenterLon: Double? = null
)

// =====================================================================
// Activity
// =====================================================================

class LockScreenAlertActivity : ComponentActivity() {

    /** 使用单一的 mutableStateOf 持有整个数据流 */
    private val uiState = mutableStateOf(AlertUiData())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持锁屏亮屏的系统设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 初始化第一报数据
        updateDataFromIntent(intent)

        setContent {
            val alertData by remember { uiState }

            // 自动关闭：倒计时归零后再等 60 秒；无倒计时时直接等 60 秒
            LaunchedEffect(key1 = alertData.triggerTime) {
                val countdown = alertData.countdown  // 先赋值给局部变量
                val waitMs = if (countdown != null) {
                    (countdown + 60) * 1000L         // 局部变量可以正常 smart cast
                } else {
                    60_000L
                }
                delay(waitMs)
                clearScreenFlagsAndFinish()
            }

            // ======= 核心修复：在此处调用 UI 组件以渲染界面 =======
            AlertScreenUI(
                alertData = alertData,
                onDismiss = { clearScreenFlagsAndFinish() }
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 后续报文刷新数据
        intent?.let { updateDataFromIntent(it) }
    }

    private fun updateDataFromIntent(intent: Intent) {
        // 用 hasExtra 判断是否真的传入了值，避免把"未传"误当作"0"
        val distanceKm = if (intent.hasExtra("EEW_DISTANCE_KM"))
            intent.getDoubleExtra("EEW_DISTANCE_KM", -1.0).let { if (it < 0) null else it }
        else null

        val localIntensity = if (intent.hasExtra("EEW_LOCAL_INTENSITY"))
            intent.getIntExtra("EEW_LOCAL_INTENSITY", -1).let { if (it < 0) null else it }
        else null

        val countdown = if (intent.hasExtra("EEW_COUNTDOWN"))
            intent.getIntExtra("EEW_COUNTDOWN", -1).let { if (it < 0) null else it }
        else null

        val epicenterLat = if (intent.hasExtra("EEW_LATITUDE"))
            intent.getDoubleExtra("EEW_LATITUDE", 0.0)
        else null

        val epicenterLon = if (intent.hasExtra("EEW_LONGITUDE"))
            intent.getDoubleExtra("EEW_LONGITUDE", 0.0)
        else null

        uiState.value = AlertUiData(
            magnitude = intent.getStringExtra("EEW_MAGNITUDE") ?: "0.0",
            intensity = intent.getStringExtra("EEW_INTENSITY") ?: "未知",
            hypoCenter = intent.getStringExtra("EEW_HYPOCENTER") ?: "未知",
            depth = intent.getStringExtra("EEW_DEPTH") ?: "未知",
            time = intent.getStringExtra("EEW_TIME") ?: "未知",
            reportNum = intent.getStringExtra("EEW_REPORT_NUM") ?: "1",
            triggerTime = System.currentTimeMillis(),
            distanceKm = distanceKm,
            localIntensity = localIntensity,
            countdown = countdown,
            epicenterLat = epicenterLat,
            epicenterLon = epicenterLon
        )
    }

    private fun clearScreenFlagsAndFinish() {
        // 向后台服务发送"显式停止警报"指令
        val stopIntent = Intent(this, EewForegroundService::class.java).apply {
            action = EewForegroundService.ACTION_STOP_ALERT
        }
        startService(stopIntent)

        // 解除锁屏长亮标志位，让屏幕可以自然休眠
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        finish()
    }
}

// =====================================================================
// 任务5：烈度描述文本辅助函数
// =====================================================================

/** 根据本地预估烈度返回注意度 Tag 文字；0 或 null 时返回 null（不显示） */
fun intensityTagText(intensity: Int?): String? {
    if (intensity == null || intensity <= 0) return null
    return when (intensity) {
        in 1..3  -> "【勿惊慌】"
        in 4..6  -> "【注意安全！合理避险！】"
        in 7..12 -> "【尽快采取必要避险措施！】"
        else     -> null
    }
}

/** 根据烈度返回 Tag 的背景色 */
fun intensityTagColor(intensity: Int?): Color {
    if (intensity == null || intensity <= 0) return Color.Transparent
    return when (intensity) {
        in 1..3  -> Color(0xFF4CAF50)   // 绿色
        in 4..6  -> Color(0xFFFFC107)   // 黄色
        in 7..12 -> Color(0xFFE53935)   // 红色
        else     -> Color.Transparent
    }
}

/** 根据本地预估烈度返回描述文字；null 或无法解析时返回兜底文字 */
fun intensityDescription(intensity: Int?): String {
    return when (intensity) {
        0, 1 -> "无感"
        2    -> "室内个别静止中的人有感觉，个别较高楼层中的人有感觉"
        3    -> "室内少数静止中的人有感觉，少数较高楼层中的人有明显感觉；门、窗轻微作响，悬挂物微动"
        4    -> "室内多数人、室外少数人有感觉，少数人睡梦中惊醒；门、窗、器皿作响，悬挂物明显摆动"
        5    -> "室内绝大多数、室外多数人有感觉，少数人惊逃户外；不稳定物可能倾倒，老旧墙体可能出现裂缝"
        6    -> "多数人站立不稳，多数人惊逃户外；部分轻家具可能移动；建筑结构可能出现轻微破坏"
        7    -> "大多数人惊逃户外；部分轻家具倾倒；建筑结构可能出现中等破坏"
        8    -> "多数人摇晃颠簸，行走困难；除重家具外，室内物品大多数倾倒或移位；建筑结构可能出现严重破坏"
        9    -> "行动的人摔倒；室内家具和物品大多数倾倒或移位；多数建筑结构严重破坏"
        10   -> "处不稳状态的人会摔离原地，有抛起感。绝大多数建筑严重破坏或损毁。"
        11   -> "绝大多数建筑损毁；大量山崩滑坡"
        12   -> "几乎所有建筑全部损毁；地面剧烈变化，山河改观"
        null -> "未解析到数据"
        else -> "未解析到数据"
    }
}

// =====================================================================
// 主 UI 组件
// =====================================================================

/** 全屏弹窗 UI，纯粹负责根据 AlertUiData 进行绘图 */
@Composable
fun AlertScreenUI(alertData: AlertUiData, onDismiss: () -> Unit) {

    // 实时倒计时状态：从 alertData.countdown 开始，每秒递减
    var liveCountdown by remember(alertData.triggerTime) {
        mutableStateOf(alertData.countdown)
    }
    LaunchedEffect(alertData.triggerTime) {
        var remaining = alertData.countdown ?: return@LaunchedEffect
        while (remaining > 0) {
            delay(1_000L)
            remaining--
            liveCountdown = remaining
        }
    }

    val scrollState = rememberScrollState()

    // 获取屏幕高度，用于设定滚动区域的最小高度，确保内容不足时能完美居中
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp

    // 根布局
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFC63A2F)) // 红色背景
    ) {
        // ── 页面主体滚动内容 ──────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .defaultMinSize(minHeight = screenHeight) // 核心修改：确保最低高度等同屏幕高度
                .padding(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center // 核心修改：当内容小于屏幕时，整体垂直居中
        ) {

            // ── 顶部标题 ──────────────────────────────────────────────
            Text(
                text = "⚠️ 地震警报 ⚠️",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ══════════════════════════════════════════════════════════
            // 卡片 1：白底圆角，震源信息 + 本地烈度
            // ══════════════════════════════════════════════════════════
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, shape = RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    // ── 第一行：震源最大震级 | 震源最大烈度 ──────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // 左：震级
                        Column {
                            Text(
                                "震源最大震级",
                                fontSize = 12.sp,
                                color = Color(0xFF666666)
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = alertData.magnitude,
                                    fontSize = 52.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF1A1A1A)
                                )
                                Text(
                                    text = "级",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1A1A1A),
                                    modifier = Modifier.padding(bottom = 8.dp, start = 2.dp)
                                )
                            }
                        }

                        // 右：最大烈度
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "震源最大烈度",
                                fontSize = 12.sp,
                                color = Color(0xFF666666)
                            )
                            Text(
                                text = alertData.intensity,
                                fontSize = 52.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF1A1A1A)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFFE0E0E0))
                    Spacer(modifier = Modifier.height(12.dp))

                    // ── 第二行：本地预估烈度 | 注意度Tag + 描述 ──────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        // 左：本地预估烈度
                        Column(modifier = Modifier.width(100.dp)) {
                            Text("本地预估烈度", fontSize = 12.sp, color = Color(0xFF666666))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (alertData.localIntensity != null)
                                    "约 ${alertData.localIntensity} 度"
                                else "未解析到数据",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1A1A1A)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // 右：注意度Tag + 烈度描述文字
                        Column(modifier = Modifier.weight(1f)) {
                            // 注意度 Tag（烈度为0或null时不显示）
                            val tagText = intensityTagText(alertData.localIntensity)
                            if (tagText != null) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = intensityTagColor(alertData.localIntensity),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = tagText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            // 烈度描述
                            Text(
                                text = intensityDescription(alertData.localIntensity),
                                fontSize = 13.sp,
                                color = Color(0xFF444444),
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFFE0E0E0))
                    Spacer(modifier = Modifier.height(12.dp))

                    // ── 第三区：预警编号 / 震源地 / 深度 / 时间（网格布局）─
                    CardInfoRow(label = "预警编号", value = "第 ${alertData.reportNum} 报")
                    CardInfoRow(label = "震  源  地", value = alertData.hypoCenter)
                    CardInfoRow(label = "深      度", value = alertData.depth)
                    CardInfoRow(label = "时      间", value = alertData.time)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ══════════════════════════════════════════════════════════
            // 卡片 2：深色圆角，距离 + 倒计时 | 地图（任务6占位）
            // ══════════════════════════════════════════════════════════
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2C1A1A), shape = RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：距离 + 倒计时
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        // 距离震源地
                        Text("距离震源地", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                        Text(
                            text = if (alertData.distanceKm != null)
                                "${"%.0f".format(alertData.distanceKm)} 公里"
                            else "未解析到数据",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(8.dp))

                        // 震波到达还有
                        Text("震波到达还有", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))

                        if (liveCountdown != null) {
                            if (liveCountdown!! > 0) {
                                // 倒计时进行中：显示大数字
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = liveCountdown.toString(),
                                        fontSize = 72.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "秒",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                                    )
                                }
                            } else {
                                // 倒计时归零
                                Text(
                                    text = "地震波\n已抵达",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center // 让多行文字居中对齐
                                )
                            }
                        } else {
                            Text(
                                text = "未解析到数据",
                                fontSize = 16.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // 右侧：震中地图（任务6，使用 OSMDroid + OpenStreetMap）
                    if (alertData.epicenterLat != null && alertData.epicenterLon != null) {
                        EpicenterMapView(
                            epicenterLat = alertData.epicenterLat,
                            epicenterLon = alertData.epicenterLon,
                            modifier = Modifier
                                .size(width = 150.dp, height = 170.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        // 数据缺失时显示兜底占位组件
                        EpicenterMapPlaceholder(
                            modifier = Modifier.size(width = 150.dp, height = 170.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 免责声明小字 ─────────────────────────────────────────
            Text(
                text = "*本页面信息为计算和预估数据，相关描述仅供参考。不构成法定意义上的指导意见。\n请依据实际情况采取必要避险措施。", // 👈 核心修改：合并了字符串，并在“请依据”前加入了 \n
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center, // 👈 这行配置保证了上下两行都会居中
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── 恢复："我知道了"按钮 ───────────────────────────────────
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(52.dp)
            ) {
                Text(
                    text = "我知道了",
                    color = Color(0xFFC63A2F),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 软件署名 ──────────────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("EEW Receiver", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                Text("地震预警接收器", color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp)
            }
        }

        // ── 顶部右侧半透明关闭悬浮按钮（X） ──────────────────────────
        // 放在这里（Column 之后），确保它覆盖在滚动内容之上并且位置固定
        Box(
            modifier = Modifier
                .padding(top = 24.dp, end = 16.dp) // 距离右上角的边距
                .size(36.dp) // 悬浮圆形按钮的整体大小
                .align(Alignment.TopEnd) // 固定于右上角
                .clip(CircleShape) // 裁剪为圆形
                .background(Color.Black.copy(alpha = 0.3f)) // 黑色背景，30% 不透明度（半透明灰色）
                .clickable(onClick = onDismiss), // 绑定相同的关闭逻辑
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close, // Compose 内置的 X 图标
                contentDescription = "关闭",
                tint = Color.White, // X 图标颜色为白色
                modifier = Modifier.size(20.dp) // X 图标的缩放大小
            )
        }
    }
}

// =====================================================================
// 辅助 Composable：卡片内信息行（标签两端对齐）
// =====================================================================

/** 卡片1内的信息行：标签左对齐，值左对齐，整体占满宽度 */
@Composable
fun CardInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color(0xFF888888),
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = "：$value",
            fontSize = 14.sp,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.weight(1f)
        )
    }
}