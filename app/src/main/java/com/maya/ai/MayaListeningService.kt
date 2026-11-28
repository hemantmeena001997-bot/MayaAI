
package com.maya.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
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
        const val ACTION_UI_UPDATE = "com.maya.ai.ACTION_UI_UPDATE"
        const val EXTRA_MESSAGE = "extra_message"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var wakeListening = false

    @Volatile
    private var serviceStopping = false

    @Volatile
    private var conversationMode = false

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var recognizer: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private val conversationTimeout = 30_000L
    private val conversationHandler = Handler(Looper.getMainLooper())
    private var conversationRunnable: Runnable? = null

    private val workerUrl =
        "https://maya-ai-brain.hemantmeena001997.workers.dev/"

    // ऐप की स्क्रीन पर संदेश भेजना
    private fun publishToApp(message: String) {
        val intent = Intent(ACTION_UI_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    // जवाब को सुरक्षित करके स्क्रीन पर भेजना
    private fun publishReplyToApp(message: String) {
        getSharedPreferences("maya_data", MODE_PRIVATE)
            .edit()
            .putString("last_reply", message)
            .apply()

        publishToApp(message)
    }

    override fun onCreate() {
        super.onCreate()
        serviceStopping = false
        createNotificationChannel()
        initializeSpeaker()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val notification = makeNotification("MAYA शुरू हो रही है...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!wakeListening && audioThread?.isAlive != true) {
            startWakeWordListening()
        }

        return START_STICKY
    }

    private fun initializeSpeaker() {
        speaker = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {
                val result = speaker?.setLanguage(Locale("hi", "IN"))

                ttsReady =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

                speaker?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                if (!serviceStopping) {
                                    if (conversationMode) {
                                        startCommandListening()
                                    } else {
                                        startWakeWordListening()
                                    }
                                }
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                if (!serviceStopping) {
                                    if (conversationMode) {
                                        startCommandListening()
                                    } else {
                                        startWakeWordListening()
                                    }
                                }
                            }
                        }
                    }
                )

                if (ttsReady && pendingSpeech != null) {
                    val reply = pendingSpeech!!
                    pendingSpeech = null
                    speakReply(reply)
                }
            }
        }
    }

    private fun cancelConversationTimeout() {
        conversationRunnable?.let {
            conversationHandler.removeCallbacks(it)
        }
        conversationRunnable = null
    }

    private fun resetConversationTimeout() {
        cancelConversationTimeout()

        val task = Runnable {
            conversationRunnable = null

            if (conversationMode && !serviceStopping) {
                conversationMode = false
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null

                updateNotification("MAYA फिर से Wake Word सुन रही है...")
                startWakeWordListening()
            }
        }

        conversationRunnable = task
        conversationHandler.postDelayed(task, conversationTimeout)
    }

    private fun startWakeWordListening() {

        if (serviceStopping || wakeListening) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("माइक्रोफोन की अनुमति नहीं मिली")
            return
        }

        wakeListening = true
        updateNotification("MAYA आवाज़ सुन रही है...")

        audioThread = Thread {

            var detected = false
            var recorder: AudioRecord? = null
            var localSpotter: KeywordSpotter? = null

            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            try {
                val modelConfig = getKwsModelConfig(1)
                    ?: throw Exception("KWS मॉडल नहीं मिला")

                val config = KeywordSpotterConfig(
                    featConfig = getFeatureConfig(16000, 80),
                    modelConfig = modelConfig,
                    keywordsFile = getKeywordsFile(1),
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.20f
                )

                val spotter = KeywordSpotter(
                    assetManager = assets,
                    config = config
                )

                localSpotter = spotter
                val stream = spotter.createStream()

                try {
                    val minBuffer = AudioRecord.getMinBufferSize(
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )

                    if (minBuffer <= 0) {
                        throw Exception("Audio buffer उपलब्ध नहीं है")
                    }

                    recorder = AudioRecord(
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

                    while (wakeListening && !serviceStopping) {

                        val count = recorder.read(
                            shortBuffer,
                            0,
                            shortBuffer.size
                        )

                        if (count < 0) {
                            throw Exception("Microphone पढ़ने में समस्या")
                        }

                        if (count > 0) {
                            val samples = FloatArray(count) { i ->
                                shortBuffer[i] / 32768.0f
                            }

                            android.util.Log.d(
                                "MAYA_AUDIO",
                                "Audio samples received: $count"
                            )

                            stream.acceptWaveform(samples, 16000)

                            while (spotter.isReady(stream)) {
                                spotter.decode(stream)

                                val result = spotter.getResult(stream)

                                if (result.keyword.isNotEmpty()) {
                                    detected = true
                                    wakeListening = false
                                    conversationMode = true

                                    updateNotification("HELLO MAYA पहचाना गया!")
                                    break
                                }
                            }
                        }
                    }
                } finally {
                    stream.release()
                }

            } catch (e: Exception) {
                if (!serviceStopping) {
                    updateNotification(
                        "Voice समस्या: ${e.message ?: "अज्ञात त्रुटि"}"
                    )
                }
            } finally {
                wakeListening = false

                recorder?.let {
                    try {
                        if (it.recordingState ==
                            AudioRecord.RECORDSTATE_RECORDING
                        ) {
                            it.stop()
                        }
                    } catch (_: Exception) {}

                    it.release()
                }

                if (audioRecord === recorder) {
                    audioRecord = null
                }

                localSpotter?.release()
            }

            if (detected && !serviceStopping) {
                mainHandler.post {
                    startCommandListening()
                }
            }
        }.apply {
            name = "MAYA-WakeWord"
        }

        audioThread?.start()
    }

    private fun startCommandListening() {

        if (serviceStopping) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech Recognition उपलब्ध नहीं है")
            cancelConversationTimeout()
            conversationMode = false
            startWakeWordListening()
            return
        }

        updateNotification("जी, बोलिए। मैं सुन रही हूँ...")

        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        updateNotification("जी, बोलिए...")
                    }

                    override fun onBeginningOfSpeech() {
                        cancelConversationTimeout()
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        updateNotification("आपकी बात समझ रही हूँ...")
                        if (conversationMode) {
                            resetConversationTimeout()
                        }
                    }

                    override fun onError(error: Int) {
                        recognizer?.destroy()
                        recognizer = null

                        if (serviceStopping) return

                        updateNotification("फिर से सुनने के लिए तैयार हूँ")

                        mainHandler.postDelayed({
                            if (!serviceStopping) {
                                if (conversationMode) {
                                    startCommandListening()
                                } else {
                                    startWakeWordListening()
                                }
                            }
                        }, 1000)
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        val words = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()

                        recognizer?.destroy()
                        recognizer = null
                        cancelConversationTimeout()

                        if (!words.isNullOrBlank()) {

    val command = words.lowercase(Locale.ROOT)

    when {
        command.contains("youtube") ||
        command.contains("यूट्यूब") -> {
            if (openMayaApp("youtube")) {
                speakReply("YouTube खोल रही हूँ।")
            } else {
                speakReply("YouTube आपके फोन में नहीं मिला।")
            }
        }

        command.contains("chrome") ||
command.contains("क्रोम") -> {
    if (openMayaApp("chrome")) {
        speakReply("Chrome खोल रही हूँ।")
    } else {
        speakReply("Chrome आपके फोन में नहीं मिला।")
    }
}

command.contains("whatsapp") ||
command.contains("व्हाट्सएप") ||
command.contains("व्हाट्स ऐप") -> {
    if (openMayaApp("whatsapp")) {
        speakReply("WhatsApp खोल रही हूँ।")
    } else {
        speakReply("WhatsApp आपके फोन में नहीं मिला।")
    }
}
        command.contains("camera") ||
command.contains("कैमरा") -> {
    if (openMayaApp("camera")) {
        speakReply("कैमरा खोल रही हूँ।")
    } else {
        speakReply("कैमरा ऐप नहीं मिला।")
    }
}
        command.contains("settings") ||
        command.contains("सेटिंग") -> {
            if (openMayaApp("settings")) {
                speakReply("फोन की Settings खोल रही हूँ।")
            } else {
                speakReply("Settings नहीं खोल पाई।")
            }
        }

        else -> askMaya(words)
    }

} else {
    speakReply("माफ कीजिए, फिर से बोलिए।")
                        }
                    }

                    override fun onPartialResults(
                        partialResults: android.os.Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: android.os.Bundle?
                    ) {}
                }
            )

            val speechIntent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            recognizer?.startListening(speechIntent)

            if (conversationMode && conversationRunnable == null) {
                resetConversationTimeout()
            }

        } catch (e: Exception) {
            recognizer?.destroy()
            recognizer = null

            updateNotification("सुनने में समस्या हुई")

            mainHandler.postDelayed({
                if (!serviceStopping) {
                    if (conversationMode) {
                        startCommandListening()
                    } else {
                        startWakeWordListening()
                    }
                }
            }, 1000)
        }
    }

    
private fun openMayaApp(appName: String): Boolean {
    val packageName = when (appName) {
        "youtube" -> "com.google.android.youtube"
        "settings" -> "com.android.settings"
        "chrome" -> "com.android.chrome"
        "whatsapp" -> "com.whatsapp"
        "camera" -> return openDefaultCamera()
        else -> return false
    }

    return try {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }
}

private fun openDefaultCamera(): Boolean {
    return try {
        val intent = Intent(
            android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}


    return try {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }
    }
    private fun askMaya(question: String) {

        updateNotification("MAYA सोच रही है...")

        networkExecutor.execute {

            try {
                val connection =
                    URL(workerUrl).openConnection() as HttpURLConnection

                val reply: String

                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000
                    connection.doOutput = true

                    connection.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    val body = JSONObject()
                        .put("message", question)
                        .toString()

                    connection.outputStream.use {
                        it.write(body.toByteArray(Charsets.UTF_8))
                    }

                    val code = connection.responseCode

                    val responseStream =
                        if (code in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }

                    val response = responseStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: throw Exception("सर्वर से जवाब नहीं मिला")

                    val json = JSONObject(response)

                    if (code !in 200..299) {
                        throw Exception(
                            json.optString(
                                "details",
                                "AI सेवा में समस्या है"
                            )
                        )
                    }

                    reply = json.optString(
                        "reply",
                        "अभी जवाब नहीं मिला।"
                    )

                } finally {
                    connection.disconnect()
                }

                val cleanReply = reply
                    .replace(Regex("[*_#`~]"), "")
                    .replace(Regex("(?m)^\\s*[-•]\\s*"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                mainHandler.post {
                    publishReplyToApp(cleanReply)
                    speakReply(cleanReply)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    val errorMessage =
                        "कनेक्शन में समस्या है। ${e.message ?: "दोबारा कोशिश करें"}"

                    publishReplyToApp(errorMessage)
                    speakReply(errorMessage)
                }
            }
        }
    }

    private fun speakReply(text: String) {

        if (!ttsReady) {
            pendingSpeech = text
            updateNotification("MAYA जवाब तैयार कर रही है...")
            return
        }

        updateNotification("MAYA जवाब दे रही है...")

        speaker?.setPitch(1.05f)
        speaker?.setSpeechRate(0.93f)

        speaker?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "maya_reply"
        )
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

        publishToApp(message)
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
        serviceStopping = true
        wakeListening = false
        conversationMode = false
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }

        try {
            speaker?.stop()
            speaker?.shutdown()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
