package com.example.camhttp

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.File
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.JSchException
import kotlin.collections.filter
import kotlin.collections.forEach
import kotlin.io.inputStream
import kotlin.text.endsWith
import kotlin.text.isNotEmpty
import kotlin.text.isNullOrBlank
import kotlin.text.removePrefix
import kotlin.text.replace
import kotlin.text.split
import kotlin.text.startsWith
import kotlin.text.trimStart

/**
 * Структура, съхраняваща информация за локален файл, готов за качване.
 * @param fullPath Пълният път до файла на устройството.
 * @param relativePath Пътят до файла, относителен спрямо началната папка за сканиране.
 * @param name Името на файла с разширението.
 */
data class LocalFileInfo(
    val fullPath: String,
    val relativePath: String,

    val name: String
)

/**
 * Дефинира възможните статуси при качване на файл.
 */
enum class UploadStatus {
    SUCCESS,
    SETTINGS_NOT_FOUND,
    LOCAL_FILE_NOT_FOUND,
    SFTP_CONNECTION_FAILED,
    REMOTE_DIR_CREATION_FAILED,
    UPLOAD_FAILED,
    UNKNOWN_ERROR
}


class SftpUpload(private val context: Context) {


    /**
     * Основен публичен метод, който изпълнява целия цикъл на качване:
     * 1. Сканира за локални файлове.
     * 2. За всеки намерен файл, извиква функцията за качване и изтриване.
     * @return Списък със статусите от всяка операция по качване.
     */
    suspend fun processLocalFiles(){
        val filesToUpload = getLocalFilesToUpload()


        if (filesToUpload.isEmpty()) {
            FileLogger.logToFile(context, "SftpUpload", "Няма локални файлове за качване.")
            val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
            prefsStat.edit().putString("SFTP_STATUS", "Няма локални файлове за качване.").apply()
            return
        }

        FileLogger.logToFile(context, "SftpUpload", "Намерени са ${filesToUpload.size} файла за качване. Започвам обработка...")
        filesToUpload.forEach { fileInfo ->
            val status = uploadAndDeleteFile(fileInfo)
            Log.i("SftpUpload", "Обработката на файл '${fileInfo.name}' завърши със статус: $status")
        }
    }

    /**
     * Сканира локалната папка за изтегляния и връща списък с информация за всички намерени файлове.
     * @return Списък от тип List<LocalFileInfo>.
     */
    fun getLocalFilesToUpload(): List<LocalFileInfo> {
        // Дефинираме началната папка "camera", където HttpClient сваля файловете
        val startDir = File(context.getExternalFilesDir(null), "camera")
        val allFilesList = mutableListOf<LocalFileInfo>()

        if (startDir.exists() && startDir.isDirectory) {
            FileLogger.logToFile(context, "SftpUpload", "Започва сканиране на локална папка: ${startDir.path}")
            // rootPath е самият startDir.path, за да може relativePath да съответства на структурата от камерата
            scanDirectoryRecursive(startDir, startDir.path, allFilesList)
        } else {
            FileLogger.logToFile(context, "SftpUpload", "Локалната папка 'camera' не съществува или е празна.")
        }

        FileLogger.logToFile(context, "SftpUpload", "Намерени са ${allFilesList.size} локални файла за качване.")
        return allFilesList
    }

    /**
     * Свързва се със SFTP, качва един файл в правилната под-папка (като я създава, ако е нужно),
     * изтрива локалното копие при успех и връща детайлен статус.
     *
     * @param fileInfo Информация за локалния файл, който трябва да се качи.
     * @return UploadStatus enum, описващ резултата.
     */
    suspend fun uploadAndDeleteFile(fileInfo: LocalFileInfo): UploadStatus {
        val prefs = context.getSharedPreferences("FtpSettings", Context.MODE_PRIVATE)
        val ip = prefs.getString("IP_SERVER", null)
        val user = prefs.getString("USER_SERVER", null)
        val pass = prefs.getString("PASS_SERVER", null)
        val baseRemotePath = prefs.getString("PATH_SERVER", "/") ?: "/"

        if (ip.isNullOrBlank() || user.isNullOrBlank()) {
            Log.e("SftpUpload", "IP адрес или потребител за SFTP сървъра не са зададени.")
            val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
            prefsStat.edit().putString("SFTP_STATUS", "IP адрес или потребител за SFTP сървъра не са зададени.").apply()
            return UploadStatus.SETTINGS_NOT_FOUND
        }

        val localFile = File(fileInfo.fullPath)
        if (!localFile.exists()) {
            Log.e("SftpUpload", "Локалният файл не съществува: ${fileInfo.fullPath}")
            val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
            prefsStat.edit().putString("SFTP_STATUS", "Локалният файл не съществува: ${fileInfo.fullPath}").apply()
            return UploadStatus.LOCAL_FILE_NOT_FOUND
        }

        var session: Session? = null
        var channelSftp: ChannelSftp? = null

        try {
            val jsch = JSch()
            session = jsch.getSession(user, ip, 22)
            session.setPassword(pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect()

            channelSftp = session.openChannel("sftp") as ChannelSftp
            channelSftp.connect()
            FileLogger.logToFile(context, "SftpUpload", "Успешна връзка с SFTP сървъра.")

            // 1. Създаваме отдалечените папки, ако не съществуват
            val fullRemoteDir = (baseRemotePath + "/" + fileInfo.relativePath).replace("//", "/")
            try {
                createRemoteDirectories(channelSftp, fullRemoteDir)
            } catch (e: SftpException) {
                Log.e("SftpUpload", "Грешка при създаване на отдалечени папки: ${e.message}")
                val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                prefsStat.edit().putString("SFTP_STATUS", "Грешка при създаване на отдалечени папки: ${e.message}").apply()
                return UploadStatus.REMOTE_DIR_CREATION_FAILED
            }

            // 2. Качваме файла
            FileLogger.logToFile(context, "SftpUpload", "Качвам '${fileInfo.name}' в '$fullRemoteDir'")
            try {
                channelSftp.put(localFile.inputStream(), "$fullRemoteDir/${fileInfo.name}")
            } catch (e: SftpException) {
                Log.e("SftpUpload", "Грешка при качване на файла: ${e.message}")
                val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                prefsStat.edit().putString("SFTP_STATUS", "Грешка при качване на файла: ${e.message}").apply()
                return UploadStatus.UPLOAD_FAILED
            }

            // 3. Изтриваме локалния файл
            if (localFile.delete()) {
                FileLogger.logToFile(context, "SftpUpload", "Локалният файл е изтрит: ${fileInfo.fullPath}")
            } else {
                Log.w("SftpUpload", "Файлът е качен, но локалното копие не можа да бъде изтрито.")
            }

            val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
            prefsStat.edit().putString("SFTP_STATUS", "Локалният файл е качен и изтрит: ${fileInfo.fullPath}").apply()

            val currentTotal = prefsStat.getInt("TOTAL_UPLOADS", 0)
            val newTotal = currentTotal + 1
            prefsStat.edit().putInt("TOTAL_UPLOADS", newTotal).apply()

            return UploadStatus.SUCCESS

        } catch (e: JSchException) {
            Log.e("SftpUpload", "Грешка при свързване/автентикация със SFTP: ${e.message}")
            val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
            prefsStat.edit().putString("SFTP_STATUS", "Грешка при свързване/автентикация със SFTP: ${e.message}").apply()
            return UploadStatus.SFTP_CONNECTION_FAILED
        } catch (e: Exception) {
            Log.e("SftpUpload", "Неизвестна грешка при качване на файла ${fileInfo.name}: ${e.message}")
            val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
            prefsStat.edit().putString("SFTP_STATUS", "Неизвестна грешка при качване на файла ${fileInfo.name}: ${e.message}").apply()
            return UploadStatus.UNKNOWN_ERROR
        } finally {
            channelSftp?.disconnect()
            session?.disconnect()
        }
    }

    /**
     * Проверява и създава рекурсивно отдалечени папки през SFTP.
     */
    private fun createRemoteDirectories(channel: ChannelSftp, path: String) {
        val folders = path.split('/').filter { it.isNotEmpty() }
        var currentPath = ""
        // Ако пътят започва с '/', започваме от коренната директория
        if (path.startsWith("/")) {
            currentPath = "/"
        }

        for (folder in folders) {
            // Проверяваме дали пътят е абсолютен или относителен и конструираме правилно
            if (currentPath.endsWith("/")) {
                currentPath += folder
            } else {
                currentPath += "/$folder"
            }

            try {
                // Проверяваме дали папката съществува
                channel.stat(currentPath)
            } catch (e: SftpException) {
                // Ако хвърли грешка, значит не съществува - създаваме я
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    FileLogger.logToFile(context, "SftpUpload", "Създавам отдалечена папка: $currentPath")
                    channel.mkdir(currentPath)
                } else {
                    // Хвърляме отново грешката, ако е различна
                    throw e
                }
            }
        }
    }


    /**
     * Помощен рекурсивен метод за обхождане на локалните папки.
     * @param currentDir Текущата папка за сканиране.
     * @param rootPath Пътят на началната папка (за изчисляване на относителния път).
     * @param fileList Списъкът, в който се натрупват резултатите.
     */
    private fun scanDirectoryRecursive(currentDir: File, rootPath: String, fileList: MutableList<LocalFileInfo>) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                // Ако е папка, извикваме функцията отново за нея
                scanDirectoryRecursive(file, rootPath, fileList)
            } else {
                // Ако е файл, изчисляваме относителния път и създаваме обект
                val relativePath = file.parentFile?.absolutePath?.removePrefix(rootPath)?.trimStart('/') ?: ""
                val fileInfo = LocalFileInfo(
                    fullPath = file.absolutePath,
                    relativePath = relativePath,
                    name = file.name
                )
                fileList.add(fileInfo)
            }
        }
    }







//    suspend fun uploadAndDeleteFiles() {
//
//        val fileList = getLocalFilesToUpload()
//
//        if(fileList.isEmpty())
//        {
//            return
//        }
//
//        fileList.forEach { fileInfo ->
//            Log.d("FileService", " - Път: ${fileInfo.relativePath}, Име: ${fileInfo.name}, ")
//            val fileWritedDone = uploadAndDeleteFile(fileInfo)
//            Log.d("FileService", " - WriteDone: ${fileWritedDone}")
//        }
//
//
//    }
}
