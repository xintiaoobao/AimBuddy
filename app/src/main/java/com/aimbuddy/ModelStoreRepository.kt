package com.aimbuddy

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val STORE_TAG = "ModelStoreRepo"

data class StoreModelDefinition(
    val id: String,
    val title: String,
    val description: String,
    val paramUrl: String,
    val binUrl: String,
    val paramSizeBytes: Long,
    val binSizeBytes: Long,
    val isDownloadable: Boolean
) {
    val totalSizeBytes: Long
        get() = paramSizeBytes + binSizeBytes
}

class ModelStoreRepository(
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val preferredFolder: String = "model"
) {

    fun fetchAvailableModels(): List<StoreModelDefinition> {
        val baseFolder = resolveBaseFolder() ?: return emptyList()
        val rootUrl = buildContentsUrl(baseFolder)
        val rootItems = httpGetJsonArray(rootUrl)
        val models = mutableListOf<StoreModelDefinition>()

        for (i in 0 until rootItems.length()) {
            val item = rootItems.optJSONObject(i) ?: continue
            if (item.optString("type") != "dir") {
                continue
            }

            val dirName = item.optString("name")
            if (dirName.isBlank()) {
                continue
            }

            val modelPath = "$baseFolder/$dirName"
            val model = parseModelFolder(dirName, modelPath)
            if (model != null) {
                models.add(model)
            }
        }

        return models.sortedBy { it.title.lowercase() }
    }

    fun downloadModel(model: StoreModelDefinition, targetDir: File): Pair<File, File> {
        if (!model.isDownloadable) {
            throw IllegalStateException("This model is marked as demo/non-downloadable")
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("Failed to create model directory: ${targetDir.absolutePath}")
        }

        val paramFile = File(targetDir, "${model.id}.param")
        val binFile = File(targetDir, "${model.id}.bin")

        downloadToFile(model.paramUrl, paramFile)
        downloadToFile(model.binUrl, binFile)

        if (paramFile.length() <= 0L || binFile.length() <= 0L) {
            throw IllegalStateException("Downloaded files are empty for model ${model.id}")
        }

        return Pair(paramFile, binFile)
    }

    private fun parseModelFolder(modelId: String, modelPath: String): StoreModelDefinition? {
        return try {
            val folderItems = httpGetJsonArray(buildContentsUrl(modelPath))

            var metadataFileEntry: JSONObject? = null
            var paramFileEntry: JSONObject? = null
            var binFileEntry: JSONObject? = null

            for (j in 0 until folderItems.length()) {
                val fileItem = folderItems.optJSONObject(j) ?: continue
                if (fileItem.optString("type") != "file") {
                    continue
                }

                val name = fileItem.optString("name")
                when {
                    name.equals("model.json", ignoreCase = true) -> metadataFileEntry = fileItem
                    name.endsWith(".param", ignoreCase = true) -> paramFileEntry = fileItem
                    name.endsWith(".bin", ignoreCase = true) -> binFileEntry = fileItem
                }
            }

            val title: String
            val description: String
            val demoOnly: Boolean

            if (metadataFileEntry != null) {
                val metadataDownloadUrl = metadataFileEntry.optString("download_url")
                val metadata = if (metadataDownloadUrl.isNotBlank()) {
                    JSONObject(httpGetString(metadataDownloadUrl))
                } else {
                    JSONObject()
                }

                title = metadata.optString("title", modelId)
                description = metadata.optString("description", "No description provided.")
                demoOnly = metadata.optBoolean("demoOnly", false)
            } else {
                title = modelId
                description = "No description provided."
                demoOnly = false
            }

            val paramUrl = paramFileEntry?.optString("download_url").orEmpty()
            val binUrl = binFileEntry?.optString("download_url").orEmpty()

            if (demoOnly || paramFileEntry == null || binFileEntry == null || paramUrl.isBlank() || binUrl.isBlank()) {
                if (!demoOnly) {
                    Log.w(STORE_TAG, "Model $modelPath is metadata-only or missing binaries; listing as non-downloadable")
                }
                return StoreModelDefinition(
                    id = modelId,
                    title = title,
                    description = description,
                    paramUrl = "",
                    binUrl = "",
                    paramSizeBytes = 0L,
                    binSizeBytes = 0L,
                    isDownloadable = false
                )
            }

            StoreModelDefinition(
                id = modelId,
                title = title,
                description = description,
                paramUrl = paramUrl,
                binUrl = binUrl,
                paramSizeBytes = paramFileEntry?.optLong("size", 0L) ?: 0L,
                binSizeBytes = binFileEntry?.optLong("size", 0L) ?: 0L,
                isDownloadable = true
            )
        } catch (e: Exception) {
            Log.e(STORE_TAG, "Failed parsing store model folder $modelPath: ${e.message}", e)
            null
        }
    }

    private fun resolveBaseFolder(): String? {
        val candidates = listOf(preferredFolder, "models")
        for (candidate in candidates) {
            try {
                httpGetJsonArray(buildContentsUrl(candidate))
                return candidate
            } catch (_: Exception) {
            }
        }
        Log.w(STORE_TAG, "No store folder found. Expected '$preferredFolder/' or 'models/'")
        return null
    }

    private fun buildContentsUrl(path: String): String {
        return "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch"
    }

    private fun httpGetJsonArray(url: String): JSONArray {
        return JSONArray(httpGetString(url))
    }

    private fun httpGetString(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "AimBuddy-ModelStore")

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException("HTTP $responseCode for $url ${errorText ?: ""}".trim())
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(url: String, destination: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "AimBuddy-ModelStore")

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode while downloading $url")
            }

            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
