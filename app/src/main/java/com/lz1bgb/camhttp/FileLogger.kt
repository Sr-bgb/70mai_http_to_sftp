package com.lz1bgb.camhttp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * @brief Utility singleton for simultaneous logging to Logcat and a local text file.
 * 
 * Logs are stored in the application's private files directory:
 * /Android/data/com.lz1bgb.camhttp/files/app_logs.txt
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val FILE_NAME = "app_logs.txt"

    /**
     * @brief Logs a message to both Logcat (Debug) and the persistent log file.
     * @param context Application context.
     * @param tag Logging tag.
     * @param message Message content.
     */
    fun logToFile(context: Context, tag: String, message: String) {
        // 1. Standard Logcat output
        Log.d(tag, message)

        try {
            // 2. Resolve persistent file path
            val logFile = File(context.getExternalFilesDir(null), FILE_NAME)
            
            // Format timestamp and entry string
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logEntry = "$timestamp [$tag]: $message\n"

            // 3. Append to file
            FileOutputStream(logFile, true).use { output ->
                output.write(logEntry.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to log file", e)
        }
    }
}
