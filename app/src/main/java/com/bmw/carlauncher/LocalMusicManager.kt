package com.bmw.carlauncher

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val path: String,
    val albumId: Long,
    val folderName: String
) {
    fun formatDuration(): String {
        if (durationMs <= 0) return "--:--"
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%d:%02d", min, sec)
    }

    val contentUri: Uri
        get() = if (id > 0) {
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            Uri.fromFile(File(path))
        }
}

data class LocalPlaylist(
    val id: String,
    var name: String,
    val isSystem: Boolean = false,
    val isFolder: Boolean = false,
    val trackPaths: MutableList<String> = mutableListOf()
)

object LocalMusicManager {
    private const val TAG = "LocalMusicManager"
    private const val PREFS_FILE = "local_playlists.json"

    const val ID_ALL_SONGS = "system_all_songs"
    const val ID_FAVORITES = "system_favorites"

    val cachedTracks = mutableListOf<LocalTrack>()
    val favoritePaths = mutableSetOf<String>()
    val customPlaylists = mutableListOf<LocalPlaylist>()
    val folderPlaylists = mutableListOf<LocalPlaylist>()

    private var isInitialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        loadJsonData(context)
        scanDeviceTracks(context)
        isInitialized = true
    }

    @Synchronized
    fun scanDeviceTracks(context: Context): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()
        val seenPaths = mutableSetOf<String>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.DURATION} >= 3000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (c.moveToNext()) {
                    val path = c.getString(dataCol) ?: continue
                    if (path.isEmpty() || !File(path).exists()) continue
                    if (seenPaths.contains(path)) continue
                    seenPaths.add(path)

                    val id = c.getLong(idCol)
                    var title = c.getString(titleCol) ?: ""
                    var artist = c.getString(artistCol) ?: "<Necunoscut>"
                    var album = c.getString(albumCol) ?: ""
                    val duration = c.getLong(durationCol)
                    val albumId = c.getLong(albumIdCol)

                    val file = File(path)
                    if (title.isEmpty() || title.equals("<unknown>", ignoreCase = true)) {
                        title = file.nameWithoutExtension
                    }
                    if (artist.equals("<unknown>", ignoreCase = true)) {
                        artist = "<Necunoscut>"
                    }

                    val folderName = file.parentFile?.name ?: "Muzica"

                    tracks.add(
                        LocalTrack(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = duration,
                            path = path,
                            albumId = albumId,
                            folderName = folderName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore: ${e.message}", e)
        }

        // Direct directory fallback if few tracks found (e.g. newly downloaded songs or USB OTG)
        val candidateDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES),
            File("/storage")
        )

        for (dir in candidateDirs) {
            if (dir.exists() && dir.canRead()) {
                scanDirectoryRecursively(dir, seenPaths, tracks, maxDepth = 4)
            }
        }

        cachedTracks.clear()
        cachedTracks.addAll(tracks.sortedBy { it.title.lowercase() })

        // Build folder playlists
        folderPlaylists.clear()
        val groupedByFolder = cachedTracks.groupBy { it.folderName }
        for ((folder, trackList) in groupedByFolder) {
            val pl = LocalPlaylist(
                id = "folder_${folder.hashCode()}",
                name = folder,
                isSystem = false,
                isFolder = true,
                trackPaths = trackList.map { it.path }.toMutableList()
            )
            folderPlaylists.add(pl)
        }
        folderPlaylists.sortBy { it.name.lowercase() }

        Log.d(TAG, "Scanned total ${cachedTracks.size} tracks across ${folderPlaylists.size} folders")
        return cachedTracks
    }

    private fun scanDirectoryRecursively(
        dir: File,
        seenPaths: MutableSet<String>,
        outList: MutableList<LocalTrack>,
        maxDepth: Int
    ) {
        if (maxDepth <= 0) return
        val files = dir.listFiles() ?: return

        val audioExts = setOf("mp3", "m4a", "wav", "flac", "ogg", "aac")
        for (f in files) {
            if (f.isDirectory) {
                // skip hidden directories or Android internal app data
                if (!f.name.startsWith(".") && !f.name.equals("Android", ignoreCase = true)) {
                    scanDirectoryRecursively(f, seenPaths, outList, maxDepth - 1)
                }
            } else if (f.isFile) {
                val ext = f.extension.lowercase()
                if (audioExts.contains(ext) && !seenPaths.contains(f.absolutePath)) {
                    seenPaths.add(f.absolutePath)
                    try {
                        val mmr = MediaMetadataRetriever()
                        mmr.setDataSource(f.absolutePath)
                        val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            ?.ifBlank { f.nameWithoutExtension } ?: f.nameWithoutExtension
                        val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?.ifBlank { "<Necunoscut>" } ?: "<Necunoscut>"
                        val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                        val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val duration = durStr?.toLongOrNull() ?: 0L
                        mmr.release()

                        if (duration >= 3000 || duration == 0L) {
                            outList.add(
                                LocalTrack(
                                    id = 0L,
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    durationMs = duration,
                                    path = f.absolutePath,
                                    albumId = 0L,
                                    folderName = f.parentFile?.name ?: "Muzica"
                                )
                            )
                        }
                    } catch (_: Exception) {
                        outList.add(
                            LocalTrack(
                                id = 0L,
                                title = f.nameWithoutExtension,
                                artist = "<Necunoscut>",
                                album = "",
                                durationMs = 0L,
                                path = f.absolutePath,
                                albumId = 0L,
                                folderName = f.parentFile?.name ?: "Muzica"
                            )
                        )
                    }
                }
            }
        }
    }

    fun getAllPlaylists(): List<LocalPlaylist> {
        val result = mutableListOf<LocalPlaylist>()

        // 1. All Songs
        result.add(
            LocalPlaylist(
                id = ID_ALL_SONGS,
                name = "Toate piesele",
                isSystem = true,
                isFolder = false,
                trackPaths = cachedTracks.map { it.path }.toMutableList()
            )
        )

        // 2. Favorites
        val favTracks = cachedTracks.filter { it.path in favoritePaths }
        result.add(
            LocalPlaylist(
                id = ID_FAVORITES,
                name = "Favorite ⭐",
                isSystem = true,
                isFolder = false,
                trackPaths = favTracks.map { it.path }.toMutableList()
            )
        )

        // 3. User Custom Playlists
        result.addAll(customPlaylists)

        // 4. Folder Playlists
        result.addAll(folderPlaylists)

        return result
    }

    fun getTracksForPlaylist(playlist: LocalPlaylist): List<LocalTrack> {
        return when (playlist.id) {
            ID_ALL_SONGS -> cachedTracks
            ID_FAVORITES -> cachedTracks.filter { it.path in favoritePaths }
            else -> {
                val pathSet = playlist.trackPaths.toSet()
                cachedTracks.filter { it.path in pathSet }
            }
        }
    }

    fun isFavorite(track: LocalTrack): Boolean {
        return track.path in favoritePaths
    }

    fun toggleFavorite(context: Context, track: LocalTrack): Boolean {
        val isFav = if (track.path in favoritePaths) {
            favoritePaths.remove(track.path)
            false
        } else {
            favoritePaths.add(track.path)
            true
        }
        saveJsonData(context)
        return isFav
    }

    fun createCustomPlaylist(context: Context, name: String): LocalPlaylist {
        val trimmed = name.trim().ifEmpty { "Playlist Nou" }
        val pl = LocalPlaylist(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            isSystem = false,
            isFolder = false
        )
        customPlaylists.add(pl)
        saveJsonData(context)
        return pl
    }

    fun deleteCustomPlaylist(context: Context, playlistId: String): Boolean {
        val removed = customPlaylists.removeAll { it.id == playlistId }
        if (removed) {
            saveJsonData(context)
        }
        return removed
    }

    fun addTrackToPlaylist(context: Context, playlistId: String, track: LocalTrack): Boolean {
        val pl = customPlaylists.find { it.id == playlistId } ?: return false
        if (!pl.trackPaths.contains(track.path)) {
            pl.trackPaths.add(track.path)
            saveJsonData(context)
            return true
        }
        return false
    }

    fun removeTrackFromPlaylist(context: Context, playlistId: String, track: LocalTrack): Boolean {
        val pl = customPlaylists.find { it.id == playlistId } ?: return false
        val removed = pl.trackPaths.remove(track.path)
        if (removed) {
            saveJsonData(context)
        }
        return removed
    }

    private fun saveJsonData(context: Context) {
        try {
            val root = JSONObject()

            // Favorites
            val favArr = JSONArray()
            for (p in favoritePaths) favArr.put(p)
            root.put("favorites", favArr)

            // Custom Playlists
            val plArr = JSONArray()
            for (pl in customPlaylists) {
                val obj = JSONObject()
                obj.put("id", pl.id)
                obj.put("name", pl.name)
                val tracksArr = JSONArray()
                for (tp in pl.trackPaths) tracksArr.put(tp)
                obj.put("tracks", tracksArr)
                plArr.put(obj)
            }
            root.put("custom_playlists", plArr)

            val file = File(context.filesDir, PREFS_FILE)
            file.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving playlists JSON: ${e.message}", e)
        }
    }

    private fun loadJsonData(context: Context) {
        try {
            val file = File(context.filesDir, PREFS_FILE)
            if (!file.exists()) return

            val content = file.readText()
            if (content.isBlank()) return

            val root = JSONObject(content)

            // Favorites
            favoritePaths.clear()
            val favArr = root.optJSONArray("favorites")
            if (favArr != null) {
                for (i in 0 until favArr.length()) {
                    favoritePaths.add(favArr.getString(i))
                }
            }

            // Custom playlists
            customPlaylists.clear()
            val plArr = root.optJSONArray("custom_playlists")
            if (plArr != null) {
                for (i in 0 until plArr.length()) {
                    val obj = plArr.getJSONObject(i)
                    val id = obj.optString("id", UUID.randomUUID().toString())
                    val name = obj.optString("name", "Playlist")
                    val tracksArr = obj.optJSONArray("tracks")
                    val tracks = mutableListOf<String>()
                    if (tracksArr != null) {
                        for (j in 0 until tracksArr.length()) {
                            tracks.add(tracksArr.getString(j))
                        }
                    }
                    customPlaylists.add(
                        LocalPlaylist(
                            id = id,
                            name = name,
                            isSystem = false,
                            isFolder = false,
                            trackPaths = tracks
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading playlists JSON: ${e.message}", e)
        }
    }
}
