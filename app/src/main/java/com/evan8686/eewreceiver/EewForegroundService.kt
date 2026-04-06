package com.evan8686.eewreceiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson

class EewForegroundService : Service() {

    private val webSocketManagers = mutableListOf<WebSocketManager>()
    private val gson = Gson()

    // 🚨 核心修改 1：将 AlertManager 提升为成员变量（单例化）
    private lateinit var alertManager: AlertManager

    // 🚨 核心修改 2：定义广播动作常量（刹车信号）
    companion object {
        const val ACTION_STOP_ALERT = "com.evan8686.eewreceiver.ACTION_STOP_ALERT"
    }

    // 🚨 核心修改 3：创建广播接收器，专门负责“踩刹车”
    private val stopAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_ALERT) {
                Log.d("EEW_Receiver", "接收到停止指令，正在释放警报资源...")
                alertManager.release()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 初始化单例警报管理器
        alertManager = AlertManager(applicationContext)

        // 🚨 核心修改 4：注册广播接收器
        val filter = IntentFilter(ACTION_STOP_ALERT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopAlertReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopAlertReceiver, filter)
        }

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

            // 🚨 核心修改 5：复用唯一的 alertManager 实例
            // 内部已实现“新报文自动掐断旧报文”逻辑
            alertManager.triggerAlert(eewData, threshold)

        } catch (e: Exception) {
            Log.e("EEW_Receiver", "⚠️ JSON解析致命失败: ${e.message}\n错误报文: $message")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // 🚨 核心修改 6：服务销毁时，执行彻底清理
        try {
            unregisterReceiver(stopAlertReceiver)
        } catch (e: Exception) {
            Log.e("EEW_Receiver", "注销广播接收器失败: ${e.message}")
        }

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
