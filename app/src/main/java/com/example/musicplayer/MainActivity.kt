package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: MusicService? = null
    private var bound = false
    private lateinit var adapter: SongAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    
    private var allSongs: List<Song> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MusicService.MusicBinder).getService()
            bound = true
            service?.onSongChanged = { song, playing -> updateNowPlaying(song, playing) }
            service?.onPlaylistChanged = { songs ->
                adapter = SongAdapter(songs, 
                    onClick = { idx -> service?.playSong(idx) },
                    onFavoriteClick = { pos -> toggleFavorite(pos) },
                    onMoreClick = { pos -> showPlaylistDialog(pos) }
                )
                binding.recyclerView.adapter = adapter
            }
            service?.onPlaybackStateChanged = { playing ->
                binding.btnPlayPause.setIconResource(
                    if (playing) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )
            }
            loadSongs()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false; service = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) loadSongs() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SongAdapter(emptyList(), { }, { }, { })
        binding.recyclerView.adapter = adapter

        setupControls()
        requestPermissionsIfNeeded()

        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        handler.post(updateSeekBarRunnable)
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener { service?.togglePlayPause() }
        binding.btnNext.setOnClickListener { service?.playNext() }
        binding.btnPrevious.setOnClickListener { service?.playPrevious() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                service?.seekTo(sb?.progress ?: 0)
            }
        })
        
        // Долгое нажатие на кнопку Next -> Включить/выключить Shuffle
        binding.btnNext.setOnLongClickListener {
            service?.let {
                it.toggleShuffle()
                Toast.makeText(this, if (it.isShuffle) "Случайный порядок ВКЛ" else "Случайный порядок ВЫКЛ", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun toggleFavorite(position: Int) {
        val song = allSongs[position]
        val isNowFavorite = PlaylistManager.toggleFavorite(this, song.id)
        song.isFavorite = isNowFavorite
        
        // Обновляем список в сервисе, чтобы сердечко перекрасилось
        service?.setPlaylist(allSongs) 
        adapter.notifyItemChanged(position)
    }

    private fun showPlaylistDialog(position: Int) {
        val song = allSongs[position]
        val playlists = PlaylistManager.getPlaylists(this)
        
        val options = mutableListOf<String>()
        options.add("➕ Создать новый плейлист")
        playlists.forEach { options.add(it.name) }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Добавить в плейлист")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreatePlaylistDialog(song.id)
                } else {
                    val plName = playlists[which - 1].name
                    PlaylistManager.addSongToPlaylist(this, plName, song.id)
                    Toast.makeText(this, "Добавлено в $plName", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showCreatePlaylistDialog(songId: Long) {
        val input = TextInputEditText(this)
        input.hint = "Название плейлиста"
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Новый плейлист")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    PlaylistManager.createPlaylist(this, name)
                    PlaylistManager.addSongToPlaylist(this, name, songId)
                    Toast.makeText(this, "Плейлист $name создан", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            service?.let { s ->
                if (!isSeeking) {
                    val pos = s.getPosition()
                    val dur = s.getDuration()
                    if (dur > 0) {
                        binding.seekBar.max = dur
                        binding.seekBar.progress = pos
                        binding.tvPosition.text = formatTime(pos)
                        binding.tvDuration.text = formatTime(dur)
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun updateNowPlaying(song: Song, playing: Boolean) {
        binding.tvCurrentTitle.text = song.title
        binding.tvCurrentArtist.text = song.artist
        binding.btnPlayPause.setIconResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun requestPermissionsIfNeeded() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(perm)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadSongs() {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE // <-- Добавили MIME-тип для фильтрации
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val favorites = PlaylistManager.getFavorites(this)

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val mimeType = cursor.getString(mimeCol) ?: ""
                
                // ФИЛЬТРАЦИЯ: Пропускаем .ogg и другие ненужные форматы
                if (mimeType == "audio/ogg" || mimeType == "application/ogg") {
                    continue // Пропускаем этот трек
                }

                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                songs.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown",
                        duration = cursor.getLong(durCol),
                        uri = uri.toString(),
                        isFavorite = favorites.contains(id)
                    )
                )
            }
        }

        allSongs = songs
        adapter = SongAdapter(songs, 
            onClick = { idx -> service?.playSong(idx) },
            onFavoriteClick = { pos -> toggleFavorite(pos) },
            onMoreClick = { pos -> showPlaylistDialog(pos) }
        )
        binding.recyclerView.adapter = adapter
        service?.setPlaylist(songs)
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateSeekBarRunnable)
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }
}
