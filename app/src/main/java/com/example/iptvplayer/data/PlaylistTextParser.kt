package com.example.iptvplayer.data

/**
 * Auto-detect and parse common IPTV playlist text formats:
 * - Standard M3U / M3U8 (#EXTM3U / #EXTINF)
 * - Hotel / portal "name,url" lists separated by &lt;br&gt; or newlines
 *   (e.g. kaniptv PHP: `CCTV1,http://host/…m3u8?key=…&lt;br&gt;`)
 * - DIYP-style lists with `#genre#分组名`
 */
object PlaylistTextParser {

    private val urlStartRegex = Regex(
        """(?i)(https?://|rtsp://|rtmp://|udp://|rtp://)"""
    )

    fun parse(content: String): List<Channel> {
        if (content.isBlank()) return emptyList()
        val normalized = normalize(content)

        // Prefer M3U when markers present
        if (looksLikeM3u(normalized)) {
            val m3u = M3uParser.parse(normalized)
            if (m3u.isNotEmpty()) return m3u
        }

        val nameUrl = parseNameUrlList(normalized)
        if (nameUrl.isNotEmpty()) return nameUrl

        // Last resort: plain M3U / URL lines
        return M3uParser.parse(normalized)
    }

    /**
     * Suggest a short display name from a subscription URL path
     * (e.g. …/普通酒店.php?ip=… → 普通酒店).
     */
    fun suggestNameFromUrl(url: String): String {
        return runCatching {
            val noQuery = url.substringBefore('?')
            val segment = noQuery.substringAfterLast('/').ifBlank { noQuery }
            val decoded = java.net.URLDecoder.decode(segment, Charsets.UTF_8.name())
            decoded
                .removeSuffix(".php")
                .removeSuffix(".PHP")
                .removeSuffix(".txt")
                .removeSuffix(".m3u")
                .removeSuffix(".m3u8")
                .ifBlank { url.take(48) }
        }.getOrDefault(url.take(48))
    }

    private fun looksLikeM3u(text: String): Boolean {
        val head = text.take(800)
        return head.contains("#EXTM3U", ignoreCase = true) ||
            head.contains("#EXTINF", ignoreCase = true)
    }

    /**
     * Normalize HTML-ish hotel lists into plain lines.
     */
    private fun normalize(raw: String): String {
        var s = raw
            .replace("\uFEFF", "") // BOM
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        // Common separators used by portal PHP lists
        s = s
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("(?i)</div\\s*>"), "\n")
            .replace(Regex("(?i)</li\\s*>"), "\n")
            .replace(Regex("(?i)<hr\\s*/?>"), "\n")

        // Drop remaining tags (keep text)
        s = s.replace(Regex("<[^>]+>"), "")

        // HTML entities occasionally appear
        s = s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#44;", ",")

        return s
    }

    /**
     * Parse `name,url` lines and optional `#genre#Group` section headers.
     */
    private fun parseNameUrlList(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var group = "未分组"
        val seenUrls = HashSet<String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // DIYP genre header
            if (line.startsWith("#genre#", ignoreCase = true)) {
                group = line.substringAfter('#').substringAfter('#').trim()
                    .ifBlank { "未分组" }
                continue
            }

            // Skip other comments / M3U tags if mixed in
            if (line.startsWith("#")) continue

            val channel = parseNameUrlLine(line, group) ?: continue
            if (channel.url in seenUrls) continue
            seenUrls += channel.url
            channels += channel
        }
        return channels
    }

    private fun parseNameUrlLine(line: String, group: String): Channel? {
        val urlMatch = urlStartRegex.find(line) ?: return null
        val urlStart = urlMatch.range.first
        var url = line.substring(urlStart).trim()
        // Trim trailing junk sometimes glued on (quotes, commas)
        url = url.trimEnd(',', ';', '"', '\'', ')', ']', '}', '>', ' ')
        if (url.isBlank() || !looksLikeStreamUrl(url)) return null

        var name = if (urlStart > 0) {
            line.substring(0, urlStart).trim().trimEnd(',', '，', ';', '|', ' ')
        } else {
            ""
        }
        if (name.isBlank()) {
            name = guessNameFromUrl(url)
        }

        return Channel(
            name = name,
            url = url,
            group = group.ifBlank { "未分组" }
        )
    }

    private fun looksLikeStreamUrl(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtmp://") ||
            lower.startsWith("udp://") ||
            lower.startsWith("rtp://")
    }

    private fun guessNameFromUrl(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        return path.ifBlank { "频道" }
    }
}
