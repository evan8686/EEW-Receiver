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

class LockScreenAlertActivity : ComponentActivity() {

    // 💡 核心魔法 1：独立管理每个字段的状态，确保多报文丝滑刷新且不影响局部 UI
    private var magnitudeState = mutableStateOf("0.0")
    private var intensityState = mutableStateOf("未知")
    private var hypoCenterState = mutableStateOf("未知")
    private var depthState = mutableStateOf("未知")
    private var timeState = mutableStateOf("未知")
    private var updateTriggerState = mutableStateOf(0L) // 用于触发倒计时重置的时间戳

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // 初始化第一报的数据
        updateDataFromIntent(intent)

        setContent {
            // 💡 核心魔法 2：带 Key 的 LaunchedEffect，逻辑与之前完全一致
            LaunchedEffect(key1 = updateTriggerState.value) {
                delay(60_000L)
                clearScreenFlagsAndFinish()
            }

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

                    // ================= 新增：半透明数据卡片 (Glassmorphism) =================
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
                                    text = magnitudeState.value,
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
                                text = intensityState.value,
                                color = Color.White,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        // 半透明分割线
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))
                        Spacer(modifier = Modifier.height(16.dp))

                        // 详细参数 (使用全角空格实现垂直对齐)
                        Text("震源地：${hypoCenterState.value}", color = Color.White, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("深　度：${depthState.value}", color = Color.White, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("时　间：${timeState.value}", color = Color.White, fontSize = 18.sp)
                    }
                    // ================= 卡片区域结束 =================

                    Spacer(modifier = Modifier.height(48.dp))

                    // 扁平化圆角按钮
                    Button(
                        onClick = { clearScreenFlagsAndFinish() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(50), // 胶囊形状
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
    }

    // 💡 核心魔法 3：处理后续报文的推入
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { updateDataFromIntent(it) }
    }

    // 将解析 Intent 的逻辑抽取出来，方便 onCreate 和 onNewIntent 复用
    private fun updateDataFromIntent(intent: Intent) {
        magnitudeState.value = intent.getStringExtra("EEW_MAGNITUDE") ?: "0.0"
        intensityState.value = intent.getStringExtra("EEW_INTENSITY") ?: "未知"
        hypoCenterState.value = intent.getStringExtra("EEW_HYPOCENTER") ?: "未知"
        depthState.value = intent.getStringExtra("EEW_DEPTH") ?: "未知"
        timeState.value = intent.getStringExtra("EEW_TIME") ?: "未知"

        // 更新时间戳，60秒倒计时瞬间重置
        updateTriggerState.value = System.currentTimeMillis()
    }

    private fun clearScreenFlagsAndFinish() {
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        finish()
    }
}
