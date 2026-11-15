package com.isaakhanimann.journal.ui.tabs.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

const val GITHUB_REPO_URL = "https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus"
const val GITHUB_ISSUES_URL = "$GITHUB_REPO_URL/issues"
const val GITHUB_RELEASES_LATEST_URL = "$GITHUB_REPO_URL/releases/latest"
const val GITHUB_API_LATEST_RELEASE_URL =
    "https://api.github.com/repos/yuanyuan2233/PsychonautWiki-Journal-Plus/releases/latest"

private const val UPDATE_CHECK_TIMEOUT_MS = 8_000

data class ReleaseUpdateInfo(
    val tagName: String,
    val releaseName: String,
    val body: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val publishedAt: String?
)

sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data class UpdateAvailable(
        val currentVersion: String,
        val latest: ReleaseUpdateInfo
    ) : UpdateCheckUiState

    data class UpToDate(
        val currentVersion: String,
        val latest: ReleaseUpdateInfo
    ) : UpdateCheckUiState

    data class Error(val reason: String) : UpdateCheckUiState
}

internal object AppUpdateChecker {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    /**
     * Parses semantic version tags/names such as "v13.5.0" or "13.5.0-zh-plus"
     * into numeric components like [13, 5, 0].
     */
    fun parseVersionTuple(raw: String): List<Int> {
        val core = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (core.isEmpty()) return emptyList()

        val parts = core.split('.')
        val parsed = parts.mapNotNull { it.trim().toIntOrNull() }
        return if (parsed.size == parts.size) parsed else emptyList()
    }

    /**
     * Returns true strictly when [remoteTagName] represents a newer numeric version
     * than [currentVersionName].
     */
    fun isRemoteVersionNewer(currentVersionName: String, remoteTagName: String): Boolean {
        val currentTuple = parseVersionTuple(currentVersionName)
        val remoteTuple = parseVersionTuple(remoteTagName)
        if (currentTuple.isEmpty() || remoteTuple.isEmpty()) return false

        val maxLen = maxOf(currentTuple.size, remoteTuple.size)
        for (i in 0 until maxLen) {
            val remotePart = remoteTuple.getOrElse(i) { 0 }
            val currentPart = currentTuple.getOrElse(i) { 0 }
            if (remotePart > currentPart) return true
            if (remotePart < currentPart) return false
        }
        return false
    }

    fun parseLatestReleaseJson(jsonText: String): ReleaseUpdateInfo {
        val root = jsonParser.parseToJsonElement(jsonText) as? JsonObject
            ?: throw IllegalStateException("Unexpected release response format")

        val tagName = root.stringOrNull("tag_name").orEmpty().trim()
        if (tagName.isEmpty()) {
            throw IllegalStateException("Missing tag_name in release payload")
        }

        val releaseName = root.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: tagName
        val body = root.stringOrNull("body").orEmpty().trim()
        val htmlUrl = root.stringOrNull("html_url")
            ?.trim()
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: GITHUB_RELEASES_LATEST_URL
        val publishedAt = root.stringOrNull("published_at")?.trim()?.takeIf { it.isNotEmpty() }

        val apkDownloadUrl = (root["assets"] as? JsonArray)
            ?.asSequence()
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { asset ->
                val name = asset.stringOrNull("name").orEmpty()
                val downloadUrl = asset.stringOrNull("browser_download_url").orEmpty()
                name.endsWith(".apk", ignoreCase = true) &&
                    downloadUrl.startsWith("https://", ignoreCase = true)
            }
            ?.stringOrNull("browser_download_url")
            ?.trim()

        return ReleaseUpdateInfo(
            tagName = tagName,
            releaseName = releaseName,
            body = body,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkDownloadUrl,
            publishedAt = publishedAt
        )
    }

    suspend fun checkLatestRelease(currentVersionName: String): UpdateCheckUiState =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(GITHUB_API_LATEST_RELEASE_URL)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = UPDATE_CHECK_TIMEOUT_MS
                    readTimeout = UPDATE_CHECK_TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty(
                        "User-Agent",
                        "PsychonautWiki-Journal-Plus/$currentVersionName"
                    )
                }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val errorHint = when (code) {
                        404 -> "HTTP 404"
                        403 -> "HTTP 403 (Rate Limit)"
                        else -> "HTTP $code"
                    }
                    return@withContext UpdateCheckUiState.Error(errorHint)
                }

                val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }
                val releaseInfo = parseLatestReleaseJson(payload)
                if (isRemoteVersionNewer(currentVersionName, releaseInfo.tagName)) {
                    UpdateCheckUiState.UpdateAvailable(
                        currentVersion = currentVersionName,
                        latest = releaseInfo
                    )
                } else {
                    UpdateCheckUiState.UpToDate(
                        currentVersion = currentVersionName,
                        latest = releaseInfo
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateCheckUiState.Error(
                    e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: e::class.simpleName
                        ?: "Network error"
                )
            } finally {
                connection?.disconnect()
            }
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
