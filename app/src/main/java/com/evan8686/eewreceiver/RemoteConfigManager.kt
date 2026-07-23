package com.evan8686.eewreceiver

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant

data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("forceUpdate") val forceUpdate: Boolean,
    @SerializedName("apkUrls") val apkUrls: List<String>, // 🚨 关键修复：改为 List<String> 匹配最新 JSON 结构
    @SerializedName("fileSize") val fileSize: Long,
    @SerializedName("sha256") val sha256: String,
    @SerializedName("changelog") val changelog: String
)

data class NoticeInfo(
    @SerializedName("noticeId") val noticeId: String,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("title") val title: String,
    @SerializedName("content") val content: String,
    @SerializedName("expireAt") val expireAt: String,
    @SerializedName("forceEveryLaunch") val forceEveryLaunch: Boolean,
    @SerializedName("linkUrl") val linkUrl: String?
)

data class RemoteConfig(
    val update: UpdateInfo?,
    val notice: NoticeInfo?
)

object RemoteConfigManager {
    // 💡 采用 GitHub + 多镜像备份策略，彻底解决 Gitee 封禁问题
    private val UPDATE_URLS = listOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/evan8686/eewconfig/main/update.json",
        "https://cdn.jsdmirror.cn/gh/evan8686/eewconfig@main/update.json",
        "https://cdn.jsdmirror.com/gh/evan8686/eewconfig@main/update.json",
        "https://raw.githubusercontent.com/evan8686/eewconfig/main/update.json"
    )

    private val NOTICE_URLS = listOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/evan8686/eewconfig/main/notice.json",
        "https://cdn.jsdmirror.cn/gh/evan8686/eewconfig@main/notice.json",
        "https://cdn.jsdmirror.com/gh/evan8686/eewconfig@main/notice.json",
        "https://raw.githubusercontent.com/evan8686/eewconfig/main/notice.json"
    )

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private suspend fun fetchJsonFromUrls(urls: List<String>): String? = withContext(Dispatchers.IO) {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        for (url in urls) {
            try {
                val fullUrl = if (url.contains("?")) "$url&t=${System.currentTimeMillis()}" else "$url?t=${System.currentTimeMillis()}"
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", userAgent)
                    .header("Cache-Control", "no-cache")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val content = response.body?.string()
                    if (content != null && content.trim().startsWith("{")) {
                        Log.d("RemoteConfig", "Successfully fetched from: $url")
                        return@withContext content
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteConfig", "Fetch failed for $url: ${e.message}")
            }
        }
        null
    }

    suspend fun fetchRemoteConfig(): RemoteConfig = withContext(Dispatchers.IO) {
        val updateJson = fetchJsonFromUrls(UPDATE_URLS)
        val noticeJson = fetchJsonFromUrls(NOTICE_URLS)

        val update = updateJson?.let {
            try { gson.fromJson(it, UpdateInfo::class.java) } catch (e: Exception) { null }
        }
        val notice = noticeJson?.let {
            try { gson.fromJson(it, NoticeInfo::class.java) } catch (e: Exception) { null }
        }

        RemoteConfig(update, notice)
    }

    fun isNoticeValid(context: Context, notice: NoticeInfo): Boolean {
        if (!notice.enabled) return false
        try {
            // 💡 优化：支持带时区偏移的 ISO 格式 (如 +08:00)
            val expireTime = when {
                notice.expireAt.contains("T") -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try { 
                            java.time.OffsetDateTime.parse(notice.expireAt).toInstant().toEpochMilli() 
                        } catch (e: Exception) { 
                            // 尝试回退：去掉时区偏移后按 yyyy-MM-dd HH:mm:ss 处理
                            try {
                                val cleanDate = notice.expireAt.replace("T", " ").substring(0, 19)
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                sdf.parse(cleanDate)?.time ?: Long.MAX_VALUE
                            } catch (e2: Exception) { Long.MAX_VALUE }
                        }
                    } else {
                        // API 26 以下的兼容解析
                        try {
                            val cleanDate = notice.expireAt.replace("T", " ").substring(0, 19)
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            sdf.parse(cleanDate)?.time ?: Long.MAX_VALUE
                        } catch (e: Exception) { Long.MAX_VALUE }
                    }
                }
                notice.expireAt.contains(":") -> {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    try { sdf.parse(notice.expireAt)?.time ?: Long.MAX_VALUE } catch (e: Exception) { Long.MAX_VALUE }
                }
                else -> {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    try { sdf.parse(notice.expireAt)?.time ?: Long.MAX_VALUE } catch (e: Exception) { Long.MAX_VALUE }
                }
            }
            
            if (System.currentTimeMillis() > expireTime) return false
        } catch (e: Exception) {
            Log.e("RemoteConfig", "Parse expireAt failed: ${e.message}")
            return true // 解析失败默认显示，防止公告失效
        }

        if (!notice.forceEveryLaunch) {
            val prefs = context.getSharedPreferences("notice_prefs", Context.MODE_PRIVATE)
            if (prefs.getString("last_notice_id", "") == notice.noticeId) return false
        }
        return true
    }

    fun markNoticeAsShown(context: Context, noticeId: String) {
        val prefs = context.getSharedPreferences("notice_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_notice_id", noticeId).apply()
    }

    // ========== 频率控制相关 ==========

    fun shouldCheckUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_check_time", 0L)
        val twoDaysMillis = 2 * 24 * 60 * 60 * 1000L
        return (System.currentTimeMillis() - lastCheck) > twoDaysMillis
    }

    fun markUpdateChecked(context: Context) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_check_time", System.currentTimeMillis()).apply()
    }

    fun startDownload(context: Context, urls: List<String>, versionName: String) {
        val validUrl = urls.firstOrNull { it.isNotBlank() }
        if (validUrl == null) {
            Toast.makeText(context, "未找到有效的下载链接", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) {
                // 如果系统下载管理器不可用，则回退到浏览器方案
                openBrowserDownload(context, validUrl)
                return
            }

            val request = DownloadManager.Request(Uri.parse(validUrl))
                .setTitle("EEW Receiver $versionName")
                .setDescription("正在从云端下载最新版本...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, "EEWReceiver_$versionName.apk")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            dm.enqueue(request)
            Toast.makeText(context, "正在后台下载，请查看通知栏", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("RemoteConfig", "Start download failed, falling back: ${e.message}")
            // 🚨 终极保险：如果 DownloadManager 抛出任何异常，自动切换到浏览器方案
            openBrowserDownload(context, validUrl)
        }
    }

    fun openBrowserDownload(context: Context, url: String) {
        if (url.isBlank()) return
        try {
            val uri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("RemoteConfig", "Browser fallback failed: ${e.message}")
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "请在系统设置中手动开启安装权限", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
