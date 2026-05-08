package com.lz1bgb.camhttp
import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import android.util.Log

import java.math.BigInteger
import java.security.MessageDigest


import java.io.FileOutputStream
import java.io.OutputStream

import androidx.core.content.edit
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import android.os.Build
import java.util.Locale

@Serializable
data class FileInfo(
    val path: String,
    val name: String,
    val size: String,
    val type: String,
)

@Serializable
data class ApiResponse(
    @SerialName("ResultCode")
    val resultCode: kotlinx.serialization.json.JsonElement,
    @SerialName("Result")
    val result: List<FileInfo> = emptyList(),
)

class HttpClient(context: Context) {

    /**
     * ▼▼▼ 2. Съхраняваме applicationContext, за да избегнем memory leaks ▼▼▼
     * (Това е най-добрата практика)
     */
    val appContext = context.applicationContext

    var currentIpAddress: String? = null
        private set // Правим setter-а private, за да се задава само от класа

    /**
     * IP адресът на DHCP сървъра.
     * Обновява се чрез извикване на updateNetworkInfo().
     */
    var dhcpServerIpAddress: String? = null
        private set // Правим setter-а private

    // Създаваме единствен екземпляр на OkHttpClient,
    // който да се преизползва за всички заявки.
    // Настройваме timeouts на 0 (безкрайно), за да поддържаме много дълги изтегляния на големи файлове.
    private var client = OkHttpClient.Builder()
        .connectTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Специален клиент за камерата, вързан към Wi-Fi мрежата
    private var cameraClient: OkHttpClient? = null
    private var cameraNetwork: Network? = null

    private val json = Json { ignoreUnknownKeys = true }

    // Сесиен токен, получен след успешно свързване (pairing)
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
     * Изчислява MD5 хеш на входния стринг и го връща като hex стринг
     * @param input входният стринг за хеширане
     * @return MD5 хеш като hex стринг (32 символа)
     */
    fun calculateMD5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    /**
     * Генерира подпис (sign) за 70mai заявки.
     * Алгоритъм: MD5(URL_Query + Token)
     */
    fun generateSignKey(urlQuery: String, token: String): String {
        return calculateMD5(urlQuery + token)
    }

    fun updateNetworkInfo() {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Намираме всички налични мрежи.
        @Suppress("DEPRECATION")
        val networks = connectivityManager.allNetworks
        var foundCameraNetwork: Network? = null

        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)

            // Търсим Wi-Fi мрежа, чийто DHCP сървър (Gateway) е 192.168.0.1
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // Поддръжка за различни API нива
                val dhcpAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    linkProperties?.dhcpServerAddress?.hostAddress
                } else {
                    // Fallback за по-стари версии
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

                if (dhcpAddr == "192.168.0.1") {
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
                // Създаваме OkHttpClient, който е вързан директно към тази мрежа
                this.cameraClient = client.newBuilder()
                    .socketFactory(foundCameraNetwork.socketFactory)
                    .build()
                FileLogger.logToFile(appContext, "HttpClient", "Връзката с камерата е пренасочена през Wi-Fi (дори при активни мобилни данни).")
            }
        } else {
            this.cameraNetwork = null
            this.cameraClient = null
            FileLogger.logToFile(appContext, "HttpClient", "Камерата не е открита в нито една Wi-Fi мрежа.")
            
            // Ако не сме в мрежата на камерата, обновяваме стандартното инфо за активната мрежа
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
     * Свързва се към даден URL и връща получения JSON отговор като String.
     * Използва специалния cameraClient, ако заявката е към камерата.
     */
    suspend fun executeRequest(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                
                // Използваме cameraClient за заявки към 192.168.0.1
                val activeClient = if (url.contains("192.168.0.1") && cameraClient != null) {
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
                    FileLogger.logToFile(appContext, "HttpClient", "Грешка при заявка (${response.code}): $url")
                    response.close()
                    null
                }
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Изтегля файл от даден URL и го записва в локална папка.
     */
    suspend fun downloadFile(url: String, destinationFolderPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                FileLogger.logToFile(appContext, "HttpClient", "Проба URL: $url")
                FileLogger.logToFile(appContext, "HttpClient", "Проба цел: $destinationFolderPath")

                val fileName = url.substringAfterLast('/').substringBefore('?')
                if (fileName.isEmpty()) return@withContext false

                val folder = File(destinationFolderPath)
                if (!folder.exists() && !folder.mkdirs()) return@withContext false

                val destinationFile = File(folder, fileName)

                val request = Request.Builder().url(url).build()
                FileLogger.logToFile(appContext, "HttpClient", "Downloading from URL: $url")
                
                // Използваме cameraClient за изтегляне от камерата
                val activeClient = if (url.contains("192.168.0.1") && cameraClient != null) {
                    cameraClient!!
                } else {
                    client
                }
                
                val response = activeClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    FileLogger.logToFile(appContext, "HttpClient", "Грешка при изтегляне на файл: ${response.code}")
                    response.close() // Затваряме при грешка
                    return@withContext false
                }

                val body = response.body
                if (body == null) {
                    FileLogger.logToFile(appContext, "HttpClient", "Грешка: Празно тяло на отговора")
                    response.close() // Затваряме
                    return@withContext false
                }

                // 5. Записване на файла на диска
                // .use автоматично затваря body (което затваря и response)
                body.use { responseBody ->
                    responseBody.byteStream().use { inputStream ->
                        FileOutputStream(destinationFile).use { outputStream ->
                            val buffer = ByteArray(65536) // Увеличен буфер (64KB) за по-стабилен трансфер
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }

                return@withContext true // Успех!

            } catch (e: IOException) {
                e.printStackTrace()
                return@withContext false
            } catch (e: SecurityException) {
                // Възможно е да нямаме права за писане в тази папка
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    /**
     * Включва или изключва режима 'Албум' на камерата.
     * @param enable true за включване (1), false за изключване (0).
     */
    suspend fun setAlbumMode(enable: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                updateNetworkInfo() // Гарантираме, че сме в правилната мрежа
                val dhcp = dhcpServerIpAddress ?: "192.168.0.1"
                val token = sessionToken ?: return@withContext false
                val timestamp = System.currentTimeMillis() / 1000L
                // Обръщаме логиката според изискването: 
                // enable=0 за включване на достъп (албум), enable=1 за запис
                val enableValue = if (enable) 0 else 1
                
                val queryPath = "setaccessalbum.cgi?"
                val queryParams = "&-enable=$enableValue&-timestamp=$timestamp"
                val sign = generateSignKey(queryPath + queryParams, token)
                
                val url = "http://$dhcp/cgi-bin/$queryPath$queryParams&-signkey=$sign"
                val response = executeRequest(url)
                
                FileLogger.logToFile(appContext, "HttpClient", "Set album mode response: $response")
                // Камерата връща ResultCode 0 при успех
                response?.contains("\"ResultCode\":0") == true || response?.contains("\"ResultCode\":\"0\"") == true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * Регистрира клиента пред камерата (Keep-alive / Phone Connected UI).
     */
    suspend fun registerClient(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val dhcp = dhcpServerIpAddress ?: "192.168.0.1"
                val ip = currentIpAddress ?: return@withContext false
                val token = sessionToken ?: return@withContext false
                val timestamp = System.currentTimeMillis() / 1000L
                
                val queryPath = "client.cgi?"
                val queryParams = "&-operation=register&-ip=$ip&-timestamp=$timestamp"
                val sign = generateSignKey(queryPath + queryParams, token)
                
                val url = "http://$dhcp/cgi-bin/$queryPath$queryParams&-signkey=$sign"
                val response = executeRequest(url)
                
                FileLogger.logToFile(appContext, "HttpClient", "Register client response: $response")
                response?.contains("success") == true || response?.contains("\"ResultCode\":0") == true || response?.contains("\"ResultCode\":\"0\"") == true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * Връща списък с ВСИЧКИ файлове от камерата за определен тип,
     * като автоматично се справя с пагинацията (лимит 100 файла).
     */
    suspend fun getFileList(type: Int): List<FileInfo>? {
        return withContext(Dispatchers.IO) {
            try {
                val dhcp = dhcpServerIpAddress ?: "192.168.0.1"
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
                        
                        // Ако сме получили по-малко от заявените, значи няма повече
                        if (batch.size < pageSize) break
                        
                        startIndex += pageSize
                    } else {
                        break
                    }
                }
                
                if (allFiles.isNotEmpty()) allFiles else null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Изтрива файл от камерата.
     *
     * @param path Пътят до папката (напр. /mnt/sd/Normal/Front).
     * @param name Името на файла (напр. NO20210103-080052-000233F.MP4).
     * @param token Токенът за автентикация.
     * @return true при успех, false при грешка.
     */
    suspend fun deleteRemoteFile(path: String, name: String, token: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val dhcp = dhcpServerIpAddress ?: "192.168.0.1"
                val timestamp = System.currentTimeMillis() / 1000L
                
                // Формираме URL_Query за изтриване според спецификацията
                val queryPath = "delete.cgi?"
                val queryParams = "&-path=$path&-name=$name&-timestamp=$timestamp"
                
                // Генерираме подписа: MD5(URI + Params + Token)
                val sign = generateSignKey(queryPath + queryParams, token)
                
                val url = "http://$dhcp/cgi-bin/$queryPath$queryParams&-signkey=$sign"
                val responseJson = executeRequest(url)

                responseJson != null && responseJson.contains("\"ResultCode\":\"0\"")
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * Изтегля файл и го изтрива от камерата само при успешно записване.
     */
    suspend fun downloadAndDeleteFile(
        downloadUrl: String,
        remotePath: String,
        remoteName: String,
        destinationFolderPath: String,
        token: String
    ): Boolean {
        val success = downloadFile(downloadUrl, destinationFolderPath)
        if (success) {
            println("Файлът е изтеглен успешно. Изпълнява се изтриване...")
            return deleteRemoteFile(remotePath, remoteName, token)
        }
        return false
    }

    fun checkAuthentication(): Boolean {
        updateNetworkInfo()
        return dhcpServerIpAddress == "192.168.0.1"
    }

    /**
     * Изпраща заявка за завършване на OTA ъпдейт.
     */
    suspend fun updateFW(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                updateNetworkInfo()
                val dhcp = dhcpServerIpAddress ?: "192.168.0.1"
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
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * Изтрива конкретен файл LOG.MP3 за целите на поддръжката.
     */
    suspend fun injectFTP(): Boolean {
//        val success = downloadFile("http://192.168.0.1/usr/bin/main_app",
//              "/storage/emulated/0/Android/data/com.example.camhttp/files")
        val success1 = updateFW()
        val success = downloadFile("http://192.168.0.1/usr/bin/log/syslog_file",
            "/storage/emulated/0/Android/data/com.lz1bgb.camhttp/files")
        val success2 = downloadFile("http://192.168.0.1/usr/bin/ota_run",
            "/storage/emulated/0/Android/data/com.lz1bgb.camhttp/files")

        val token = sessionToken ?: return false
        return deleteRemoteFile("/mnt/sd/Parking/Front", "LOG.MP4;/usr/sbin/inetd%20%26", token)
    }
}
