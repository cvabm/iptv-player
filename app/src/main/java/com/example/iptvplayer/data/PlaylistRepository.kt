package com.example.iptvplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class PlaylistRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1, okhttp3.Protocol.HTTP_2))
        .build()

    fun loadChannels(): List<Channel> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return emptyList()
        return runCatching { channelsFromJson(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun saveChannels(channels: List<Channel>) {
        val arr = JSONArray()
        channels.forEach { c ->
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("url", c.url)
                    .put("group", c.group)
                    .put("logo", c.logo)
                    .put("tvgId", c.tvgId)
            )
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    fun loadSources(): List<PlaylistSource> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        PlaylistSource(
                            name = o.getString("name"),
                            type = SourceType.valueOf(o.getString("type")),
                            value = o.getString("value"),
                            importedAt = o.optLong("importedAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveSources(sources: List<PlaylistSource>) {
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("type", s.type.name)
                    .put("value", s.value)
                    .put("importedAt", s.importedAt)
            )
        }
        prefs.edit().putString(KEY_SOURCES, arr.toString()).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY_CHANNELS).remove(KEY_SOURCES).apply()
    }

    suspend fun importFromUrl(url: String, displayName: String? = null): Result<List<Channel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = url.trim()
                val body = downloadTextWithRetry(trimmed)
                val channels = M3uParser.parse(body)
                if (channels.isEmpty()) error("未解析到任何频道，请确认是 M3U 播放列表")
                val source = PlaylistSource(
                    name = displayName ?: trimmed.take(60),
                    type = SourceType.URL,
                    value = trimmed
                )
                mergeAndPersist(channels, source)
                channels
            }
        }

    /**
     * Download playlist text with browser-like headers and retries for 429/503
     * (common on Cloudflare-fronted IPTV hosts).
     */
    private fun downloadTextWithRetry(url: String, maxAttempts: Int = 4): String {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val request = buildBrowserRequest(url)
                http.newCall(request).execute().use { resp ->
                    val code = resp.code
                    val bodyText = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> {
                            if (bodyText.isBlank()) error("响应为空")
                            return bodyText
                        }
                        code == 429 || code == 502 || code == 503 || code == 504 -> {
                            lastError = IllegalStateException(httpErrorMessage(code, resp.message, bodyText))
                            // exponential backoff: 1s, 2s, 4s
                            if (attempt < maxAttempts - 1) {
                                Thread.sleep((1000L shl attempt).coerceAtMost(8000L))
                            }
                        }
                        else -> error(httpErrorMessage(code, resp.message, bodyText))
                    }
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    Thread.sleep((1000L shl attempt).coerceAtMost(8000L))
                }
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    private fun buildBrowserRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Connection", "keep-alive")
            // Some CDNs check Referer / Origin loosely
            .header("Referer", url.substringBeforeLast('/') + "/")
            .build()
    }

    private fun httpErrorMessage(code: Int, message: String, body: String): String {
        val snippet = body.replace(Regex("\\s+"), " ").take(120)
        return when (code) {
            403 -> "HTTP 403 被拒绝：源站或 CDN 拦截了请求（可换网络，或用浏览器下载 m3u 后本地导入）"
            404 -> "HTTP 404 地址不存在，请检查链接是否正确"
            429 -> "HTTP 429 请求过于频繁，请稍后再试"
            502, 503, 504 ->
                "HTTP $code 服务暂时不可用：源站过载或 CDN 限流。\n" +
                    "建议：1) 稍后重试 2) 换 Wi‑Fi/流量 3) 用浏览器打开该链接，另存为 .m3u 后「本地文件」导入" +
                    if (snippet.isNotBlank()) "\n详情: $snippet" else ""
            else -> "HTTP $code: $message" + if (snippet.isNotBlank()) "\n$snippet" else ""
        }
    }

    suspend fun importFromUri(uri: Uri, displayName: String? = null): Result<List<Channel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                } ?: error("无法读取文件")
                val channels = M3uParser.parse(content)
                if (channels.isEmpty()) error("未解析到任何频道，请确认是 M3U 播放列表")
                val name = displayName
                    ?: uri.lastPathSegment
                    ?: "本地文件"
                val source = PlaylistSource(
                    name = name,
                    type = SourceType.FILE,
                    value = uri.toString()
                )
                mergeAndPersist(channels, source)
                channels
            }
        }

    suspend fun importSingleStream(
        name: String,
        url: String,
        group: String = "单路流"
    ): Result<List<Channel>> = withContext(Dispatchers.IO) {
        runCatching {
            val channel = Channel(
                name = name.ifBlank { "直播流" },
                url = url.trim(),
                group = group.ifBlank { "单路流" }
            )
            if (!channel.url.startsWith("http", ignoreCase = true) &&
                !channel.url.startsWith("rtsp", ignoreCase = true) &&
                !channel.url.startsWith("rtmp", ignoreCase = true)
            ) {
                error("请输入有效的流地址（http/https/rtsp/rtmp）")
            }
            val source = PlaylistSource(
                name = channel.name,
                type = SourceType.SINGLE_STREAM,
                value = channel.url
            )
            mergeAndPersist(listOf(channel), source)
            listOf(channel)
        }
    }

    suspend fun importRawContent(
        content: String,
        displayName: String = "粘贴导入"
    ): Result<List<Channel>> = withContext(Dispatchers.IO) {
        runCatching {
            val channels = M3uParser.parse(content)
            if (channels.isEmpty()) error("未解析到任何频道")
            val source = PlaylistSource(
                name = displayName,
                type = SourceType.FILE,
                value = "paste"
            )
            mergeAndPersist(channels, source)
            channels
        }
    }

    private fun mergeAndPersist(newChannels: List<Channel>, source: PlaylistSource) {
        val existing = loadChannels().toMutableList()
        val existingKeys = existing.map { it.url }.toHashSet()
        newChannels.forEach { c ->
            if (c.url !in existingKeys) {
                existing += c
                existingKeys += c.url
            }
        }
        saveChannels(existing)
        val sources = loadSources().toMutableList()
        // replace same value source if present
        sources.removeAll { it.value == source.value }
        sources.add(0, source)
        saveSources(sources)
    }

    private fun channelsFromJson(arr: JSONArray): List<Channel> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(
                Channel(
                    name = o.getString("name"),
                    url = o.getString("url"),
                    group = o.optString("group", "未分组"),
                    logo = o.optString("logo").ifBlank { null },
                    tvgId = o.optString("tvgId").ifBlank { null }
                )
            )
        }
    }

    companion object {
        private const val PREFS = "iptv_player"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_SOURCES = "sources"
        // Desktop Chrome UA — some IPTV CDNs treat mobile UA more aggressively
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
