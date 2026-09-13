package com.mantra.cockpit

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * ASKING GITHUB WHETHER THERE IS A NEWER BUILD. Copied from MINIMALIST_STOPWATCH (13.9.2026).
 * It does not install anything: the download URL is handed to Android, whose own installer takes
 * it from there, with its own dialogue and its own warnings.
 */
object UpdateCheck {

    fun check(current: Int, onState: (UpdateState) -> Unit) {
        onState(UpdateState.Checking)
        thread(name = "update-check", isDaemon = true) {
            val connection = try {
                (URL(Updates.LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
            } catch (e: Exception) {
                onState(UpdateState.Failed("no network"))
                return@thread
            }
            try {
                if (connection.responseCode != 200) {
                    onState(UpdateState.Failed("github " + connection.responseCode + if (connection.responseCode == 404) " (a private repository)" else ""))
                    return@thread
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
                val assets = json.optJSONArray("assets")
                val url = (0 until (assets?.length() ?: 0))
                    .map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")
                onState(Updates.compare(current, tag, url?.takeIf { it.isNotBlank() }))
            } catch (e: Exception) {
                onState(UpdateState.Failed("bad answer"))
            } finally {
                connection.disconnect()
            }
        }
    }

    fun download(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
