package com.example.iptvplayer.data

data class Channel(
    val name: String,
    val url: String,
    val group: String = "未分组",
    val logo: String? = null,
    val tvgId: String? = null
)

/**
 * One imported playlist / subscription. Channels belong only to this subscription.
 */
data class Subscription(
    val id: String,
    val name: String,
    val type: SourceType,
    val value: String, // URL, content uri, stream url, or "paste"
    val importedAt: Long = System.currentTimeMillis(),
    val channels: List<Channel> = emptyList()
) {
    val channelCount: Int get() = channels.size
}

enum class SourceType {
    URL,
    FILE,
    SINGLE_STREAM,
    PASTE
}
