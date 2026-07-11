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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var threshold by remember { mutableStateOf(DataManager.getThreshold(context)) }
    var sourceList by remember { mutableStateOf(DataManager.getSources(context)) }

    // ========== 本地预估烈度阈值状态 ==========
    // 读取已保存值，0 表示不启用
    var localIntensityThreshold by remember {
        mutableStateOf(DataManager.getLocalIntensityThreshold(context))
    }

    // ========== 用户坐标状态 ==========
    // 从 SharedPreferences 读取已保存的坐标
    val savedLat = DataManager.getLatitude(context)
    val savedLon = DataManager.getLongitude(context)

    // 👇 新增：如果检测到没有保存过坐标（如首次安装），直接静默保存默认值到系统存储 👇
    LaunchedEffect(Unit) {
        if (savedLat.isNaN() || savedLon.isNaN()) {
            DataManager.saveLatitude(context, 25.996985)
            DataManager.saveLongitude(context, 119.419210)
        }
    }

    var latitudeText by remember { mutableStateOf(if (savedLat.isNaN()) "25.996985" else savedLat.toString()) }
    var longitudeText by remember { mutableStateOf(if (savedLon.isNaN()) "119.419210" else savedLon.toString()) }

    // 各输入框的错误提示信息，null 表示无错误
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

    // 用于记录当前准备删除的自定义源
    var sourceToDelete by remember { mutableStateOf<ApiSource?>(null) }

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
                    imageVector = Icons.Filled.Info,
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
                            // 前 7 个（索引 0-6）是默认源，7 及之后的是用户自定义源
                            val isCustomSource = index >= 7

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            // 点击整行也能切换选中状态，优化触摸体验
                                            val newList = sourceList.toMutableList()
                                            val newIsSelected = !source.isSelected
                                            newList[index] = source.copy(isSelected = newIsSelected)
                                            sourceList = newList

                                            // 🚨 任务三：检测到勾选 FAN API 时的风险提示
                                            if (newIsSelected && source.name.contains("FAN API") && !DataManager.isFanWarningDismissed(context)) {
                                                showFanWarningDialog = true
                                            }
                                        },
                                        onLongClick = {
                                            if (isCustomSource) {
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
                                        newList[index] = source.copy(isSelected = isChecked)
                                        sourceList = newList

                                        // 🚨 任务三：检测到勾选 FAN API 时的风险提示
                                        if (isChecked && source.name.contains("FAN API") && !DataManager.isFanWarningDismissed(context)) {
                                            showFanWarningDialog = true
                                        }
                                    }
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(source.name, style = MaterialTheme.typography.bodyMedium)
                                    // 隐藏了原先的 url 显示，改为优雅的文字说明
                                    if (isCustomSource) {
                                        Text("自定义源 (长按可删除)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Text("预置节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { showAddDialog = true }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("+ 添加自定义源（开发者调试用接口，不建议用户开启。不同订阅源字段名称差异巨大，未做后端解析匹配的自定义订阅链接无法解析和推送）")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 判断本地预估烈度是否开启
        val isLocalIntensityEnabled = localIntensityThreshold > 0

        // ================= 阈值卡片 1 (震源震级阈值) =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val thresholdLabel = if (isLocalIntensityEnabled) {
                    "警报触发阈值: 未启用，已使用本地预估过滤器"
                } else {
                    "警报触发阈值: ${String.format("%.1f", threshold)} 级"
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
                    steps = 15, // 优化2：(9-1)/0.5 - 1 = 15个间隔，实现每0.5一个点
                    enabled = !isLocalIntensityEnabled, // 优化1：启用烈度阈值时禁用本滑块
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ================= 本地预估烈度阈值卡片 2 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            // 优化1：当未启用时，整体加上视觉置灰效果 (alpha) 但保持可交互
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .alpha(if (isLocalIntensityEnabled) 1f else 0.6f)
            ) {
                // 标题行：动态展示当前选值
                val thresholdLabel = if (localIntensityThreshold == 0) "不启用" else "${localIntensityThreshold} 度"
                Text(
                    "本地预估烈度触发阈值：$thresholdLabel",
                    style = MaterialTheme.typography.titleMedium
                )

                // 说明文字
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

                // 滑动条：0（不启用）到 12（12度），步进 1
                Slider(
                    value = localIntensityThreshold.toFloat(),
                    onValueChange = { localIntensityThreshold = it.toInt() },
                    valueRange = 0f..12f,
                    steps = 11, // steps = 总刻度数 - 2，即 0~12 共13个值，steps=11
                    modifier = Modifier.fillMaxWidth()
                )

                // 两端刻度标注
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

                // 优化4：带有超链接的可点击坐标描述文本
                val locationDescString = androidx.compose.ui.text.buildAnnotatedString {
                    append("用于计算本地预估烈度和P波到达时间。请填写您所在地的经纬度。预置初始坐标位于福州（")
                    pushStringAnnotation(tag = "URL", annotation = "https://lbs.navinfo.com/picker/index.html")
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

                // 纬度输入框（范围 -90 ~ 90）
                OutlinedTextField(
                    value = latitudeText,
                    onValueChange = { input ->
                        latitudeText = input
                        // 实时清除之前的错误提示，等保存时再统一校验
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

                // 经度输入框（范围 -180 ~ 180）
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

                // ---- 坐标校验逻辑 ----
                var coordValid = true

                // 校验纬度：允许为空（表示不启用），若有值则必须是合法浮点数且在范围内
                val latDouble: Double? = if (latitudeText.isBlank()) {
                    null // 空值视为"不设置"
                } else {
                    val parsed = latitudeText.toDoubleOrNull()
                    when {
                        parsed == null -> {
                            latitudeError = "请输入有效的数字"
                            coordValid = false
                            null
                        }
                        parsed < -90.0 || parsed > 90.0 -> {
                            latitudeError = "纬度必须在 -90 到 90 之间"
                            coordValid = false
                            null
                        }
                        else -> parsed
                    }
                }

                // 校验经度：同上
                val lonDouble: Double? = if (longitudeText.isBlank()) {
                    null
                } else {
                    val parsed = longitudeText.toDoubleOrNull()
                    when {
                        parsed == null -> {
                            longitudeError = "请输入有效的数字"
                            coordValid = false
                            null
                        }
                        parsed < -180.0 || parsed > 180.0 -> {
                            longitudeError = "经度必须在 -180 到 180 之间"
                            coordValid = false
                            null
                        }
                        else -> parsed
                    }
                }

                if (!coordValid) return@Button

                // 坐标合法，持久化保存（或删除）
                if (latDouble != null) DataManager.saveLatitude(context, latDouble)
                else DataManager.saveLatitude(context, Double.NaN)
                if (lonDouble != null) DataManager.saveLongitude(context, lonDouble)
                else DataManager.saveLongitude(context, Double.NaN)

                // 保存本地预估烈度触发阈值（0=不启用，1-12=对应烈度度数）
                DataManager.saveLocalIntensityThreshold(context, localIntensityThreshold)

                DataManager.saveSources(context, sourceList)
                DataManager.saveThreshold(context, threshold)

                val reloadIntent = Intent(context, EewForegroundService::class.java).apply {
                    action = "ACTION_RELOAD_SOURCES"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(reloadIntent)
                } else {
                    context.startService(reloadIntent)
                }

                Toast.makeText(context, "保存成功，服务已无缝重载！", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存配置并生效")
        }

        // 👇 新增的未保存提示说明（已居中并占满宽度） 👇
        Text(
            text = "在上方所做的任何改动，都需要在保存后才能生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center, // 设置文字居中对齐
            modifier = Modifier
                .fillMaxWidth() // 宽度撑满，确保相对于屏幕完美居中
                .padding(top = 6.dp)
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

                        // 🚨 核心修复：强制使用北京时间（UTC+8）来格式化当前的系统时间。
                        // 这样无论用户设备时间在哪个时区，生成的文本永远是北京时间，能与解析器完美匹配。
                        sdf.timeZone = java.util.TimeZone.getTimeZone("GMT+08:00")

                        val currentTimeString = sdf.format(java.util.Date())

                        val dummyData = EewData(
                            type = "cwa_eew", // 明确指定为国内源类型，确保触发计算器的 UTC+8 解析逻辑
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
                                putExtra("DUMMY_DATA", com.google.gson.Gson().toJson(dummyData))
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

        // ================= 应急资源 =================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("应急资源", style = MaterialTheme.typography.titleMedium)

                // 按钮1
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

                // 按钮2
                val scope = rememberCoroutineScope()
                Button(
                    onClick = {
                        showRecommendDialog = true
                        isLoadingRecommendations = true
                        loadError = null // 每次点击重置错误状态
                        scope.launch(Dispatchers.IO) {
                            val client = OkHttpClient.Builder()
                                .connectTimeout(10, TimeUnit.SECONDS)
                                .readTimeout(10, TimeUnit.SECONDS)
                                .build()
                            
                            var success = false
                            // 💡 兜底策略：最多尝试 3 次
                            for (attempt in 1..3) {
                                try {
                                    val request = Request.Builder()
                                        .url("https://raw.giteeusercontent.com/evan8686/eewconfig/raw/master/myrecommend.json?t=${System.currentTimeMillis()}")
                                        .header("Cache-Control", "no-cache")
                                        .build()
                                    
                                    val response = client.newCall(request).execute()
                                    if (response.isSuccessful) {
                                        val body = response.body?.string()
                                        if (body != null) {
                                            val data = Gson().fromJson(body, RecommendationResponse::class.java)
                                            withContext(Dispatchers.Main) {
                                                recommendations = data
                                                isLoadingRecommendations = false
                                                success = true
                                            }
                                            break // 成功后跳出重试循环
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("EEW_Receiver", "好物推荐加载重试 $attempt/3 失败: ${e.message}")
                                }
                                // 如果没成功，且还没到最后一次，稍微等一下再试
                                if (!success && attempt < 3) {
                                    kotlinx.coroutines.delay(1000) 
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
            Text(
                text = "您安装的当前版本为2.1.0版本，您可随时访问项目仓库获取版本更新情况。\n\n免责声明：本应用提供的预估烈度与倒计时均为算法模型推演的【参考值】，绝非官方指导。受网络、设备及算法限制，可能存在延迟、误差或误报。请务必结合实际体感与官方渠道通报采取避险措施。开发者按“现状”提供本应用，若用户因单一依赖本应用数据而导致任何生命、财产的直接或间接损失，开发者概不承担任何法律责任。继续使用则表示您已理解并接受上述声明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 👇 修改后的项目仓库链接部分 👇
            val annotatedLinkString = androidx.compose.ui.text.buildAnnotatedString {
                append("项目仓库：")

                // Github 链接
                pushStringAnnotation(tag = "URL", annotation = "https://github.com/evan8686/EEW-Receiver")
                withStyle(style = androidx.compose.ui.text.SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )) {
                    append("Github")
                }
                pop()

                append(" ；")

                // Gitee 链接
                pushStringAnnotation(tag = "URL", annotation = "https://gitee.com/evan8686/eew-receiver")
                withStyle(style = androidx.compose.ui.text.SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )) {
                    append("Gitee (大陆境内可访问)")
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

    // 3. 删除自定义源确认弹窗
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
            dismissButton = {
                TextButton(onClick = { sourceToDelete = null }) { Text("取消") }
            }
        )
    }

    // 4. 作者的好物推荐弹窗
    if (showRecommendDialog) {
        AlertDialog(
            onDismissRequest = { showRecommendDialog = false },
            title = { Text("作者的好物推荐", fontWeight = FontWeight.Bold) },
            text = {
                Box(modifier = Modifier.heightIn(max = 450.dp)) {
                    if (isLoadingRecommendations) {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (recommendations != null && loadError == null) {
                        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = recommendations?.tips ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            recommendations?.categories?.forEach { category ->
                                Text(
                                    text = category.categoryName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                                category.items.forEach { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
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
                            Text(
                                text = "更新时间: ${recommendations?.updateTime}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End
                            )
                        }
                    } else {
                        Text(
                            text = loadError ?: "加载失败，请检查网络连接",
                            color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecommendDialog = false }) { Text("关闭") }
            }
        )
    }

    // 5. FAN API 风险提示弹窗
    if (showFanWarningDialog) {
        AlertDialog(
            onDismissRequest = { showFanWarningDialog = false },
            title = { Text("数据源风险提示", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("FAN API目前仅推荐在Wolfx API异常时备份使用。若和wolfx API同时开启，可能出现异常（如报文解析冲突或重复推送）。建议仅 二选一 使用。")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { fanWarningDontShowAgain = !fanWarningDontShowAgain }
                    ) {
                        Checkbox(
                            checked = fanWarningDontShowAgain,
                            onCheckedChange = { fanWarningDontShowAgain = it }
                        )
                        Text("下次不再提示", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (fanWarningDontShowAgain) {
                        DataManager.saveFanWarningDismissed(context, true)
                    }
                    showFanWarningDialog = false
                }) { Text("我知道了") }
            }
        )
    }
}

data class RecommendationResponse(
    val version: Int,
    val updateTime: String,
    val tips: String,
    val categories: List<RecommendationCategory>
)

data class RecommendationCategory(
    val categoryName: String,
    val items: List<RecommendationItem>
)

data class RecommendationItem(
    val title: String,
    val desc: String,
    val copyContent: String
)
