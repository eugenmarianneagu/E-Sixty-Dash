package com.bmw.carlauncher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

@OptIn(UnstableApi::class)
object LocalMusicPlayer {
    private const val TAG = "LocalMusicPlayer"

    interface PlaybackListener {
        fun onTrackChanged(track: LocalTrack?)
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    private var appContext: Context? = null
    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentPlaylist: LocalPlaylist? = null
        private set
    val currentQueue = mutableListOf<LocalTrack>()
    private val listeners = mutableListOf<PlaybackListener>()

    val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    val currentTrack: LocalTrack?
        get() {
            val idx = exoPlayer?.currentMediaItemIndex ?: -1
            return if (idx in currentQueue.indices) currentQueue[idx] else null
        }

    val isShuffleEnabled: Boolean
        get() = exoPlayer?.shuffleModeEnabled == true

    val repeatMode: Int
        get() = exoPlayer?.repeatMode ?: Player.REPEAT_MODE_ALL

    fun addListener(listener: PlaybackListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun initialize(context: Context) {
        if (exoPlayer != null) return
        appContext = context.applicationContext

        val player = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // handleAudioFocus = true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.repeatMode = Player.REPEAT_MODE_ALL

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                notifyPlaybackStateChanged(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                notifyTrackChanged(currentTrack)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (currentQueue.isNotEmpty()) {
                        player.seekTo(0, 0L)
                        player.play()
                    } else {
                        notifyPlaybackStateChanged(false)
                    }
                }
            }
        })

        exoPlayer = player
        Log.d(TAG, "LocalMusicPlayer initialized successfully")
    }

    fun playPlaylist(context: Context, playlist: LocalPlaylist, startIndex: Int = 0) {
        initialize(context)
        val player = exoPlayer ?: return

        // Stop Radio
        try {
            val stopIntent = Intent(RadioService.ACTION_STOP_RADIO)
            context.sendBroadcast(stopIntent)
        } catch (_: Exception) {}

        val tracks = LocalMusicManager.getTracksForPlaylist(playlist)
        if (tracks.isEmpty()) {
            Log.w(TAG, "Cannot play empty playlist: ${playlist.name}")
            return
        }

        currentPlaylist = playlist
        currentQueue.clear()
        currentQueue.addAll(tracks)

        val mediaItems = tracks.map { track ->
            val uri = if (track.id > 0) {
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
            } else {
                Uri.fromFile(File(track.path))
            }
            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(track.path)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .build()
                )
                .build()
        }

        val safeIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        player.setMediaItems(mediaItems, safeIndex, 0L)
        player.prepare()
        player.play()

        notifyTrackChanged(currentTrack)
        notifyPlaybackStateChanged(true)
    }

    fun playSingleTrack(context: Context, track: LocalTrack) {
        initialize(context)
        // Find existing playlist or use all songs
        val allSongsPl = LocalPlaylist(
            id = LocalMusicManager.ID_ALL_SONGS,
            name = "Toate piesele",
            isSystem = true,
            isFolder = false,
            trackPaths = LocalMusicManager.cachedTracks.map { it.path }.toMutableList()
        )
        val idx = LocalMusicManager.cachedTracks.indexOfFirst { it.path == track.path }
        playPlaylist(context, allSongsPl, if (idx >= 0) idx else 0)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE && currentQueue.isNotEmpty()) {
                val pl = currentPlaylist
                if (pl != null && appContext != null) {
                    playPlaylist(appContext!!, pl, 0)
                    return
                }
            }
            // Stop Radio if radio was playing
            appContext?.let { ctx ->
                try {
                    ctx.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO))
                } catch (_: Exception) {}
            }
            player.play()
        }
    }

    fun playOrResumeLastTrack(context: Context): Boolean {
        initialize(context)
        val player = exoPlayer ?: return false

        // Stop Radio if radio was playing
        try {
            context.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(context.packageName))
        } catch (_: Exception) {}

        // If we already have a loaded queue in memory, just resume playback
        if (currentQueue.isNotEmpty() && player.mediaItemCount > 0) {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
            notifyTrackChanged(currentTrack)
            notifyPlaybackStateChanged(true)
            return true
        }

        // Otherwise load from SharedPreferences (or default to first available track)
        if (LocalMusicManager.cachedTracks.isEmpty()) {
            LocalMusicManager.scanDeviceTracks(context)
        }
        if (LocalMusicManager.cachedTracks.isEmpty()) {
            return false
        }

        val prefs = context.getSharedPreferences("agama_local_music", Context.MODE_PRIVATE)
        val lastPlaylistId = prefs.getString("last_playlist_id", null)
        val lastTrackPath = prefs.getString("last_track_path", null)

        val playlists = LocalMusicManager.getAllPlaylists()
        val targetPlaylist = playlists.firstOrNull { it.id == lastPlaylistId }
            ?: playlists.firstOrNull { LocalMusicManager.getTracksForPlaylist(it).isNotEmpty() }
            ?: LocalPlaylist(
                id = LocalMusicManager.ID_ALL_SONGS,
                name = "Toate piesele",
                isSystem = true,
                isFolder = false,
                trackPaths = LocalMusicManager.cachedTracks.map { it.path }.toMutableList()
            )

        val tracks = LocalMusicManager.getTracksForPlaylist(targetPlaylist)
        if (tracks.isEmpty()) return false

        val startIndex = if (!lastTrackPath.isNullOrEmpty()) {
            val idx = tracks.indexOfFirst { it.path == lastTrackPath }
            if (idx >= 0) idx else 0
        } else {
            0
        }

        playPlaylist(context, targetPlaylist, startIndex)
        return true
    }

    fun play() {
        val player = exoPlayer ?: return
        appContext?.let { ctx ->
            try {
                ctx.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO))
            } catch (_: Exception) {}
        }
        player.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun stop() {
        exoPlayer?.pause()
        exoPlayer?.stop()
    }

    fun next() {
        val player = exoPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (currentQueue.isNotEmpty()) {
            player.seekToDefaultPosition(0)
            player.play()
        }
    }

    fun previous() {
        val player = exoPlayer ?: return
        if (player.currentPosition > 3000) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else if (currentQueue.isNotEmpty()) {
            player.seekToDefaultPosition(currentQueue.size - 1)
            player.play()
        }
    }

    fun toggleShuffle(): Boolean {
        val player = exoPlayer ?: return false
        val newShuffle = !player.shuffleModeEnabled
        player.shuffleModeEnabled = newShuffle
        return newShuffle
    }

    fun cycleRepeatMode(): Int {
        val player = exoPlayer ?: return Player.REPEAT_MODE_ALL
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
        player.repeatMode = nextMode
        return nextMode
    }

    fun loadArtwork(context: Context, track: LocalTrack): Bitmap {
        var rawBmp: Bitmap? = null
        if (track.albumId > 0) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, track.albumId)
                    rawBmp = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                }
            } catch (_: Exception) {}
        }
        if (rawBmp == null) {
            try {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, track.contentUri)
                } catch (_: Exception) {
                    mmr.setDataSource(track.path)
                }
                val artBytes = mmr.embeddedPicture
                mmr.release()
                if (artBytes != null) {
                    rawBmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                }
            } catch (_: Exception) {}
        }
        if (rawBmp == null && track.albumId > 0) {
            try {
                val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
                val albumArtUri = ContentUris.withAppendedId(sArtworkUri, track.albumId)
                context.contentResolver.openInputStream(albumArtUri)?.use { stream ->
                    rawBmp = BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {}
        }

        val finalBmp = rawBmp
        if (finalBmp != null) {
            return cropSolidBorders(finalBmp)
        }
        return createDefaultMusicBitmap(track.title, track.artist)
    }

    /**
     * Detecteaza si decupeaza automat marginile/barele solide (letterboxing, pillarboxing,
     * spatii albe, bare albastre/negre etc.) din artwork pentru ca imaginea reala
     * sa umple complet (fill/crop) cercul central.
     */
    fun cropSolidBorders(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 40 || h < 40) return bitmap

        fun colorDiff(c1: Int, c2: Int): Int {
            val a1 = (c1 ushr 24) and 0xFF
            val a2 = (c2 ushr 24) and 0xFF
            if (a1 < 16 && a2 < 16) return 0
            if ((a1 < 16) != (a2 < 16)) return 255
            val rDiff = Math.abs(((c1 ushr 16) and 0xFF) - ((c2 ushr 16) and 0xFF))
            val gDiff = Math.abs(((c1 ushr 8) and 0xFF) - ((c2 ushr 8) and 0xFF))
            val bDiff = Math.abs((c1 and 0xFF) - (c2 and 0xFF))
            return Math.max(rDiff, Math.max(gDiff, bDiff))
        }

        fun isRowPadding(y: Int, width: Int): Boolean {
            val ref = bitmap.getPixel(width / 2, y)
            val samples = 15
            for (i in 0 until samples) {
                val x = (i * (width - 1)) / (samples - 1)
                val p = bitmap.getPixel(x, y)
                if (colorDiff(p, ref) > 18) return false
            }
            return true
        }

        var top = 0
        val maxScanH = (h * 0.42).toInt()
        for (y in 0 until maxScanH) {
            if (isRowPadding(y, w)) {
                top = y + 1
            } else {
                break
            }
        }

        var bottom = h - 1
        val minScanH = (h * 0.58).toInt()
        for (y in (h - 1) downTo minScanH) {
            if (isRowPadding(y, w)) {
                bottom = y - 1
            } else {
                break
            }
        }

        val contentH = bottom - top + 1
        if (contentH < (h * 0.25)) return bitmap

        fun isColPadding(x: Int, yStart: Int, yEnd: Int): Boolean {
            val ref = bitmap.getPixel(x, (yStart + yEnd) / 2)
            val samples = 15
            for (i in 0 until samples) {
                val y = yStart + (i * (yEnd - yStart)) / (samples - 1)
                val p = bitmap.getPixel(x, y)
                if (colorDiff(p, ref) > 18) return false
            }
            return true
        }

        var left = 0
        val maxScanW = (w * 0.42).toInt()
        for (x in 0 until maxScanW) {
            if (isColPadding(x, top, bottom)) {
                left = x + 1
            } else {
                break
            }
        }

        var right = w - 1
        val minScanW = (w * 0.58).toInt()
        for (x in (w - 1) downTo minScanW) {
            if (isColPadding(x, top, bottom)) {
                right = x - 1
            } else {
                break
            }
        }

        val contentW = right - left + 1
        if (contentW < (w * 0.25)) return bitmap

        val hasPadding = top > (h * 0.03) || (h - 1 - bottom) > (h * 0.03) ||
                left > (w * 0.03) || (w - 1 - right) > (w * 0.03)
        if (!hasPadding) return bitmap

        return try {
            Bitmap.createBitmap(bitmap, left, top, contentW, contentH)
        } catch (_: Exception) {
            bitmap
        }
    }

    fun createDefaultMusicBitmap(title: String, artist: String): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        // Dark vinyl disc background
        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#121212")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, cx, bgPaint)

        // Vinyl concentric grooves
        val groovePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#242424")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        for (r in 145..242 step 11) {
            canvas.drawCircle(cx, cy, r.toFloat(), groovePaint)
        }

        // Accent amber outer ring
        val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FF7200")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(cx, cy, 126f, ringPaint)

        // Center vinyl label
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1C1A17")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 124f, labelPaint)

        // Center vinyl hole & subtle inner ring
        val innerRingPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#55FF7200")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(cx, cy, 34f, innerRingPaint)

        val holePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0A0A0A")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 18f, holePaint)

        return bmp
    }

    private fun notifyTrackChanged(track: LocalTrack?) {
        if (track != null) {
            appContext?.let { ctx ->
                try {
                    ctx.getSharedPreferences("agama_local_music", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_track_path", track.path)
                        .putString("last_playlist_id", currentPlaylist?.id ?: LocalMusicManager.ID_ALL_SONGS)
                        .apply()
                } catch (_: Exception) {}
            }
        }
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onTrackChanged(track)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error onTrackChanged: ${e.message}", e)
                }
            }
        }
    }

    private fun notifyPlaybackStateChanged(isPlaying: Boolean) {
        if (isPlaying) {
            appContext?.let { ctx ->
                try {
                    ctx.getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_active_media_source", "LOCAL_PLAYER")
                        .apply()
                } catch (_: Exception) {}
            }
        }
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onPlaybackStateChanged(isPlaying)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error onPlaybackStateChanged: ${e.message}", e)
                }
            }
        }
    }
}
