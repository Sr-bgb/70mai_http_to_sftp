package com.lz1bgb.camhttp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.util.Locale
//import java.util.Date
//import java.util.Calendar

/**
 * @brief Data class for reporting service status to the UI.
 */
data class ServiceStatus(
    val generalStatus: String,          /**< Main status message (Waiting/Running) */
    val lastDownloadResult: String,     /**< Result of the last dashcam download */
    val totalFilesDownloaded: Int,      /**< Total files saved to phone */
    val pendingCameraFiles: Int,        /**< Number of files remaining on camera */
    val lastDownloadDuration: String,   /**< Time taken for last download */
    val lastUploadResult: String,       /**< Result of the last SFTP upload */
    val totalFilesUploaded: Int,        /**< Total files backed up to SFTP */
    val pendingSftpFiles: Int,          /**< Number of local files waiting for upload */
    val currentFileName: String,        /**< Name of the file currently being transferred */
    val currentSpeed: String,           /**< Current transfer speed (MB/s or KB/s) */
)

/**
 * @brief Foreground service that orchestrates file transfers from Dashcam (HTTP) to Phone, and Phone to SFTP.
 * 
 * It periodically checks for the camera Wi-Fi. If found, it downloads new files. 
 * If not found, it attempts to upload downloaded files to an SFTP server.
 */
class FileTransferService : Service(){

    companion object {
        var isServiceRunningInForeground = false
            private set
    }

    private var job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var httpClient: HttpClient
    private lateinit var httpAuth: HttpAuthentication
    private lateinit var sftpUpload: SftpUpload

    // Handler and Runnable for periodic execution
    private lateinit var handler: Handler
    private val checkInterval: Long = 30 * 1000 // 30 seconds

    private val isTaskRunning = AtomicBoolean(false)
    private val isScheduled = AtomicBoolean(false)
    private var nextExecutionTime: Long = 0L

    // Binder for communication with Activity
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FileTransferService = this@FileTransferService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val periodicCheck = Runnable {
        // Start the check and transfer cycle
        if (isTaskRunning.compareAndSet(false, true)) {
            nextExecutionTime = 0L // Reset while running
            scope.launch {
                try {
                    FileLogger.logToFile(this@FileTransferService, "FileService", "Starting periodic check...")
                    performTransferCycle()
                } catch (e: Exception) {
                    FileLogger.logToFile(this@FileTransferService, "FileService", "Error in cycle: ${e.message}")
                    Log.e("FileService", "Error in cycle: ${e.message}")
                } finally {
                    isTaskRunning.set(false)
                    if (job.isActive) {
                        scheduleNextRun()
                    }
                }
            }
        } else {
            FileLogger.logToFile(this, "FileService", "Skip check - task still running.")
        }
    }

    private var pendingCameraFiles = 0
    private var pendingSftpFiles = 0
    private var currentFileName = ""
    private var currentSpeed = ""

    private var lastBytes = 0L
    private var lastTime = 0L

    private suspend fun performTransferCycle() {
        httpClient.updateNetworkInfo()
        val dhcp = httpClient.dhcpServerIpAddress
        val camIp = httpClient.cameraIp

        // 1. Try HTTP (Camera) first
        if (dhcp == camIp) {
            FileLogger.logToFile(this, "FileService", "Camera detected ($camIp). Starting HTTP cycle.")
            runHttpCycle()
            updateStatus(getString(R.string.msg_http_success), "SUCCESS")
            pendingSftpFiles = 0 
            return 
        }

        // 2. If not on camera network, try SFTP
        FileLogger.logToFile(this, "FileService", "Starting SFTP backup...")
        pendingCameraFiles = 0
        try {
            val filesToUpload = sftpUpload.getLocalFilesToUpload()
            pendingSftpFiles = filesToUpload.size
            
            if (pendingSftpFiles > 0) {
                filesToUpload.forEach { fileInfo ->
                    val parentFolder = fileInfo.relativePath.substringAfterLast('/').ifEmpty { fileInfo.relativePath }
                    currentFileName = "$parentFolder/${fileInfo.name}"
                    resetSpeed()
                    sftpUpload.uploadAndDeleteFile(fileInfo) { bytes, _ ->
                        updateSpeed(bytes)
                    }
                    pendingSftpFiles--
                }
                currentFileName = ""
                currentSpeed = ""
                updateStatus(null, null, getString(R.string.msg_sftp_success), "SUCCESS")
            } else {
                updateStatus(null, null, getString(R.string.msg_sftp_idle), "IDLE")
            }
        } catch (e: Exception) {
            FileLogger.logToFile(this, "FileService", "SFTP error: ${e.message}")
            Log.e("FileService", "SFTP error: ${e.message}")
            updateStatus(null, null, getString(R.string.msg_sftp_success), "ERROR")
        }
    }

    private suspend fun runHttpCycle(): Boolean {
        var token = httpClient.sessionToken
        
        // If no token, attempt pairing
        if (token == null) {
            FileLogger.logToFile(this, "FileService", "No token. Starting pairing...")
            val authSuccess = httpAuth.performAuthenticationLogic()
            if (!authSuccess) return false
            token = httpClient.sessionToken ?: return false
        } else {
            // Register periodically for keep-alive
            httpClient.registerClient()
        }

        // 0: Front Normal, 8: Back Normal, 1: Front Emergency, 6: Back Emergency, 2: Parking, 3: Photo
        val fileTypes = listOf(0, 8, 1, 6, 2, 3)
        val allFiles = mutableListOf<FileInfo>()

        for (type in fileTypes) {
            // Register before each list request
            httpClient.registerClient()
            val files = httpClient.getFileList(type)
            if (files != null) {
                FileLogger.logToFile(this, "FileService", "Type $type: Found ${files.size} files.")
                
                // Remove last 2 files from video categories to ensure recording is finished
                val typeFiles = if (type in listOf(0, 8, 1, 6, 2)) {
                    if (files.size > 2) {
                        files.sortedBy { it.name }.dropLast(2)
                    } else {
                        FileLogger.logToFile(this, "FileService", "Type $type has only ${files.size} files. Waiting for finalization.")
                        emptyList()
                    }
                } else {
                    files // Photos etc.
                }
                
                allFiles.addAll(typeFiles)
            }
        }

        if (allFiles.isEmpty()) {
            FileLogger.logToFile(this, "FileService", "No new ready files for download.")
            return true
        }

        // Global sort (oldest first)
        val sortedFiles = allFiles.sortedBy { it.name }
        pendingCameraFiles = sortedFiles.size

        // Group files by the first 19 characters of their name
        val groupedFiles = sortedFiles.groupBy { it.name.take(19) }

        for ((groupKey, filesInGroup) in groupedFiles) {
            FileLogger.logToFile(this, "FileService", "Processing group $groupKey with ${filesInGroup.size} files.")
            
            val successfullyDownloadedFiles = mutableListOf<FileInfo>()

            for (file in filesInGroup) {
                FileLogger.logToFile(this, "FileService", "Downloading ${file.name} (Path: ${file.path})")
                val parentFolder = file.path.substringAfterLast('/').ifEmpty { file.path }
                currentFileName = "$parentFolder/${file.name}"
                resetSpeed()
                
                // Construct URL
                val downloadUrl = "http://${httpClient.cameraIp}${file.path}/${file.name}"
                
                // Remove /mnt/sd for local path
                val cleanPath = file.path.removePrefix("/mnt/sd").trimStart('/')
                val localDirFile = File(getExternalFilesDir(null), "camera/$cleanPath")
                if (!localDirFile.exists()) localDirFile.mkdirs()
                
                val localPath = localDirFile.absolutePath
                
                httpClient.registerClient()
                val startTime = System.currentTimeMillis()
                val isDownloaded = httpClient.downloadFile(downloadUrl, localPath) { bytes, _ ->
                    updateSpeed(bytes)
                }
                
                val endTime = System.currentTimeMillis()
                
                if (isDownloaded) {
                    val downloadedFile = File(localDirFile, file.name)
                    if (downloadedFile.exists() && (downloadedFile.length() > 0)) {
                        Log.i("FileService", "Successfully downloaded: ${file.name}")
                        val duration = (endTime - startTime) / 1000.0
                        saveDownloadDuration(duration)
                        incrementDownloadCount()
                        successfullyDownloadedFiles.add(file)
                    } else {
                        Log.e("FileService", "File ${file.name} is 0 bytes or missing.")
                    }
                } else {
                    Log.e("FileService", "Error downloading ${file.name}. Skipping deletion for this group.")
                }
            }

            // Only delete the group if ALL files in the group were successfully downloaded
            if (successfullyDownloadedFiles.size == filesInGroup.size) {
                FileLogger.logToFile(this, "FileService", "Group $groupKey downloaded successfully. Deleting from camera...")
                for (file in successfullyDownloadedFiles) {
                    httpClient.registerClient()
                    val deleted = httpClient.deleteRemoteFile(file.path, file.name, token)
                    if (deleted) {
                        FileLogger.logToFile(this, "FileService", "Deleted from camera: ${file.name}")
                        if (pendingCameraFiles > 0) pendingCameraFiles--
                    } else {
                        Log.e("FileService", "Failed to delete ${file.name} from camera.")
                    }
                }
            } else {
                FileLogger.logToFile(this, "FileService", "Group $groupKey was NOT fully downloaded. Deletion skipped.")
                // Decrease pending count only for what we actually finished or skip
                pendingCameraFiles -= filesInGroup.size
            }
        }
        currentFileName = ""
        currentSpeed = ""
        
        return true
    }

    private fun resetSpeed() {
        lastBytes = 0L
        lastTime = System.currentTimeMillis()
        currentSpeed = "0 KB/s"
    }

    /**
     * @brief Calculates and updates the current transfer speed.
     */
    private fun updateSpeed(currentBytes: Long) {
        val now = System.currentTimeMillis()
        val timeDiff = now - lastTime
        if (timeDiff >= 1000) { // Update every second
            val bytesDiff = currentBytes - lastBytes
            val speedBytesPerSec = (bytesDiff * 1000) / timeDiff
            
            currentSpeed = when {
                speedBytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", speedBytesPerSec / (1024.0 * 1024.0))
                speedBytesPerSec >= 1024 -> String.format(Locale.US, "%.2f KB/s", speedBytesPerSec / 1024.0)
                else -> "$speedBytesPerSec B/s"
            }
            
            lastBytes = currentBytes
            lastTime = now
        }
    }

    private fun incrementDownloadCount() {
        val prefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        val current = prefs.getInt("TOTAL_DOWNLOADS", 0)
        prefs.edit {
            putInt("TOTAL_DOWNLOADS", current + 1)
        }
    }

    private fun saveDownloadDuration(seconds: Double) {
        val prefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        prefs.edit {
            putString("LAST_DOWNLOAD_DURATION", String.format(Locale.US, "%.2f sec.", seconds))
        }
    }

    private fun updateStatus(dlStatus: String?, dlRes: String?, upStatus: String? = null, upRes: String? = null) {
        val prefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        prefs.edit {
            dlStatus?.let { putString("FTP_STATUS", it) }
            dlRes?.let { putString("FTP_RESULT", it) }
            upStatus?.let { putString("SFTP_STATUS", it) }
            upRes?.let { putString("SFTP_RESULT", it) }
        }
    }

    private fun scheduleNextRun() {
        nextExecutionTime = System.currentTimeMillis() + checkInterval
        isScheduled.set(true)
        handler.postDelayed(periodicCheck, checkInterval)
        FileLogger.logToFile(this, "FileService", "Next check scheduled.")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Initialize Handler with main thread
        handler = Handler(Looper.getMainLooper())

        httpClient = HttpClient(this)
        httpAuth = HttpAuthentication(httpClient)
        sftpUpload = SftpUpload(this)
    }

    fun stopTransfer() {
        handler.removeCallbacks(periodicCheck)
        isScheduled.set(false)
        job.cancel() 
        isTaskRunning.set(false)
        nextExecutionTime = 0L
        
        // Re-initialize job and scope for the next Start command
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + job)
        
        FileLogger.logToFile(this, "FileService", "Transfer manually stopped and reset.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunningInForeground = false
        // Stop scheduled checks
        handler.removeCallbacks(periodicCheck)
        isScheduled.set(false)
        // Cancel all active Coroutines
        job.cancel()
        FileLogger.logToFile(this, "FileService", "Service stopped and cleaned up.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        isServiceRunningInForeground = true

        // Only start if not already scheduled or running
        if (!isTaskRunning.get() && !isScheduled.get()) {
            handler.removeCallbacks(periodicCheck)
            isScheduled.set(true)
            handler.post(periodicCheck) 
        }

        return START_STICKY
    }



    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel("FileTransferChannel", "File Transfer Service", NotificationManager.IMPORTANCE_DEFAULT)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "FileTransferChannel")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.foreground_service_justification))
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun getServiceStatus(): ServiceStatus {
        val now = System.currentTimeMillis()
        val remainingMillis = nextExecutionTime - now

        val generalStatus = when {
            isScheduled.get() || isTaskRunning.get() -> {
                if (isTaskRunning.get()) {
                    getString(R.string.status_running)
                } else if (nextExecutionTime == 0L) {
                    getString(R.string.status_waiting_initial)
                } else if (remainingMillis <= 0) {
                    getString(R.string.status_waiting_start)
                } else {
                    val minutes = (remainingMillis / 1000) / 60
                    val seconds = (remainingMillis / 1000) % 60
                    getString(R.string.status_next_check, minutes, seconds)
                }
            }
            else -> "Status: Stopped"
        }
        val statsPrefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        val totalDownloads = statsPrefs.getInt("TOTAL_DOWNLOADS", 0)
        val totalUploads = statsPrefs.getInt("TOTAL_UPLOADS", 0)
        val lastDuration = statsPrefs.getString("LAST_DOWNLOAD_DURATION", "0 sec.") ?: "0 sec."

        val lastDownloadResult = statsPrefs.getString("FTP_STATUS", null)?: "Waiting for FTP"
        val lastUploadResult = statsPrefs.getString("SFTP_STATUS", null)?: "Waiting for SFTP"

        return ServiceStatus(
            generalStatus, 
            lastDownloadResult, 
            totalDownloads, 
            pendingCameraFiles,
            lastDuration, 
            lastUploadResult, 
            totalUploads,
            pendingSftpFiles,
            currentFileName,
            currentSpeed
        )
    }
}
