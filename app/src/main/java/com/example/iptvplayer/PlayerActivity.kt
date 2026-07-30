package com.example.iptvplayer

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
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
import com.example.iptvplayer.data.PlaylistRepository
import com.example.iptvplayer.databinding.ActivityPlayerBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Playback via LibVLC so IPTV streams with MPEG Audio Layer 2 (MP2) have sound.
 * Default Android MediaCodec / ExoPlayer cannot decode MP2 (shows "no audio").
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    private var names: List<String> = emptyList()
    private var urls: List<String> = emptyList()
    private var index: Int = 0
    private var isFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        names = intent.getStringArrayListExtra(EXTRA_PLAYLIST_NAMES) ?: arrayListOf(
            intent.getStringExtra(EXTRA_NAME) ?: "直播"
        )
        urls = intent.getStringArrayListExtra(EXTRA_PLAYLIST_URLS) ?: arrayListOf(
            intent.getStringExtra(EXTRA_URL) ?: ""
        )
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    toggleFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnPrev.setOnClickListener { switchChannel(-1) }
        binding.btnNext.setOnClickListener { switchChannel(1) }
        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }
        binding.btnRetry.setOnClickListener { playCurrent() }

        updateChrome()
        initPlayer()
        playCurrent()
    }

    private fun initPlayer() {
        val options = arrayListOf(
            "--aout=opensles",
            "--audio-time-stretch",
            "--network-caching=1500",
            "--live-caching=1500",
            "--http-reconnect",
            "--no-drop-late-frames",
            "--no-skip-frames",
            // Prefer software audio so MP2 always works even if HW audio path fails
            "--no-omxil-dr"
        )
        libVlc = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVlc).also { mp ->
            mp.attachViews(binding.vlcLayout, null, false, false)
            mp.volume = 100
            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Buffering -> {
                        val pct = event.buffering
                        binding.progress.visibility =
                            if (pct in 0f..99.5f) View.VISIBLE else View.GONE
                    }
                    MediaPlayer.Event.Playing -> {
                        binding.progress.visibility = View.GONE
                        binding.errorPanel.visibility = View.GONE
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        binding.progress.visibility = View.GONE
                        binding.errorPanel.visibility = View.VISIBLE
                        binding.tvError.text = getString(R.string.playback_error)
                    }
                    MediaPlayer.Event.EndReached -> {
                        // Live streams rarely end; ignore for continuous IPTV
                    }
                }
            }
        }
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

        val url = urls[index].trim()
        runCatching {
            player.stop()
            val media = Media(vlc, Uri.parse(url))
            // HW video OK; audio still soft-decoded by VLC for MP2/AC3 etc.
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=1500")
            media.addOption(":live-caching=1500")
            media.addOption(":http-user-agent=${PlaylistRepository.USER_AGENT}")
            media.addOption(":no-audio-time-stretch")
            player.media = media
            media.release()
            player.volume = 100
            player.play()
        }.onFailure { e ->
            binding.progress.visibility = View.GONE
            binding.errorPanel.visibility = View.VISIBLE
            binding.tvError.text = e.message ?: getString(R.string.playback_error)
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

    override fun onStart() {
        super.onStart()
        mediaPlayer?.play()
    }

    override fun onStop() {
        mediaPlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        mediaPlayer?.apply {
            stop()
            detachViews()
            setEventListener(null)
            release()
        }
        mediaPlayer = null
        libVlc?.release()
        libVlc = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
        const val EXTRA_GROUP = "group"
        const val EXTRA_PLAYLIST_URLS = "playlist_urls"
        const val EXTRA_PLAYLIST_NAMES = "playlist_names"
        const val EXTRA_INDEX = "index"
    }
}
