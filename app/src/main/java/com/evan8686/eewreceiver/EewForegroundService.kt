package com.evan8686.eewreceiver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class EewForegroundService : Service() {

    private val webSocketManagers = mutableMapOf<String, WebSocketManager>() // 🚨 改为 Map 方便索引
    private val gson = Gson()

    // 🚨 2.2.0 新增：连接状态 Flow，全 App 唯一状态总线
    companion object {
        private val _connectionStates = MutableStateFlow<Map<String, ApiSource>>(emptyMap())
        val connectionStates = _connectionStates.asStateFlow()

        const val ACTION_STOP_ALERT = "com.evan8686.eewreceiver.ACTION_STOP_ALERT"
        const val ACTION_TEST_ALERT = "ACTION_TEST_ALERT"
        const val ACTION_RELOAD_SOURCES = "ACTION_RELOAD_SOURCES"
        
        private const val CONNECTION_CHANNEL_ID = "CONNECTION_STATUS_CHANNEL"
    }

    private lateinit var alertManager: AlertManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watchdogJob: Job? = null
    // 🚨 2.2.2 优化：记录每个源的连续红灯重试次数
    private val redLightRetryCounts = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        alertManager = AlertManager(applicationContext)
        createNotificationChannels()

        val notification = NotificationCompat.Builder(this, "EEW_CHANNEL_ID")
            .setContentTitle("EEW Receiver")
            .setContentText("地震预警监控中...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
        
        startAllConnections()
        startWatchdog()
    }

    private fun startAllConnections() {
        val activeSources = DataManager.getSources(this).filter { it.isSelected && !it.isHidden }
        
        // 初始化状态表
        val initialMap = activeSources.associate { it.url to it.copy(connectionStatus = ConnectionStatus.DISCONNECTED) }
        _connectionStates.value = initialMap

        activeSources.forEach { source ->
            val ws = WebSocketManager(
                sourceName = source.name,
                url = source.url,
                onStatusChanged = { status, activeTime ->
                    updateSourceStatus(source.url, status, activeTime)
                }
            ) { message ->
                handleMessage(message)
            }
            ws.connect()
            webSocketManagers[source.url] = ws
        }
    }

    private fun updateSourceStatus(url: String, status: ConnectionStatus, activeTime: Long) {
        val currentMap = _connectionStates.value.toMutableMap()
        val source = currentMap[url] ?: return
        
        val newSource = source.copy(
            connectionStatus = status,
            lastActiveTime = if (activeTime != 0L) activeTime else source.lastActiveTime
        )
        currentMap[url] = newSource
        _connectionStates.value = currentMap
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(30000) // 每 30 秒巡检一次
                val now = System.currentTimeMillis()
                val currentStates = _connectionStates.value
                
                currentStates.forEach { (url, source) ->
                    if (source.isSelected) {
                        val diff = (now - source.lastActiveTime) / 1000
                        
                        if (diff > 180 && source.lastActiveTime != 0L) {
                            // 🚨 进入红灯状态
                            val currentRetry = redLightRetryCounts[url] ?: 0
                            
                            if (currentRetry < 3) {
                                // 第一阶段：前 3 次发现红灯，执行静默自愈重连
                                val nextRetry = currentRetry + 1
                                redLightRetryCounts[url] = nextRetry
                                Log.w("EEW_Receiver", "检测到死链 [${source.name}]，正在执行第 $nextRetry 次静默重连...")
                                webSocketManagers[url]?.connect()
                            } else if (currentRetry == 3) {
                                // 第二阶段：3 次尝试后依然红灯，做出停止决策并通知用户
                                redLightRetryCounts[url] = 4 // 标记为已停止
                                Log.e("EEW_Receiver", "[${source.name}] 连续 3 次自愈失败，已停止后台重连并通知用户")
                                sendConnectionAlertNotification(source.name)
                            }
                            // currentRetry >= 4 时，保持红灯并进入静默期，不再操作
                        } else {
                            // 🟢 恢复绿灯/黄灯：重置重试计数
                            if (redLightRetryCounts.containsKey(url)) {
                                redLightRetryCounts.remove(url)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendConnectionAlertNotification(sourceName: String) {
        val notification = NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("订阅源已断联")
            .setContentText("[$sourceName] 订阅源 持续无心跳。请检查网络，尝试杀端重开APP或重启手机（请务必确认您已将APP的耗电管理设为 完全允许后台行为）。若仍未恢复，请访问 Wolfx API 官网确认上游服务状态。")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("[$sourceName] 订阅源 持续无心跳。请检查网络，尝试杀端重开APP或重启手机（请务必确认您已将APP的耗电管理设为 完全允许后台行为）。若仍未恢复，请访问 Wolfx API 官网确认上游服务状态。"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(sourceName.hashCode(), notification)
    }

    private fun handleMessage(message: String) {
        // ... (保持原有逻辑不变)
        // 🚨 增强兼容性：同时检测 Wolfx 的 HypoCenter/Hypocenter 和 FAN API 的 placeName 关键字
        if (!message.contains("\"HypoCenter\"") && !message.contains("\"Hypocenter\"") && !message.contains("\"placeName\"")) {
            return
        }

        try {
            val jsonObject = gson.fromJson(message, JsonObject::class.java)

            // 1. 检查外层 type 过滤 initial 数据
            val outerType = jsonObject.get("type")?.asString
            if (outerType != null && outerType.contains("initial")) {
                Log.d("EEW_Receiver", "收到 initial 类型数据，已忽略。")
                return
            }

            // 2. 核心解析逻辑：处理 FAN API 的嵌套结构
            val eewData: EewData? = if (jsonObject.has("Data") && jsonObject.get("Data").isJsonObject) {
                // 📦 FAN API 模式：解析内部的 "Data" 对象
                val dataJson = jsonObject.getAsJsonObject("Data")
                val parsed = gson.fromJson(dataJson, EewData::class.java)

                // 补全 type 字段（将外层 source 转换为 type，如 "cwa" -> "cwa_eew"）
                // 这样可以复用原有的 getFormattedTime() 时区判定逻辑
                val source = jsonObject.get("source")?.asString
                if (parsed != null && source != null) {
                    parsed.copy(type = "${source}_eew")
                } else {
                    parsed
                }
            } else {
                // 📃 Wolfx 模式：直接解析根对象
                gson.fromJson(message, EewData::class.java)
            }

            if (eewData?.id.isNullOrEmpty()) {
                Log.w("EEW_Receiver", "数据缺乏唯一 ID，无法处理，已抛弃。")
                return
            }

            Log.d("EEW_Receiver", "成功解析地震预警 [源: ${eewData.type}]:\n${eewData.toReadableText()}")

            val threshold = DataManager.getThreshold(this).toDouble()

            // 🚨 复用唯一的 alertManager 实例，内部已实现“新报文自动掐断旧报文”逻辑
            alertManager.triggerAlert(eewData, threshold)

        } catch (e: Exception) {
            Log.e("EEW_Receiver", "⚠️ JSON解析致命失败: ${e.message}\n错误报文: $message")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALERT -> {
                Log.d("EEW_Receiver", "直接收到停止指令，正在释放全局警报资源...")
                alertManager.release()
            }
            ACTION_TEST_ALERT -> {
                val dummyJson = intent.getStringExtra("DUMMY_DATA")
                if (dummyJson != null) {
                    try {
                        val dummyData = gson.fromJson(dummyJson, EewData::class.java)
                        val threshold = DataManager.getThreshold(this).toDouble()
                        Log.d("EEW_Receiver", "收到测试指令，触发全局警报管理器...")
                        alertManager.triggerAlert(dummyData, threshold)
                    } catch (e: Exception) {
                        Log.e("EEW_Receiver", "测试数据解析失败: ${e.message}")
                    }
                }
            }
            ACTION_RELOAD_SOURCES -> {
                Log.d("EEW_Receiver", "收到热重载指令，正在无缝切换订阅源...")
                // 1. 彻底清理旧状态
                redLightRetryCounts.clear()
                webSocketManagers.values.forEach { it.disconnect() }
                webSocketManagers.clear()
                // 2. 重新启动连接
                startAllConnections()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // 取消所有协程
        alertManager.release()
        webSocketManagers.values.forEach { it.disconnect() }
        webSocketManagers.clear()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // 渠道 1：主监控频道
            val mainChannel = android.app.NotificationChannel(
                "EEW_CHANNEL_ID",
                "地震预警后台监控",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "维持 WebSocket 连接以接收推送" }
            
            // 渠道 2：连接状态监控频道
            val statusChannel = android.app.NotificationChannel(
                CONNECTION_CHANNEL_ID,
                "订阅源连接情况",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "当订阅源连接异常时发送静默提示" }
            
            manager.createNotificationChannels(listOf(mainChannel, statusChannel))
        }
    }
}
