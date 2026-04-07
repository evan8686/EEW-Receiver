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
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    // 🔑 记录"用户已手动静音"的开关
    @Volatile
    private var userSilenced = false

    // 🔑 记录每场地震最近已警报过的报号，防止同一报文重复触发
    private val recentlyAlertedMap = mutableMapOf<String, Int>()

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
                // 🚨 必须静音：防止系统默认通知音和我们的长警报音抢占音频焦点
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    @Synchronized
    fun triggerAlert(eewData: EewData, threshold: Double = 3.0) {
        if (eewData.id.isNullOrEmpty()) {
            Log.d("EEW_Receiver", "拦截到无 ID 的无效数据，不执行通知逻辑。")
            return
        }

        // 🔑 重复报文拦截
        val lastHandledReportNum = recentlyAlertedMap[eewData.id] ?: -1
        if (eewData.reportNum <= lastHandledReportNum) {
            Log.d("EEW_Receiver", "重复或旧报文已忽略: ID=${eewData.id}, 第${eewData.reportNum}报")
            return
        }
        recentlyAlertedMap[eewData.id!!] = eewData.reportNum

        // 防止 Map 无限增长
        if (recentlyAlertedMap.size > 20) {
            val keysToRemove = recentlyAlertedMap.keys.take(10)
            keysToRemove.forEach { recentlyAlertedMap.remove(it) }
        }

        DataManager.saveHistory(context, eewData)

        val safeHypoCenter =
            if (eewData.hypoCenter.isNullOrEmpty() || eewData.hypoCenter == "null") "未知区域"
            else eewData.hypoCenter
        val safeIntensity =
            if (eewData.maxIntensity.isNullOrEmpty() || eewData.maxIntensity == "null") "未知"
            else eewData.maxIntensity

        // 🚨 核心判断：是否达到强警报阈值
        if (eewData.magnitude >= threshold) {
            Log.d("EEW_Receiver", "震级 ${eewData.magnitude} >= $threshold，触发强警报！")
            userSilenced = false

            // 1. 硬件预热
            wakeUpScreen()
            vibratePhone()
            playSound()

            // 2. 发送紧急通知（删除了全屏意图，退回到最纯粹的通知栏提醒）
            sendEventNotification(eewData, "【强震预警】$safeHypoCenter", safeIntensity, isEmergency = true)

            // 3. 🚨 恢复强制拉起界面！
            // 初次测试时，这行代码会唤醒 OPPO 系统的“后台弹窗”权限询问。
            // 授权后，无论是亮屏还是锁屏，都能粗暴直接地全屏拍在用户脸上！
            showLockScreenUI(eewData, safeHypoCenter, safeIntensity)

        } else {
            Log.d("EEW_Receiver", "震级 ${eewData.magnitude} < $threshold，仅发送详细通知。")
            // 未达阈值，仅发送横幅通知，不弹全屏
            sendEventNotification(eewData, "【地震速报】$safeHypoCenter", safeIntensity, isEmergency = false)
        }
    }

    private fun sendEventNotification(eewData: EewData, title: String, safeIntensity: String, isEmergency: Boolean) {
        val safeDepth = if (eewData.depth != null) "${eewData.depth}km" else "未知"
        val safeTime = if (eewData.originTime.isNullOrEmpty() || eewData.originTime == "null") "未知" else eewData.originTime

        // 普通点击事件（进主页）
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText("震级:${eewData.magnitude} / 烈度:$safeIntensity")
            .setStyle(NotificationCompat.BigTextStyle().bigText(eewData.toReadableText()))
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent)

        if (isEmergency) {
            // 🚨 删除了 .setFullScreenIntent()
            // 这样系统管家就不会因为“滥用特权”而没收你的询问弹窗了
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        }

        val notificationId = eewData.id?.hashCode() ?: System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, builder.build())
    }

    // 🚨 找回来的 showLockScreenUI 方法
    private fun showLockScreenUI(
        eewData: EewData,
        safeHypoCenter: String,
        safeIntensity: String
    ) {
        val safeDepth = if (eewData.depth != null) "${eewData.depth}km" else "未知"
        val safeTime =
            if (eewData.originTime.isNullOrEmpty() || eewData.originTime == "null") "未知"
            else eewData.originTime

        val intent = Intent(context, LockScreenAlertActivity::class.java).apply {
            putExtra("EEW_MAGNITUDE", eewData.magnitude.toString())
            putExtra("EEW_INTENSITY", safeIntensity)
            putExtra("EEW_HYPOCENTER", safeHypoCenter)
            putExtra("EEW_DEPTH", safeDepth)
            putExtra("EEW_TIME", safeTime)
            putExtra("EEW_REPORT_NUM", eewData.reportNum.toString())
            // 必须带上 FLAG_ACTIVITY_NEW_TASK，否则从后台服务启动界面会崩溃
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(intent)
            Log.d("EEW_Receiver", "已执行强制全屏弹窗拉起指令！")
        } catch (e: Exception) {
            Log.e("EEW_Receiver", "强拉全屏界面失败 (可能是权限未给): ${e.message}")
        }
    }

    private fun playSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val player = MediaPlayer.create(
                context,
                R.raw.warn,
                audioAttributes,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            ) ?: return

            mediaPlayer = player

            var playCount = 0
            player.setOnCompletionListener { mp ->
                playCount++
                if (playCount < 2 && !userSilenced) {
                    mp.start()
                } else {
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                }
            }
            player.start()

        } catch (e: Exception) {
            Log.e("EEW_Receiver", "播放警报音失败: ${e.message}")
        }
    }

    private fun wakeUpScreen() {
        wakeLock?.let { if (it.isHeld) it.release() }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EEWReceiver::AlertWakeLock"
        )
        wakeLock?.acquire(60000L)
    }

    private fun vibratePhone() {
        vibrator?.cancel()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 500, 500, 500, 500, 500, 500, 500, 500, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    @Synchronized
    fun release() {
        userSilenced = true
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
