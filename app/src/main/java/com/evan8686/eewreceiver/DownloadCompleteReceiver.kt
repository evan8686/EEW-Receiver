package com.evan8686.eewreceiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == -1L) return

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = downloadManager.query(query)

            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                        val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (localUriIndex != -1) {
                            val uriString = cursor.getString(localUriIndex)
                            if (uriString != null) {
                                installApk(context, Uri.parse(uriString))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DownloadReceiver", "处理下载完成广播失败: ${e.message}")
            } finally {
                cursor?.close()
            }
        }
    }

    private fun installApk(context: Context, uri: Uri) {
        try {
            // 获取下载文件的物理路径
            val file = File(uri.path!!)
            if (!file.exists()) {
                Log.e("DownloadReceiver", "APK 文件不存在: ${file.absolutePath}")
                return
            }

            // 通过 FileProvider 生成安全 Content URI
            val apkUri = FileProvider.getUriForFile(
                context, 
                "${context.packageName}.fileprovider", 
                file
            )
            
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("DownloadReceiver", "启动安装程序失败: ${e.message}")
        }
    }
}
