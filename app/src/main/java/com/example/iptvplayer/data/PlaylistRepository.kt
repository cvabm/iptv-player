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
import java.util.UUID
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

    init {
        migrateIfNeeded()
    }

    // ─── Subscription CRUD ───────────────────────────────────────────

    fun loadSubscriptions(): List<Subscription> {
        migrateIfNeeded()
        val raw = prefs.getString(KEY_SUBSCRIPTIONS, null) ?: return emptyList()
        return runCatching { subscriptionsFromJson(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun getActiveSubscriptionId(): String? {
        migrateIfNeeded()
        val id = prefs.getString(KEY_ACTIVE_ID, null)
        if (id != null && loadSubscriptions().any { it.id == id }) return id
        return loadSubscriptions().firstOrNull()?.id
    }

    fun getActiveSubscription(): Subscription? {
        val id = getActiveSubscriptionId() ?: return null
        return loadSubscriptions().find { it.id == id }
    }

    fun loadActiveChannels(): List<Channel> =
        getActiveSubscription()?.channels.orEmpty()

    fun setActiveSubscription(id: String): Boolean {
        val exists = loadSubscriptions().any { it.id == id }
        if (!exists) return false
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        return true
    }

    fun deleteSubscription(id: String) {
        val list = loadSubscriptions().toMutableList()
        list.removeAll { it.id == id }
        saveSubscriptions(list)
        val active = prefs.getString(KEY_ACTIVE_ID, null)
        if (active == id || active == null || list.none { it.id == active }) {
            prefs.edit().putString(KEY_ACTIVE_ID, list.firstOrNull()?.id).apply()
        }
    }

    fun clearAll() {
        prefs.edit()
            .remove(KEY_SUBSCRIPTIONS)
            .remove(KEY_ACTIVE_ID)
            .remove(KEY_CHANNELS)
            .remove(KEY_SOURCES)
            .apply()
    }

    fun clearActiveSubscription() {
        val id = getActiveSubscriptionId() ?: return
        deleteSubscription(id)
    }

    // ─── Import ──────────────────────────────────────────────────────

    suspend fun importFromUrl(url: String, displayName: String? = null): Result<Subscription> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = url.trim()
                val body = downloadTextWithRetry(trimmed)
                val channels = PlaylistTextParser.parse(body)
                if (channels.isEmpty()) {
                    error("未解析到任何频道。支持 M3U/#EXTM3U，或酒店订阅「名称,地址」列表（含 <br> 分隔）")
                }
                upsertSubscription(
                    name = displayName
                        ?: PlaylistTextParser.suggestNameFromUrl(trimmed).take(60),
                    type = SourceType.URL,
                    value = trimmed,
                    channels = channels
                )
            }
        }

    suspend fun importFromUri(uri: Uri, displayName: String? = null): Result<Subscription> =
        withContext(Dispatchers.IO) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                } ?: error("无法读取文件")
                val channels = PlaylistTextParser.parse(content)
                if (channels.isEmpty()) {
                    error("未解析到任何频道。支持 M3U，或「名称,地址」列表")
                }
                val name = displayName
                    ?: uri.lastPathSegment?.let {
                        runCatching {
                            java.net.URLDecoder.decode(it, Charsets.UTF_8.name())
                        }.getOrDefault(it)
                    }
                    ?: "本地文件"
                upsertSubscription(
                    name = name,
                    type = SourceType.FILE,
                    value = uri.toString(),
                    channels = channels
                )
            }
        }

    suspend fun importSingleStream(
        name: String,
        url: String,
        group: String = "单路流"
    ): Result<Subscription> = withContext(Dispatchers.IO) {
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
            upsertSubscription(
                name = channel.name,
                type = SourceType.SINGLE_STREAM,
                value = channel.url,
                channels = listOf(channel)
            )
        }
    }

    suspend fun importRawContent(
        content: String,
        displayName: String = "粘贴导入"
    ): Result<Subscription> = withContext(Dispatchers.IO) {
        runCatching {
            val channels = PlaylistTextParser.parse(content)
            if (channels.isEmpty()) {
                error("未解析到任何频道。支持 M3U，或「名称,地址」/<br> 列表")
            }
            // Each paste is a new subscription (value is unique)
            upsertSubscription(
                name = displayName,
                type = SourceType.PASTE,
                value = "paste:${System.currentTimeMillis()}",
                channels = channels,
                matchExisting = false
            )
        }
    }

    /**
     * Re-download a URL subscription and replace its channel list.
     */
    suspend fun refreshSubscription(id: String): Result<Subscription> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sub = loadSubscriptions().find { it.id == id }
                    ?: error("订阅不存在")
                if (sub.type != SourceType.URL) {
                    error("仅 URL 订阅支持刷新")
                }
                val body = downloadTextWithRetry(sub.value)
                val channels = PlaylistTextParser.parse(body)
                if (channels.isEmpty()) error("未解析到任何频道")
                val updated = sub.copy(
                    channels = channels,
                    importedAt = System.currentTimeMillis()
                )
                val list = loadSubscriptions().toMutableList()
                val idx = list.indexOfFirst { it.id == id }
                if (idx >= 0) list[idx] = updated else list.add(0, updated)
                saveSubscriptions(list)
                prefs.edit().putString(KEY_ACTIVE_ID, updated.id).apply()
                updated
            }
        }

    // ─── Persist helpers ─────────────────────────────────────────────

    /**
     * @param matchExisting when true, same [value] replaces the previous subscription;
     * otherwise always creates a new one.
     */
    private fun upsertSubscription(
        name: String,
        type: SourceType,
        value: String,
        channels: List<Channel>,
        matchExisting: Boolean = true
    ): Subscription {
        val list = loadSubscriptions().toMutableList()
        val existingIdx = if (matchExisting) {
            list.indexOfFirst { it.type == type && it.value == value }
        } else {
            -1
        }
        val sub = if (existingIdx >= 0) {
            list[existingIdx].copy(
                name = name,
                channels = channels,
                importedAt = System.currentTimeMillis()
            )
        } else {
            Subscription(
                id = UUID.randomUUID().toString(),
                name = name,
                type = type,
                value = value,
                channels = channels
            )
        }
        if (existingIdx >= 0) {
            list.removeAt(existingIdx)
        }
        list.add(0, sub)
        saveSubscriptions(list)
        prefs.edit().putString(KEY_ACTIVE_ID, sub.id).apply()
        return sub
    }

    private fun saveSubscriptions(list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { s ->
            val chArr = JSONArray()
            s.channels.forEach { c ->
                chArr.put(channelToJson(c))
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("type", s.type.name)
                    .put("value", s.value)
                    .put("importedAt", s.importedAt)
                    .put("channels", chArr)
            )
        }
        prefs.edit().putString(KEY_SUBSCRIPTIONS, arr.toString()).apply()
    }

    private fun channelToJson(c: Channel): JSONObject =
        JSONObject()
            .put("name", c.name)
            .put("url", c.url)
            .put("group", c.group)
            .put("logo", c.logo)
            .put("tvgId", c.tvgId)

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

    private fun subscriptionsFromJson(arr: JSONArray): List<Subscription> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val typeName = o.getString("type")
            val type = runCatching { SourceType.valueOf(typeName) }.getOrDefault(SourceType.FILE)
            val channels = if (o.has("channels")) {
                channelsFromJson(o.getJSONArray("channels"))
            } else {
                emptyList()
            }
            add(
                Subscription(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.getString("name"),
                    type = type,
                    value = o.getString("value"),
                    importedAt = o.optLong("importedAt", 0L),
                    channels = channels
                )
            )
        }
    }

    /**
     * Migrate flat channel list + source metadata into per-subscription storage.
     * Old model merged all imports; we keep channels as one legacy subscription.
     */
    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_SUBSCRIPTIONS)) return
        val rawChannels = prefs.getString(KEY_CHANNELS, null)
        if (rawChannels.isNullOrBlank()) {
            // nothing to migrate; still mark clean storage
            return
        }
        val channels = runCatching { channelsFromJson(JSONArray(rawChannels)) }
            .getOrDefault(emptyList())
        if (channels.isEmpty()) {
            prefs.edit().remove(KEY_CHANNELS).remove(KEY_SOURCES).apply()
            return
        }

        val sourcesRaw = prefs.getString(KEY_SOURCES, null)
        val firstSourceName = runCatching {
            if (sourcesRaw.isNullOrBlank()) null
            else {
                val arr = JSONArray(sourcesRaw)
                if (arr.length() == 0) null
                else arr.getJSONObject(0).optString("name").ifBlank { null }
            }
        }.getOrNull()

        val name = when {
            !firstSourceName.isNullOrBlank() && sourcesRaw != null &&
                runCatching { JSONArray(sourcesRaw).length() }.getOrDefault(0) == 1 -> firstSourceName
            else -> "历史合并订阅"
        }

        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            name = name,
            type = SourceType.FILE,
            value = "migrated",
            channels = channels
        )
        saveSubscriptions(listOf(sub))
        prefs.edit()
            .putString(KEY_ACTIVE_ID, sub.id)
            .remove(KEY_CHANNELS)
            .remove(KEY_SOURCES)
            .apply()
    }

    // ─── Network ─────────────────────────────────────────────────────

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

    companion object {
        private const val PREFS = "iptv_player"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_ACTIVE_ID = "active_subscription_id"
        // Legacy keys (pre multi-subscription)
        private const val KEY_CHANNELS = "channels"
        private const val KEY_SOURCES = "sources"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
