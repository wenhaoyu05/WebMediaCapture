package com.webmediacapture.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.snackbar.Snackbar
import com.webmediacapture.R
import java.io.File

class PlayerActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var speedButton: TextView
    private lateinit var player: ExoPlayer
    private lateinit var path: String
    private var restored = false
    private var resumeOnStart = false
    private var speed = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val file = File(path)
        if (!file.exists()) {
            finish()
            return
        }
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.player)
        speedButton = findViewById(R.id.player_speed)
        findViewById<TextView>(R.id.player_title).text =
            intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: file.name
        findViewById<ImageButton>(R.id.player_back).setOnClickListener { finish() }
        speedButton.setOnClickListener { showSpeedMenu() }
        bindSpeed()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY || restored) return
                restored = true
                val saved = positions().getLong(path, 0L)
                val duration = player.duration
                if (saved in 1 until duration - 3_000) player.seekTo(saved)
            }

            override fun onPlayerError(error: PlaybackException) {
                Snackbar.make(playerView, R.string.player_error, Snackbar.LENGTH_LONG).show()
            }
        })
        player.prepare()
        player.playWhenReady = true
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && resumeOnStart) player.play()
    }

    override fun onStop() {
        if (::player.isInitialized) {
            resumeOnStart = player.isPlaying
            savePosition()
            player.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            savePosition()
            if (::playerView.isInitialized) playerView.player = null
            player.release()
        }
        super.onDestroy()
    }

    private fun showSpeedMenu() {
        PopupMenu(this, speedButton).apply {
            SPEEDS.forEachIndexed { index, value ->
                menu.add(0, index, index, getString(R.string.player_speed, label(value)))
            }
            setOnMenuItemClickListener { item ->
                speed = SPEEDS[item.itemId]
                player.setPlaybackSpeed(speed)
                bindSpeed()
                true
            }
            show()
        }
    }

    private fun bindSpeed() {
        speedButton.text = getString(R.string.player_speed, label(speed))
    }

    private fun savePosition() {
        if (!::player.isInitialized) return
        val position = player.currentPosition
        val duration = player.duration
        val prefs = positions().edit()
        if (duration > 0 && position >= duration - 3_000) prefs.remove(path) else prefs.putLong(path, position.coerceAtLeast(0))
        prefs.apply()
    }

    private fun positions() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun label(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        private const val PREFS = "player_positions"
        private val SPEEDS = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)

        fun start(context: Context, path: String, title: String?) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_TITLE, title),
            )
        }
    }
}
