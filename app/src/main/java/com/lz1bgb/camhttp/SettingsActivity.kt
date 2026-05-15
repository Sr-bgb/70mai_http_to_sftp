package com.lz1bgb.camhttp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
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
    
    private var manualServiceState: Boolean? = null

    private val isServiceRunning: Boolean
        get() {
            manualServiceState?.let { return it }
            return FileTransferService.isServiceRunningInForeground
        }

    private val handler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Take service instance via  Binder-а
            val binder = service as FileTransferService.LocalBinder
            fileTransferService = binder.getService()
            isBound = true
            updateStartStopButtonUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileTransferService = null
            isBound = false
            updateStartStopButtonUI()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            connectToWifi()
        } else {
            Toast.makeText(this, "Location permission is required for Wi-Fi", Toast.LENGTH_LONG).show()
        }
    }
    
    private val updateTask = object : Runnable {
        override fun run() {
            refreshServiceStatus()
            handler.postDelayed(this, 5000) 
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
    }

    override fun onStart() {
        super.onStart()
        handler.post(updateTask)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun refreshServiceStatus() {
        if (fileTransferService == null || !isServiceRunning) {
            binding.statusTextView.text = getString(R.string.status_waiting_start)
        }
        if (manualServiceState == null) {
            updateStartStopButtonUI()
        }
        if (isBound && fileTransferService != null) {
            val status = fileTransferService!!.getServiceStatus()
            
            binding.statusTextView.text = status.generalStatus
            binding.lastDownloadResultTextView.text = getString(R.string.label_last_download, status.lastDownloadResult)
            binding.totalFilesDownloadedTextView.text = getString(R.string.label_total_downloaded, status.totalFilesDownloaded)
            binding.lastDownloadDurationTextView.text = getString(R.string.label_last_duration, status.lastDownloadDuration)
            binding.pendingCameraFilesTextView.text = getString(R.string.label_pending_camera, status.pendingCameraFiles)
            binding.lastUploadResultTextView.text = getString(R.string.label_last_upload, status.lastUploadResult)
            binding.totalFilesUploadedTextView.text = getString(R.string.label_total_uploaded, status.totalFilesUploaded)
            binding.pendingSftpFilesTextView.text = getString(R.string.label_pending_sftp, status.pendingSftpFiles)
            
            if (status.currentFileName.isNotEmpty()) {
                binding.fileNameInProcess.text = getString(R.string.label_current_file, status.currentFileName)
                binding.fileTransferSpeed.text = getString(R.string.label_current_speed, status.currentSpeed)
                binding.fileProgressPercentage.text = getString(R.string.label_current_progress, status.currentProgress)
                binding.fileProgressPercentage.visibility = View.VISIBLE
            } else {
                binding.fileNameInProcess.text = getString(R.string.status_idle)
                binding.fileTransferSpeed.text = getString(R.string.speed_idle)
                binding.fileProgressPercentage.visibility = View.GONE
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
        } else {
            updateStartStopButtonUI()
        }
    }

    private fun updateStartStopButtonUI() {
        if (isServiceRunning) {
            binding.startStopButton.text = getString(R.string.btn_stop)
            binding.startStopButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.toggle_stop))
        } else {
            binding.startStopButton.text = getString(R.string.btn_start)
            binding.startStopButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.toggle_start))
            binding.statusTextView.text = getString(R.string.status_waiting_start)
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

        // Bind to service if it's already running or to allow it to be created
        if (isServiceRunning) {
            Intent(this, FileTransferService::class.java).also { intent ->
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            }
        }
        
        val prefs = getSharedPreferences("FtpSettings", MODE_PRIVATE)
        val statsPrefs = getSharedPreferences("FtpStats", MODE_PRIVATE)

        binding.camIPTxt.setText(prefs.getString("IP_CAM", ""))
        binding.camSSIDTxt.setText(prefs.getString("SSID_CAM", ""))
        binding.camWifiPassTxt.setText(prefs.getString("PASS_WIFI_CAM", ""))
        
        binding.backupIPTxt.setText(prefs.getString("IP_SERVER", ""))
        binding.backupUserTxt.setText(prefs.getString("USER_SERVER", ""))
        binding.backupPassTxt.setText(prefs.getString("PASS_SERVER", ""))
        binding.backupPathTxt.setText(prefs.getString("PATH_SERVER", ""))

        // Display initial counters
        val initialTotalDownloads = statsPrefs.getInt("TOTAL_DOWNLOADS", 0)
        binding.totalFilesDownloadedTextView.text = getString(R.string.label_total_downloaded, initialTotalDownloads)
        binding.totalFilesDownloadedTextView.visibility = View.VISIBLE

        val initialTotalUploads = statsPrefs.getInt("TOTAL_UPLOADS", 0)
        binding.totalFilesUploadedTextView.text = getString(R.string.label_total_uploaded, initialTotalUploads)
        binding.totalFilesUploadedTextView.visibility = View.VISIBLE

        /**
         * Toggle button logic for Start/Stop
         */
        binding.startStopButton.setOnClickListener {
            val serviceIntent = Intent(this, FileTransferService::class.java)
            if (!isServiceRunning) {
                // START
                manualServiceState = true
                prefs.edit {
                    putString("IP_CAM", binding.camIPTxt.text.toString())
                    putString("SSID_CAM", binding.camSSIDTxt.text.toString())
                    putString("PASS_WIFI_CAM", binding.camWifiPassTxt.text.toString())
                    putString("IP_SERVER", binding.backupIPTxt.text.toString())
                    putString("USER_SERVER", binding.backupUserTxt.text.toString())
                    putString("PASS_SERVER", binding.backupPassTxt.text.toString())
                    putString("PATH_SERVER", binding.backupPathTxt.text.toString())
                }

                startForegroundService(serviceIntent)
                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                Toast.makeText(this, "Service Started.", Toast.LENGTH_SHORT).show()
            } else {
                // STOP
                manualServiceState = false
                if (isBound && fileTransferService != null) {
                    fileTransferService!!.stopTransfer()

                    try {
                        unbindService(serviceConnection)
                    } catch (e: Exception) {
                        FileLogger.logToFile(this@SettingsActivity,"Activity", "Unbind error: ${e.message}")

                    }

                    isBound = false
                    fileTransferService = null
                }
                stopService(serviceIntent)
                updateStartStopButtonUI()
                Toast.makeText(this, "Service Stopped.", Toast.LENGTH_SHORT).show()
            }
            updateStartStopButtonUI()
        }

        binding.clrCount.setOnClickListener {
            val sp = getSharedPreferences("FtpStats", MODE_PRIVATE)
            sp.edit {
                putInt("TOTAL_DOWNLOADS", 0)
                putInt("TOTAL_UPLOADS", 0)
                putString("FTP_STATUS", getString(R.string.status_idle))
                putString("SFTP_STATUS", getString(R.string.status_idle))
                putString("LAST_DOWNLOAD_DURATION", "0 sec.")
            }
            binding.totalFilesDownloadedTextView.text = getString(R.string.label_total_downloaded, 0)
            binding.lastDownloadDurationTextView.text = getString(R.string.label_last_duration, "0 sec.")
            binding.totalFilesUploadedTextView.text = getString(R.string.label_total_uploaded, 0)
            Toast.makeText(this, "Stats Reset.", Toast.LENGTH_SHORT).show()
        }

        binding.clrToken.setOnClickListener {
            val cp = getSharedPreferences("CamSettings", MODE_PRIVATE)
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
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
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

        if (ssid.isEmpty()) return

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Define the network
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply {
                if (pass.isNotEmpty()) setWpa2Passphrase(pass)
            }
            .build()

        // Request to the system
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Allows network without internet
            .setNetworkSpecifier(specifier)
            .build()

        try {
            // This calls a system BottomSheet, WITHOUT leaving the application
            connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    connectivityManager.bindProcessToNetwork(network)

                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "Connection successful!", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "Camera not found", Toast.LENGTH_SHORT).show()
                    }
                }
            })

            Toast.makeText(this, "Searching for $ssid...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FileLogger.logToFile(this, "WifiError", "Error: ${e.message}")
            // If an error is thrown here, the NEARBY_WIFI_DEVICES permission is likely missing
        }
    }

    private fun setupLanguageSpinner() {
        val languages = arrayOf("Български", "English", "Русский")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = adapter

        // Get current language from AppCompatDelegate
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentLang = if (!currentLocales.isEmpty) currentLocales.get(0)?.language ?: "bg" else "bg"
        
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
                    // Modern way to set application locales
                    val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(langCode)
                    AppCompatDelegate.setApplicationLocales(appLocale)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
