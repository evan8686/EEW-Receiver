package com.evan8686.eewreceiver

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val sourceName: String,
    private val url: String,
    private val onStatusChanged: (ConnectionStatus, Long) -> Unit, // 🚨 新增：状态与活跃时间回调
    private val onMessageReceived: (String) -> Unit
) {
    private var webSocket: WebSocket? = null

    // 🚨 核心修复 1：添加 @Volatile 保证多线程可见性
    @Volatile private var isClosedByUser = false
    @Volatile private var reconnectAttemptCount = 0 

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        if (!isClosedByUser) connect()
    }

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS) // ⚡ 优化：缩短至 15s 更快发现死链
            .build()
    }

    fun connect() {
        isClosedByUser = false
        onStatusChanged(ConnectionStatus.CONNECTING, 0L) // 通知正在连接
        
        val request = Request.Builder().url(url).build()

        webSocket = sharedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                reconnectAttemptCount = 0 
                // 💡 Wolfx 连上即发心跳，此处同步更新状态和活跃时间
                onStatusChanged(ConnectionStatus.CONNECTED, System.currentTimeMillis())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                // ⚡ 只要有消息（含心跳），就证明活着
                onStatusChanged(ConnectionStatus.CONNECTED, System.currentTimeMillis())
                onMessageReceived(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                if (!isClosedByUser) {
                    onStatusChanged(ConnectionStatus.RECONNECTING, 0L)
                    scheduleReconnect()
                } else {
                    onStatusChanged(ConnectionStatus.DISCONNECTED, 0L)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                if (!isClosedByUser) {
                    onStatusChanged(ConnectionStatus.RECONNECTING, 0L)
                    scheduleReconnect()
                } else {
                    onStatusChanged(ConnectionStatus.DISCONNECTED, 0L)
                }
            }
        })
    }

    private fun scheduleReconnect() {
        // 在发起新的倒计时前，先清除可能遗留的旧任务，防止多个重连任务叠加排队
        reconnectHandler.removeCallbacks(reconnectRunnable)

        // 💡 核心优化：指数退避重连算法 (5s, 10s, 20s, 40s, 最大 60s)
        val delayMillis = (5000L * Math.pow(2.0, reconnectAttemptCount.toDouble())).toLong().coerceAtMost(60000L)

        Log.d("EEW_Receiver", "[$sourceName] $delayMillis 毫秒后尝试重新连接...")
        reconnectAttemptCount++

        // 发射倒计时任务，把任务信件塞进系统邮筒
        reconnectHandler.postDelayed(reconnectRunnable, delayMillis)
    }

    fun disconnect() {
        isClosedByUser = true

        // 🚨 核心修复 3：主动关闭时，撕毁邮筒里尚未执行的"幽灵倒计时信件"
        reconnectHandler.removeCallbacks(reconnectRunnable)

        // 只需优雅地关闭当前这条 WebSocket 即可
        webSocket?.close(1000, "用户主动停止监控")
        webSocket = null

        // 🚨 核心修复 4 (已修正)：因为现在 sharedClient 是全局单例
        // 其他数据源可能还在使用它，所以绝不能在这里调用 evictAll() 或 shutdown() 进行“连坐”销毁。
        // 闲置的底层线程会在 60 秒后由系统自动回收，这是最安全的做法。
    }
}