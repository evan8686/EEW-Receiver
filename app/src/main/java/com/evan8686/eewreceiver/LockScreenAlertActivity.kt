package com.evan8686.eewreceiver

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// 💡 规范化：将所有零散的字段打包成一个标准的数据类（高内聚）
data class AlertUiData(
    val magnitude: String = "0.0",
    val intensity: String = "未知",
    val hypoCenter: String = "未知",
    val depth: String = "未知",
    val time: String = "未知",
    val reportNum: String = "1",
    val triggerTime: Long = 0L // 用于触发 60 秒超时自动关闭
)

class LockScreenAlertActivity : ComponentActivity() {

    // 💡 规范化：使用单一的 mutableStateOf 来持有整个数据流
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

        // 初始化第一报数据 (Activity 重建时也会重新解析 Intent)
        updateDataFromIntent(intent)

        setContent {
            // 💡 规范化：在 Compose 作用域内观察 uiState 的变化。
            // 结合 onCreate 里的 updateDataFromIntent，完美应对横竖屏旋转等重建场景！
            val alertData by remember { uiState }

            LaunchedEffect(key1 = alertData.triggerTime) {
                delay(60_000L) // 60秒无人操作，自动关闭弹窗并刹车
                clearScreenFlagsAndFinish()
            }

            // 抽取为独立的 Composable 函数，让 UI 代码逻辑更清爽
            AlertScreenUI(alertData = alertData) {
                clearScreenFlagsAndFinish()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 当后续报文（如第 2 报、第 3 报）连续发来时，刷新数据
        intent?.let { updateDataFromIntent(it) }
    }

    private fun updateDataFromIntent(intent: Intent) {
        uiState.value = AlertUiData(
            magnitude = intent.getStringExtra("EEW_MAGNITUDE") ?: "0.0",
            intensity = intent.getStringExtra("EEW_INTENSITY") ?: "未知",
            hypoCenter = intent.getStringExtra("EEW_HYPOCENTER") ?: "未知",
            depth = intent.getStringExtra("EEW_DEPTH") ?: "未知",
            time = intent.getStringExtra("EEW_TIME") ?: "未知",
            reportNum = intent.getStringExtra("EEW_REPORT_NUM") ?: "1",
            triggerTime = System.currentTimeMillis()
        )
    }

    private fun clearScreenFlagsAndFinish() {
        // 🚨 核心刹车线修改：抛弃广播，改为向后台服务发送“显式停止警报”的启动指令
        val stopIntent = Intent(this, EewForegroundService::class.java).apply {
            action = EewForegroundService.ACTION_STOP_ALERT
        }
        startService(stopIntent)

        // 解除锁屏长亮的标志位，让屏幕可以自然休眠
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        finish()
    }
}

// 💡 纯粹的 UI 渲染组件，只负责根据传入的 AlertUiData 进行绘图
@Composable
fun AlertScreenUI(alertData: AlertUiData, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFC63A2F))
            .padding(24.dp)
    ) {
        // 中间核心视觉区
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚠️ 地震警报 ⚠️",
                fontSize = 36.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(32.dp))

            // ================= 半透明数据卡片 =================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.Black.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(24.dp)
            ) {
                // 顶部小标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("震源震级", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    Text("最大烈度", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }

                // 超大数字行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = alertData.magnitude,
                            color = Color.White,
                            fontSize = 56.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "级",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                    }
                    Text(
                        text = alertData.intensity,
                        color = Color.White,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                // 半透明分割线
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))
                Spacer(modifier = Modifier.height(16.dp))

                AlignedInfoRow(label = "预警编号", value = "第 ${alertData.reportNum} 报")
                AlignedInfoRow(label = "震源地", value = alertData.hypoCenter)
                AlignedInfoRow(label = "深度", value = alertData.depth)
                AlignedInfoRow(label = "时间", value = alertData.time)
            }
            // ================= 卡片区域结束 =================

            Spacer(modifier = Modifier.height(48.dp))

            // 扁平化圆角按钮
            Button(
                onClick = onDismiss, // 触发 service 指令和销毁逻辑
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(0.6f).height(50.dp)
            ) {
                Text(text = "我知道了", color = Color(0xFFC63A2F), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 底部安静的品牌水印区
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("EEW Receiver", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            Text("地震预警接收器", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }
}

// 💡 解决“强迫症不对齐”的专属 Compose 组件
@Composable
fun AlignedInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左侧容器：固定宽度，内部字符两端对齐
        Row(
            modifier = Modifier.width(72.dp), // 72dp 刚好完美放下4个 16sp 的汉字
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            label.forEach { char ->
                Text(text = char.toString(), color = Color.White, fontSize = 16.sp)
            }
        }
        // 右侧容器：冒号及内容绝对左对齐
        Text(text = "：$value", color = Color.White, fontSize = 16.sp)
    }
}