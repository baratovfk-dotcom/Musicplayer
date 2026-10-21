package com.example.musicplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PlaylistManager {
    private const val PREF_NAME = "music_player_prefs"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_PLAYLISTS = "playlists"

    fun getFavorites(context: Context): MutableSet<Long> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        val array = JSONArray(json)
        val set = mutableSetOf<Long>()
        for (i in 0 until array.length()) {
            set.add(array.getLong(i))
        }
        return set
    }

    fun toggleFavorite(context: Context, songId: Long): Boolean {
        val favs = getFavorites(context)
        val isAdded = if (favs.contains(songId)) {
            favs.remove(songId)
            false
        } else {
            favs.add(songId)
            true
        }
        saveFavorites(context, favs)
        return isAdded
    }

    private fun saveFavorites(context: Context, favs: Set<Long>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        favs.forEach { array.put(it) }
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    data class Playlist(val name: String, val songIds: MutableSet<Long>)

    fun getPlaylists(context: Context): List<Playlist> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PLAYLISTS, "[]") ?: "[]"
        val array = JSONArray(json)
        val playlists = mutableListOf<Playlist>()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            val idsArray = obj.getJSONArray("ids")
            val ids = mutableSetOf<Long>()
            for (j in 0 until idsArray.length()) {
                ids.add(idsArray.getLong(j))
            }
            playlists.add(Playlist(name, ids))
        }
        return playlists
    }

    fun createPlaylist(context: Context, name: String) {
        val playlists = getPlaylists(context).toMutableList()
        if (playlists.any { it.name == name }) return
        playlists.add(Playlist(name, mutableSetOf()))
        savePlaylists(context, playlists)
    }

    fun addSongToPlaylist(context: Context, playlistName: String, songId: Long) {
        val playlists = getPlaylists(context).toMutableList()
        val index = playlists.indexOfFirst { it.name == playlistName }
        if (index != -1) {
            playlists[index].songIds.add(songId)
            savePlaylists(context, playlists)
        }
    }

    private fun savePlaylists(context: Context, playlists: List<Playlist>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        playlists.forEach { pl ->
            val obj = JSONObject()
            obj.put("name", pl.name)
            val idsArray = JSONArray()
            pl.songIds.forEach { idsArray.add(it) }
            obj.put("ids", idsArray)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }
}
