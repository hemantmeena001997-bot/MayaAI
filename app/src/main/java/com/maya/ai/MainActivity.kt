package com.maya.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MayaScreen()
        }
    }
}

@Composable
fun MayaScreen() {
    val purple = Color(0xFF7C4DFF)
    val blue = Color(0xFF2979FF)

    var message by remember {
        mutableStateOf("नमस्ते! मैं MAYA हूँ। आपकी AI साथी।")
    }

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
            Spacer(modifier = Modifier.height(35.dp))

            Text(
                text = "MAYA AI",
                color = Color.White,
                fontSize = 32.sp
            )

            Text(
                text = "YOUR PERSONAL AI ASSISTANT",
                color = Color.LightGray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(55.dp))

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

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Hello, I'm Maya",
                color = Color.White,
                fontSize = 25.sp
            )

            Text(
                text = "मैं आपकी सहायता के लिए तैयार हूँ",
                color = Color.LightGray,
                fontSize = 15.sp
            )

            Spacer(modifier = Modifier.height(35.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF222B50)
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(20.dp),
                    color = Color.White,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(25.dp))

            Button(
                onClick = {
                    message = "जी, मैं सुन रही हूँ। बताइए क्या करना है?"
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = purple
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp)
            ) {
                Text("🎙  TALK TO MAYA", fontSize = 17.sp)
            }

            Spacer(modifier = Modifier.height(15.dp))

            Text(
                text = "ONLINE  •  OFFLINE  •  SMART ASSISTANT",
                color = Color.LightGray,
                fontSize = 11.sp
            )
        }
    }
}

