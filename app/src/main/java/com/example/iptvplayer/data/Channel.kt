package com.example.iptvplayer.data

data class Channel(
    val name: String,
    val url: String,
    val group: String = "未分组",
    val logo: String? = null,
    val tvgId: String? = null
)

data class PlaylistSource(
    val name: String,
    val type: SourceType,
    val value: String, // URL or absolute path or raw content marker
    val importedAt: Long = System.currentTimeMillis()
)

enum class SourceType {
    URL,
    FILE,
    SINGLE_STREAM
}
