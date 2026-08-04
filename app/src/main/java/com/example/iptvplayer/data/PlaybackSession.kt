package com.example.iptvplayer.data

/**
 * Holds the current play queue in-process so we never put tens of thousands of
 * URLs into an Intent (TransactionTooLargeException + huge main-thread cost).
 */
object PlaybackSession {
    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    @Volatile
    var index: Int = 0
        private set

    fun set(list: List<Channel>, startIndex: Int) {
        channels = list
        index = startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    fun clear() {
        channels = emptyList()
        index = 0
    }
}
