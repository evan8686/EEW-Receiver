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

    private var mediaPlayer: MediaPlayer? = null
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val EVENT_CHANNEL_ID = "EEW_EVENT_CHANNEL"
    private val NOTIFICATION_ID_EVENT = 1002

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
        // 🚨 完美适配 String 类型的 ID，使用 isNullOrEmpty() 判断
        if (eewData.id.isNullOrEmpty() || eewData.hypoCenter == null || eewData.hypoCenter == "null") {
            Log.d("EEW_Receiver", "拦截到无效或空数据，不执行通知逻辑。")
            return
        }

        // 只要数据有效，第一时间将其保存到本地历史记录中！
        DataManager.saveHistory(context, eewData)

        val readableText = eewData.toReadableText()

        if (eewData.magnitude >= threshold) {
            Log.d("EEW_Receiver", "震级 ${eewData.magnitude} >= $threshold，触发强警报！")
            sendEventNotification(eewData, "【强震预警】${eewData.hypoCenter}")
            wakeUpScreen()
            vibratePhone()
            playSound()
            showLockScreenUI(readableText)
        } else {
            Log.d("EEW_Receiver", "震级 ${eewData.magnitude} < $threshold，仅发送详细通知。")
            sendEventNotification(eewData, "【地震速报】${eewData.hypoCenter}")
        }
    }

    private fun sendEventNotification(eewData: EewData, title: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // 如果你有自己的图标，可以替换这里
            .setContentTitle(title)
            .setContentText("震级:${eewData.magnitude} / 烈度:${eewData.maxIntensity}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(eewData.toReadableText()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_EVENT, notification)
    }

    private fun playSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()

            // 1. 构建音频属性，强制指定走“闹钟”通道 (USAGE_ALARM)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // 2. 使用带有音频属性的 create 方法初始化 MediaPlayer
            mediaPlayer = MediaPlayer.create(
                context,
                R.raw.warn,
                audioAttributes,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            mediaPlayer?.setOnCompletionListener { mp ->
                mp.release()
                mediaPlayer = null
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e("EEW_Receiver", "播放警报音失败: ${e.message}")
        }
    }

    private fun wakeUpScreen() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EEWReceiver::AlertWakeLock"
        )
        wakeLock.acquire(30000L)
    }

    private fun vibratePhone() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(2000)
        }
    }

    private fun showLockScreenUI(eewText: String) {
        val intent = android.content.Intent(context, LockScreenAlertActivity::class.java).apply {
            putExtra("EEW_TEXT", eewText)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}