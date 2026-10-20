package com.example.musicplayer

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: String
)
