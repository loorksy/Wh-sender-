package cloud.lork.mursal.data

import cloud.lork.mursal.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches optional update info from this app's own public release feed
 * (configured by BuildConfig.UPDATE_URL, pointing at the Wh-sender- repo).
 *
 * No license server is ever contacted, and updates are advisory only:
 * `force` is always treated as false so an update can never block the app.
 */
class UpdateClient(
    private val feedUrl: String = BuildConfig.UPDATE_URL,
) {
    fun fetchUpdateInfo(): UpdateInfo? {
        if (feedUrl.isBlank()) return null
        return try {
            val url = URL(feedUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.readText()
                    .orEmpty()
            } finally {
                conn.disconnect()
            }
            if (code !in 200..299 || body.isBlank()) return null
            val json = JSONObject(body)
            val changelog = buildList {
                val arr = json.optJSONArray("changelog")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val line = arr.optString(i).trim()
                        if (line.isNotEmpty()) add(line)
                    }
                }
            }
            UpdateInfo(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName"),
                // Forced updates are disabled in this build regardless of the feed.
                force = false,
                apkUrl = json.optString("apkUrl"),
                title = json.optString("title").ifBlank { "يتوفر تحديث جديد" },
                changelog = changelog,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun isNewer(info: UpdateInfo): Boolean =
        info.versionCode > BuildConfig.VERSION_CODE && info.apkUrl.isNotBlank()
}
