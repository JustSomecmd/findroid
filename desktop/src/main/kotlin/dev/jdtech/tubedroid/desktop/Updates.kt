package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks on GitHub for a newer Winchivist and installs it: the installer is downloaded, started, and
 * the app closes so it can be replaced. Settings stay as they are.
 */
class Updates {
    /** A newer version, once one is found. */
    var available by mutableStateOf<Release?>(null)
        private set

    /** How far the installer's download is, from 0 to 1, while it runs. */
    var progress by mutableStateOf<Float?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** This copy's version, which the installer sets; none when run from Gradle. */
    val current: String? = System.getProperty("jpackage.app-version")

    /**
     * Looks for a newer release. Returns whether there is one; throws when GitHub can't be read.
     */
    suspend fun checkForUpdate(): Boolean =
        withContext(Dispatchers.IO) {
            checking = true
            try {
                val request =
                    Request.Builder()
                        .url("https://api.github.com/repos/$REPOSITORY/releases?per_page=30")
                        .header("Accept", "application/vnd.github+json")
                        .build()
                val body =
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "GitHub answered ${response.code}" }
                        response.body.string()
                    }
                val newest =
                    json
                        .decodeFromString<List<Release>>(body)
                        .filter { it.tag.startsWith(TAG_PREFIX) && !it.draft }
                        .filter { release -> release.assets.any { it.name.endsWith(".msi") } }
                        .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                val newer = newest?.takeIf { release ->
                    val installed = current ?: return@takeIf false
                    compareVersions(release.version, installed) > 0
                }
                available = newer
                newer != null
            } finally {
                checking = false
            }
        }

    /**
     * Downloads the newer installer and starts it, then closes Winchivist so the installer can
     * replace it.
     */
    suspend fun install() {
        val release = available ?: return
        val asset = release.assets.first { it.name.endsWith(".msi") }
        val file =
            withContext(Dispatchers.IO) {
                val target = File(System.getProperty("java.io.tmpdir"), asset.name)
                progress = 0f
                val request = Request.Builder().url(asset.downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "GitHub answered ${response.code}" }
                    val total = response.body.contentLength().takeIf { it > 0 } ?: asset.size
                    response.body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
                target
            }
        ProcessBuilder("msiexec", "/i", file.absolutePath).start()
        exitProcess(0)
    }

    @Serializable
    data class Release(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val draft: Boolean = false,
        @SerialName("html_url") val page: String? = null,
        val assets: List<Asset> = emptyList(),
    ) {
        /** Such as 1.0.12, from the tag desktop-1.0.12. */
        val version: String
            get() = tag.removePrefix(TAG_PREFIX)
    }

    @Serializable
    data class Asset(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    companion object {
        private const val REPOSITORY = "JustSomecmd/findroid"
        private const val TAG_PREFIX = "desktop-"

        private fun versionParts(version: String): List<Int> =
            version.split('.').map { it.toIntOrNull() ?: 0 }

        /** Orders versions such as 1.0.9 and 1.0.12 by their numbers. */
        private fun compareVersions(a: String, b: String): Int {
            val left = versionParts(a)
            val right = versionParts(b)
            for (i in 0 until maxOf(left.size, right.size)) {
                val difference = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
                if (difference != 0) return difference
            }
            return 0
        }
    }
}
