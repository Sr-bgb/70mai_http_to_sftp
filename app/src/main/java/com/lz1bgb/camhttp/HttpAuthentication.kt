package com.lz1bgb.camhttp

import kotlinx.coroutines.delay

import android.util.Log
import java.security.MessageDigest
import kotlin.random.Random

// Конструкторът вече не приема EditText
class HttpAuthentication(private val httpClient: HttpClient) {

    private val pairingSalt = "73VpsAfdety8FDd0"

    /**
     * Същинската логика за автентикация.
     * Реализира двустепенния процес на свързване (pairing) според спецификацията.
     */
    suspend fun performAuthenticationLogic(): Boolean {
        return try {
            httpClient.updateNetworkInfo()
            val dhcp = httpClient.dhcpServerIpAddress ?: "192.168.0.1"

            // 1. Иницииране на свързване (BindByBanya)
            val userId = Random.nextInt(100000, 999999).toString()
            val key1 = getMd5Hash(userId + pairingSalt)
            val bindUrl = "http://$dhcp/cgi-bin/BindByBanya.cgi?&-usr=$userId&-signkey=$key1"
            
            Log.i("HttpAuth", "--- СТЪПКА 1: BindByBanya ---")
            Log.i("HttpAuth", "Изпращане на URL: $bindUrl")
            
            val bindResponse = httpClient.executeRequest(bindUrl)
            Log.i("HttpAuth", "Получен отговор: $bindResponse")

            if (bindResponse == null) {
                Log.e("HttpAuth", "Грешка: Празен отговор при Bind")
                return false
            }
            
            // Очакваме JSON с Token и timestamp
            if (!bindResponse.contains("\"ResultCode\":0") && !bindResponse.contains("\"ResultCode\":\"0\"")) {
                Log.e("HttpAuth", "Bind failed: ResultCode is not 0")
                return false
            }
            
            // Извличаме токена и таймстампа по-гъвкаво
            val token = extractJsonValue(bindResponse, "Token")
            val timestamp = extractJsonValue(bindResponse, "timestamp")

            if (token.isEmpty() || timestamp.isEmpty()) {
                Log.e("HttpAuth", "Failed to extract token or timestamp from bind response")
                return false
            }

            // 2. Потвърждение от потребителя (UserconfirmByBanya)
            // Трябва да се вика периодично, докато потребителят не натисне бутона на камерата
            val key2 = getMd5Hash(timestamp + pairingSalt)
            val confirmUrl = "http://$dhcp/cgi-bin/UserconfirmByBanya.cgi?&-timestamp=$timestamp&-signkey=$key2"
            
            Log.i("HttpAuth", "--- СТЪПКА 2: Userconfirm (Изчакване на бутона) ---")
            Log.i("HttpAuth", "Използван timestamp: $timestamp")
            Log.i("HttpAuth", "URL за потвърждение: $confirmUrl")

            var confirmed = false
            for (i in 1..20) { // Опитваме 20 пъти (100 секунди общо)
                val confirmResponse = httpClient.executeRequest(confirmUrl)
                FileLogger.logToFile(httpClient.appContext, "HttpAuth", "Опит $i/20 - Отговор: $confirmResponse")

                if (confirmResponse != null && 
                    (confirmResponse.contains("\"ResultCode\":0") || confirmResponse.contains("\"ResultCode\":\"0\""))) {
                    
                    confirmed = true
                    // Отново извличаме токена, защото понякога финалният токен се дава тук
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
                Log.i("HttpAuth", "--- СТЪПКА 3: Регистрация на клиента (Keep-alive) ---")
                val registered = httpClient.registerClient()
                if (registered) {
                    Log.i("HttpAuth", "Регистрацията е успешна. Камерата вече трябва да е готова.")
                } else {
                    Log.w("HttpAuth", "Регистрацията върна грешка, но продължаваме с наличния токен.")
                }

                println("Автентикация успешна! Токен: ${httpClient.sessionToken}")
                return true
            }

            false
        } catch (e: Exception) {
            Log.e("HttpAuth", "Грешка при автентикация: ${e.message}")
            false
        }
    }

    /**
     * Изчислява MD5 хеш на даден стринг.
     * @param input Стрингът за хеширане (напр. парола).
     * @return MD5 хешът като 32-символен хексадецимален стринг.
     */
    fun getMd5Hash(input: String): String {
        return try {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            // Преобразуваме масива от байтове в хексадецимален стринг
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "" // Връщаме празен стринг при грешка
        }
    }

    /**
     * Помощен метод за извличане на стойност от прост JSON низ.
     */
    private fun extractJsonValue(json: String, key: String): String {
        return try {
            // Търсим "key":"value" или "key":value
            val searchKey = "\"$key\""
            if (!json.contains(searchKey)) return ""
            
            val afterKey = json.substringAfter(searchKey).trim().removePrefix(":")
            if (afterKey.startsWith("\"")) {
                // Стойността е в кавички
                afterKey.substringAfter("\"").substringBefore("\"")
            } else {
                // Стойността е число или друго (до запетая или край на обекта)
                afterKey.split(Regex("[,}]"))[0].trim()
            }
        } catch (e: Exception) {
            ""
        }
    }
}
