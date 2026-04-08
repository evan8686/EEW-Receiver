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

    // 🚨 核心修复 1：添加 @Volatile 保证多线程可见性
    // 强制所有线程（包括主线程和 OkHttp 后台线程）直接从主内存读写该变量，拒绝 CPU 缓存导致的信息差
    @Volatile private var isClosedByUser = false

    @Volatile private var reconnectAttemptCount = 0 // 记录重连次数

    // 🚨 核心修复 2：将 Handler 和重连任务 (Runnable) 声明为成员变量
    // 这样我们手里就有了"遥控器"，随时可以取消它
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        if (!isClosedByUser) connect()
    }

    // 🚨 终极架构修复：使用 companion object 将 OkHttpClient 提升为全局单例
    // 配置心跳包：每 30 秒自动发送 ping，这是最省电的保活方式
    // 无论勾选几个源，全 App 共享这一个 Client，复用底层线程池和连接池，彻底杜绝内存/线程泄漏
    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun connect() {
        isClosedByUser = false
        val request = Request.Builder().url(url).build()

        // 使用全局单例 sharedClient 创建 WebSocket 连接
        webSocket = sharedClient.newWebSocket(request, object : WebSocketListener() {
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