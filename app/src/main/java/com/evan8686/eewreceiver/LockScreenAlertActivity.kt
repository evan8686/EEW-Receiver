package com.evan8686.eewreceiver

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class LockScreenAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 核心特权：允许在锁屏之上显示，并强制点亮屏幕
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
        // 保持屏幕常亮，不让它马上又黑屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 获取传过来的地震文本信息
        val eewText = intent.getStringExtra("EEW_TEXT") ?: "未知地震预警！"

        setContent {
            // 🚨 V1.1.5 核心防烧屏逻辑：进入此界面后开启 60 秒倒计时
            LaunchedEffect(Unit) {
                delay(60_000L) // 严格等待 60 秒
                clearScreenFlagsAndFinish() // 超时未操作，自动清理并息屏
            }

            // 🚨 V1.1.5 画一个全屏红色的警告界面 (颜色已替换为 #C63A2F)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFC63A2F))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "⚠️ 地震警报 ⚠️",
                        fontSize = 40.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = eewText,
                        fontSize = 24.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(64.dp))
                    Button(
                        onClick = { clearScreenFlagsAndFinish() }, // 手动点击也会触发标准清理流程
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) {
                        Text(text = "我知道了", color = Color(0xFFC63A2F), fontSize = 20.sp)
                    }
                }
            }
        }
    }

    // 🚨 V1.1.5 提取清理机制，确保能把手机干净地还给睡眠状态
    private fun clearScreenFlagsAndFinish() {
        // 清除亮屏和常亮权限，让系统接管屏幕状态（自动息屏）
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        // 关闭当前警告页面
        finish()
    }
}
