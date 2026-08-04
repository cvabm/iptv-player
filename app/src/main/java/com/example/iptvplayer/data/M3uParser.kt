package com.example.iptvplayer.data

/**
 * Lightweight M3U / M3U8 playlist parser for IPTV (#EXTINF attributes).
 */
object M3uParser {

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(content: String): List<Channel> {
        // Single-pass over lines; avoid content.lines() which materializes the full list.
        val channels = ArrayList<Channel>(4096)
        var pending: ExtInf? = null
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pending = parseExtInf(line)
                continue
            }
            if (line.startsWith("#")) continue
            if (pending != null) {
                val meta = pending
                pending = null
                channels += Channel(
                    name = meta.name.ifBlank { "频道 ${channels.size + 1}" },
                    url = line,
                    group = meta.group.ifBlank { "未分组" },
                    logo = meta.logo,
                    tvgId = meta.tvgId
                )
            } else if (looksLikeUrl(line)) {
                channels += Channel(
                    name = "频道 ${channels.size + 1}",
                    url = line,
                    group = "未分组"
                )
            }
        }
        return channels
    }

    private data class ExtInf(
        val name: String,
        val group: String,
        val logo: String?,
        val tvgId: String?
    )

    private fun parseExtInf(line: String): ExtInf {
        // #EXTINF:-1 tvg-id="x" tvg-logo="..." group-title="Sports",Channel Name
        val comma = line.lastIndexOf(',')
        val name = if (comma >= 0) line.substring(comma + 1).trim() else "未知频道"
        val attrPart = if (comma >= 0) line.substring(0, comma) else line

        var group = "未分组"
        var logo: String? = null
        var tvgId: String? = null

        attrRegex.findAll(attrPart).forEach { m ->
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2]
            when (key) {
                "group-title" -> group = value.ifBlank { "未分组" }
                "tvg-logo", "logo" -> logo = value.ifBlank { null }
                "tvg-id" -> tvgId = value.ifBlank { null }
            }
        }
        return ExtInf(name, group, logo, tvgId)
    }

    private fun looksLikeUrl(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtmp://") ||
            lower.startsWith("udp://") ||
            lower.startsWith("rtp://")
    }
}
