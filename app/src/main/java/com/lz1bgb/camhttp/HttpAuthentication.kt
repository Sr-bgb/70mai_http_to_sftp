package com.lz1bgb.camhttp

import kotlinx.coroutines.delay
import android.util.Log
import java.security.MessageDigest
import kotlin.random.Random

/**
 * @brief Class handling the 70mai dashcam pairing and authentication protocol.
 * 
 * Implements the multi-step handshake required to obtain a session token:
 * 1. Initial Binding (BindByBanya)
 * 2. User Confirmation (UserconfirmByBanya - physical button press)
 * 3. Client Registration (client.cgi - keep-alive)
 */
class HttpAuthentication(private val httpClient: HttpClient) {

    private val pairingSalt = "73VpsAfdety8FDd0"

    /**
     * @brief Executes the full authentication handshake logic.
     * 
     * This method initiates pairing, waits for the user to press the power button on the camera,
     * and finally registers the client.
     * @return true if authentication succeeded and token was saved.
     */
    suspend fun performAuthenticationLogic(): Boolean {
        return try {
            httpClient.updateNetworkInfo()
            val dhcp = httpClient.dhcpServerIpAddress ?: httpClient.cameraIp

            // 1. Initiate Binding (BindByBanya)
            val userId = Random.nextInt(100000, 999999).toString()
            val key1 = getMd5Hash(userId + pairingSalt)
            val bindUrl = "http://$dhcp/cgi-bin/BindByBanya.cgi?&-usr=$userId&-signkey=$key1"
            
            Log.i("HttpAuth", "--- STEP 1: BindByBanya ---")
            Log.i("HttpAuth", "Sending URL: $bindUrl")
            
            val bindResponse = httpClient.executeRequest(bindUrl)
            Log.i("HttpAuth", "Response received: $bindResponse")

            if (bindResponse == null) {
                Log.e("HttpAuth", "Error: Empty response during Bind")
                return false
            }
            
            // Check for success ResultCode
            if (!bindResponse.contains("\"ResultCode\":0") && !bindResponse.contains("\"ResultCode\":\"0\"")) {
                Log.e("HttpAuth", "Bind failed: ResultCode is not 0")
                return false
            }
            
            // Extract temporary token and timestamp
            val token = extractJsonValue(bindResponse, "Token")
            val timestamp = extractJsonValue(bindResponse, "timestamp")

            if (token.isEmpty() || timestamp.isEmpty()) {
                Log.e("HttpAuth", "Failed to extract token or timestamp from bind response")
                return false
            }

            // 2. User Confirmation (UserconfirmByBanya)
            // Polling endpoint until user presses the physical button on the camera
            val key2 = getMd5Hash(timestamp + pairingSalt)
            val confirmUrl = "http://$dhcp/cgi-bin/UserconfirmByBanya.cgi?&-timestamp=$timestamp&-signkey=$key2"
            
            Log.i("HttpAuth", "--- STEP 2: Userconfirm (Wait for button) ---")
            Log.i("HttpAuth", "Timestamp used: $timestamp")
            Log.i("HttpAuth", "Confirmation URL: $confirmUrl")

            var confirmed = false
            for (i in 1..20) { // Try for ~100 seconds
                val confirmResponse = httpClient.executeRequest(confirmUrl)
                FileLogger.logToFile(httpClient.appContext, "HttpAuth", "Attempt $i/20 - Response: $confirmResponse")

                if (confirmResponse != null && 
                    ((confirmResponse.contains("\"ResultCode\":0") || confirmResponse.contains("\"ResultCode\":\"0\"")))) {
                    
                    confirmed = true
                    // Final token might be returned here
                    val finalToken = extractJsonValue(confirmResponse, "Token")
                    if (finalToken.isNotEmpty()) {
                        httpClient.sessionToken = finalToken
                        FileLogger.logToFile(httpClient.appContext, "HttpAuth", "Final session token updated: $finalToken")
                    } else {
                        httpClient.sessionToken = token
                        FileLogger.logToFile(httpClient.appContext, "HttpAuth", "Using initial session token: $token")
                    }
                    break
                }
                
                if (confirmResponse == null) {
                    Log.w("HttpAuth", "Confirm request failed (null response)")
                }

                delay(5000)
            }

            if (confirmed) {
                // 3. Register client (Keep-alive step)
                Log.i("HttpAuth", "--- STEP 3: Client Registration (Keep-alive) ---")
                val registered = httpClient.registerClient()
                if (registered) {
                    Log.i("HttpAuth", "Registration successful. Camera ready.")
                } else {
                    Log.w("HttpAuth", "Registration returned error, but proceeding with token.")
                }

                println("Authentication Success! Token: ${httpClient.sessionToken}")
                return true
            }

            false
        } catch (e: Exception) {
            FileLogger.logToFile(httpClient.appContext, "HttpAuth", "Authentication error: ${e.message}")
            Log.e("HttpAuth", "Authentication error: ${e.message}")
            false
        }
    }

    /**
     * @brief Utility to generate MD5 hash from input string.
     */
    fun getMd5Hash(input: String): String {
        return try {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            FileLogger.logToFile(httpClient.appContext, "HttpAuth", "MD5 Hash error: ${e.message}")
            "" 
        }
    }

    /**
     * @brief Helper method to extract a specific value from a simple JSON string.
     * @param json Raw JSON string.
     * @param key Target key.
     * @return Extracted value or empty string.
     */
    private fun extractJsonValue(json: String, key: String): String {
        return try {
            val searchKey = "\"$key\""
            if (!json.contains(searchKey)) return ""
            
            val afterKey = json.substringAfter(searchKey).trim().removePrefix(":")
            if (afterKey.startsWith("\"")) {
                afterKey.substringAfter("\"").substringBefore("\"")
            } else {
                afterKey.split(Regex("[,}]"))[0].trim()
            }
        } catch (e: Exception) {
            FileLogger.logToFile(httpClient.appContext, "HttpAuth", "JSON Extraction error: ${e.message}")
            ""
        }
    }
}
