package com.example.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.data.model.AmbientSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AmbientAudioSynthesizer {

    private var audioTrack: AudioTrack? = null
    private var synthJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun startPlaying(sound: AmbientSound) {
        stopPlaying()
        if (sound == AmbientSound.NONE) return

        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        synthJob = scope.launch {
            val buffer = ShortArray(bufferSize / 2)
            var phase1 = 0.0
            var phase2 = 0.0
            var lofiPhase = 0.0
            var beatCounter = 0

            while (isActive) {
                when (sound) {
                    AmbientSound.WHITE_NOISE -> {
                        for (i in buffer.indices) {
                            // Gentle filtered white noise
                            buffer[i] = ((Random.nextDouble() * 2 - 1) * 3500).toInt().toShort()
                        }
                    }
                    AmbientSound.DEEP_SPACE -> {
                        // 432Hz carrier with 8Hz Alpha binaural pulsation
                        val freq1 = 216.0
                        val freq2 = 224.0
                        for (i in buffer.indices) {
                            phase1 += 2 * PI * freq1 / sampleRate
                            phase2 += 2 * PI * freq2 / sampleRate
                            val sample = (sin(phase1) * 4000) + (sin(phase2) * 3500)
                            buffer[i] = sample.toInt().toShort()
                        }
                    }
                    AmbientSound.LOFI_BEATS -> {
                        // Relaxing warm chord tones
                        val rootFreq = 174.61 // F3
                        val thirdFreq = 220.0  // A3
                        val fifthFreq = 261.63 // C4
                        for (i in buffer.indices) {
                            lofiPhase += 2 * PI * rootFreq / sampleRate
                            val p2 = lofiPhase * (thirdFreq / rootFreq)
                            val p3 = lofiPhase * (fifthFreq / rootFreq)
                            beatCounter++
                            val envelope = (0.7 + 0.3 * sin(2 * PI * (beatCounter % 44100) / 44100))
                            val wave = (sin(lofiPhase) * 3000 + sin(p2) * 2000 + sin(p3) * 1800) * envelope
                            // Gentle subtle vinyl crackle
                            val crackle = if (Random.nextInt(400) == 0) (Random.nextDouble() * 1200).toInt() else 0
                            buffer[i] = (wave + crackle).toInt().toShort()
                        }
                    }
                    AmbientSound.RAINSTORM, AmbientSound.FOREST_STREAM -> {
                        // Rain drops & organic fluid noise
                        for (i in buffer.indices) {
                            val noise = (Random.nextDouble() * 2 - 1) * 2500
                            val drop = if (Random.nextInt(500) == 0) ((Random.nextDouble() * 2 - 1) * 4500).toInt() else 0
                            buffer[i] = (noise + drop).toInt().toShort()
                        }
                    }
                    AmbientSound.NONE -> {
                        buffer.fill(0)
                    }
                }
                try {
                    audioTrack?.write(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun stopPlaying() {
        synthJob?.cancel()
        synthJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore release exceptions
        }
        audioTrack = null
    }
}
