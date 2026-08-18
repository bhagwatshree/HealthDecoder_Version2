package com.healthdecoder.app.util

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import java.io.File

/**
 * Plays a sequence of base64-encoded WAV clips (returned by the Sarvam TTS backend) one
 * after another. Used to read AI answers aloud in Indic languages that on-device TTS lacks.
 */
object AudioPlayer {
    private var player: MediaPlayer? = null

    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    /** Decodes and plays the clips sequentially. Returns true if playback started. */
    fun playBase64Wavs(context: Context, audios: List<String>): Boolean {
        stop()
        val files = audios.mapNotNull { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                File.createTempFile("tts_", ".wav", context.cacheDir).apply { writeBytes(bytes) }
            }.getOrNull()
        }
        if (files.isEmpty()) return false
        playIndex(files, 0)
        return true
    }

    private fun playIndex(files: List<File>, i: Int) {
        if (i >= files.size) { stop(); files.forEach { runCatching { it.delete() } }; return }
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(files[i].absolutePath)
            mp.setOnCompletionListener { playIndex(files, i + 1) }
            mp.setOnErrorListener { _, _, _ -> playIndex(files, i + 1); true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        }.onFailure { playIndex(files, i + 1) }
    }
}
