package com.evan8686.eewreceiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startEewService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startEewService()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startEewService()
        }
        setContent { MainScreen() }
    }

    private fun startEewService() {
        val serviceIntent = Intent(this, EewForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

@Composable
fun MainScreen() {
    var selectedItem by remember { mutableStateOf(0) }
    val items = listOf("最近地震", "设置")

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(imageVector = if (index == 0) Icons.Filled.List else Icons.Filled.Settings, contentDescription = item) },
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var historyList by remember { mutableStateOf(DataManager.getHistory(context)) }

    LaunchedEffect(Unit) {
        historyList = DataManager.getHistory(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("最近收到的预警", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            if (historyList.isEmpty()) {
                Text("暂无地震记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
        items(historyList) { data ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = data.toReadableText(),
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

    var threshold by remember { mutableStateOf(DataManager.getThreshold(context)) }
    var sourceList by remember { mutableStateOf(DataManager.getSources(context)) }

    // 控制弹窗的状态
    var showAddDialog by remember { mutableStateOf(false) }
    var newSourceName by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("预警设置", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // ================= 订阅源多选卡片 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("API 订阅源 (支持多选)", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                sourceList.forEachIndexed { index, source ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = source.isSelected,
                            onCheckedChange = { isChecked ->
                                val newList = sourceList.toMutableList()
                                newList[index] = source.copy(isSelected = isChecked)
                                sourceList = newList
                            }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(source.name, style = MaterialTheme.typography.bodyMedium)
                            Text(source.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                TextButton(onClick = { showAddDialog = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("+ 添加自定义源")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ================= 阈值卡片 =================
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

        // ================= 保存按钮 =================
        Button(
            onClick = {
                // 拦截：如果不选任何源就不让保存
                if (sourceList.none { it.isSelected }) {
                    Toast.makeText(context, "请至少勾选一个订阅源！", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                DataManager.saveSources(context, sourceList)
                DataManager.saveThreshold(context, threshold)
                Toast.makeText(context, "保存成功，服务已重启！", Toast.LENGTH_SHORT).show()
                val intent = Intent(context, EewForegroundService::class.java)
                context.stopService(intent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存配置并生效")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 测试卡片 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("系统测试", style = MaterialTheme.typography.titleMedium)
                Text("点击后请立即按下电源键息屏，测试 App 能否在 3 秒后强制亮屏并发出警报。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val currentTimeString = sdf.format(java.util.Date())

                        val dummyData = EewData(
                            id = System.currentTimeMillis().toString(),
                            reportTime = currentTimeString,
                            reportNum = 1,
                            originTime = currentTimeString,
                            hypoCenter = "模拟测试海域",
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

        Spacer(modifier = Modifier.height(32.dp))

        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                text = "该项目为个人测试项目，本人无软件开发经验。此 APP 由 Gemini 协助开发完成。仅供个人测试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(8.dp))

            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val annotatedLinkString = androidx.compose.ui.text.buildAnnotatedString {
                append("项目仓库：")
                pushStringAnnotation(tag = "URL", annotation = "https://github.com/evan8686/EEW-Receiver")
                withStyle(style = androidx.compose.ui.text.SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )) {
                    append("https://github.com/evan8686/EEW-Receiver")
                }
                pop()
            }

            androidx.compose.foundation.text.ClickableText(
                text = annotatedLinkString,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline),
                onClick = { offset ->
                    annotatedLinkString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            uriHandler.openUri(annotation.item)
                        }
                }
            )

        }
    }

    

    // ================= 添加自定义源的弹窗 =================
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加自定义源") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newSourceName, onValueChange = { newSourceName = it },
                        label = { Text("源名称") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newSourceUrl, onValueChange = { newSourceUrl = it },
                        label = { Text("WebSocket 链接") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newSourceName.isNotBlank() && newSourceUrl.isNotBlank()) {
                        val newList = sourceList.toMutableList()
                        // 默认将新加的源设置为勾选状态
                        newList.add(ApiSource(newSourceName, newSourceUrl, true))
                        sourceList = newList
                        showAddDialog = false
                        newSourceName = ""
                        newSourceUrl = ""
                    } else {
                        Toast.makeText(context, "名称和链接不能为空", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }
}