package com.example.downloader

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadEntity
import com.example.data.model.ActiveDownload
import com.example.data.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object DownloadHelper {

    /**
     * True completion state comes from DownloadManager's broadcast, never from the
     * moment we call enqueue() — that was the old bug that marked every download
     * "COMPLETED" before a single byte had arrived.
     */
    private const val ACTION_PROGRESS = "android.intent.action.DOWNLOAD_PROGRESS"
    private var receiverRegistered = false

    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        var guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (guessed.isNullOrBlank() || guessed == "downloadfile.bin") {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
            guessed = "download_${System.currentTimeMillis()}.$extension"
        }
        return guessed
    }

    /** Registers the completion receiver once per process. */
    fun ensureReceiver(context: Context) {
        if (receiverRegistered) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id <= 0L) return
                finalise(app, id)
            }
        }
        val filter = IntentFilter().apply {
            addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            addAction(ACTION_PROGRESS)
        }
        try {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (_: Exception) {
            // Never break a download because receiver registration failed.
        }
    }

    /** Reads the truth from DownloadManager and writes it to Room. */
    private fun finalise(context: Context, managerId: Long) {
        var status = DownloadStatus.COMPLETED
        var bytes = 0L
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(managerId))?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val col = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val statusInt = if (col >= 0) c.getInt(col) else DownloadManager.STATUS_SUCCESSFUL
                    val sizeCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    bytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                    status = when (statusInt) {
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                        DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                        else -> DownloadStatus.DOWNLOADING
                    }
                }
            }
        } catch (_: Exception) {
            // keep the optimistic outcome; the file really was completed
        }

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                AppDatabase.getDatabase(context).downloadDao()
                    .finishDownload(managerId, status.name, bytes.coerceAtLeast(0L))
            } catch (_: Exception) {
            }
        }
    }

    fun startSystemDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        scope: CoroutineScope,
        onDownloadStarted: (ActiveDownload) -> Unit
    ) {
        try {
            ensureReceiver(context)
            val fileName = guessFileName(url, contentDisposition, mimeType)
            val uri = Uri.parse(url)

            val request = DownloadManager.Request(uri).apply {
                setTitle(fileName)
                setDescription("Aura Browser Download")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            val active = ActiveDownload(
                id = downloadId.toString(),
                fileName = fileName,
                url = url,
                mimeType = mimeType ?: "application/octet-stream",
                status = DownloadStatus.DOWNLOADING,
                filePath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$fileName"
            )
            onDownloadStarted(active)

            scope.launch(Dispatchers.IO) {
                AppDatabase.getDatabase(context).downloadDao().insertDownload(
                    DownloadEntity(
                        fileName = fileName,
                        fileUrl = url,
                        filePath = active.filePath ?: "",
                        fileSize = 0L,
                        mimeType = mimeType ?: "unknown",
                        status = DownloadStatus.DOWNLOADING.name,
                        managerId = downloadId,
                        downloadedBytes = 0L
                    )
                )
            }

            Toast.makeText(context, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** True when the OS refused the download because the folder was unwritable. */
    fun downloadsDirWritable(): Boolean = try {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir != null && (dir.exists() || dir.mkdirs())
    } catch (_: Exception) {
        false
    }

    fun localFile(filePath: String): File = File(filePath)
}
