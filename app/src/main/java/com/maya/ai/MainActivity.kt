
package com.maya.ai
import rikka.shizuku.Shizuku

import android.Manifest
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
        companion object {
        init {
            System.loadLibrary("maya_native")
        }
    }

    external fun getNativeMessage(): String
        external fun generateOffline(modelPath: String, userPrompt: String): String

    private val messageState = mutableStateOf(
        "नमस्ते! मैं MAYA हूँ। कुछ भी पूछिए।"
    )

    private var receiverRegistered = false
        private var offlineModelPath: String = ""

private val modelPicker =
    registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                val input = contentResolver.openInputStream(uri)
    ?: throw Exception("Model file open nahi hui")

val outputFile = java.io.File(filesDir, "maya-model.gguf")

input.use { source ->
    outputFile.outputStream().use { destination ->
        source.copyTo(destination)
    }
}

if (!outputFile.exists() || outputFile.length() < 100_000_000L) {
    throw Exception("Model file incomplete hai")
}

offlineModelPath = outputFile.absolutePath

messageState.value =
    "MAYA Offline Model तैयार है: ${outputFile.length() / (1024 * 1024)} MB"
            } catch (e: Exception) {
                messageState.value = "Model error: ${e.message}"
            }
        }
    }

    private val mayaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MayaListeningService.ACTION_UI_UPDATE) {
                val message = intent.getStringExtra(
                    MayaListeningService.EXTRA_MESSAGE
                )
                if (!message.isNullOrBlank()) {
                    messageState.value = message
                }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startMayaService()
            } else {
                messageState.value = "माइक्रोफोन की अनुमति जरूरी है।"
            }
        }

    private val shizukuPermissionListener =
    Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001) {
            messageState.value =
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    "MAYA को Shizuku की अनुमति मिल गई है।"
                } else {
                    "Shizuku की अनुमति नहीं मिली।"
                }
        }
    }
        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                Shizuku.addRequestPermissionResultListener(
    shizukuPermissionListener
)

if (Shizuku.pingBinder()) {
    if (Shizuku.checkSelfPermission() ==
        PackageManager.PERMISSION_GRANTED
    ) {
        messageState.value = "Shizuku MAYA से जुड़ा हुआ है।"
    } else {
        Shizuku.requestPermission(1001)
    }
} else {
    messageState.value = "पहले Shizuku Service चालू करें।"
}

    val savedReply = getSharedPreferences(
        "maya_data",
        MODE_PRIVATE
    ).getString("last_reply", null)

    if (!savedReply.isNullOrBlank()) {
        messageState.value = savedReply
    }

    setContent {
        MayaScreen(
            message = messageState.value,
            onTalk = { checkMicrophonePermission() },
            onSelectModel = {
                modelPicker.launch(arrayOf("*/*"))
            },
            onAskOffline = { question ->
    askOfflineMaya(question)
},
onDeviceInfo = {
    messageState.value =
        MayaAndroidControl.getDeviceInfo()
}
        )
    }

    checkMicrophonePermission()
    }

    override fun onDestroy() {
    Shizuku.removeRequestPermissionResultListener(
        shizukuPermissionListener
    )
    super.onDestroy()
    }
        override fun onStart() {
        super.onStart()

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                mayaReceiver,
                IntentFilter(MayaListeningService.ACTION_UI_UPDATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(mayaReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun checkMicrophonePermission() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startMayaService()
        } else {
            permissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun startMayaService() {
        try {
            val serviceIntent = Intent(
                this,
                MayaListeningService::class.java
            )

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            messageState.value = "MAYA Voice Service चालू है।"

        } catch (e: Exception) {
            messageState.value =
                "MAYA शुरू नहीं हो पाई: ${e.message}"
        }
    }
    private fun askOfflineMaya(question: String) {
    if (offlineModelPath.isBlank()) {
        messageState.value = "पहले Offline Model चुनिए।"
        return
    }

    messageState.value = "MAYA Offline में जवाब तैयार कर रही है..."

    Thread {
        val reply = try {
            generateOffline(offlineModelPath, question)
        } catch (e: Exception) {
            "Offline AI Error: ${e.message}"
        }

        runOnUiThread {
            messageState.value = reply
        }
    }.start()
    }
}

@Composable
fun MayaScreen(
    message: String,
    onTalk: () -> Unit,
    onSelectModel: () -> Unit,
    onAskOffline: (String) -> Unit,
    onDeviceInfo: () -> Unit
) {

    val purple = Color(0xFF7C4DFF)
    val blue = Color(0xFF2979FF)
    var offlineQuestion by remember { mutableStateOf("") }

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
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
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

            Text(
                "Hello, I'm Maya",
                color = Color.White,
                fontSize = 25.sp
            )

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
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
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
            Spacer(Modifier.height(12.dp))

Button(
    onClick = onSelectModel,
    colors = ButtonDefaults.buttonColors(
        containerColor = blue
    ),
    modifier = Modifier
        .fillMaxWidth()
        .height(55.dp)
) {
    Text("📂 SELECT OFFLINE MODEL", fontSize = 16.sp)
}
Spacer(Modifier.height(12.dp))

OutlinedTextField(
    value = offlineQuestion,
    onValueChange = { offlineQuestion = it },
    label = { Text("MAYA से सवाल पूछें") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = false
)

Button(
    onClick = {
        if (offlineQuestion.isNotBlank()) {
            onAskOffline(offlineQuestion)
        }
    },
    modifier = Modifier.fillMaxWidth()
) {
    Text("ASK MAYA OFFLINE")
}
Spacer(Modifier.height(12.dp))

Button(
    onClick = onDeviceInfo,
    modifier = Modifier
        .fillMaxWidth()
        .height(55.dp),
    colors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF00897B)
    )
) {
    Text("📱 PHONE INFORMATION")
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
