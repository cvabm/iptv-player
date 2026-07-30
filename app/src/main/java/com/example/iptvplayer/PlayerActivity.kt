package com.example.iptvplayer

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.iptvplayer.data.PlaylistRepository
import com.example.iptvplayer.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

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
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PlaylistRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                binding.playerView.player = exo
                binding.playerView.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        binding.progress.visibility =
                            if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        if (playbackState == Player.STATE_READY) {
                            binding.errorPanel.visibility = View.GONE
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        binding.progress.visibility = View.GONE
                        binding.errorPanel.visibility = View.VISIBLE
                        binding.tvError.text = error.message
                            ?: getString(R.string.playback_error)
                    }
                })
                exo.playWhenReady = true
            }
    }

    private fun playCurrent() {
        if (urls.isEmpty() || urls[index].isBlank()) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        binding.errorPanel.visibility = View.GONE
        updateChrome()
        val url = urls[index]
        // DefaultMediaSourceFactory auto-detects HLS / progressive / DASH
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.play()
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
                // Title row sits fully below the status bar clock area
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
        player?.play()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        binding.playerView.player = null
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
