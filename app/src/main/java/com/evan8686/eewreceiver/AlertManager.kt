package com.evan8686.eewreceiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

class AlertManager(private val context: Context) {

    // 🚨 核心修复：将关键硬件控制器提升为全局成员变量，确保随时可以被拦截和释放
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val EVENT_CHANNEL_ID = "EEW_EVENT_CHANNEL"

    init {
        createEventNotificationChannel()
    }

    private fun createEventNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "地震预警事件"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(EVENT_CHANNEL_ID, name, importance).apply {
                description = "用于显示具体的地震预警详细信息"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun triggerAlert(eewData: EewData, threshold: Double = 3.0) {
        // 核心防线：只拦截连 ID 都没有的绝对非法数据
        if (eewData.id.isNullOrEmpty()) {
            Log.d("EEW_Receiver", "拦截到无 ID 的无效数据，不执行通知逻辑。")
            return
        }

        DataManager.saveHistory(context, eewData)

        // 全局容错包装：为可能为空的字段提供安全的“未知”替代文本，防止 UI 渲染 null
        val safeHypoCenter = if (eewData.hypoCenter.isNullOrEmpty() || eewData.hypoCenter == "null") "未知区域" else eewData.hypoCenter
        val safeIntensity = if (eewData.maxIntensity.isNullOrEmpty() || eewData.maxIntensity == "null") "未知" else eewData.maxIntensity

        if (eewData.magnitude >= threshold) {
            Log.d("EEW_Receiver", "震级 ${eewData.magnitude} >= $threshold，触发强警报！")
            sendEventNotification(eewData, "【强震预警】$safeHypoCenter", safeIntensity)
            wakeUpScreen()
            vibratePhone()
            playSound()
            showLockScreenUI(eewData, safeHypoCenter, safeIntensity)
        } else {
            Log.d("EEW_Receiver", "震级 ${eewData.magnitude} < $threshold，仅发送详细通知。")
            sendEventNotification(eewData, "【地震速报】$safeHypoCenter", safeIntensity)
        }
    }

    private fun sendEventNotification(eewData: EewData, title: String, safeIntensity: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText("震级:${eewData.magnitude} / 烈度:$safeIntensity")
            .setStyle(NotificationCompat.BigTextStyle().bigText(eewData.toReadableText()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // 🚨 核心修复：使用地震 ID 的哈希值作为通知 ID。
        // 这样同一场地震的更新报文会完美覆盖前一报，而不同地点的独立地震会并列显示，绝不漏报！
        val notificationId = eewData.id?.hashCode() ?: System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notification)
    }

    private fun playSound() {
        try {
            // 🚨 核心修复：由于 AlertManager 变为单例，这里的 stop 终于可以成功掐断上一首警报了
            mediaPlayer?.stop()
            mediaPlayer?.release()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaPlayer = MediaPlayer.create(
                context,
                R.raw.warn,
                audioAttributes,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            // 循环播放两遍逻辑
            var playCount = 0
            mediaPlayer?.setOnCompletionListener { mp ->
                playCount++
                if (playCount < 2) {
                    mp.start() // 播放第二遍
                } else {
                    mp.release()
                    mediaPlayer = null
                }
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e("EEW_Receiver", "播放警报音失败: ${e.message}")
        }
    }

    private fun wakeUpScreen() {
        // 🚨 核心修复：先释放旧的屏幕锁，防止 60 秒内多次预警导致锁死不息屏
        wakeLock?.let { if (it.isHeld) it.release() }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EEWReceiver::AlertWakeLock"
        )
        wakeLock?.acquire(60000L)
    }

    private fun vibratePhone() {
        // 🚨 核心修复：先取消旧震动，避免多重叠加
        vibrator?.cancel()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // 🚨 核心修复：彻底抛弃不稳定且容易被杀的 Handler。
        // 将震动数组直接写长（5次脉冲 = 4.5秒），并将 repeat 设为 -1（不循环）。
        // 这样交由底层硬件执行一遍自动停止，绝对不会无限震动。
        val pattern = longArrayOf(0, 500, 500, 500, 500, 500, 500, 500, 500, 500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    private fun showLockScreenUI(eewData: EewData, safeHypoCenter: String, safeIntensity: String) {
        val safeDepth = if (eewData.depth != null) "${eewData.depth}km" else "未知"
        val safeTime = if (eewData.originTime.isNullOrEmpty() || eewData.originTime == "null") "未知" else eewData.originTime

        val intent = android.content.Intent(context, LockScreenAlertActivity::class.java).apply {
            putExtra("EEW_MAGNITUDE", eewData.magnitude.toString())
            putExtra("EEW_INTENSITY", safeIntensity)
            putExtra("EEW_HYPOCENTER", safeHypoCenter)
            putExtra("EEW_DEPTH", safeDepth)
            putExtra("EEW_TIME", safeTime)
            putExtra("EEW_REPORT_NUM", eewData.reportNum.toString())
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    // 🚨 终极防线：提供给 Service 调用的全局销毁方法
    // 确保服务被关闭或重置时，所有的警报、震动和屏幕锁全部被强行解除。
    fun release() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            Log.e("EEW_Receiver", "释放音频资源失败: ${e.message}")
        } finally {
            mediaPlayer = null
        }

        vibrator?.cancel()
        vibrator = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
