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

    // 💡 独立管理每个字段的状态
    private var magnitudeState = mutableStateOf("0.0")
    private var intensityState = mutableStateOf("未知")
    private var hypoCenterState = mutableStateOf("未知")
    private var depthState = mutableStateOf("未知")
    private var timeState = mutableStateOf("未知")
    private var reportNumState = mutableStateOf("1")
    private var updateTriggerState = mutableStateOf(0L)

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

        // 初始化第一报数据
        updateDataFromIntent(intent)

        setContent {
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

                        // 🚨 核心排版重构：使用自定义的完美对齐组件
                        // 你不再需要手动敲空格了，算法会自动让“深度”和“预警编号”变得一样宽！
                        AlignedInfoRow(label = "预警编号", value = "第 ${reportNumState.value} 报")
                        AlignedInfoRow(label = "震源地", value = hypoCenterState.value)
                        AlignedInfoRow(label = "深度", value = depthState.value)
                        AlignedInfoRow(label = "时间", value = timeState.value)
                    }
                    // ================= 卡片区域结束 =================

                    Spacer(modifier = Modifier.height(48.dp))

                    // 扁平化圆角按钮
                    Button(
                        onClick = { clearScreenFlagsAndFinish() },
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
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { updateDataFromIntent(it) }
    }

    private fun updateDataFromIntent(intent: Intent) {
        magnitudeState.value = intent.getStringExtra("EEW_MAGNITUDE") ?: "0.0"
        intensityState.value = intent.getStringExtra("EEW_INTENSITY") ?: "未知"
        hypoCenterState.value = intent.getStringExtra("EEW_HYPOCENTER") ?: "未知"
        depthState.value = intent.getStringExtra("EEW_DEPTH") ?: "未知"
        timeState.value = intent.getStringExtra("EEW_TIME") ?: "未知"
        reportNumState.value = intent.getStringExtra("EEW_REPORT_NUM") ?: "1"

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

// 💡 新增：用于解决“强迫症不对齐”的专属 Compose 组件
@Composable
fun AlignedInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左侧容器：固定宽度，内部字符两端对齐
        Row(
            modifier = Modifier.width(72.dp), // 72dp 刚好完美放下4个 16sp 的汉字
            horizontalArrangement = Arrangement.SpaceBetween // 核心魔法：字符自动均匀散开
        ) {
            label.forEach { char ->
                Text(text = char.toString(), color = Color.White, fontSize = 16.sp)
            }
        }
        // 右侧容器：冒号及内容绝对左对齐
        Text(text = "：$value", color = Color.White, fontSize = 16.sp)
    }
}
