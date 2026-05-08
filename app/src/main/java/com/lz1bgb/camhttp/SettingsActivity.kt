package com.lz1bgb.camhttp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lz1bgb.camhttp.databinding.ActivitySettingsBinding

import android.content.ServiceConnection
import android.graphics.Color



import android.util.Log
import android.view.View
import android.view.WindowManager

import androidx.activity.enableEdgeToEdge

import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import java.net.NetworkInterface
import java.util.Collections

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var httpClient: HttpClient
    // Променливи за връзката със сървиса
    private var fileTransferService: FileTransferService? = null
    private var isBound = false

    private var isModeActive = false
    private var isAlbumActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val updateTask = object : Runnable {
        override fun run() {
            refreshServiceStatus()
            handler.postDelayed(this, 5000) // 5 секунди
        }
    }

    /**
     * ServiceConnection обект, който управлява връзката със сървиса.
     */
    private val connection = object : ServiceConnection {

        // ЗАДЪЛЖИТЕЛЕН МЕТОД 1
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as FileTransferService.LocalBinder
            fileTransferService = binder.getService()
            isBound = true
            FileLogger.logToFile(this@SettingsActivity, "SettingsActivity", "Service connected")
        }

        // ЗАДЪЛЖИТЕЛЕН МЕТОД 2
        override fun onServiceDisconnected(className: ComponentName) {
            isBound = false
            fileTransferService = null
            FileLogger.logToFile(this@SettingsActivity, "SettingsActivity", "Service disconnected")
        }
    }

    /**
     * Активира режима, при който екранът на телефона остава включен.
     * Телефонът няма да "заспива", докато тази активност е видима.
     */
    fun activateKeepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FileLogger.logToFile(this, "SettingsActivity", "Keep Screen On: ACTIVATED")
    }

    /**
     * Деактивира режима и връща телефона към нормалното му поведение
     * (екранът се изключва според системните настройки).
     */
    fun deactivateKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FileLogger.logToFile(this, "SettingsActivity", "Keep Screen On: DEACTIVATED")
    }

    override fun onStop() {
        super.onStop()
        // Спираме автоматичното обновяване
        handler.removeCallbacks(updateTask)
        // Прекъсваме връзката, когато Activity-то вече не е видимо
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
    override fun onStart() {
        super.onStart()
        // Свързваме се със сървиса, когато Activity-то стане видимо
        Intent(this, FileTransferService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        // Стартираме автоматичното обновяване
        handler.post(updateTask)
    }

    private fun refreshServiceStatus() {
        if (isBound && fileTransferService != null) {
            val status = fileTransferService!!.getServiceStatus()

            // Показваме всички статуси в текстовите полета
            binding.statusTextView.text = status.generalStatus
            binding.lastDownloadResultTextView.text = "Последно изтегляне: ${status.lastDownloadResult}"
            binding.totalFilesDownloadedTextView.text = "Общо изтеглени файлове: ${status.totalFilesDownloaded}"
            binding.lastDownloadDurationTextView.text = "Време на последно изтегляне: ${status.lastDownloadDuration}"
            binding.pendingCameraFilesTextView.text = "Оставащи в камерата: ${status.pendingCameraFiles}"
            binding.lastUploadResultTextView.text = "Последно качване: ${status.lastUploadResult}"
            binding.totalFilesUploadedTextView.text = "Общо качени файлове: ${status.totalFilesUploaded}"
            binding.pendingSftpFilesTextView.text = "Оставащи за качване: ${status.pendingSftpFiles}"

            binding.statusTextView.visibility = View.VISIBLE
            binding.lastDownloadResultTextView.visibility = View.VISIBLE
            binding.totalFilesDownloadedTextView.visibility = View.VISIBLE
            binding.lastDownloadDurationTextView.visibility = View.VISIBLE
            binding.pendingCameraFilesTextView.visibility = View.VISIBLE
            binding.lastUploadResultTextView.visibility = View.VISIBLE
            binding.totalFilesUploadedTextView.visibility = View.VISIBLE
            binding.pendingSftpFilesTextView.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        httpClient = HttpClient(this)
        
        val prefs = getSharedPreferences("FtpSettings", Context.MODE_PRIVATE)
        val statsPrefs = getSharedPreferences("FtpStats", Context.MODE_PRIVATE)

        // Зареждане на запазените настройки
        binding.camIPTxt.setText(prefs.getString("IP_CAM", ""))

        binding.backupIPTxt.setText(prefs.getString("IP_SERVER", ""))
        binding.backupUserTxt.setText(prefs.getString("USER_SERVER", ""))
        binding.backupPassTxt.setText(prefs.getString("PASS_SERVER", ""))
        binding.backupPathTxt.setText(prefs.getString("PATH_SERVER", ""))

        // Показваме броячите при стартиране
        val initialTotalDownloads = statsPrefs.getInt("TOTAL_DOWNLOADS", 0)
        binding.totalFilesDownloadedTextView.text = "Общо изтеглени файлове: $initialTotalDownloads"
        binding.totalFilesDownloadedTextView.visibility = View.VISIBLE

        val initialTotalUploads = statsPrefs.getInt("TOTAL_UPLOADS", 0)
        binding.totalFilesUploadedTextView.text = "Общо качени файлове: $initialTotalUploads"
        binding.totalFilesUploadedTextView.visibility = View.VISIBLE


        binding.okButton.setOnClickListener {

            prefs.edit {

                // Запазване на настройките
                putString("IP_CAM", binding.camIPTxt.text.toString())


                putString("IP_SERVER", binding.backupIPTxt.text.toString())
                putString("USER_SERVER", binding.backupUserTxt.text.toString())
                putString("PASS_SERVER", binding.backupPassTxt.text.toString())
                putString("PATH_SERVER", binding.backupPathTxt.text.toString())

            }


            // Стартираме сървиса по правилния начин
            val serviceIntent = Intent(this, FileTransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Сървисът е стартиран.", Toast.LENGTH_SHORT).show()
        }

        binding.infoButton.setOnClickListener {
            refreshServiceStatus()
            if (!isBound || fileTransferService == null) {
                Toast.makeText(this, "Сървисът не е активен или все още се свързва.", Toast.LENGTH_LONG).show()
            }
        }

        // --- НОВАТА ЛОГИКА ЗА БУТОНА "СПРИ И ИЗЛЕЗ" ---
        binding.stopButton.setOnClickListener {
            // 1. Създаваме Intent към нашия сървис
            val serviceIntent = Intent(this, FileTransferService::class.java)
            // 2. Изпращаме команда за спиране на сървиса
            stopService(serviceIntent)
            Toast.makeText(this, "Сървисът е спрян.", Toast.LENGTH_SHORT).show()
            // 3. Затваряме приложението
            finishAffinity()
        }

        binding.clrCount.setOnClickListener {
            val statsPrefs = getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
            statsPrefs.edit {
                putInt("TOTAL_DOWNLOADS", 0)
                putInt("TOTAL_UPLOADS", 0)
                putString("FTP_STATUS", "Статистиката е нулирана")
                putString("SFTP_STATUS", "Статистиката е нулирана")
                putString("LAST_DOWNLOAD_DURATION", "0 сек.")
            }
            
            // Обновяваме UI веднага
            binding.totalFilesDownloadedTextView.text = "Общо изтеглени файлове: 0"
            binding.lastDownloadDurationTextView.text = "Време на последно изтегляне: 0 сек."
            binding.totalFilesUploadedTextView.text = "Общо качени файлове: 0"
            
            Toast.makeText(this, "Статистиката е нулирана.", Toast.LENGTH_SHORT).show()
        }

        binding.clrToken.setOnClickListener {
            // Изчистваме само сесийния токен на камерата
            val camPrefs = getSharedPreferences("CamSettings", Context.MODE_PRIVATE)
            camPrefs.edit {
                remove("SESSION_TOKEN")
            }
            
            Toast.makeText(this, "Токенът е нулиран. Камерата ще изиска ново сдвояване.", Toast.LENGTH_SHORT).show()
        }

        binding.albumButton.setOnClickListener {
            lifecycleScope.launch {
                isAlbumActive = !isAlbumActive
                val success = httpClient.setAlbumMode(isAlbumActive)
                if (success) {
                    if (isAlbumActive) {
                        binding.albumButton.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_blue_dark))
                        binding.albumButton.setTextColor(Color.WHITE)
                        Toast.makeText(this@SettingsActivity, "Режим 'Албум' (Достъп до файлове)", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.albumButton.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, android.R.color.white))
                        binding.albumButton.setTextColor(Color.BLACK)
                        Toast.makeText(this@SettingsActivity, "Режим 'Запис' (Камерата снима)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    isAlbumActive = !isAlbumActive // Връщаме състоянието при грешка
                    Toast.makeText(this@SettingsActivity, "Грешка при промяна на режима", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.injectButton.setOnClickListener {
            lifecycleScope.launch {
                val success = httpClient.injectFTP()
                if (success) {
                    Toast.makeText(this@SettingsActivity, "Командата е изпратена успешно", Toast.LENGTH_SHORT).show()
                    FileLogger.logToFile(this@SettingsActivity, "SettingsActivity", "Inject command: SUCCESS")
                } else {
                    Toast.makeText(this@SettingsActivity, "Грешка при изпращане на командата", Toast.LENGTH_SHORT).show()
                    FileLogger.logToFile(this@SettingsActivity, "SettingsActivity", "Inject command: FAILED")
                }
            }
        }

        binding.sleepButton.setOnClickListener {
            isModeActive = !isModeActive
            if(isModeActive){
                activateKeepScreenOn()
                binding.sleepButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                binding.sleepButton.setTextColor(Color.WHITE)
                Toast.makeText(this, "Режим 'Без заспиване' е активен", Toast.LENGTH_SHORT).show()
            }else{
                deactivateKeepScreenOn()
                binding.sleepButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                binding.sleepButton.setTextColor(Color.BLACK)
                Toast.makeText(this, "Режим 'Без заспиване' е деактивиран", Toast.LENGTH_SHORT).show()
            }
        }
    }
}