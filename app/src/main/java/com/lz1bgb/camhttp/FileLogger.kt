package com.lz1bgb.camhttp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {
    private const val TAG = "FileLogger"
    private const val FILE_NAME = "app_logs.txt"

    fun logToFile(context: Context, tag: String, message: String) {
        // 1. Стандартен лог в Logcat
        Log.d(tag, message)

        try {
            // 2. Път до /Android/data/com.lz1bgb.camhttp/files
            val logFile = File(context.getExternalFilesDir(null), FILE_NAME)
            
            // Форматиране на времето
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logEntry = "$timestamp [$tag]: $message\n"

            // 3. Записване във файла (append = true)
            FileOutputStream(logFile, true).use { output ->
                output.write(logEntry.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Грешка при запис във файл", e)
        }
    }
}
