package com.evan8686.eewreceiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson

class EewForegroundService : Service() {

    // 【核心修改1】：将单一连接改为列表，用于管理多条通道
    private val webSocketManagers = mutableListOf<WebSocketManager>()
    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, "EEW_CHANNEL_ID")
            .setContentTitle("EEW Receiver")
            .setContentText("地震预警监控中...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        // 【核心修改2】：读取本地保存的所有源，并过滤出被用户勾选的源
        val activeSources = DataManager.getSources(this).filter { it.isSelected }

        if (activeSources.isEmpty()) {
            Log.w("EEW_Receiver", "没有勾选任何数据源！")
        }

        // 【核心修改3】：遍历所有被勾选的源，分别为它们建立独立的 WebSocket 连接
        activeSources.forEach { source ->
            val ws = WebSocketManager(
                sourceName = source.name,
                url = source.url
            ) { message ->
                handleMessage(message)
            }
            ws.connect()
            webSocketManagers.add(ws) // 加进列表统一管理
            Log.d("EEW_Receiver", "已连接订阅源: ${source.name}")
        }
    }

    private fun handleMessage(message: String) {
        // 🚨 终极防守：兼容大写 C 和小写 c，只要包含其中一个就放行！
        // 这样既能挡住绝对没有这两个词的心跳包，又能完美兼容中国大陆/台湾地区/日本的所有预警。
        if (!message.contains("\"HypoCenter\"") && !message.contains("\"Hypocenter\"")) {
            return
        }

        try {
            // 如果能走到这里，说明是一条真实的、包含有效字段的地震预警数据
            val eewData = gson.fromJson(message, EewData::class.java)

            // 兜底校验：只看 ID！不管震源地有没有内容，只要有 ID 就算有效预警（防止抛弃保命的第1报）
            if (eewData?.id.isNullOrEmpty()) {
                Log.w("EEW_Receiver", "数据缺乏唯一 ID，无法处理，已抛弃。")
                return
            }

            Log.d("EEW_Receiver", "成功解析地震预警:\n${eewData.toReadableText()}")

            val threshold = DataManager.getThreshold(this).toDouble()
            AlertManager(applicationContext).triggerAlert(eewData, threshold)

        } catch (e: Exception) {
            // 🚨 核心排错：如果下次真地震来了没响，连接电脑看这行日志
            // 它会明确把你拦截到的导致崩溃的原始报文打印出来，方便光速修 Bug
            Log.e("EEW_Receiver", "⚠️ JSON解析致命失败: ${e.message}\n错误报文: $message")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 【核心修改4】：服务销毁或重启时，一并切断列表里所有的 WebSocket 连接
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
