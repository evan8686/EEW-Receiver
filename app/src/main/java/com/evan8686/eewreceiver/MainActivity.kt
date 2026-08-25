package com.evan8686.eewreceiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startEewService()
        }
    }

    // 全局配置状态
    private var updateState by mutableStateOf<UpdateInfo?>(null)
    private var noticeState by mutableStateOf<NoticeInfo?>(null)
    private var displayUpdateDialog by mutableStateOf(false)
    private var displayNoticeDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 监听生命周期，回到前台时检查配置
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_START) {
                    checkRemoteConfig()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startEewService()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startEewService()
        }
        setContent { 
            MainScreen(
                updateInfo = updateState,
                noticeInfo = noticeState,
                showUpdate = displayUpdateDialog,
                showNotice = displayNoticeDialog,
                onUpdateDismiss = { displayUpdateDialog = false },
                onNoticeDismiss = { displayNoticeDialog = false },
                onCheckUpdate = { checkRemoteConfig(isManual = true) }
            ) 
        }
    }

    private fun startEewService() {
        val serviceIntent = Intent(this, EewForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // 记录本进程生命周期内是否已经执行过自动更新检查，用于实现“冷启动必查一次”
    private var hasCheckedUpdateThisSession = false

    private fun checkRemoteConfig(isManual: Boolean = false) {
        lifecycleScope.launch {
            val config = RemoteConfigManager.fetchRemoteConfig()
            
            // 1. 公告检查：每次回到前台/启动时始终执行
            config.notice?.let {
                if (RemoteConfigManager.isNoticeValid(this@MainActivity, it)) {
                    noticeState = it
                    displayNoticeDialog = true
                }
            }

            // 2. 更新检查频率控制逻辑
            val isTimeForAutoCheck = RemoteConfigManager.shouldCheckUpdate(this@MainActivity)
            
            // 策略：如果是手动点击 OR 本进程冷启动后的第一次检查 OR 距离上次检查已满2天
            if (isManual || !hasCheckedUpdateThisSession || isTimeForAutoCheck) {
                
                // 获取当前安装包的 VersionCode
                val currentVersionCode = try {
                    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        packageManager.getPackageInfo(packageName, 0)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    }
                } catch (e: Exception) { 0 }

                var hasUpdate = false
                config.update?.let {
                    if (it.versionCode > currentVersionCode) {
                        updateState = it
                        displayUpdateDialog = true
                        hasUpdate = true
                    }
                }

                // 标记本进程已完成一次检查
                hasCheckedUpdateThisSession = true
                
                // 只有在满足2天周期时，才更新持久化的时间戳（手动检查和冷启动检查不消耗2天配额，除非当时刚好也满了2天）
                if (config.update != null && (isManual || isTimeForAutoCheck)) {
                    RemoteConfigManager.markUpdateChecked(this@MainActivity)
                }

                // 手动反馈
                if (isManual && !hasUpdate) {
                    Toast.makeText(this@MainActivity, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    updateInfo: UpdateInfo?,
    noticeInfo: NoticeInfo?,
    showUpdate: Boolean,
    showNotice: Boolean,
    onUpdateDismiss: () -> Unit,
    onNoticeDismiss: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    var selectedItem by remember { mutableIntStateOf(0) }
    val items = listOf("最近地震", "设置")

    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    // 1. 更新弹窗
    if (showUpdate && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { if (!updateInfo.forceUpdate) onUpdateDismiss() },
            title = { Text("发现新版本 ${updateInfo.versionName}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("更新内容：\n${updateInfo.changelog}")
                    if (updateInfo.forceUpdate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("本次为强制更新，请务必安装。", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    // 🚀 核心优化：获取首个可用的下载链接并执行跳转
                    val downloadUrls = updateInfo.apkUrls
                    if (downloadUrls.isEmpty()) {
                        Toast.makeText(context, "未找到有效的下载链接", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    // 1. 检查安装权限 (API 26+)
                    if (RemoteConfigManager.canInstallPackages(context)) {
                        // 2. 执行系统原生下载 (内部已集成 DownloadManager + 浏览器兜底)
                        RemoteConfigManager.startDownload(context, downloadUrls, updateInfo.versionName)
                        if (!updateInfo.forceUpdate) onUpdateDismiss()
                    } else {
                        // 3. 引导开启安装权限
                        Toast.makeText(context, "请先允许本应用安装新版本", Toast.LENGTH_LONG).show()
                        RemoteConfigManager.openInstallPermissionSettings(context)
                    }
                }) { Text("立即更新") }
            },
            dismissButton = if (!updateInfo.forceUpdate) {
                { TextButton(onClick = onUpdateDismiss) { Text("稍后") } }
            } else null
        )
    }

    // 2. 公告弹窗
    if (showNotice && noticeInfo != null) {
        AlertDialog(
            onDismissRequest = onNoticeDismiss,
            title = { Text(noticeInfo.title) },
            text = { Text(noticeInfo.content) },
            confirmButton = {
                TextButton(onClick = {
                    RemoteConfigManager.markNoticeAsShown(context, noticeInfo.noticeId)
                    onNoticeDismiss()
                }) { Text("知道了") }
            },
            dismissButton = if (!noticeInfo.linkUrl.isNullOrBlank()) {
                {
                    TextButton(onClick = {
                        uriHandler.openUri(noticeInfo.linkUrl)
                    }) { Text("查看详情") }
                }
            } else null
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(imageVector = if (index == 0) Icons.AutoMirrored.Filled.List else Icons.Filled.Settings, contentDescription = item) },
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
                SettingsScreen(onCheckUpdate = onCheckUpdate)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var historyList by remember { mutableStateOf(DataManager.getHistory(context)) }
    var itemToDelete by remember { mutableStateOf<EewData?>(null) }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { /* 可以增加点击进入详情的逻辑 */ },
                        onLongClick = { itemToDelete = data }
                    ),
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

    // 删除确认弹窗
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("删除记录") },
            text = { Text("确定要删除这条地震预警记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    itemToDelete?.id?.let { id ->
                        DataManager.deleteHistoryItem(context, id)
                        historyList = DataManager.getHistory(context)
                    }
                    itemToDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(onCheckUpdate: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var threshold by remember { mutableFloatStateOf(DataManager.getThreshold(context)) }
    var sourceList by remember { mutableStateOf(DataManager.getSources(context)) }

    // ========== 本地预估烈度阈值状态 ==========
    var localIntensityThreshold by remember {
        mutableIntStateOf(DataManager.getLocalIntensityThreshold(context))
    }

    // ========== 用户坐标状态 ==========
    val savedLat = DataManager.getLatitude(context)
    val savedLon = DataManager.getLongitude(context)

    LaunchedEffect(Unit) {
        if (savedLat.isNaN() || savedLon.isNaN()) {
            DataManager.saveLatitude(context, 25.996985)
            DataManager.saveLongitude(context, 119.419210)
        }
    }

    var latitudeText by remember { mutableStateOf(if (savedLat.isNaN()) "25.996985" else "%.6f".format(java.util.Locale.US, savedLat).trimEnd('0').trimEnd('.')) }
    var longitudeText by remember { mutableStateOf(if (savedLon.isNaN()) "119.419210" else "%.6f".format(java.util.Locale.US, savedLon).trimEnd('0').trimEnd('.')) }

    var latitudeError by remember { mutableStateOf<String?>(null) }
    var longitudeError by remember { mutableStateOf<String?>(null) }

    // 状态控制
    var showAddDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showRecommendDialog by remember { mutableStateOf(false) }
    var showFanWarningDialog by remember { mutableStateOf(false) }
    var fanWarningDontShowAgain by remember { mutableStateOf(false) }
    var recommendations by remember { mutableStateOf<RecommendationResponse?>(null) }
    var isLoadingRecommendations by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var sourcesExpanded by remember { mutableStateOf(false) }

    var sourceToDelete by remember { mutableStateOf<ApiSource?>(null) }

    var newSourceName by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("预警设置", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { showHelpDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "帮助说明",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 订阅源多选卡片 =================
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
                        sourceList.filter { !it.isHidden }.forEachIndexed { index, source ->
                            // 重新获取在原列表中的真实索引，用于更新状态
                            val realIndex = sourceList.indexOf(source)
                            if (realIndex != -1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                val newList = sourceList.toMutableList()
                                                val newIsSelected = !source.isSelected
                                                newList[realIndex] = source.copy(isSelected = newIsSelected)
                                                sourceList = newList

                                                if (newIsSelected && source.name.contains("FAN API") && !DataManager.isFanWarningDismissed(context)) {
                                                    showFanWarningDialog = true
                                                }
                                            },
                                            onLongClick = {
                                                if (!source.isPredefined) {
                                                    sourceToDelete = source
                                                } else {
                                                    Toast.makeText(context, "预置节点无法删除", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        .padding(vertical = 6.dp)
                                ) {
                                    Checkbox(
                                        checked = source.isSelected,
                                        onCheckedChange = { isChecked ->
                                            val newList = sourceList.toMutableList()
                                            newList[realIndex] = source.copy(isSelected = isChecked)
                                            sourceList = newList

                                            if (isChecked && source.name.contains("FAN API") && !DataManager.isFanWarningDismissed(context)) {
                                                showFanWarningDialog = true
                                            }
                                        }
                                    )
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(source.name, style = MaterialTheme.typography.bodyMedium)
                                        if (!source.isPredefined) {
                                            Text("自定义源 (长按可删除)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        } else {
                                            Text("预置节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { showAddDialog = true }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("+ 添加自定义源（开发者调试用，非用户功能）")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val isLocalIntensityEnabled = localIntensityThreshold > 0

        // ================= 阈值卡片 1 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val thresholdLabel = if (isLocalIntensityEnabled) {
                    "警报触发阈值: 未启用，已使用本地预估过滤器"
                } else {
                    "警报触发阈值: ${"%.1f".format(threshold)} 级"
                }

                Text(
                    text = thresholdLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isLocalIntensityEnabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                )

                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = 1f..9f,
                    steps = 15,
                    enabled = !isLocalIntensityEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 本地预估烈度阈值卡片 2 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .alpha(if (isLocalIntensityEnabled) 1f else 0.6f)
            ) {
                val thresholdLabel = if (localIntensityThreshold == 0) "不启用" else "$localIntensityThreshold 度"
                Text(
                    "本地预估烈度触发阈值：$thresholdLabel",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = if (localIntensityThreshold == 0)
                        "当前使用原有的震源震级阈值逻辑触发弹窗"
                    else
                        "当本地预估烈度 ≥ $localIntensityThreshold 度时触发全屏弹窗（将替代震级阈值逻辑）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (localIntensityThreshold == 0)
                        MaterialTheme.colorScheme.outline
                    else
                        MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                Slider(
                    value = localIntensityThreshold.toFloat(),
                    onValueChange = { localIntensityThreshold = it.toInt() },
                    valueRange = 0f..12f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("不启用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text("12度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 用户坐标卡片 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("我的位置坐标", style = MaterialTheme.typography.titleMedium)

                val locationDescString = androidx.compose.ui.text.buildAnnotatedString {
                    append("用于计算本地预估烈度和P波到达时间。请填写您所在地的经纬度。预置初始坐标位于福州（")
                    pushStringAnnotation(tag = "URL", annotation = "https://www.mapchaxun.cn/jingweidu")
                    withStyle(style = androidx.compose.ui.text.SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )) {
                        append("可在此处获取您的坐标")
                    }
                    pop()
                    append("）。")
                }

                androidx.compose.foundation.text.ClickableText(
                    text = locationDescString,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline),
                    onClick = { offset ->
                        locationDescString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let { annotation -> uriHandler.openUri(annotation.item) }
                    },
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                OutlinedTextField(
                    value = latitudeText,
                    onValueChange = { input ->
                        latitudeText = input
                        latitudeError = null
                    },
                    label = { Text("纬度（-90 ~ 90）") },
                    placeholder = { Text("例如：26.0745") },
                    isError = latitudeError != null,
                    supportingText = {
                        if (latitudeError != null) {
                            Text(latitudeError!!, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = longitudeText,
                    onValueChange = { input ->
                        longitudeText = input
                        longitudeError = null
                    },
                    label = { Text("经度（-180 ~ 180）") },
                    placeholder = { Text("例如：119.3062") },
                    isError = longitudeError != null,
                    supportingText = {
                        if (longitudeError != null) {
                            Text(longitudeError!!, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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

                var coordValid = true
                val latDouble: Double? = if (latitudeText.isBlank()) null else {
                    val parsed = latitudeText.toDoubleOrNull()
                    if (parsed == null || parsed < -90.0 || parsed > 90.0) {
                        latitudeError = "请输入有效的数字"
                        coordValid = false
                        null
                    } else parsed
                }

                val lonDouble: Double? = if (longitudeText.isBlank()) null else {
                    val parsed = longitudeText.toDoubleOrNull()
                    if (parsed == null || parsed < -180.0 || parsed > 180.0) {
                        longitudeError = "请输入有效的数字"
                        coordValid = false
                        null
                    } else parsed
                }

                if (!coordValid) return@Button

                if (latDouble != null) DataManager.saveLatitude(context, latDouble) else DataManager.saveLatitude(context, Double.NaN)
                if (lonDouble != null) DataManager.saveLongitude(context, lonDouble) else DataManager.saveLongitude(context, Double.NaN)

                DataManager.saveLocalIntensityThreshold(context, localIntensityThreshold)
                DataManager.saveSources(context, sourceList)
                DataManager.saveThreshold(context, threshold)

                val reloadIntent = Intent(context, EewForegroundService::class.java).apply { action = "ACTION_RELOAD_SOURCES" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(reloadIntent) else context.startService(reloadIntent)

                Toast.makeText(context, "保存成功，服务已无缝重载！", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存配置并生效")
        }

        Text(
            text = "在上方所做的任何改动，都需要在保存后才能生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 系统测试 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("系统测试", style = MaterialTheme.typography.titleMedium)
                Text("点击后请立即按下电源键息屏，测试 App 能否在 3 秒后强制亮屏并发出警报。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        sdf.timeZone = java.util.TimeZone.getTimeZone("GMT+08:00")
                        val currentTimeString = sdf.format(java.util.Date())

                        val dummyData = EewData(
                            type = "cwa_eew",
                            id = System.currentTimeMillis().toString(),
                            reportTime = currentTimeString,
                            reportNum = 1,
                            originTime = currentTimeString,
                            hypoCenter = "[模拟测试]花莲外海",
                            latitude = 23.9,
                            longitude = 122.2,
                            magnitude = 7.0,
                            depth = 10,
                            maxIntensity = "6弱"
                        )

                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val intent = Intent(context, EewForegroundService::class.java).apply {
                                action = "ACTION_TEST_ALERT"
                                putExtra("DUMMY_DATA", Gson().toJson(dummyData))
                            }
                            context.startService(intent)
                        }, 3000)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("3秒后模拟触发 7.0 级预警")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 版本信息 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("版本信息", style = MaterialTheme.typography.titleMedium)
                
                // 动态获取版本号
                val packageInfo = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }
                } catch (e: Exception) { null }
                
                val vName = packageInfo?.versionName ?: "未知"
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo?.longVersionCode ?: 0L
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo?.versionCode?.toLong() ?: 0L
                }

                Text("当前版本: $vName (Build $vCode)", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { 
                        onCheckUpdate()
                        Toast.makeText(context, "正在检查更新...", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("手动检查更新")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 应急资源 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("应急资源", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { uriHandler.openUri("https://cloud.kepuchina.cn/emergency") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("国家应急科普资源库(应急知识)")
                }
                Text(
                    text = "应急管理部新闻宣传司官方提供的应急知识库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                val scope = rememberCoroutineScope()
                Button(
                    onClick = {
                        showRecommendDialog = true
                        isLoadingRecommendations = true
                        loadError = null
                        scope.launch(Dispatchers.IO) {
                            val client = OkHttpClient.Builder()
                                .connectTimeout(15, TimeUnit.SECONDS)
                                .readTimeout(15, TimeUnit.SECONDS)
                                .build()
                            
                            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            val urls = listOf(
                                "https://gh-proxy.com/https://raw.githubusercontent.com/evan8686/eewconfig/main/myrecommend.json",
                                "https://cdn.jsdmirror.cn/gh/evan8686/eewconfig@main/myrecommend.json",
                                "https://cdn.jsdmirror.com/gh/evan8686/eewconfig@main/myrecommend.json",
                                "https://raw.githubusercontent.com/evan8686/eewconfig/main/myrecommend.json"
                            )
                            
                            var success = false
                            for (url in urls) {
                                try {
                                    val fullUrl = "$url?t=${System.currentTimeMillis()}"
                                    val request = Request.Builder()
                                        .url(fullUrl)
                                        .header("User-Agent", userAgent)
                                        .header("Cache-Control", "no-cache")
                                        .build()
                                    
                                    val response = client.newCall(request).execute()
                                    if (response.isSuccessful) {
                                        val body = response.body?.string()
                                        if (body != null && body.trim().startsWith("{")) {
                                            val data = Gson().fromJson(body, RecommendationResponse::class.java)
                                            withContext(Dispatchers.Main) {
                                                recommendations = data
                                                isLoadingRecommendations = false
                                                success = true
                                            }
                                            break // 成功获取，跳出循环
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("EEW_Receiver", "好物推荐加载失败 ($url): ${e.message}")
                                }
                            }

                            if (!success) {
                                withContext(Dispatchers.Main) {
                                    isLoadingRecommendations = false
                                    loadError = "抱歉，好物推荐数据异常，请稍后再查看"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("作者的好物推荐")
                }
                Text(
                    text = "开发者精选的防灾物资与生活好物分享",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            // 获取版本名
            val vName = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }
            } catch (e: Exception) { "未知" } ?: "未知"

            Text(
                text = "您安装的当前版本为${vName}版本，您可随时访问项目仓库获取版本更新情况。\n\n免责声明：本应用提供的预估烈度与倒计时均为算法模型推演的【参考值】，绝非官方指导。受网络、设备及算法限制，可能存在延迟、误差或误报。请务必结合实际体感与官方渠道通报采取避险措施。开发者按“现状”提供本应用，若用户因单一依赖本应用数据而导致任何生命、财产的直接或间接损失，开发者概不承担任何法律责任。继续使用则表示您已理解并接受上述声明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))

            val annotatedLinkString = androidx.compose.ui.text.buildAnnotatedString {
                append("项目仓库：")
                pushStringAnnotation(tag = "URL", annotation = "https://github.com/evan8686/EEW-Receiver")
                withStyle(style = androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) { append("Github") }
                pop()
                append(" ；")
                pushStringAnnotation(tag = "URL", annotation = "https://gitee.com/evan8686/eew-receiver")
                withStyle(style = androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) { append("Gitee (大陆境内可访问)") }
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

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(text = "EEW-Receiver 地震预警接收器\n配置说明", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = "为了确保您能持续正常接收地震预警，此APP需维持后台保活，请务必按照以下说明进行配置。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("1. 设置 - 通知 - EEW Receiver", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("• a. 开启“允许通知”，并将 “锁屏/横幅” 打勾。\n• b. 打开 “铃声/震动/允许打扰”（该项指即便手机处于免打扰模式时，应用仍能正常响铃和震动）。\n• c. 将“类别”下的 “地震预警事件” 设为允许通知，“地震预警后台监控”则维持默认的“静默通知”即可。", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("2. 设置 - 应用管理 - EEW Receiver - 权限管理", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("• a. 锁屏显示：设为 “允许”。\n• b. 滚动页面到下方，进入 “其他权限” -> “特殊应用权限”：（您先进入这个页面，尝试调整权限，系统会提示您需要解锁限制）\n• c. 回到EEW Receiver - 权限管理 主界面，此时点击右上角新出现的 3 个点，选择 “解锁所有授权限制” 进行解除。\n• d. 回到“特殊应用权限”页面\n    * 悬浮窗：设为“允许”。\n    * 后台弹出界面：设为“允许”。\n    * 发送全屏通知：设为“允许”。", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("3. 桌面多任务界面 - EEW Receiver - 锁定不清理", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("• a. 上滑呼出多任务界面\n• b. 找到 EEW Reveiver，点击 3 个点，选择 “锁定”  （即，在多任务窗口一键清理使用过的应用时，不会被杀掉）", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("4. 耗电管理 - EEW Receiver - 完全允许后台行为", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("• a. 进入耗电管理相关的设置界面\n• b. 找到 EEW Reveiver，选择 “完全允许后台行为”", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("5. 应用 - 自启动 - 允许 EEW Receiver 自启动", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "以上设置 以 OPPO ColorOS 16 为例，若您使用 小米澎湃OS，vivo OriginOS 等，请自行参考并在系统设置中操作相关选项。部分选项名称和入口在不同OS上可能略有差异，但基本类似。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = { Button(onClick = { showHelpDialog = false }) { Text("我知道了") } }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加自定义源") },
            text = {
                Column {
                    OutlinedTextField(value = newSourceName, onValueChange = { newSourceName = it }, label = { Text("源名称") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newSourceUrl, onValueChange = { newSourceUrl = it }, label = { Text("WebSocket 链接") }, modifier = Modifier.fillMaxWidth())
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
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }

    if (sourceToDelete != null) {
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text("删除自定义源") },
            text = { Text("确定要删除数据源「${sourceToDelete?.name}」吗？\n删除后需点击下方“保存配置并生效”才能生效。") },
            confirmButton = {
                TextButton(onClick = {
                    val newList = sourceList.toMutableList()
                    newList.remove(sourceToDelete)
                    sourceList = newList
                    sourceToDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { sourceToDelete = null }) { Text("取消") } }
        )
    }

    if (showRecommendDialog) {
        AlertDialog(
            onDismissRequest = { showRecommendDialog = false },
            title = { Text("作者的好物推荐", fontWeight = FontWeight.Bold) },
            text = {
                Box(modifier = Modifier.heightIn(max = 450.dp)) {
                    if (isLoadingRecommendations) {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (recommendations != null && loadError == null) {
                        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(text = recommendations?.tips ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                            recommendations?.categories?.forEach { category ->
                                Text(text = category.categoryName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                category.items.forEach { item ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(item.copyContent))
                                            Toast.makeText(context, "已复制，请打开淘宝查看", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                            Text(item.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "更新时间: ${recommendations?.updateTime}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                        }
                    } else {
                        Text(text = loadError ?: "加载失败，请检查网络连接", color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRecommendDialog = false }) { Text("关闭") } }
        )
    }

    if (showFanWarningDialog) {
        AlertDialog(
            onDismissRequest = { showFanWarningDialog = false },
            title = { Text("数据源风险提示", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("FAN API目前仅推荐在Wolfx API已知异常时备份使用。FAN API长期维护率目前存疑。若和wolfx API同时开启，可能出现异常（如报文解析冲突或重复推送）。建议仅 二选一 使用。")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { fanWarningDontShowAgain = !fanWarningDontShowAgain }) {
                        Checkbox(checked = fanWarningDontShowAgain, onCheckedChange = { fanWarningDontShowAgain = it })
                        Text("下次不再提示", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (fanWarningDontShowAgain) { DataManager.saveFanWarningDismissed(context, true) }
                    showFanWarningDialog = false
                }) { Text("我知道了") }
            }
        )
    }
}

data class RecommendationResponse(
    @SerializedName("version") val version: Int,
    @SerializedName("updateTime") val updateTime: String,
    @SerializedName("tips") val tips: String,
    @SerializedName("categories") val categories: List<RecommendationCategory>
)

data class RecommendationCategory(
    @SerializedName("categoryName") val categoryName: String,
    @SerializedName("items") val items: List<RecommendationItem>
)

data class RecommendationItem(
    @SerializedName("title") val title: String,
    @SerializedName("desc") val desc: String,
    @SerializedName("copyContent") val copyContent: String
)
