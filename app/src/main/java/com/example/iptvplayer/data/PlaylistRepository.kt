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
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Persists subscription **metadata** in SharedPreferences (small) and each
 * subscription's channel list in its own file under [channelsDir].
 *
 * Large IPTV sources (10k–100k channels) must never be fully JSON-encoded into
 * a single prefs string or re-parsed for every UI action.
 */
class PlaylistRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val channelsDir: File =
        File(context.filesDir, "subscription_channels").also { it.mkdirs() }

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

    /** Metadata only (no channel bodies). Invalidated on any write. */
    @Volatile
    private var metaCache: List<Subscription>? = null

    /** Last fully-loaded channel list for a subscription id. */
    @Volatile
    private var channelsCache: Pair<String, List<Channel>>? = null

    private val lock = Any()

    // Migration runs lazily on first load (preferably off the main thread via reload).

    // ─── Subscription CRUD ───────────────────────────────────────────

    /**
     * Fast: metadata only. Safe to call when opening the subscription picker
     * or painting chrome (name / count). Does **not** load channel bodies.
     */
    fun loadSubscriptions(): List<Subscription> {
        metaCache?.let { return it }
        synchronized(lock) {
            metaCache?.let { return it }
            migrateIfNeeded()
            val raw = prefs.getString(KEY_SUBSCRIPTIONS_META, null)
                ?: prefs.getString(KEY_SUBSCRIPTIONS, null)
            if (raw.isNullOrBlank()) {
                metaCache = emptyList()
                return emptyList()
            }
            val list = runCatching {
                subscriptionsMetaFromJson(JSONArray(raw))
            }.getOrDefault(emptyList())
            metaCache = list
            return list
        }
    }

    fun getActiveSubscriptionId(): String? {
        val subs = loadSubscriptions()
        if (subs.isEmpty()) return null
        val id = prefs.getString(KEY_ACTIVE_ID, null)
        if (id != null && subs.any { it.id == id }) return id
        return subs.first().id
    }

    /** Metadata of the active subscription (channels empty). */
    fun getActiveSubscription(): Subscription? {
        val id = getActiveSubscriptionId() ?: return null
        return loadSubscriptions().find { it.id == id }
    }

    /**
     * Load channel bodies for [subscriptionId]. Uses memory cache when possible.
     * Prefer calling from a background dispatcher when the list is large.
     */
    fun loadChannels(subscriptionId: String): List<Channel> {
        channelsCache?.let { (id, list) ->
            if (id == subscriptionId) return list
        }
        synchronized(lock) {
            channelsCache?.let { (id, list) ->
                if (id == subscriptionId) return list
            }
            val list = readChannelsFile(subscriptionId)
            channelsCache = subscriptionId to list
            return list
        }
    }

    fun loadActiveChannels(): List<Channel> {
        val id = getActiveSubscriptionId() ?: return emptyList()
        return loadChannels(id)
    }

    /**
     * Active subscription with channels filled. For UI that needs both.
     */
    fun getActiveSubscriptionWithChannels(): Subscription? {
        val meta = getActiveSubscription() ?: return null
        val channels = loadChannels(meta.id)
        return meta.copy(channels = channels, channelCount = channels.size)
    }

    fun setActiveSubscription(id: String): Boolean {
        val exists = loadSubscriptions().any { it.id == id }
        if (!exists) return false
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        // Keep channel cache only if it already matches the new id
        channelsCache?.let { (cachedId, _) ->
            if (cachedId != id) channelsCache = null
        }
        return true
    }

    fun deleteSubscription(id: String) {
        synchronized(lock) {
            val list = loadSubscriptions().toMutableList()
            list.removeAll { it.id == id }
            writeMeta(list)
            deleteChannelsFile(id)
            if (channelsCache?.first == id) channelsCache = null
            val active = prefs.getString(KEY_ACTIVE_ID, null)
            if (active == id || active == null || list.none { it.id == active }) {
                prefs.edit().putString(KEY_ACTIVE_ID, list.firstOrNull()?.id).apply()
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            loadSubscriptions().forEach { deleteChannelsFile(it.id) }
            metaCache = null
            channelsCache = null
            prefs.edit()
                .remove(KEY_SUBSCRIPTIONS_META)
                .remove(KEY_SUBSCRIPTIONS)
                .remove(KEY_ACTIVE_ID)
                .remove(KEY_CHANNELS)
                .remove(KEY_SOURCES)
                .apply()
            channelsDir.listFiles()?.forEach { it.delete() }
        }
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
                    channelCount = channels.size,
                    importedAt = System.currentTimeMillis()
                )
                synchronized(lock) {
                    val list = loadSubscriptions().toMutableList()
                    val idx = list.indexOfFirst { it.id == id }
                    val meta = updated.copy(channels = emptyList())
                    if (idx >= 0) list[idx] = meta else list.add(0, meta)
                    writeMeta(list)
                    writeChannelsFile(id, channels)
                    channelsCache = id to channels
                    prefs.edit().putString(KEY_ACTIVE_ID, updated.id).apply()
                }
                updated
            }
        }

    // ─── Persist helpers ─────────────────────────────────────────────

    private fun upsertSubscription(
        name: String,
        type: SourceType,
        value: String,
        channels: List<Channel>,
        matchExisting: Boolean = true
    ): Subscription {
        synchronized(lock) {
            val list = loadSubscriptions().toMutableList()
            val existingIdx = if (matchExisting) {
                list.indexOfFirst { it.type == type && it.value == value }
            } else {
                -1
            }
            val id = if (existingIdx >= 0) list[existingIdx].id else UUID.randomUUID().toString()
            val oldId = if (existingIdx >= 0) list[existingIdx].id else null
            val sub = Subscription(
                id = id,
                name = name,
                type = type,
                value = value,
                importedAt = System.currentTimeMillis(),
                channelCount = channels.size,
                channels = channels
            )
            if (existingIdx >= 0) {
                list.removeAt(existingIdx)
                if (oldId != null && oldId != id) deleteChannelsFile(oldId)
            }
            list.add(0, sub.copy(channels = emptyList()))
            writeMeta(list)
            writeChannelsFile(id, channels)
            channelsCache = id to channels
            prefs.edit().putString(KEY_ACTIVE_ID, sub.id).apply()
            return sub
        }
    }

    private fun writeMeta(list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("type", s.type.name)
                    .put("value", s.value)
                    .put("importedAt", s.importedAt)
                    .put("channelCount", s.channelCount)
            )
        }
        prefs.edit()
            .putString(KEY_SUBSCRIPTIONS_META, arr.toString())
            // Drop legacy giant blob if present
            .remove(KEY_SUBSCRIPTIONS)
            .apply()
        metaCache = list.map { it.copy(channels = emptyList()) }
    }

    private fun channelsFile(id: String): File =
        File(channelsDir, "$id.json")

    private fun writeChannelsFile(id: String, channels: List<Channel>) {
        val arr = JSONArray()
        // Compact encoding: array of arrays [name, url, group, logo?, tvgId?]
        // Smaller and faster than full objects for 10k–100k rows.
        channels.forEach { c ->
            val row = JSONArray()
                .put(c.name)
                .put(c.url)
                .put(c.group)
            if (c.logo != null || c.tvgId != null) {
                row.put(c.logo ?: "")
                row.put(c.tvgId ?: "")
            }
            arr.put(row)
        }
        channelsFile(id).writeText(arr.toString(), Charsets.UTF_8)
    }

    private fun readChannelsFile(id: String): List<Channel> {
        val file = channelsFile(id)
        if (!file.exists()) {
            // Legacy: channels may still live inside old KEY_SUBSCRIPTIONS blob
            // (already migrated on first run). Empty if missing.
            return emptyList()
        }
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching { channelsFromStoredJson(JSONArray(raw)) }
            .getOrDefault(emptyList())
    }

    private fun deleteChannelsFile(id: String) {
        channelsFile(id).delete()
    }

    /**
     * Supports compact rows `[name,url,group,...]` and legacy `{name,url,...}` objects.
     */
    private fun channelsFromStoredJson(arr: JSONArray): List<Channel> = buildList(arr.length()) {
        for (i in 0 until arr.length()) {
            val item = arr.get(i)
            when (item) {
                is JSONArray -> {
                    if (item.length() < 2) continue
                    add(
                        Channel(
                            name = item.optString(0, "未知频道"),
                            url = item.optString(1, ""),
                            group = item.optString(2, "未分组").ifBlank { "未分组" },
                            logo = item.optString(3).ifBlank { null },
                            tvgId = item.optString(4).ifBlank { null }
                        )
                    )
                }
                is JSONObject -> {
                    add(
                        Channel(
                            name = item.getString("name"),
                            url = item.getString("url"),
                            group = item.optString("group", "未分组"),
                            logo = item.optString("logo").ifBlank { null },
                            tvgId = item.optString("tvgId").ifBlank { null }
                        )
                    )
                }
            }
        }
    }

    private fun channelsFromJson(arr: JSONArray): List<Channel> = channelsFromStoredJson(arr)

    private fun subscriptionsMetaFromJson(arr: JSONArray): List<Subscription> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val typeName = o.getString("type")
            val type = runCatching { SourceType.valueOf(typeName) }.getOrDefault(SourceType.FILE)
            // Legacy blob may still embed channels; count them but do not keep in memory here.
            val embedded = if (o.has("channels")) o.getJSONArray("channels") else null
            val count = when {
                o.has("channelCount") -> o.optInt("channelCount", 0)
                embedded != null -> embedded.length()
                else -> 0
            }
            add(
                Subscription(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.getString("name"),
                    type = type,
                    value = o.getString("value"),
                    importedAt = o.optLong("importedAt", 0L),
                    channelCount = count,
                    channels = emptyList()
                )
            )
        }
    }

    /**
     * Migrate:
     * 1) Flat channel list + source metadata → one subscription
     * 2) Monolithic KEY_SUBSCRIPTIONS (meta+channels) → meta prefs + per-id files
     */
    private fun migrateIfNeeded() {
        // Already on meta format
        if (prefs.contains(KEY_SUBSCRIPTIONS_META)) {
            // Still may need to split leftover monolithic blob (should be rare)
            return
        }

        // Split monolithic multi-subscription blob
        val monolithic = prefs.getString(KEY_SUBSCRIPTIONS, null)
        if (!monolithic.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(monolithic)
                val metaList = mutableListOf<Subscription>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val typeName = o.getString("type")
                    val type = runCatching { SourceType.valueOf(typeName) }
                        .getOrDefault(SourceType.FILE)
                    val channels = if (o.has("channels")) {
                        channelsFromJson(o.getJSONArray("channels"))
                    } else {
                        emptyList()
                    }
                    writeChannelsFile(id, channels)
                    metaList += Subscription(
                        id = id,
                        name = o.getString("name"),
                        type = type,
                        value = o.getString("value"),
                        importedAt = o.optLong("importedAt", 0L),
                        channelCount = channels.size
                    )
                }
                writeMeta(metaList)
                if (prefs.getString(KEY_ACTIVE_ID, null) == null) {
                    prefs.edit().putString(KEY_ACTIVE_ID, metaList.firstOrNull()?.id).apply()
                }
                prefs.edit().remove(KEY_SUBSCRIPTIONS).apply()
            }
            return
        }

        // Legacy single flat channel list
        val rawChannels = prefs.getString(KEY_CHANNELS, null)
        if (rawChannels.isNullOrBlank()) return

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

        val id = UUID.randomUUID().toString()
        val sub = Subscription(
            id = id,
            name = name,
            type = SourceType.FILE,
            value = "migrated",
            channelCount = channels.size
        )
        writeMeta(listOf(sub))
        writeChannelsFile(id, channels)
        prefs.edit()
            .putString(KEY_ACTIVE_ID, id)
            .remove(KEY_CHANNELS)
            .remove(KEY_SOURCES)
            .remove(KEY_SUBSCRIPTIONS)
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
        /** Metadata only (no channel arrays). */
        private const val KEY_SUBSCRIPTIONS_META = "subscriptions_meta"
        /** Legacy monolithic blob (meta + all channels). Migrated away. */
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_ACTIVE_ID = "active_subscription_id"
        // Legacy keys (pre multi-subscription)
        private const val KEY_CHANNELS = "channels"
        private const val KEY_SOURCES = "sources"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
