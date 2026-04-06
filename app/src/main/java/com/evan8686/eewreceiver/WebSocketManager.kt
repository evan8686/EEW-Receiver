package com.evan8686.eewreceiver

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val sourceName: String,
    private val url: String,
    private val onMessageReceived: (String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private var isClosedByUser = false
    private var reconnectAttemptCount = 0 // 记录重连次数

    // 配置心跳包：每 30 秒自动发送 ping，这是最省电的保活方式
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        isClosedByUser = false
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                Log.d("EEW_Receiver", "[$sourceName] WebSocket 已连接: $url")
                reconnectAttemptCount = 0 // 连接成功，重置重连次数
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                onMessageReceived(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                Log.d("EEW_Receiver", "[$sourceName] 连接关闭: $reason")
                if (!isClosedByUser) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.e("EEW_Receiver", "[$sourceName] 连接异常断开: ${t.message}")
                if (!isClosedByUser) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        // 💡 核心优化：指数退避重连算法 (5s, 10s, 20s, 40s, 最大 60s)
        val delayMillis = (5000L * Math.pow(2.0, reconnectAttemptCount.toDouble())).toLong().coerceAtMost(60000L)

        Log.d("EEW_Receiver", "[$sourceName] $delayMillis 毫秒后尝试重新连接...")
        reconnectAttemptCount++

        // 使用主线程 Handler 延迟执行，绝对不会卡死 OkHttp 的网络线程池
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isClosedByUser) connect()
        }, delayMillis)
    }

    fun disconnect() {
        isClosedByUser = true
        webSocket?.close(1000, "用户主动停止监控")
        webSocket = null
        client.dispatcher.executorService.shutdown()
    }
}
