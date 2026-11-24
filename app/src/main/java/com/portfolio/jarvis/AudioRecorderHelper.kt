package com.portfolio.jarvis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorderHelper {
    private val sampleRate = 16000
    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val recordedSamples = mutableListOf<Float>()

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            android.util.Log.e("JarvisAudio", "Ошибка инициализации микрофона")
            return
        }

        recordedSamples.clear()
        isRecording.set(true)
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ShortArray(minBufferSize)
            while (isRecording.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(recordedSamples) {
                        for (i in 0 until read) {
                            recordedSamples.add(buffer[i] / 32768.0f)
                        }
                    }
                }
            }
        }.apply { start() }
    }

    fun stopRecordingAndGetSamples(): FloatArray {
        isRecording.set(false)
        try {
            recordingThread?.join(500)
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recordingThread = null
        audioRecord = null

        synchronized(recordedSamples) {
            return recordedSamples.toFloatArray()
        }
    }
}
