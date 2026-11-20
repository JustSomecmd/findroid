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
     *
     * GitHub doesn't list releases in date order, so the newest tags are found first and then their
     * releases.
     */
    suspend fun checkForUpdate(): Boolean =
        withContext(Dispatchers.IO) {
            checking = true
            try {
                val installed = current
                val tags =
                    get("git/matching-refs/tags/$TAG_PREFIX")
                        ?.let { json.decodeFromString<List<Ref>>(it) }
                        .orEmpty()
                        .map { it.ref.removePrefix("refs/tags/") }
                        .filter {
                            installed != null && compareVersions(versionOf(it), installed) > 0
                        }
                        .sortedWith { a, b -> compareVersions(versionOf(b), versionOf(a)) }
                // The newest release may still be getting its installer, so the one before it
                // will do until then
                val newer =
                    tags.take(TAGS_TO_TRY).firstNotNullOfOrNull { tag ->
                        get("releases/tags/$tag")
                            ?.let { json.decodeFromString<Release>(it) }
                            ?.takeIf { release ->
                                !release.draft && release.assets.any { it.name.endsWith(".msi") }
                            }
                    }
                available = newer
                newer != null
            } finally {
                checking = false
            }
        }

    /** A page of GitHub's API for the repository, or null when it isn't there. */
    private fun get(path: String): String? {
        val request =
            Request.Builder()
                .url("https://api.github.com/repos/$REPOSITORY/$path")
                .header("Accept", "application/vnd.github+json")
                .build()
        return client.newCall(request).execute().use { response ->
            // 409 is a repository with nothing in it yet
            if (response.code == 404 || response.code == 409) return@use null
            check(response.isSuccessful) { "GitHub answered ${response.code}" }
            response.body.string()
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
        /** Such as 1.1.3, from the tag v1.1.3. */
        val version: String
            get() = versionOf(tag)
    }

    @Serializable data class Ref(val ref: String)

    @Serializable
    data class Asset(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    companion object {
        // Winchivist's own repository, apart from the phone app's since 1.1
        private const val REPOSITORY = "JustSomecmd/Winchivist"
        private const val TAG_PREFIX = "v"
        private const val TAGS_TO_TRY = 3

        private fun versionOf(tag: String): String = tag.removePrefix(TAG_PREFIX)

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
