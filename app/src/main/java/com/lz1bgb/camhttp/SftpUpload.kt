package com.lz1bgb.camhttp

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import java.io.File
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.edit

/**
 * @brief Data structure storing info about a local file ready for upload.
 */
data class LocalFileInfo(
    val fullPath: String,     /**< Absolute path on the device */
    val relativePath: String, /**< Relative path from the base download folder */
    val name: String          /**< Filename with extension */
)

/**
 * @brief Possible statuses of an SFTP upload operation.
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

/**
 * Handles uploading files to a remote SFTP server and managing local storage.
 *
 * This class provides methods to scan local directories for files downloaded from the camera
 * and upload them to a configured SFTP server.
 */
class SftpUpload(private val context: Context) {

//    /**
//     * @brief Main public method that executes the upload cycle:
//     * 1. Scans for local files in the "camera" folder.
//     * 2. For each file, uploads it to the remote server and deletes the local copy upon success.
//     */
//    suspend fun processLocalFiles(){
//        val filesToUpload = getLocalFilesToUpload()
//
//        if (filesToUpload.isEmpty()) {
//            FileLogger.logToFile(context, "SftpUpload", "No local files found for upload.")
//            val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
//            prefsStat.edit {
//                putString("SFTP_STATUS", "No files for upload.")
//            }
//            return
//        }
//
//        FileLogger.logToFile(context, "SftpUpload", "Found ${filesToUpload.size} files for upload. Starting process...")
//        filesToUpload.forEach { fileInfo ->
//            val status = uploadAndDeleteFile(fileInfo)
//            Log.i("SftpUpload", "Processing of '${fileInfo.name}' finished with status: $status")
//        }
//    }

    /**
     * Scans the internal app storage for files downloaded from the camera.
     *
     * @return List of [LocalFileInfo] objects.
     */
    fun getLocalFilesToUpload(): List<LocalFileInfo> {
        // Define the start folder "camera" where HttpClient downloads files
        val startDir = File(context.getExternalFilesDir(null), "camera")
        val allFilesList = mutableListOf<LocalFileInfo>()

        if (startDir.exists() && startDir.isDirectory) {
            FileLogger.logToFile(context, "SftpUpload", "Scanning local directory: ${startDir.path}")
            // rootPath is startDir.path so relativePath matches camera structure
            scanDirectoryRecursive(startDir, startDir.path, allFilesList)
        } else {
            FileLogger.logToFile(context, "SftpUpload", "Local 'camera' folder is missing or empty.")
        }

        FileLogger.logToFile(context, "SftpUpload", "Found ${allFilesList.size} local files for upload.")
        return allFilesList
    }

    /**
     * Connects to SFTP, uploads a single file preserving directory structure, and deletes local copy.
     *
     * @param fileInfo Information about the file to upload.
     * @param onProgress Lambda for tracking upload progress.
     * @return [UploadStatus] enum describing the result.
     */
    suspend fun uploadAndDeleteFile(fileInfo: LocalFileInfo, onProgress: ((Long, Long) -> Unit)? = null): UploadStatus {
        return withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("FtpSettings", Context.MODE_PRIVATE)
            val ip = prefs.getString("IP_SERVER", null)
            val user = prefs.getString("USER_SERVER", null)
            val pass = prefs.getString("PASS_SERVER", null)
            val baseRemotePath = prefs.getString("PATH_SERVER", "/") ?: "/"

            if (ip.isNullOrBlank() || user.isNullOrBlank()) {
                FileLogger.logToFile(context, "SftpUpload", "SFTP settings (IP or User) are not configured.")
                val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                prefsStat.edit {
                    putString("SFTP_STATUS", "Settings missing.")
                }
                return@withContext UploadStatus.SETTINGS_NOT_FOUND
            }

            val localFile = File(fileInfo.fullPath)
            if (!localFile.exists()) {
                FileLogger.logToFile(context, "SftpUpload", "Local file does not exist: ${fileInfo.fullPath}")
                val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                prefsStat.edit {
                    putString("SFTP_STATUS", "Local file missing.")
                }
                return@withContext UploadStatus.LOCAL_FILE_NOT_FOUND
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
                FileLogger.logToFile(context, "SftpUpload", "SFTP connection established.")

                // 1. Create remote directory tree if necessary
                val fullRemoteDir = (baseRemotePath + "/" + fileInfo.relativePath).replace("//", "/")
                try {
                    createRemoteDirectories(channelSftp, fullRemoteDir)
                } catch (e: SftpException) {
                    FileLogger.logToFile(context, "SftpUpload", "Failed to create remote directories: ${e.message}")
                    val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                    prefsStat.edit {
                        putString("SFTP_STATUS", "Remote dir error.")
                    }
                    return@withContext UploadStatus.REMOTE_DIR_CREATION_FAILED
                }

                // 2. Upload the file
                FileLogger.logToFile(context, "SftpUpload", "Uploading '${fileInfo.name}' to '$fullRemoteDir'")
                try {
                    val monitor = if (onProgress != null) {
                        object : SftpProgressMonitor {
                            private var count = 0L
                            private var max = 0L
                            override fun init(op: Int, src: String?, dest: String?, max: Long) {
                                this.max = max
                            }
                            override fun count(count: Long): Boolean {
                                this.count += count
                                onProgress(this.count, this.max)
                                return true
                            }
                            override fun end() {}
                        }
                    } else null

                    channelSftp.put(localFile.inputStream(), "$fullRemoteDir/${fileInfo.name}", monitor)
                } catch (e: SftpException) {
                    FileLogger.logToFile(context, "SftpUpload", "Upload failed: ${e.message}")
                    val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                    prefsStat.edit {
                        putString("SFTP_STATUS", "Upload error.")
                    }
                    return@withContext UploadStatus.UPLOAD_FAILED
                }

                // 3. Delete local file
                if (localFile.delete()) {
                    FileLogger.logToFile(context, "SftpUpload", "Local file deleted: ${fileInfo.fullPath}")
                } else {
                    Log.w("SftpUpload", "File uploaded, but local deletion failed.")
                }

                val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                prefsStat.edit {
                    putString("SFTP_STATUS", "Uploaded and deleted: ${fileInfo.fullPath}")
                }

                val currentTotal = prefsStat.getInt("TOTAL_UPLOADS", 0)
                prefsStat.edit {
                    putInt("TOTAL_UPLOADS", currentTotal + 1)
                }

                return@withContext UploadStatus.SUCCESS

            } catch (e: JSchException) {
                FileLogger.logToFile(context, "SftpUpload", "SFTP Connection/Auth error: ${e.message}")
                val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                prefsStat.edit {
                    putString("SFTP_STATUS", "SFTP error.")
                }
                return@withContext UploadStatus.SFTP_CONNECTION_FAILED
            } catch (e: Exception) {
                FileLogger.logToFile(context, "SftpUpload", "Unknown error during upload: ${e.message}")
                val prefsStat = context.getSharedPreferences("FtpStats", Context.MODE_PRIVATE)
                prefsStat.edit {
                    putString("SFTP_STATUS", "Unknown error.")
                }
                return@withContext UploadStatus.UNKNOWN_ERROR
            } finally {
                channelSftp?.disconnect()
                session?.disconnect()
            }
        }
    }

    /**
     * @brief Recursively creates remote directories on the SFTP server.
     * @param channel Active SFTP channel.
     * @param path Full directory path to create.
     */
    private fun createRemoteDirectories(channel: ChannelSftp, path: String) {
        val folders = path.split('/').filter { it.isNotEmpty() }
        var currentPath = if (path.startsWith("/")) "/" else ""

        for (folder in folders) {
            currentPath = if (currentPath.endsWith("/")) "$currentPath$folder" else "$currentPath/$folder"
            try {
                channel.stat(currentPath)
            } catch (e: SftpException) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    FileLogger.logToFile(context, "SftpUpload", "Creating remote directory: $currentPath")
                    channel.mkdir(currentPath)
                } else {
                    FileLogger.logToFile(context, "SftpUpload", "SftpException during mkdir: ${e.message}")
                    throw e
                }
            }
        }
    }

    /**
     * @brief Internal recursive method for directory scanning.
     */
    private fun scanDirectoryRecursive(currentDir: File, rootPath: String, fileList: MutableList<LocalFileInfo>) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                // If directory, recurse
                scanDirectoryRecursive(file, rootPath, fileList)
            } else {
                // If the file, calculate relative path and add to list
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
}
