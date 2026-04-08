package com.evan8686.eewreceiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson

class EewForegroundService : Service() {

    private val webSocketManagers = mutableListOf<WebSocketManager>()
    private val gson = Gson()

    // 🚨 核心修改 1：将 AlertManager 提升为全局唯一的成员变量（单例化）
    private lateinit var alertManager: AlertManager

    // 🚨 核心修改 2：定义服务接收的指令常量
    companion object {
        const val ACTION_STOP_ALERT = "com.evan8686.eewreceiver.ACTION_STOP_ALERT"
        const val ACTION_TEST_ALERT = "ACTION_TEST_ALERT" // 与 MainActivity 的测试发信保持一致
        const val ACTION_RELOAD_SOURCES = "ACTION_RELOAD_SOURCES" // 🚨 新增：热重载指令
    }

    override fun onCreate() {
        super.onCreate()

        // 初始化唯一的警报管理器实例
        alertManager = AlertManager(applicationContext)

        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, "EEW_CHANNEL_ID")
            .setContentTitle("EEW Receiver")
            .setContentText("地震预警监控中...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        val activeSources = DataManager.getSources(this).filter { it.isSelected }

        if (activeSources.isEmpty()) {
            Log.w("EEW_Receiver", "没有勾选任何数据源！")
        }

        activeSources.forEach { source ->
            val ws = WebSocketManager(
                sourceName = source.name,
                url = source.url
            ) { message ->
                handleMessage(message)
            }
            ws.connect()
            webSocketManagers.add(ws)
            Log.d("EEW_Receiver", "已连接订阅源: ${source.name}")
        }
    }

    private fun handleMessage(message: String) {
        if (!message.contains("\"HypoCenter\"") && !message.contains("\"Hypocenter\"")) {
            return
        }

        try {
            val eewData = gson.fromJson(message, EewData::class.java)

            if (eewData?.id.isNullOrEmpty()) {
                Log.w("EEW_Receiver", "数据缺乏唯一 ID，无法处理，已抛弃。")
                return
            }

            Log.d("EEW_Receiver", "成功解析地震预警:\n${eewData.toReadableText()}")

            val threshold = DataManager.getThreshold(this).toDouble()

            // 🚨 复用唯一的 alertManager 实例，内部已实现“新报文自动掐断旧报文”逻辑
            alertManager.triggerAlert(eewData, threshold)

        } catch (e: Exception) {
            Log.e("EEW_Receiver", "⚠️ JSON解析致命失败: ${e.message}\n错误报文: $message")
        }
    }

    // 🚨 核心修改 3：统一指挥部！拦截所有的显式控制命令
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALERT -> {
                // 来自 LockScreenAlertActivity 的“刹车”命令
                Log.d("EEW_Receiver", "直接收到停止指令，正在释放全局警报资源...")
                alertManager.release()
            }
            ACTION_TEST_ALERT -> {
                // 来自 MainActivity 的“测试警报”命令
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
                // 🚨 魔法：热重载逻辑
                Log.d("EEW_Receiver", "收到热重载指令，正在无缝切换订阅源...")

                // 1. 优雅地断开所有旧连接，并清空名册
                webSocketManagers.forEach { it.disconnect() }
                webSocketManagers.clear()

                // 2. 读取刚被保存的新名单
                val activeSources = DataManager.getSources(this).filter { it.isSelected }

                if (activeSources.isEmpty()) {
                    Log.w("EEW_Receiver", "热重载后发现没有勾选任何数据源！")
                }

                // 3. 重新建立新连接
                activeSources.forEach { source ->
                    val ws = WebSocketManager(
                        sourceName = source.name,
                        url = source.url
                    ) { message ->
                        handleMessage(message)
                    }
                    ws.connect()
                    webSocketManagers.add(ws)
                    Log.d("EEW_Receiver", "已连接新订阅源: ${source.name}")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // 🚨 服务销毁时直接释放资源，无需再注销广播
        alertManager.release()

        webSocketManagers.forEach { it.disconnect() }
        webSocketManagers.clear()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "EEW_CHANNEL_ID",
                "地震预警后台监控",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "维持 WebSocket 连接以接收推送"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}