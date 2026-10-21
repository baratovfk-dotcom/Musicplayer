package com.example.musicplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.databinding.ItemSongBinding

class SongAdapter(
    private var songs: List<Song>,
    private val onClick: (Int) -> Unit,
    private val onFavoriteClick: (Int) -> Unit,
    private val onMoreClick: (Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.VH>() {

    private var selectedIndex = -1

    fun setSelected(index: Int) {
        val old = selectedIndex
        selectedIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    inner class VH(val b: ItemSongBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.b.tvTitle.text = song.title
        holder.b.tvArtist.text = song.artist
        holder.b.tvDuration.text = formatDuration(song.duration)
        holder.b.root.isActivated = position == selectedIndex
        holder.b.root.setOnClickListener { onClick(position) }
        
        holder.b.btnFavorite.setImageResource(
            if (song.isFavorite) android.R.drawable.btn_star_big_on 
            else android.R.drawable.btn_star_big_off
        )
        holder.b.btnFavorite.setOnClickListener { onFavoriteClick(position) }
        holder.b.btnMore.setOnClickListener { onMoreClick(position) }
    }

    override fun getItemCount() = songs.size

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        return "%d:%02d".format(sec / 60, sec % 60)
    }
}
