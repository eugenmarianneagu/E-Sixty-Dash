package com.bmw.carlauncher.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream

object VoiceMessagePlayer {
    private const val TAG = "VoiceMessagePlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingFile: File? = null
    private var isPlayingState: Boolean = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    interface PlaybackListener {
        fun onPlaybackStarted()
        fun onPlaybackPaused()
        fun onPlaybackCompleted()
        fun onPlaybackError(error: String)
    }

    var listener: PlaybackListener? = null

    fun isPlaying(): Boolean = isPlayingState

    fun getCurrentFile(): File? = currentPlayingFile

    fun init(context: Context) {
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            pause()
                        }
                    }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            pause()
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eroare requestAudioFocus: ${e.message}")
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eroare abandonAudioFocus: ${e.message}")
        }
    }

    /**
     * Caută cel mai recent fișier de notă vocală (.opus, .m4a, .mp3, etc.)
     * asociat cu timestamp-ul notificării.
     */
    fun findLatestVoiceNote(packageName: String, notifTimestamp: Long): File? {
        try {
            val storageDir = Environment.getExternalStorageDirectory() ?: return null

            val candidateDirs = mutableListOf<File>()

            // 1. WhatsApp Folders (Standard, Business, Legacy)
            candidateDirs.add(File(storageDir, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"))
            candidateDirs.add(File(storageDir, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"))
            candidateDirs.add(File(storageDir, "WhatsApp/Media/WhatsApp Voice Notes"))
            candidateDirs.add(File(storageDir, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"))
            candidateDirs.add(File(storageDir, "WhatsApp/Media/WhatsApp Audio"))

            // 2. Telegram Folders
            candidateDirs.add(File(storageDir, "Android/media/org.telegram.messenger/Telegram/Telegram Audio"))
            candidateDirs.add(File(storageDir, "Telegram/Telegram Audio"))

            val audioExtensions = setOf("opus", "m4a", "ogg", "mp3", "aac", "wav", "3gp")
            val candidateFiles = mutableListOf<File>()

            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                for (item in files) {
                    if (item.isDirectory) {
                        val subFiles = item.listFiles() ?: continue
                        for (subFile in subFiles) {
                            if (subFile.isFile && audioExtensions.contains(subFile.extension.lowercase())) {
                                candidateFiles.add(subFile)
                            }
                        }
                    } else if (item.isFile && audioExtensions.contains(item.extension.lowercase())) {
                        candidateFiles.add(item)
                    }
                }
            }

            if (candidateFiles.isEmpty()) {
                Log.d(TAG, "Nu s-au gasit fisiere audio in directoarele WhatsApp/Media")
                return null
            }

            candidateFiles.sortByDescending { it.lastModified() }

            val newest = candidateFiles.first()
            val timeDiff = Math.abs(newest.lastModified() - notifTimestamp)
            Log.d(TAG, "Cel mai nou fisier audio: ${newest.absolutePath}, modified=${newest.lastModified()}, diff=${timeDiff}ms")

            val now = System.currentTimeMillis()
            if (Math.abs(now - newest.lastModified()) < 600_000 || timeDiff < 600_000) {
                return newest
            }

            if (Math.abs(now - newest.lastModified()) < 1_800_000) {
                return newest
            }

            return newest
        } catch (e: Exception) {
            Log.e(TAG, "Eroare cautare fisier voce: ${e.message}", e)
            return null
        }
    }

    fun play(file: File) {
        stop()
        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            currentPlayingFile = file

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build()
            )
            mp.setVolume(1.0f, 1.0f)

            FileInputStream(file).use { fis ->
                mp.setDataSource(fis.fd)
            }

            mp.setOnCompletionListener {
                isPlayingState = false
                currentPlayingFile = null
                abandonFocus()
                listener?.onPlaybackCompleted()
                stop()
            }

            mp.setOnErrorListener { _, what, extra ->
                isPlayingState = false
                currentPlayingFile = null
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                abandonFocus()
                listener?.onPlaybackError("Eroare redare audio: $what/$extra")
                stop()
                true
            }

            mp.prepare()
            requestFocus()
            mp.start()
            isPlayingState = true
            listener?.onPlaybackStarted()
            Log.d(TAG, "Redare pornita cu succes pentru ${file.name}")
        } catch (e: Exception) {
            isPlayingState = false
            currentPlayingFile = null
            abandonFocus()
            Log.e(TAG, "Exceptie la pornirea redarii: ${e.message}", e)
            listener?.onPlaybackError("Eroare: ${e.message}")
            stop()
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                isPlayingState = false
                abandonFocus()
                listener?.onPlaybackPaused()
                Log.d(TAG, "Redare pusa pe pauza")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare pauza: ${e.message}")
        }
    }

    fun resume() {
        try {
            mediaPlayer?.let { mp ->
                requestFocus()
                mp.start()
                isPlayingState = true
                listener?.onPlaybackStarted()
                Log.d(TAG, "Redare reluata")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare resume: ${e.message}")
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.reset()
                mp.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare stop: ${e.message}")
        } finally {
            abandonFocus()
            mediaPlayer = null
            isPlayingState = false
        }
    }
}
