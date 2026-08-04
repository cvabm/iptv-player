package com.example.iptvplayer

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.example.iptvplayer.data.PlaybackSession
import com.example.iptvplayer.data.PlaylistRepository
import com.example.iptvplayer.databinding.ActivityPlayerBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LibVLC playback. stop()/release() can block for seconds on stuck IPTV sources
 * (e.g. slow HLS) — never run those on the main thread or back/finish freezes.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    private var names: List<String> = emptyList()
    private var urls: List<String> = emptyList()
    private var index: Int = 0
    private var isFullscreen = false

    /** Serializes all blocking VLC native calls off the UI thread. */
    private val vlcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vlc-io").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playGeneration = AtomicInteger(0)
    private var bufferingWatchdog: Runnable? = null
    private var releaseSubmitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (intent.getBooleanExtra(EXTRA_USE_SESSION, false) && PlaybackSession.channels.isNotEmpty()) {
            // Prefer in-process queue (large lists must not go through Intent).
            // Avoid mapping 10k+ Channel → two String lists on the main thread.
            val queue = PlaybackSession.channels
            names = object : AbstractList<String>() {
                override val size: Int get() = queue.size
                override fun get(index: Int): String = queue[index].name
            }
            urls = object : AbstractList<String>() {
                override val size: Int get() = queue.size
                override fun get(index: Int): String = queue[index].url
            }
            index = intent.getIntExtra(EXTRA_INDEX, PlaybackSession.index)
                .coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        } else {
            names = intent.getStringArrayListExtra(EXTRA_PLAYLIST_NAMES) ?: arrayListOf(
                intent.getStringExtra(EXTRA_NAME) ?: "直播"
            )
            urls = intent.getStringArrayListExtra(EXTRA_PLAYLIST_URLS) ?: arrayListOf(
                intent.getStringExtra(EXTRA_URL) ?: ""
            )
            index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    toggleFullscreen()
                } else {
                    // Tear down VLC off-main first so finish() never stalls on stop()
                    leavePlayer()
                }
            }
        })

        binding.toolbar.setNavigationOnClickListener { leavePlayer() }
        binding.btnPrev.setOnClickListener { switchChannel(-1) }
        binding.btnNext.setOnClickListener { switchChannel(1) }
        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }
        binding.btnRetry.setOnClickListener { playCurrent() }

        updateChrome()
        initPlayer()
        playCurrent()
    }

    /**
     * Cancel playback work, free native player without blocking UI, then finish.
     */
    private fun leavePlayer() {
        cancelBufferingWatchdog()
        playGeneration.incrementAndGet() // invalidate in-flight play
        releasePlayerAsync()
        if (!isFinishing) {
            finish()
        }
    }

    private fun initPlayer() {
        val options = arrayListOf(
            "--aout=opensles",
            "--audio-time-stretch",
            // Shorter cache: snappier start / abandon on dead streams
            "--network-caching=1000",
            "--live-caching=1000",
            "--file-caching=1000",
            "--http-reconnect",
            "--clock-jitter=0",
            "--clock-synchro=0",
            // Avoid long stalls on some HW decode paths with live TS
            "--no-omxil-dr"
        )
        libVlc = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVlc).also { mp ->
            mp.attachViews(binding.vlcLayout, null, false, false)
            mp.volume = 100
            mp.setEventListener { event ->
                // Events may arrive on VLC thread — hop to main for views
                mainHandler.post {
                    if (isDestroyed || mediaPlayer == null) return@post
                    when (event.type) {
                        MediaPlayer.Event.Buffering -> {
                            val pct = event.buffering
                            binding.progress.visibility =
                                if (pct in 0f..99.5f) View.VISIBLE else View.GONE
                            if (pct in 0f..99.5f) {
                                scheduleBufferingWatchdog()
                            } else {
                                cancelBufferingWatchdog()
                            }
                        }
                        MediaPlayer.Event.Playing -> {
                            cancelBufferingWatchdog()
                            binding.progress.visibility = View.GONE
                            binding.errorPanel.visibility = View.GONE
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            cancelBufferingWatchdog()
                            binding.progress.visibility = View.GONE
                            binding.errorPanel.visibility = View.VISIBLE
                            binding.tvError.text = getString(R.string.playback_error)
                        }
                        MediaPlayer.Event.EndReached -> {
                            // Live streams rarely end
                        }
                    }
                }
            }
        }
    }

    /** If stuck buffering too long, surface an error so user can leave cleanly. */
    private fun scheduleBufferingWatchdog() {
        cancelBufferingWatchdog()
        val gen = playGeneration.get()
        val task = Runnable {
            if (isDestroyed || gen != playGeneration.get()) return@Runnable
            if (binding.progress.visibility == View.VISIBLE) {
                binding.errorPanel.visibility = View.VISIBLE
                binding.tvError.text = getString(R.string.playback_timeout)
                binding.progress.visibility = View.GONE
                // Stop native I/O off main so UI stays responsive
                val mp = mediaPlayer
                vlcExecutor.execute {
                    try {
                        mp?.stop()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        bufferingWatchdog = task
        mainHandler.postDelayed(task, BUFFERING_TIMEOUT_MS)
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdog?.let { mainHandler.removeCallbacks(it) }
        bufferingWatchdog = null
    }

    private fun playCurrent() {
        if (urls.isEmpty() || urls[index].isBlank()) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        val vlc = libVlc ?: return
        val player = mediaPlayer ?: return

        binding.errorPanel.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        updateChrome()
        cancelBufferingWatchdog()

        val url = urls[index].trim()
        val gen = playGeneration.incrementAndGet()

        // stop + open media can block on dead/slow sources — never on main
        vlcExecutor.execute {
            if (gen != playGeneration.get()) return@execute
            try {
                try {
                    player.stop()
                } catch (_: Exception) {
                }
                if (gen != playGeneration.get()) return@execute

                val media = Media(vlc, Uri.parse(url))
                media.setHWDecoderEnabled(true, false)
                media.addOption(":network-caching=1000")
                media.addOption(":live-caching=1000")
                media.addOption(":file-caching=1000")
                media.addOption(":http-user-agent=${PlaylistRepository.USER_AGENT}")
                media.addOption(":no-audio-time-stretch")
                // Live: don't wait forever for perfect timestamps
                media.addOption(":clock-jitter=0")
                media.addOption(":clock-synchro=0")

                player.media = media
                media.release()
                player.volume = 100
                if (gen != playGeneration.get()) return@execute
                player.play()

                mainHandler.post {
                    if (!isDestroyed && gen == playGeneration.get()) {
                        scheduleBufferingWatchdog()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (isDestroyed || gen != playGeneration.get()) return@post
                    binding.progress.visibility = View.GONE
                    binding.errorPanel.visibility = View.VISIBLE
                    binding.tvError.text = e.message ?: getString(R.string.playback_error)
                }
            }
        }
    }

    private fun switchChannel(delta: Int) {
        if (urls.size <= 1) {
            Toast.makeText(this, R.string.no_more_channel, Toast.LENGTH_SHORT).show()
            return
        }
        index = (index + delta + urls.size) % urls.size
        playCurrent()
    }

    private fun updateChrome() {
        val name = names.getOrNull(index) ?: getString(R.string.app_name)
        binding.toolbar.title = name
        binding.tvChannelInfo.text = getString(
            R.string.channel_position,
            index + 1,
            urls.size
        )
        binding.btnPrev.isEnabled = urls.size > 1
        binding.btnNext.isEnabled = urls.size > 1
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.bottomBar.visibility = View.GONE
            binding.toolbar.visibility = View.GONE
            hideSystemBars()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            binding.bottomBar.visibility = View.VISIBLE
            binding.toolbar.visibility = View.VISIBLE
            showSystemBars()
        }
    }

    private fun applySystemBarInsets() {
        val actionBarSize = obtainActionBarSize()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (!isFullscreen) {
                binding.toolbar.updatePadding(top = bars.top)
                binding.toolbar.updateLayoutParams {
                    height = actionBarSize + bars.top
                }
                binding.bottomBar.updatePadding(bottom = bars.bottom)
            } else {
                binding.toolbar.updatePadding(top = 0)
                binding.toolbar.updateLayoutParams { height = actionBarSize }
                binding.bottomBar.updatePadding(bottom = 0)
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun obtainActionBarSize(): Int {
        val tv = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        } else {
            (56 * resources.displayMetrics.density).toInt()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).let { c ->
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val actionBarSize = obtainActionBarSize()
        binding.toolbar.updatePadding(top = 0)
        binding.toolbar.updateLayoutParams { height = actionBarSize }
        binding.bottomBar.updatePadding(bottom = 0)
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(binding.root)
    }

    /**
     * Detach views on main immediately; stop/release native resources on worker thread.
     * Safe to call multiple times.
     */
    private fun releasePlayerAsync() {
        if (releaseSubmitted) return
        releaseSubmitted = true
        cancelBufferingWatchdog()

        val mp = mediaPlayer
        val vlc = libVlc
        mediaPlayer = null
        libVlc = null

        // Views must be detached while Activity still has a window when possible
        try {
            mp?.setEventListener(null)
        } catch (_: Exception) {
        }
        try {
            mp?.detachViews()
        } catch (_: Exception) {
        }

        vlcExecutor.execute {
            try {
                mp?.stop()
            } catch (_: Exception) {
            }
            try {
                mp?.release()
            } catch (_: Exception) {
            }
            try {
                vlc?.release()
            } catch (_: Exception) {
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val mp = mediaPlayer ?: return
        if (releaseSubmitted) return
        vlcExecutor.execute {
            try {
                mp.play()
            } catch (_: Exception) {
            }
        }
    }

    override fun onStop() {
        // pause() can also block on bad streams — never on main
        val mp = mediaPlayer
        if (mp != null && !releaseSubmitted && !isFinishing) {
            vlcExecutor.execute {
                try {
                    mp.pause()
                } catch (_: Exception) {
                }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        cancelBufferingWatchdog()
        playGeneration.incrementAndGet()
        releasePlayerAsync()
        // Don't block Activity teardown waiting for VLC; abandon executor if stuck
        vlcExecutor.shutdown()
        try {
            if (!vlcExecutor.awaitTermination(400, TimeUnit.MILLISECONDS)) {
                vlcExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            vlcExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
        const val EXTRA_GROUP = "group"
        const val EXTRA_PLAYLIST_URLS = "playlist_urls"
        const val EXTRA_PLAYLIST_NAMES = "playlist_names"
        const val EXTRA_INDEX = "index"
        /** When true, read play queue from [PlaybackSession] instead of Intent extras. */
        const val EXTRA_USE_SESSION = "use_session"

        /** Give up UI wait if still buffering this long (native stop still async). */
        private const val BUFFERING_TIMEOUT_MS = 15_000L
    }
}
