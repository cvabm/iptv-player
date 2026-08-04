package com.example.iptvplayer.data

data class Channel(
    val name: String,
    val url: String,
    val group: String = "未分组",
    val logo: String? = null,
    val tvgId: String? = null
)

/**
 * One imported playlist / subscription.
 *
 * [channels] may be empty when only metadata is loaded (subscription picker, startup summary).
 * [channelCount] is always the authoritative size and is persisted separately from channel bodies.
 */
data class Subscription(
    val id: String,
    val name: String,
    val type: SourceType,
    val value: String, // URL, content uri, stream url, or "paste"
    val importedAt: Long = System.currentTimeMillis(),
    val channelCount: Int = 0,
    val channels: List<Channel> = emptyList()
)

enum class SourceType {
    URL,
    FILE,
    SINGLE_STREAM,
    PASTE
}
