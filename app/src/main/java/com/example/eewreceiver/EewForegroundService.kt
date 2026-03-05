package com.example.eewreceiver

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

    private var webSocketManager: WebSocketManager? = null
    // 初始化咱们第一步引入的 JSON 解析工具
    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        // 1. 创建安卓系统要求的通知渠道
        createNotificationChannel()

        // 2. 创建一个常驻通知（也就是您要求的“地震预警监控中...”）
        val notification = NotificationCompat.Builder(this, "EEW_CHANNEL_ID")
            .setContentTitle("EEW Receiver")
            .setContentText("地震预警监控中...")
            // 暂时使用安卓自带的感叹号图标，后续您可以自己换
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // 3. 正式启动前台服务，让 App 免死于系统杀后台机制
        startForeground(1, notification)

        // 4. 启动 WebSocket 监听
        // 注：目前暂时将 API 写死在这里测试，之后我们会改成从“设置”页面读取
        webSocketManager = WebSocketManager(
            sourceName = "Wolfx 台湾中央气象署 EEW",
            url = "wss://ws-api.wolfx.jp/cwa_eew"
        ) { message ->
            handleMessage(message)
        }
        webSocketManager?.connect()
    }

    // 收到推送后，处理数据的核心逻辑
    private fun handleMessage(message: String) {
        try {
            // 解析 JSON
            val eewData = gson.fromJson(message, EewData::class.java)
            Log.d("EEW_Receiver", "成功解析地震预警:\n${eewData.toReadableText()}")

            // 🚨 核心连接：把真实收到的数据丢给警报器！
            // (V1.0 测试版阈值先写死 3.0，后续您可以做成从本地 DataStore 读取用户设置)
            AlertManager(applicationContext).triggerAlert(eewData, 3.0)

        } catch (e: Exception) {
            Log.e("EEW_Receiver", "JSON解析失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 的作用是：万一服务真的被系统强杀了，只要内存一空出来，系统会自动把它拉活
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketManager?.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 我们不需要绑定模式，直接返回 null
        return null
    }

    // 安卓 8.0 以上系统要求必须有通知渠道才能发通知
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "EEW_CHANNEL_ID",
                "地震预警后台监控", // 用户在手机设置里看到的通知分类名
                NotificationManager.IMPORTANCE_LOW // 低重要性，不会每次都响铃，只静默常驻
            )
            channel.description = "维持 WebSocket 连接以接收推送"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}