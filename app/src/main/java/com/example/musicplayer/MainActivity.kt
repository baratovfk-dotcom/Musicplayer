// Замените метод updateNowPlaying на этот:

private fun updateNowPlaying(song: Song, playing: Boolean) {
    binding.tvCurrentTitle.text = song.title
    binding.tvCurrentArtist.text = song.artist
    binding.btnPlayPause.setIconResource(
        if (playing) android.R.drawable.ic_media_pause
        else android.R.drawable.ic_media_play
    )
}
