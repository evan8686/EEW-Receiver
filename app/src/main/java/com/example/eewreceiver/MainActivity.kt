package com.example.eewreceiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // 注册动态权限申请回调（安卓 13 以上必须要这步才能弹通知）
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startEewService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 检查并申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startEewService()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startEewService() // 安卓 13 以下直接启动服务
        }

        // 设置 UI 界面
        setContent {
            MainScreen()
        }
    }

    // 启动咱们刚才写好的前台监听服务
    private fun startEewService() {
        val serviceIntent = Intent(this, EewForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

// 这是底部的导航栏和页面切换逻辑
@Composable
fun MainScreen() {
    var selectedItem by remember { mutableStateOf(0) }
    val items = listOf("最近地震", "设置")

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (index == 0) Icons.Filled.List else Icons.Filled.Settings,
                                contentDescription = item
                            )
                        },
                        label = { Text(item) },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            if (selectedItem == 0) {
                HistoryScreen()
            } else {
                SettingsScreen()
            }
        }
    }
}

@Composable
fun HistoryScreen() {
    val historyList = listOf(
        "【地震预警】第 1 报\n发震时间：2026-03-05 10:00:00\n震源地：测试海域\n震级：4.5 级\n最大震度：3\n深度：10km",
        "【地震预警】第 2 报\n发震时间：2026-03-04 15:30:00\n震源地：测试陆地\n震级：3.2 级\n最大震度：1\n深度：20km"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("最近收到的预警", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(historyList) { eewText ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = eewText,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current

    var threshold by remember { mutableStateOf(3.0f) }
    var apiUrl by remember { mutableStateOf("wss://ws-api.wolfx.jp/cwa_eew") }
    var sourceName by remember { mutableStateOf("Wolfx 台湾中央气象署 EEW") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text("预警设置", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("API 订阅源", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = sourceName, onValueChange = { sourceName = it },
                    label = { Text("源名称") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = apiUrl, onValueChange = { apiUrl = it },
                    label = { Text("WebSocket 链接") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("警报触发阈值: ${String.format("%.1f", threshold)} 级", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = threshold, onValueChange = { threshold = it },
                    valueRange = 1f..9f, steps = 79, modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("系统测试", style = MaterialTheme.typography.titleMedium)
                Text("点击后请立即按下电源键息屏，测试 App 能否在 3 秒后强制亮屏并发出警报。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        val dummyData = EewData(
                            id = 999, reportTime = "测试时间", reportNum = 1,
                            originTime = "刚刚", hypoCenter = "模拟测试海域",
                            latitude = 0.0, longitude = 0.0, magnitude = 7.0,
                            depth = 10, maxIntensity = "6弱"
                        )
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            AlertManager(context).triggerAlert(dummyData, threshold.toDouble())
                        }, 3000)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("3秒后模拟触发 7.0 级预警")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp)) // 留出一点空白间距

        // 个人声明区域
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                text = "该项目为个人测试项目，本人无软件开发经验。此 APP 由 Gemini 协助开发完成。仅供个人测试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "项目仓库：https://github.com/evan8686/EEW-Receiver",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}