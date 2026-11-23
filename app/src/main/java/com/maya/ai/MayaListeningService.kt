
package com.maya.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile

class MayaListeningService : Service() {

        companion object {
        const val CHANNEL_ID = "maya_listening_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_WAKE_WORD = "com.maya.ai.ACTION_WAKE_WORD"
        }
    @Volatile
    private var running = false

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var spotter: KeywordSpotter? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val notification = makeNotification(
            "MAYA आवाज़ सुन रही है..."
        )

        startForeground(NOTIFICATION_ID, notification)

        if (!running) {
            startWakeWordListening()
        }

        return START_STICKY
    }

    private fun startWakeWordListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("माइक्रोफोन की अनुमति नहीं मिली")
            stopSelf()
            return
        }

        running = true

        audioThread = Thread {
            var wakeWordDetected = false
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            try {
                val modelConfig = getKwsModelConfig(1)
                    ?: throw Exception("KWS model config नहीं मिला")

                val config = KeywordSpotterConfig(
                    featConfig = getFeatureConfig(16000, 80),
                    modelConfig = modelConfig,
                    keywordsFile = getKeywordsFile(1),
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.20f
                )

                spotter = KeywordSpotter(
                    assetManager = assets,
                    config = config
                )

                val stream = spotter!!.createStream()

                val minBuffer = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    throw Exception("Audio buffer उपलब्ध नहीं है")
                }

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer * 2, 4096)
                )

                audioRecord = recorder

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw Exception("Microphone शुरू नहीं हुआ")
                }

                recorder.startRecording()

                val shortBuffer = ShortArray(1024)
var audioDetected = false
                while (running) {
                    val count = recorder.read(
                        shortBuffer,
                        0,
                        shortBuffer.size
                    )

                    if (count > 0) {
                            if (!audioDetected) {
    updateNotification("माइक्रोफोन आवाज़ पढ़ रहा है")
    audioDetected = true
                            }
                        val samples = FloatArray(count) { i ->
                            shortBuffer[i] / 32768.0f
                        }

                        stream.acceptWaveform(samples, 16000)

                        while (spotter!!.isReady(stream)) {
                            spotter!!.decode(stream)

                            val result = spotter!!.getResult(stream)

                            if (result.keyword.isNotEmpty()) {
    updateNotification("HEY MAYA पहचाना गया!")
    wakeWordDetected = true
    running = false
    break
                            }
                        }
                    }
                }

                stream.release()

            } catch (e: Exception) {
                updateNotification(
                    "Voice समस्या: ${e.message ?: "अज्ञात त्रुटि"}"
                )
            } finally {
                running = false
                audioRecord?.let {
                    try {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            it.stop()
                        }
                    } catch (_: Exception) {
                    }
                    it.release()
                }
                audioRecord = null
                spotter?.release()
                spotter = null
            }
            if (wakeWordDetected) {
    sendBroadcast(Intent(ACTION_WAKE_WORD).setPackage(packageName))
            }
        }

        audioThread?.start()
    }

    private fun makeNotification(message: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MAYA AI")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(
            NotificationManager::class.java
        )
        manager.notify(
            NOTIFICATION_ID,
            makeNotification(message)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MAYA Voice Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager =
                getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
