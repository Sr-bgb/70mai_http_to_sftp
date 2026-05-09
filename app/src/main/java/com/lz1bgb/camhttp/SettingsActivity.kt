package com.lz1bgb.camhttp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.lz1bgb.camhttp.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * @brief Main UI activity for configuring camera settings and monitoring transfer progress.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var httpClient: HttpClient
    
    private var fileTransferService: FileTransferService? = null
    private var isBound = false

    private var isModeActive = false
    private var isAlbumActive = false

    private val handler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            connectToWifi()
        } else {
            Toast.makeText(this, "Location permission is required for Wi-Fi", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * @brief Injects the saved locale into the activity context before creation.
     */
    override fun attachBaseContext(newBase: Context) {
        val langPrefs = newBase.getSharedPreferences("AppSettings", MODE_PRIVATE)
        val langCode = langPrefs.getString("App_Language", "bg") ?: "bg"
        val locale = java.util.Locale(langCode)
        java.util.Locale.setDefault(locale)
        
        val config = newBase.resources.configuration
        config.setLocale(locale)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    private val updateTask = object : Runnable {
        override fun run() {
            refreshServiceStatus()
            handler.postDelayed(this, 5000) 
        }
    }

    /**
     * @brief Connection handler for the FileTransferService.
     */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as FileTransferService.LocalBinder
            fileTransferService = binder.getService()
            isBound = true
            FileLogger.logToFile(this@SettingsActivity, "SettingsActivity", "Service connected")
        }

        override fun onServiceDisconnected(className: ComponentName) {
            isBound = false
            fileTransferService = null
            FileLogger.logToFile(this@SettingsActivity, "SettingsActivity", "Service disconnected")
        }
    }

    fun activateKeepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FileLogger.logToFile(this, "SettingsActivity", "Keep Screen On: ACTIVATED")
    }

    fun deactivateKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FileLogger.logToFile(this, "SettingsActivity", "Keep Screen On: DEACTIVATED")
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(updateTask)
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, FileTransferService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        handler.post(updateTask)
    }

    private fun refreshServiceStatus() {
        if (isBound && fileTransferService != null) {
            val status = fileTransferService!!.getServiceStatus()

            binding.statusTextView.text = status.generalStatus
            binding.lastDownloadResultTextView.text = "Last Download: ${status.lastDownloadResult}"
            binding.totalFilesDownloadedTextView.text = "Total Downloaded: ${status.totalFilesDownloaded}"
            binding.lastDownloadDurationTextView.text = "Last Transfer Time: ${status.lastDownloadDuration}"
            binding.pendingCameraFilesTextView.text = "Remaining on Camera: ${status.pendingCameraFiles}"
            binding.lastUploadResultTextView.text = "Last Upload: ${status.lastUploadResult}"
            binding.totalFilesUploadedTextView.text = "Total Uploaded: ${status.totalFilesUploaded}"
            binding.pendingSftpFilesTextView.text = "Remaining for Upload: ${status.pendingSftpFiles}"
            
            if (status.currentFileName.isNotEmpty()) {
                binding.fileNameInProcess.text = "File: ${status.currentFileName}"
                binding.fileTransferSpeed.text = "Speed: ${status.currentSpeed}"
            } else {
                binding.fileNameInProcess.text = getString(R.string.status_idle)
                binding.fileTransferSpeed.text = getString(R.string.speed_idle)
            }

            binding.statusTextView.visibility = View.VISIBLE
            binding.lastDownloadResultTextView.visibility = View.VISIBLE
            binding.fileNameInProcess.visibility = View.VISIBLE
            binding.fileTransferSpeed.visibility = View.VISIBLE
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

        // Add manual padding to respect the status bar and navigation bar (Edge-to-Edge safety)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        httpClient = HttpClient(this)
        setupLanguageSpinner()
        setupTabs()
        
        val prefs = getSharedPreferences("FtpSettings", Context.MODE_PRIVATE)
        val statsPrefs = getSharedPreferences("FtpStats", Context.MODE_PRIVATE)

        binding.camIPTxt.setText(prefs.getString("IP_CAM", ""))
        binding.camSSIDTxt.setText(prefs.getString("SSID_CAM", ""))
        binding.camWifiPassTxt.setText(prefs.getString("PASS_WIFI_CAM", ""))
        
        binding.backupIPTxt.setText(prefs.getString("IP_SERVER", ""))
        binding.backupUserTxt.setText(prefs.getString("USER_SERVER", ""))
        binding.backupPassTxt.setText(prefs.getString("PASS_SERVER", ""))
        binding.backupPathTxt.setText(prefs.getString("PATH_SERVER", ""))

        val initialTotalDownloads = statsPrefs.getInt("TOTAL_DOWNLOADS", 0)
        binding.totalFilesDownloadedTextView.text = "Total Downloaded: $initialTotalDownloads"
        binding.totalFilesDownloadedTextView.visibility = View.VISIBLE

        val initialTotalUploads = statsPrefs.getInt("TOTAL_UPLOADS", 0)
        binding.totalFilesUploadedTextView.text = "Total Uploaded: $initialTotalUploads"
        binding.totalFilesUploadedTextView.visibility = View.VISIBLE

        binding.okButton.setOnClickListener {
            prefs.edit {
                putString("IP_CAM", binding.camIPTxt.text.toString())
                putString("SSID_CAM", binding.camSSIDTxt.text.toString())
                putString("PASS_WIFI_CAM", binding.camWifiPassTxt.text.toString())
                putString("IP_SERVER", binding.backupIPTxt.text.toString())
                putString("USER_SERVER", binding.backupUserTxt.text.toString())
                putString("PASS_SERVER", binding.backupPassTxt.text.toString())
                putString("PATH_SERVER", binding.backupPathTxt.text.toString())
            }

            val serviceIntent = Intent(this, FileTransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Service Started.", Toast.LENGTH_SHORT).show()
        }

        binding.infoButton.setOnClickListener {
            refreshServiceStatus()
            if (!isBound || fileTransferService == null) {
                Toast.makeText(this, "Service not active.", Toast.LENGTH_LONG).show()
            }
        }

        binding.stopButton.setOnClickListener {
            val serviceIntent = Intent(this, FileTransferService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "Service Stopped.", Toast.LENGTH_SHORT).show()
        }

        binding.clrCount.setOnClickListener {
            val sp = getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
            sp.edit {
                putInt("TOTAL_DOWNLOADS", 0)
                putInt("TOTAL_UPLOADS", 0)
                putString("FTP_STATUS", "Stats Reset")
                putString("SFTP_STATUS", "Stats Reset")
                putString("LAST_DOWNLOAD_DURATION", "0 sec.")
            }
            binding.totalFilesDownloadedTextView.text = "Total Downloaded: 0"
            binding.lastDownloadDurationTextView.text = "Last Transfer Time: 0 sec."
            binding.totalFilesUploadedTextView.text = "Total Uploaded: 0"
            Toast.makeText(this, "Stats Reset.", Toast.LENGTH_SHORT).show()
        }

        binding.clrToken.setOnClickListener {
            val cp = getSharedPreferences("CamSettings", Context.MODE_PRIVATE)
            cp.edit { remove("SESSION_TOKEN") }
            Toast.makeText(this, "Token Reset. New pairing required.", Toast.LENGTH_SHORT).show()
        }

        binding.albumButton.setOnClickListener {
            lifecycleScope.launch {
                isAlbumActive = !isAlbumActive
                val success = httpClient.setAlbumMode(isAlbumActive)
                if (success) {
                    if (isAlbumActive) {
                        binding.albumButton.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_blue_dark))
                        binding.albumButton.setTextColor(Color.WHITE)
                        Toast.makeText(this@SettingsActivity, "Album Mode (File Access) ON", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.albumButton.setBackgroundColor(Color.TRANSPARENT)
                        binding.albumButton.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
                        Toast.makeText(this@SettingsActivity, "Recording Mode (Camera filming) ON", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    isAlbumActive = !isAlbumActive 
                    Toast.makeText(this@SettingsActivity, "Error changing mode", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.injectButton.setOnClickListener {
            lifecycleScope.launch {
                val success = httpClient.injectFTP()
                if (success) {
                    Toast.makeText(this@SettingsActivity, "Command SUCCESS", Toast.LENGTH_SHORT).show()
                    FileLogger.logToFile(this@SettingsActivity, "SettingsActivity", "Inject command: SUCCESS")
                } else {
                    Toast.makeText(this@SettingsActivity, "Command FAILED", Toast.LENGTH_SHORT).show()
                    FileLogger.logToFile(this@SettingsActivity, "SettingsActivity", "Inject command: FAILED")
                }
            }
        }

        binding.connectWifiButton.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                connectToWifi()
            }
        }

        binding.sleepButton.setOnClickListener {
            isModeActive = !isModeActive
            if(isModeActive){
                activateKeepScreenOn()
                binding.sleepButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                binding.sleepButton.setTextColor(Color.WHITE)
                Toast.makeText(this, "Keep Screen On: ACTIVE", Toast.LENGTH_SHORT).show()
            }else{
                deactivateKeepScreenOn()
                binding.sleepButton.setBackgroundColor(Color.TRANSPARENT)
                binding.sleepButton.setTextColor(ContextCompat.getColor(this, R.color.white))
                Toast.makeText(this, "Keep Screen On: OFF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.controlsLayout.visibility = View.VISIBLE
                        binding.settingsLayout.visibility = View.GONE
                    }
                    1 -> {
                        binding.controlsLayout.visibility = View.GONE
                        binding.settingsLayout.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun connectToWifi() {
        val ssid = binding.camSSIDTxt.text.toString()
        val pass = binding.camWifiPassTxt.text.toString()

        if (ssid.isEmpty()) {
            Toast.makeText(this, "SSID is required", Toast.LENGTH_SHORT).show()
            return
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 1. First suggest the network for auto-connection (requires password)
            if (pass.isNotEmpty()) {
                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pass)
                    .build()
                wifiManager.addNetworkSuggestions(listOf(suggestion))
            }

            // 2. Then open the Connectivity Panel for immediate manual selection if needed
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            startActivity(panelIntent)
            
            Toast.makeText(this, "Tap $ssid in the panel below", Toast.LENGTH_LONG).show()
        } else {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun setupLanguageSpinner() {
        val languages = arrayOf("Български", "English", "Русский")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = adapter

        val langPrefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val currentLang = langPrefs.getString("App_Language", "bg") ?: "bg"
        
        val selection = when (currentLang) {
            "bg" -> 0
            "en" -> 1
            "ru" -> 2
            else -> 0
        }
        
        binding.languageSpinner.setSelection(selection, false)

        binding.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val langCode = when (position) {
                    0 -> "bg"
                    1 -> "en"
                    2 -> "ru"
                    else -> "bg"
                }
                
                if (langCode != currentLang) {
                    val lp = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                    lp.edit { putString("App_Language", langCode) }
                    recreate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
