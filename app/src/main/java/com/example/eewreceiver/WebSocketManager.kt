package com.example.eewreceiver

import android.util.Log
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val sourceName: String, // 用于支持您要求的“多个源自定义名称”
    private val url: String,
    private val onMessageReceived: (String) -> Unit // 收到推送后把数据传输出去
) {
    private var webSocket: WebSocket? = null
    private var isClosedByUser = false

    // 核心：配置心跳包
    private val client = OkHttpClient.Builder()
        // 每 30 秒自动发一次 ping，保持连接不断，极其省电
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        isClosedByUser = false
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                Log.d("EEW_Receiver", "[$sourceName] WebSocket 已连接: $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Log.d("EEW_Receiver", "[$sourceName] 收到推送: $text")
                // 将收到的文本传给 App 的其他部分处理
                onMessageReceived(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                Log.d("EEW_Receiver", "[$sourceName] 连接关闭: $reason")
                // 如果不是用户手动关闭的，就自动重连
                if (!isClosedByUser) {
                    reconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.e("EEW_Receiver", "[$sourceName] 连接异常断开: ${t.message}")
                // 遇到网络波动断开时，延迟 5 秒后自动重连
                if (!isClosedByUser) {
                    Thread.sleep(5000)
                    reconnect()
                }
            }
        })
    }

    private fun reconnect() {
        Log.d("EEW_Receiver", "[$sourceName] 正在尝试重新连接...")
        connect()
    }

    fun disconnect() {
        isClosedByUser = true
        webSocket?.close(1000, "用户主动停止监控")
        webSocket = null
        // 释放网络资源
        client.dispatcher.executorService.shutdown()
    }
}