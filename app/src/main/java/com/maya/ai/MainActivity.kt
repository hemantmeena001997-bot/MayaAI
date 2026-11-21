package com.maya.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val messageState =
        mutableStateOf("नमस्ते! मैं MAYA हूँ। कुछ भी पूछिए।")

    private var recognizer: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var ttsReady = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val workerUrl =
        "https://maya-ai-brain.hemantmeena001997.workers.dev/"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else messageState.value = "माइक्रोफोन की अनुमति जरूरी है।"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speaker = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = speaker?.setLanguage(Locale("hi", "IN"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
    messageState.value = "जी, बोलिए। मैं सुन रही हूँ..."
                }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    messageState.value = "आपकी बात समझ रही हूँ..."
                }

                override fun onError(error: Int) {
                    messageState.value = "आवाज़ समझ नहीं आई। दोबारा कोशिश करें।"
                }

                override fun onResults(results: Bundle?) {
                    val words = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    if (!words.isNullOrBlank()) {
                        askMaya(words)
                    } else {
                        messageState.value = "कृपया फिर से बोलिए।"
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        setContent {
            MayaScreen(
                message = messageState.value,
                onTalk = { checkMicrophonePermission() }
            )
        }
    }

    private fun checkMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (recognizer == null) {
            messageState.value = "Speech Recognition उपलब्ध नहीं है।"
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
        }

        try {
            messageState.value = "माइक्रोफोन शुरू हो रहा है..."
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            messageState.value = "माइक्रोफोन शुरू नहीं हो पाया।"
        }
    }

    private fun askMaya(question: String) {
        messageState.value = "MAYA सोच रही है..."

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    val connection =
                        URL(workerUrl).openConnection() as HttpURLConnection

                    try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 15000
                        connection.readTimeout = 30000
                        connection.doOutput = true
                        connection.setRequestProperty(
                            "Content-Type", "application/json"
                        )

                        val body = JSONObject()
                            .put("message", question)
                            .toString()

                        connection.outputStream.use {
                            it.write(body.toByteArray(Charsets.UTF_8))
                        }

                        val code = connection.responseCode
                        val stream = if (code in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }

                        val result = stream.bufferedReader().use {
                            it.readText()
                        }

                        if (code !in 200..299) {
                            throw Exception(
                                JSONObject(result).optString(
                                    "details", "AI सेवा में समस्या है।"
                                )
                            )
                        }

                        JSONObject(result).optString(
                            "reply", "अभी जवाब नहीं मिला।"
                        )
                    } finally {
                        connection.disconnect()
                    }
                }

                if (ttsReady) {
    val lowerReply = reply.lowercase()

    val happy = listOf("बहुत अच्छा", "शानदार", "खुशी", "वाह")
        .any { lowerReply.contains(it) }

    val caring = listOf("चिंता", "परेशान", "दुख", "माफ़")
        .any { lowerReply.contains(it) }

    speaker?.setPitch(
        when {
            happy -> 1.15f
            caring -> 0.95f
            else -> 1.05f
        }
    )

    speaker?.setSpeechRate(
        when {
            happy -> 0.98f
            caring -> 0.88f
            else -> 0.93f
        }
    )

    speaker?.speak(
        cleanReply,
        TextToSpeech.QUEUE_FLUSH,
        null,
        "maya_reply"
    )
                }
            } catch (e: Exception) {
                messageState.value =
                    "कनेक्शन में समस्या: ${e.message ?: "दोबारा कोशिश करें"}"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        recognizer?.destroy()
        speaker?.stop()
        speaker?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun MayaScreen(message: String, onTalk: () -> Unit) {
    val purple = Color(0xFF7C4DFF)
    val blue = Color(0xFF2979FF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF100B2B),
                        Color(0xFF172554),
                        Color(0xFF090E20)
                    )
                )
            )
            .padding(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(35.dp))

            Text("MAYA AI", color = Color.White, fontSize = 32.sp)
            Text(
                "YOUR PERSONAL AI ASSISTANT",
                color = Color.LightGray,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(55.dp))

            Box(
                modifier = Modifier
                    .size(155.dp)
                    .background(
                        Brush.linearGradient(listOf(purple, blue)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("M", color = Color.White, fontSize = 75.sp)
            }

            Spacer(Modifier.height(28.dp))

            Text("Hello, I'm Maya", color = Color.White, fontSize = 25.sp)
            Text(
                "मैं आपकी सहायता के लिए तैयार हूँ",
                color = Color.LightGray,
                fontSize = 15.sp
            )

            Spacer(Modifier.height(35.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF222B50)
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(20.dp),
                    color = Color.White,
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(25.dp))

            Button(
                onClick = onTalk,
                colors = ButtonDefaults.buttonColors(
                    containerColor = purple
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp)
            ) {
                Text("🎙 TALK TO MAYA", fontSize = 17.sp)
            }

            Spacer(Modifier.height(15.dp))

            Text(
                "GEMINI AI • VOICE ASSISTANT",
                color = Color.LightGray,
                fontSize = 11.sp
            )
        }
    }
}

