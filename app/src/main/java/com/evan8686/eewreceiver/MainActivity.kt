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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // 状态控制
    var showAddDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) } // 新增：帮助弹窗状态
    var sourcesExpanded by remember { mutableStateOf(false) } // 新增：折叠面板状态

    var newSourceName by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        // 顶部标题栏：包含帮助按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("预警设置", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { showHelpDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Info, // 🚨 这里修改为自带的 Info 图标
                    contentDescription = "帮助说明",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 订阅源多选卡片 (折叠面板) =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sourcesExpanded = !sourcesExpanded }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("API 订阅源 (支持多选)", style = MaterialTheme.typography.titleMedium)
                        val selectedCount = sourceList.count { it.isSelected }
                        Text(
                            text = if (sourcesExpanded) "点击收起面板" else "已选择 $selectedCount 个数据源，点击配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(
                        imageVector = if (sourcesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = sourcesExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
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

        // ================= 系统测试 =================
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
                text = "您安装的当前版本为1.2.0版本，您可随时访问Github仓库获取版本更新情况。该项目为个人测试项目，本人无软件开发经验。此 APP 由 Gemini 协助开发完成。仅供个人测试。",
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
                        .firstOrNull()?.let { annotation -> uriHandler.openUri(annotation.item) }
                }
            )
        }
    }

    // ================= 弹窗集合 =================

    // 1. 帮助说明弹窗
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "EEW-Receiver 地震预警接收器\n配置说明",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "为了确保您能持续正常接收地震预警，此APP需维持后台保活，请务必按照以下说明进行配置。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("1. 设置 - 通知 - EEW Receiver", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• a. 开启“允许通知”，并将 “锁屏/横幅” 打勾。\n• b. 打开 “铃声/震动/允许打扰”（该项指即便手机处于免打扰模式时，应用仍能正常响铃和震动）。\n• c. 将“类别”下的 “地震预警事件” 设为允许通知，“地震预警后台监控”则维持默认的“静默通知”即可。", fontSize = 14.sp)

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("2. 设置 - 应用管理 - EEW Receiver - 权限管理", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• a. 锁屏显示：设为 “允许”。\n• b. 滚动页面到下方，进入 “其他权限” -> “特殊应用权限”：（您先进入这个页面，尝试调整权限，系统会提示您需要解锁限制）\n• c. 回到EEW Receiver - 权限管理 主界面，此时点击右上角新出现的 3 个点，选择 “解锁所有授权限制” 进行解除。\n• d. 回到“特殊应用权限”页面\n    * 悬浮窗：设为“允许”。\n    * 后台弹出界面：设为“允许”。\n    * 发送全屏通知：设为“允许”。", fontSize = 14.sp)

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("3. 桌面多任务界面 - EEW Receiver - 锁定不清理", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• a. 上滑呼出多任务界面\n• b. 找到 EEW Reveiver，点击 3 个点，选择 “锁定”  （即，在多任务窗口一键清理使用过的应用时，不会被杀掉）", fontSize = 14.sp)

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("4. 耗电管理 - EEW Receiver - 完全允许后台行为", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• a. 进入耗电管理相关的设置界面\n• b. 找到 EEW Reveiver，选择 “完全允许后台行为”", fontSize = 14.sp)

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("5. 应用 - 自启动 - 允许 EEW Receiver 自启动", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "以上设置 以 OPPO ColorOS 16 为例，若您使用 小米澎湃OS，vivo OriginOS 等，请自行参考并在系统设置中操作相关选项。部分选项名称和入口在不同OS上可能略有差异，但基本类似。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showHelpDialog = false }) { Text("我知道了") }
            }
        )
    }

    // 2. 添加自定义源弹窗
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
