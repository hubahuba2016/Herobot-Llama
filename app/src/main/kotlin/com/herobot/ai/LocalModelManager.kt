package com.herobot.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

enum class LocalAccelerator(val label: String) {
    CPU("CPU"),
    GPU("GPU")
}

sealed interface LocalModelStatus {
    data object NotDownloaded : LocalModelStatus
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : LocalModelStatus

    data object Importing : LocalModelStatus
    data class Ready(
        val filePath: String,
        val fileSizeBytes: Long
    ) : LocalModelStatus

    data class Error(val message: String) : LocalModelStatus
}

/**
 * Manages downloading, importing, verifying, and storing the on-device Gemma 3 1B (.litertlm) model.
 * Model files are stored privately inside filesDir/models/ (never bundled inside the APK).
 */
class LocalModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }
    private val modelFile = File(modelsDir, DEFAULT_FILE_NAME)
    private val tempFile = File(modelsDir, "$DEFAULT_FILE_NAME.part")

    private var activeJob: Job? = null

    private val _status = MutableStateFlow(computeInitialStatus())
    val status: StateFlow<LocalModelStatus> = _status.asStateFlow()

    private fun computeInitialStatus(): LocalModelStatus {
        return if (modelFile.exists() && modelFile.length() >= MIN_VALID_MODEL_BYTES) {
            LocalModelStatus.Ready(
                filePath = modelFile.absolutePath,
                fileSizeBytes = modelFile.length()
            )
        } else {
            LocalModelStatus.NotDownloaded
        }
    }

    fun getReadyModelPath(): String? {
        return if (modelFile.exists() && modelFile.length() >= MIN_VALID_MODEL_BYTES) {
            modelFile.absolutePath
        } else {
            null
        }
    }

    fun getSavedDownloadUrl(): String =
        prefs.getString(KEY_DOWNLOAD_URL, DEFAULT_DOWNLOAD_URL)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_DOWNLOAD_URL

    fun saveDownloadUrl(url: String) {
        val cleaned = url.trim().ifEmpty { DEFAULT_DOWNLOAD_URL }
        prefs.edit().putString(KEY_DOWNLOAD_URL, cleaned).apply()
    }

    fun getSavedHfToken(): String =
        prefs.getString(KEY_HF_TOKEN, "").orEmpty()

    fun saveHfToken(token: String) {
        prefs.edit().putString(KEY_HF_TOKEN, token.trim()).apply()
    }

    fun getSavedAccelerator(): LocalAccelerator {
        val raw = prefs.getString(KEY_ACCELERATOR, LocalAccelerator.CPU.name)
        return LocalAccelerator.entries.firstOrNull { it.name == raw } ?: LocalAccelerator.CPU
    }

    fun saveAccelerator(accelerator: LocalAccelerator) {
        prefs.edit().putString(KEY_ACCELERATOR, accelerator.name).apply()
    }

    fun startDownload(downloadUrl: String, hfToken: String?) {
        val resolvedUrl = downloadUrl.trim().ifEmpty { DEFAULT_DOWNLOAD_URL }
        val cleanedToken = hfToken?.trim()?.takeIf { it.isNotEmpty() }
        saveDownloadUrl(resolvedUrl)
        saveHfToken(cleanedToken.orEmpty())

        activeJob?.cancel()
        activeJob = scope.launch {
            _status.value = LocalModelStatus.Downloading(
                downloadedBytes = 0L,
                totalBytes = EXPECTED_SIZE_BYTES
            )
            var connection: HttpURLConnection? = null
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                connection = openConnectionWithRedirects(resolvedUrl, cleanedToken)
                val code = connection.responseCode
                if (code !in 200..299) {
                    val message = when (code) {
                        401, 403 ->
                            "Hugging Face requires accepting the Gemma 3 1B license at huggingface.co/litert-community/Gemma3-1B-IT and providing a Read Token (hf_...), or import a local .litertlm file."
                        404 -> "Model file not found at $resolvedUrl (HTTP 404)."
                        else -> "Download failed (HTTP $code)."
                    }
                    _status.value = LocalModelStatus.Error(message)
                    return@launch
                }

                val reportedLength = connection.contentLengthLong
                val totalBytes = if (reportedLength > 0L) reportedLength else EXPECTED_SIZE_BYTES

                var downloaded = 0L
                var lastUiUpdateMs = 0L
                val buffer = ByteArray(64 * 1024)

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateMs >= 200L) {
                                lastUiUpdateMs = now
                                _status.value = LocalModelStatus.Downloading(
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes
                                )
                            }
                        }
                    }
                }

                if (!isActive) {
                    tempFile.delete()
                    _status.value = computeInitialStatus()
                    return@launch
                }

                if (downloaded < MIN_VALID_MODEL_BYTES) {
                    tempFile.delete()
                    _status.value = LocalModelStatus.Error(
                        "Downloaded file is too small ($downloaded bytes). Check the URL and token."
                    )
                    return@launch
                }

                if (reportedLength > 0L && downloaded != reportedLength) {
                    tempFile.delete()
                    _status.value = LocalModelStatus.Error(
                        "Incomplete download ($downloaded / $reportedLength bytes). Please retry."
                    )
                    return@launch
                }

                if (modelFile.exists()) {
                    modelFile.delete()
                }
                if (!tempFile.renameTo(modelFile)) {
                    tempFile.copyTo(modelFile, overwrite = true)
                    tempFile.delete()
                }

                _status.value = LocalModelStatus.Ready(
                    filePath = modelFile.absolutePath,
                    fileSizeBytes = modelFile.length()
                )
            } catch (e: CancellationException) {
                tempFile.delete()
                _status.value = computeInitialStatus()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download Gemma 3 1B model", e)
                tempFile.delete()
                _status.value = LocalModelStatus.Error(
                    e.localizedMessage ?: "Failed to download model."
                )
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun importFromUri(uri: Uri) {
        activeJob?.cancel()
        activeJob = scope.launch {
            _status.value = LocalModelStatus.Importing
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open selected file.")

                var copied = 0L
                val buffer = ByteArray(64 * 1024)
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copied += read
                        }
                    }
                }

                if (copied < MIN_VALID_MODEL_BYTES) {
                    tempFile.delete()
                    _status.value = LocalModelStatus.Error(
                        "Selected file is too small ($copied bytes) to be a Gemma 3 1B .litertlm model."
                    )
                    return@launch
                }

                if (modelFile.exists()) {
                    modelFile.delete()
                }
                if (!tempFile.renameTo(modelFile)) {
                    tempFile.copyTo(modelFile, overwrite = true)
                    tempFile.delete()
                }

                _status.value = LocalModelStatus.Ready(
                    filePath = modelFile.absolutePath,
                    fileSizeBytes = modelFile.length()
                )
            } catch (e: CancellationException) {
                tempFile.delete()
                _status.value = computeInitialStatus()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import local .litertlm model", e)
                tempFile.delete()
                _status.value = LocalModelStatus.Error(
                    e.localizedMessage ?: "Failed to import model file."
                )
            }
        }
    }

    fun cancelDownload() {
        activeJob?.cancel()
        activeJob = null
        tempFile.delete()
        _status.value = computeInitialStatus()
    }

    suspend fun deleteModel() {
        activeJob?.cancel()
        activeJob = null
        withContext(Dispatchers.IO) {
            tempFile.delete()
            modelFile.delete()
        }
        _status.value = LocalModelStatus.NotDownloaded
    }

    private fun openConnectionWithRedirects(
        startUrl: String,
        hfToken: String?
    ): HttpURLConnection {
        var currentUrl = startUrl
        repeat(MAX_REDIRECTS) {
            val url: URL = URI.create(currentUrl).toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "HeroBot-Android/1.0")
                // Only attach Hugging Face token to huggingface.co hosts (avoid leaking to signed CDN URLs)
                if (hfToken != null && url.host.endsWith("huggingface.co", ignoreCase = true)) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect missing Location header (HTTP $code).")
                }
                currentUrl = URI.create(currentUrl).resolve(location).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many HTTP redirects while downloading model.")
    }

    companion object {
        private const val TAG = "LocalModelManager"
        private const val PREFS_NAME = "herobot_local_model_prefs"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_ACCELERATOR = "accelerator"
        private const val MAX_REDIRECTS = 8
        private const val MIN_VALID_MODEL_BYTES = 1_000_000L

        const val MODEL_ID = "gemma3-1b-it"
        const val MODEL_DISPLAY_NAME = "Gemma 3 1B (On-Device)"
        const val DEFAULT_FILE_NAME = "gemma3-1b-it-int4.litertlm"
        const val EXPECTED_SIZE_BYTES = 584_417_280L
        const val LICENSE_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT"
        const val DEFAULT_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"
    }
}
