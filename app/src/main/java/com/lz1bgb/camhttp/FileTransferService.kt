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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar

data class ServiceStatus(
    val generalStatus: String,
    val lastDownloadResult: String,
    val totalFilesDownloaded: Int,
    val pendingCameraFiles: Int,
    val lastDownloadDuration: String,
    val lastUploadResult: String,
    val totalFilesUploaded: Int,
    val pendingSftpFiles: Int
)
class FileTransferService : Service(){

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var httpClient: HttpClient
    private lateinit var httpAuth: HttpAuthentication
    private lateinit var sftpUpload: SftpUpload

    // 1. Създаваме Handler и Runnable за периодично изпълнение
    private lateinit var handler: Handler
    //    private val checkInterval: Long = 5 * 60 * 1000 // 5 минути в милисекунди
    private val checkInterval: Long = 30 * 1000 // 30 sec в милисекунди

    private val isTaskRunning = AtomicBoolean(false)
    private var nextExecutionTime: Long = 0L

    // 1. Добавяме Binder за комуникация с Activity
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FileTransferService = this@FileTransferService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val periodicCheck = Runnable {
        // Стартираме цикъла за проверка и трансфер
        if (isTaskRunning.compareAndSet(false, true)) {
            scope.launch {
                try {
                    FileLogger.logToFile(this@FileTransferService, "FileService", "Започва периодична проверка...")
                    performTransferCycle()
                } catch (e: Exception) {
                    Log.e("FileService", "Грешка в цикъла: ${e.message}")
                } finally {
                    isTaskRunning.set(false)
                    scheduleNextRun()
                }
            }
        } else {
            FileLogger.logToFile(this, "FileService", "Пропускане на проверката - задачата все още работи.")
        }
    }

    private var pendingCameraFiles = 0
    private var pendingSftpFiles = 0

    private suspend fun performTransferCycle() {
        httpClient.updateNetworkInfo()
        val dhcp = httpClient.dhcpServerIpAddress

        // 1. Пробваме първо HTTP (Камера)
        if (dhcp == "192.168.0.1") {
            FileLogger.logToFile(this, "FileService", "Засечена камера (192.168.0.1). Стартираме HTTP цикъл.")
            runHttpCycle()
            updateStatus("HTTP: Цикълът завърши", "SUCCESS")
            pendingSftpFiles = 0 // Нулираме другия брояч, докато работим с камерата
            return // Докато има връзка с камерата, не правим SFTP
        }

        // 2. Ако не сме в мрежата на камерата, пробваме SFTP
        FileLogger.logToFile(this, "FileService", "Пробваме SFTP бекъп...")
        pendingCameraFiles = 0 // Нулираме камера брояча
        try {
            val filesToUpload = sftpUpload.getLocalFilesToUpload()
            pendingSftpFiles = filesToUpload.size
            
            if (pendingSftpFiles > 0) {
                filesToUpload.forEach { fileInfo ->
                    sftpUpload.uploadAndDeleteFile(fileInfo)
                    pendingSftpFiles--
                }
                updateStatus(null, null, "SFTP: Файловете са качени", "SUCCESS")
            } else {
                updateStatus(null, null, "SFTP: Няма файлове за качване", "IDLE")
            }
        } catch (e: Exception) {
            Log.e("FileService", "SFTP грешка: ${e.message}")
            updateStatus(null, null, "SFTP: Грешка при качване", "ERROR")
        }
    }

    private suspend fun runHttpCycle(): Boolean {
        var token = httpClient.sessionToken
        
        if (token == null) {
            FileLogger.logToFile(this, "FileService", "Няма токен. Стартираме сдвояване...")
            val authSuccess = httpAuth.performAuthenticationLogic()
            if (!authSuccess) return false
            token = httpClient.sessionToken ?: return false
        } else {
            httpClient.registerClient()
        }

        // Сканираме всички типове от 0 до 15 за пълнота
        val allFiles = mutableListOf<FileInfo>()
        
        for (type in 0..15) {
            httpClient.registerClient()
            val files = httpClient.getFileList(type)
            if (files != null && files.isNotEmpty()) {
                FileLogger.logToFile(this, "FileService", "Тип $type: Намерени ${files.size} файла.")
                
                val readyFiles = if (type == 0 || type == 8 || type == 1 || type == 6 || type == 2) {
                    // Филтрираме видео файловете - сваляме само тези, които са на повече от 3 минути
                    filterFinishedFiles(files)
                } else {
                    files // Снимки и други се свалят веднага
                }
                allFiles.addAll(readyFiles)
            }
        }

        if (allFiles.isEmpty()) {
            FileLogger.logToFile(this, "FileService", "Няма готови файлове за сваляне (останалите са в процес на запис).")
            return true
        }

        // Глобално сортиране
        val sortedFiles = allFiles.sortedBy { it.name }
        pendingCameraFiles = sortedFiles.size

        for (file in sortedFiles) {
            FileLogger.logToFile(this, "FileService", "Обработка на ${file.name}")
            
            // Пътища
            val cleanPath = file.path.removePrefix("/mnt/sd").trimStart('/')
            val localDirFile = File(getExternalFilesDir(null), "camera/$cleanPath")
            if (!localDirFile.exists()) localDirFile.mkdirs()
            
            val localPath = localDirFile.absolutePath
            
            // Само един URL формат според пътя, върнат от камерата
            val downloadUrl = "http://192.168.0.1${file.path}/${file.name}"
            
            var downloadedFile: File? = null
            var startTime = 0L
            var endTime = 0L
            
            httpClient.registerClient()
            startTime = System.currentTimeMillis()
            if (httpClient.downloadFile(downloadUrl, localPath)) {
                endTime = System.currentTimeMillis()
                val f = File(localDirFile, file.name)
                if (f.exists() && f.length() > 0) {
                    downloadedFile = f
                }
            }
            
            if (downloadedFile != null) {
                Log.i("FileService", "Успешно свален: ${file.name}")
                val duration = (endTime - startTime) / 1000.0
                saveDownloadDuration(duration)
                incrementDownloadCount()
                
                httpClient.registerClient()
                if (httpClient.deleteRemoteFile(file.path, file.name, token)) {
                    FileLogger.logToFile(this, "FileService", "Изтрит от камерата.")
                    if (pendingCameraFiles > 0) pendingCameraFiles--
                }
            } else {
                Log.e("FileService", "Грешка при изтегляне или празен файл за ${file.name}. Пропускаме.")
            }
        }
        
        return true
    }

    /**
     * Премахва файловете, които са записани в последните 3 минути,
     * за да гарантира, че не сваляме текущо отворен файл.
     */
    private fun filterFinishedFiles(files: List<FileInfo>): List<FileInfo> {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val now = Calendar.getInstance()
        
        return files.filter { file ->
            try {
                // Имената са от типа: NO20260430-153218...
                val datePart = file.name.substring(2, 17)
                val fileDate = sdf.parse(datePart)
                
                if (fileDate != null) {
                    val fileCal = Calendar.getInstance()
                    fileCal.time = fileDate
                    // Камерата може да е с грешно време, затова гледаме относително
                    // Ако имаме много файлове, махаме най-новите 2 за сигурност
                    // Но ако файлът е по-стар от 3 минути спрямо "сега", той е готов.
                    // Тъй като не знаем времето на камерата точно, dropLast(2) е по-сигурно при голям списък.
                    true 
                } else true
            } catch (e: Exception) {
                true
            }
        }.sortedBy { it.name }.let { sorted ->
            if (sorted.size > 2) sorted.dropLast(2) else emptyList()
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
            putString("LAST_DOWNLOAD_DURATION", String.format(Locale.US, "%.2f сек.", seconds))
        }
    }

    private fun updateStatus(dlStatus: String?, dlRes: String?, upStatus: String? = null, upRes: String? = null) {
        val prefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        prefs.edit {
            if (dlStatus != null) putString("FTP_STATUS", dlStatus)
            if (dlRes != null) putString("FTP_RESULT", dlRes)
            if (upStatus != null) putString("SFTP_STATUS", upStatus)
            if (upRes != null) putString("SFTP_RESULT", upRes)
        }
    }

    private fun scheduleNextRun() {
        nextExecutionTime = System.currentTimeMillis() + checkInterval
        handler.postDelayed(periodicCheck, checkInterval)
        FileLogger.logToFile(this, "FileService", "Следваща проверка е планирана.")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Инициализираме Handler-а с основната нишка
        handler = Handler(Looper.getMainLooper())

        httpClient = HttpClient(this)
        httpAuth = HttpAuthentication(httpClient)
        sftpUpload = SftpUpload(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        // 2. Спираме всякакви предишни задачи и стартираме новата
        handler.removeCallbacks(periodicCheck)
        handler.post(periodicCheck) // Стартираме веднага първия път

        return START_STICKY
    }



    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel("FileTransferChannel", "File Transfer Service", NotificationManager.IMPORTANCE_DEFAULT)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "FileTransferChannel")
            .setContentTitle("File Transfer")
            .setContentText("Сървисът е активен и проверява периодично.")
            .setSmallIcon(R.drawable.ic_notification_sync)
            .build()
    }

    fun getServiceStatus(): ServiceStatus {
        val now = System.currentTimeMillis()
        val remainingMillis = nextExecutionTime - now

        val generalStatus = when {
            isTaskRunning.get() -> "Статус: Изпълнява се цикъл за трансфер..."
            nextExecutionTime == 0L -> "Статус: Очаква се първоначална проверка."
            remainingMillis <= 0 -> "Статус: В очакване на стартиране на проверката."
            else -> {
                val minutes = (remainingMillis / 1000) / 60
                val seconds = (remainingMillis / 1000) % 60
                "Статус: В изчакване. Следваща проверка след около ${minutes}м ${seconds}с."
            }
        }
        val statsPrefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        val totalDownloads = statsPrefs.getInt("TOTAL_DOWNLOADS", 0)
        val totalUploads = statsPrefs.getInt("TOTAL_UPLOADS", 0)
        val lastDuration = statsPrefs.getString("LAST_DOWNLOAD_DURATION", "0 сек.") ?: "0 сек."

        val lastDownloadResult = statsPrefs.getString("FTP_STATUS", null)?: "Изчаква FTP изпълнение"
        val lastUploadResult = statsPrefs.getString("SFTP_STATUS", null)?: "Изчаква SFTP изпълнение"

        return ServiceStatus(
            generalStatus, 
            lastDownloadResult, 
            totalDownloads, 
            pendingCameraFiles,
            lastDuration, 
            lastUploadResult, 
            totalUploads,
            pendingSftpFiles
        )
    }
}