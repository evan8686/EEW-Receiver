package com.evan8686.eewreceiver

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class AlertManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    // 记录警报音当前的渐变音量
    private var currentAlarmVolume = 1.0f
    private var volumeAnimator: ValueAnimator? = null

    // ================================================================
    // 任务8：TTS 语音播报相关字段
    // ================================================================
    private var tts: TextToSpeech? = null
    private val ttsHandler = Handler(Looper.getMainLooper())
    private val pendingTtsRunnables = mutableListOf<Runnable>()

    // 记录"用户已手动静音"的开关
    @Volatile
    private var userSilenced = false

    // 记录每场地震最近已警报过的报号，防止同一报文重复触发
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
                // 必须静音：防止系统默认通知音和我们的长警报音抢占音频焦点
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

        // 重复报文拦截
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

        // ================================================================
        // 任务2/3 接入：读取用户坐标和本地烈度阈值，计算距离 / 烈度 / 倒计时
        // ================================================================

        val userLat = DataManager.getLatitude(context)
        val userLon = DataManager.getLongitude(context)
        val localIntensityThreshold = DataManager.getLocalIntensityThreshold(context)

        // 兜底检查：震中坐标是否有效（(0.0, 0.0) 是 EewData 的默认值，表示未收到坐标）
        val epicenterValid = !(eewData.latitude == 0.0 && eewData.longitude == 0.0)

        // 计算震中距（用户坐标有效 且 震中坐标有效时才计算）
        val distanceKm: Double? = if (!userLat.isNaN() && !userLon.isNaN() && epicenterValid) {
            EarthquakeCalculator.calculateDistance(
                userLat, userLon,
                eewData.latitude, eewData.longitude
            )
        } else null

        // 计算本地预估烈度（有距离才计算）
        val localIntensity: Int? = if (distanceKm != null) {
            EarthquakeCalculator.calculateLocalIntensity(
                eewData.magnitude, distanceKm
            ).toInt()
        } else null

        // 🚨 计算 P 波到达倒计时（有距离才计算）：传入 eewData.type 进行强时区解析 🚨
        val originTimeMillis = EarthquakeCalculator.parseOriginTimeMillis(eewData.originTime, eewData.type)
        val countdown: Int? = if (distanceKm != null && originTimeMillis != null) {
            EarthquakeCalculator.calculateCountdown(distanceKm, originTimeMillis)
        } else null

        Log.d(
            "EEW_Receiver",
            "计算结果：距离=${distanceKm}km，本地烈度=${localIntensity}，" +
                    "倒计时=${countdown}s，本地烈度阈值=$localIntensityThreshold，震源震级阈值=$threshold"
        )

        // ================================================================
        // 核心判断：根据是否启用了"本地烈度阈值"决定触发条件
        // ================================================================

        val shouldTrigger: Boolean = if (localIntensityThreshold > 0 && localIntensity != null) {
            // 模式1：已设置本地烈度阈值且计算有效 → 用本地烈度判断
            val triggered = localIntensity >= localIntensityThreshold
            Log.d(
                "EEW_Receiver",
                if (triggered) "本地烈度 $localIntensity >= 阈值 $localIntensityThreshold，触发强警报！"
                else "本地烈度 $localIntensity < 阈值 $localIntensityThreshold，不触发全屏。"
            )
            triggered
        } else {
            // 模式2：未启用烈度阈值或坐标未设置 → 沿用原有震级阈值逻辑
            val triggered = eewData.magnitude >= threshold
            Log.d(
                "EEW_Receiver",
                if (triggered) "震级 ${eewData.magnitude} >= 阈值 $threshold，触发强警报！"
                else "震级 ${eewData.magnitude} < 阈值 $threshold，不触发全屏。"
            )
            triggered
        }

        if (shouldTrigger) {
            userSilenced = false

            // 1. 硬件预热：亮屏时长 = 倒计时 + 60 秒
            wakeUpScreen(countdown)
            vibratePhone()
            playSound()

            // 任务8：TTS 倒计时语音播报
            startTtsAnnouncements(countdown)

            // 2. 发送紧急通知
            sendEventNotification(eewData, "【强震预警】$safeHypoCenter", safeIntensity, isEmergency = true)

            // 3. 强制拉起全屏弹窗，同时传递计算结果
            showLockScreenUI(
                eewData, safeHypoCenter, safeIntensity,
                distanceKm = distanceKm,
                localIntensity = localIntensity,
                countdown = countdown,
                epicenterValid = epicenterValid
            )

        } else {
            Log.d("EEW_Receiver", "未达触发条件，仅发送详细通知。")
            sendEventNotification(eewData, "【地震速报】$safeHypoCenter", safeIntensity, isEmergency = false)
        }
    }

    private fun sendEventNotification(eewData: EewData, title: String, safeIntensity: String, isEmergency: Boolean) {
        val safeDepth = if (eewData.depth != null) "${eewData.depth}km" else "未知"
        // 🚨 更新为调用 EewData 新增的带时区后缀的方法
        val safeTime = eewData.getFormattedTime()

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
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        }

        val notificationId = eewData.id?.hashCode() ?: System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, builder.build())
    }

    private fun showLockScreenUI(
        eewData: EewData,
        safeHypoCenter: String,
        safeIntensity: String,
        distanceKm: Double? = null,
        localIntensity: Int? = null,
        countdown: Int? = null,
        epicenterValid: Boolean = false
    ) {
        val safeDepth = if (eewData.depth != null) "${eewData.depth}km" else "未知"

        // 🚨 替换：调用 getFormattedTime() 获取带有 (UTC+8) 或 (UTC+9) 后缀的时间字符串
        val safeTime = eewData.getFormattedTime()

        val intent = Intent(context, LockScreenAlertActivity::class.java).apply {
            putExtra("EEW_MAGNITUDE", eewData.magnitude.toString())
            putExtra("EEW_INTENSITY", safeIntensity)
            putExtra("EEW_HYPOCENTER", safeHypoCenter)
            putExtra("EEW_DEPTH", safeDepth)
            putExtra("EEW_TIME", safeTime) // 这里直接传给弹窗，弹窗会原样显示
            putExtra("EEW_REPORT_NUM", eewData.reportNum.toString())
            if (epicenterValid) {
                putExtra("EEW_LATITUDE", eewData.latitude)
                putExtra("EEW_LONGITUDE", eewData.longitude)
            }
            if (distanceKm != null) putExtra("EEW_DISTANCE_KM", distanceKm)
            if (localIntensity != null) putExtra("EEW_LOCAL_INTENSITY", localIntensity)
            if (countdown != null) putExtra("EEW_COUNTDOWN", countdown)
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

            // 初始化重置为最大音量
            currentAlarmVolume = 1.0f
            player.setVolume(currentAlarmVolume, currentAlarmVolume)

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

    /**
     * 实现平滑的 Ducking（压低音量）机制
     * 使用 ValueAnimator 在 400ms 内平滑过渡警报音量，避免割裂感
     */
    private fun fadeAlarmVolume(targetVolume: Float) {
        ttsHandler.post {
            volumeAnimator?.cancel()
            volumeAnimator = ValueAnimator.ofFloat(currentAlarmVolume, targetVolume).apply {
                duration = 400L // 400ms 缓入缓出平滑过渡
                addUpdateListener { anim ->
                    val v = anim.animatedValue as Float
                    currentAlarmVolume = v
                    try {
                        mediaPlayer?.setVolume(v, v)
                    } catch (e: Exception) {
                        // 忽略媒体播放器已释放时的异常
                    }
                }
                start()
            }
        }
    }

    private fun wakeUpScreen(countdownSec: Int? = null) {
        wakeLock?.let { if (it.isHeld) it.release() }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val holdMs = if (countdownSec != null) {
            (countdownSec + 60) * 1000L
        } else {
            60_000L
        }

        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EEWReceiver::AlertWakeLock"
        )
        wakeLock?.acquire(holdMs)
        Log.d("EEW_Receiver", "WakeLock 已获取，持续时间：${holdMs / 1000}秒")
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

    // ================================================================
    // 任务8：TTS 语音播报核心方法
    // ================================================================

    private fun startTtsAnnouncements(countdown: Int?) {
        // 取消上一次触发的所有待执行 TTS 任务
        pendingTtsRunnables.forEach { ttsHandler.removeCallbacks(it) }
        pendingTtsRunnables.clear()

        // 初始化 TTS 引擎（仅首次，复用已有实例）
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.w("EEW_Receiver", "TTS 引擎初始化失败，status=$status")
                    return@TextToSpeech
                }

                // 强制将 TTS 输出通道设为 ALARM (闹钟音量)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(audioAttributes)
                }

                // 语言优先级调整：大陆简体中文优先
                val locales = listOf(
                    Locale.SIMPLIFIED_CHINESE, // 第一志愿：大陆简体
                    Locale("zh", "TW"),        // 第二志愿：台湾繁体
                    Locale.CHINESE             // 保底志愿：通用中文
                )
                val instance = tts ?: return@TextToSpeech
                for (locale in locales) {
                    val result = instance.setLanguage(locale)
                    if (result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.d("EEW_Receiver", "TTS 语言设置成功：$locale")
                        break
                    }
                }

                // 绑定播报进度监听器，实现说话时 Ducking (音量压低到40%)
                instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        fadeAlarmVolume(0.4f) // TTS 开口说话，警报音平滑压低到 40%
                    }

                    override fun onDone(utteranceId: String?) {
                        fadeAlarmVolume(1.0f) // TTS 说完，警报音平滑恢复到 100%
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        fadeAlarmVolume(1.0f) // 发生错误，确保恢复 100%
                    }
                })
            }
        }

        // 构建播报计划并逐条投递
        for ((delayMs, text) in buildTtsSchedule(countdown)) {
            val runnable = Runnable {
                if (!userSilenced) {
                    val utteranceId = "eew_tts_${System.currentTimeMillis()}"

                    // 利用 Bundle() 设置参数，确保兼容绝大多数安卓版本的 STREAM 设定
                    val params = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                    }

                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    Log.d("EEW_Receiver", "TTS 播报（delay=${delayMs}ms）：$text")
                }
            }
            pendingTtsRunnables.add(runnable)
            ttsHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun buildTtsSchedule(countdown: Int?): List<Pair<Long, String>> {
        val schedule = mutableListOf<Pair<Long, String>>()

        if (countdown == null || countdown <= 0) {
            schedule.add(800L to "地震预警！请立即注意安全！")
            return schedule
        }

        val countdownMs = countdown * 1000L

        // 初始播报
        schedule.add(800L to "地震预警！约${countdown}秒后震波抵达！")

        if (countdown > 30) {
            val d = countdownMs - 30_000L
            if (d > 2_000L) schedule.add(d to "30秒后震波抵达")
        }

        if (countdown > 20) {
            val d = countdownMs - 20_000L
            if (d > 2_000L) schedule.add(d to "20秒后震波抵达")
        }

        // 10秒起逐秒倒数
        for (sec in 10 downTo 1) {
            val d = countdownMs - (sec * 1_000L)
            if (d > 1_500L) schedule.add(d to "$sec")
        }

        // 归零播报
        schedule.add(countdownMs + 300L to "地震波已抵达")

        return schedule
    }

    // ================================================================

    @Synchronized
    fun release() {
        userSilenced = true

        // 释放期间取消音量渐变动画防止内存泄漏
        ttsHandler.post {
            volumeAnimator?.cancel()
            volumeAnimator = null
        }
        currentAlarmVolume = 1.0f

        // 停止并释放警报音
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

        // 取消所有待播 TTS，关闭 TTS 引擎
        pendingTtsRunnables.forEach { ttsHandler.removeCallbacks(it) }
        pendingTtsRunnables.clear()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("EEW_Receiver", "释放 TTS 资源失败: ${e.message}")
        } finally {
            tts = null
        }
    }
}