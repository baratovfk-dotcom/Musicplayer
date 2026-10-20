package com.example.musicplayer

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

class MusicService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val binder = MusicBinder()
    private var currentSong: Song? = null
    private var playlist: List<Song> = emptyList()
    private var currentIndex = -1

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private lateinit var mediaSession: MediaSessionCompat

    var isPlaying = false
        private set

    var onSongChanged: ((Song, Boolean) -> Unit)? = null
    var onPlaylistChanged: ((List<Song>) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initMediaSession()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> playPrevious()
            ACTION_NEXT -> playNext()
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    fun setPlaylist(songs: List<Song>) {
        playlist = songs
        onPlaylistChanged?.invoke(songs)
    }

    fun playSong(index: Int) {
        if (index !in playlist.indices) return
        if (!requestAudioFocus()) return

        currentIndex = index
        currentSong = playlist[index]

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(currentSong!!.uri)
            prepare()
            start()
            setOnCompletionListener { playNext() }
        }

        isPlaying = true
        updateMediaSession()
        updateNotification()
        onSongChanged?.invoke(currentSong!!, true)
        onPlaybackStateChanged?.invoke(true)
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) pausePlayback() else resumePlayback()
    }

    fun pausePlayback() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
        isPlaying = false
        updateMediaSession()
        updateNotification()
        currentSong?.let { onSongChanged?.invoke(it, false) }
        onPlaybackStateChanged?.invoke(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(legacyFocusListener)
        }
    }

    fun resumePlayback() {
        val mp = mediaPlayer ?: return
        if (!requestAudioFocus()) return
        if (!mp.isPlaying) mp.start()
        isPlaying = true
        updateMediaSession()
        updateNotification()
        currentSong?.let { onSongChanged?.invoke(it, true) }
        onPlaybackStateChanged?.invoke(true)
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playSong(currentIndex)
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        val mp = mediaPlayer
        if (mp != null && mp.currentPosition > 3000) {
            mp.seekTo(0)
            updateMediaSession()
            return
        }
        currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        playSong(currentIndex)
    }

    fun getCurrentSong(): Song? = currentSong
    fun getPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun seekTo(pos: Int) {
        mediaPlayer?.seekTo(pos)
        updateMediaSession()
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = isPlaying
                if (isPlaying) pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.2f, 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resumePlayback()
                }
            }
        }
    }

    private val legacyFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        focusChangeListener.onAudioFocusChange(change)
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
            }
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                legacyFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(legacyFocusListener)
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayerSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = playNext()
                override fun onSkipToPrevious() = playPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() {
                    pausePlayback()
                    stopSelf()
                }
            })
            isActive = true
        }

        mediaSession.setSessionActivity(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }

    private fun updateMediaSession() {
        val song = currentSong ?: return

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                .build()
        )

        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, getPosition().toLong(), 1f)
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление воспроизведением музыки"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val song = currentSong ?: return

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MusicService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MusicService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MusicService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIcon: Bitmap = BitmapFactory.decodeResource(
            resources, android.R.drawable.ic_media_play
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(largeIcon)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Prev", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                "Toggle", toggleIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "music_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_PREV = "ACTION_PREV"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_TOGGLE = "ACTION_TOGGLE"
        const val ACTION_STOP = "ACTION_STOP"
    }
}
