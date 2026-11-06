package com.agus.videodownloaderhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DownloaderScreen() }
    }
}

@Composable
fun DownloaderScreen() {
    var url by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Hub") }
    var message by remember { mutableStateOf("Tempel tautan video untuk memulai") }

    val platforms = listOf("YouTube", "TikTok", "Instagram")

    val platform = when {
        url.contains("youtube.com") || url.contains("youtu.be") -> "YouTube"
        url.contains("tiktok.com") -> "TikTok"
        url.contains("instagram.com") -> "Instagram"
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101116))
            .padding(22.dp)
    ) {
        Text("VIDEO DOWNLOADER", color = Color.White, fontSize = 25.sp)
        Text("HUB", color = Color(0xFF8B7CFF), fontSize = 25.sp)
        Spacer(Modifier.height(8.dp))
        Text("Download video dalam satu aplikasi",
            color = Color.LightGray, fontSize = 13.sp)

        Spacer(Modifier.height(25.dp))

        Text("PILIH VERSI", color = Color.Gray, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Lite", "Hub").forEach {
                Button(
                    onClick = { mode = it },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (mode == it)
                            Color(0xFF6555E8) else Color(0xFF292A33)
                    )
                ) { Text(it) }
            }
        }

        Spacer(Modifier.height(25.dp))
        Text("TEMPEL LINK VIDEO", color = Color.White)
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252630), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White, fontSize = 14.sp
                ),
                decorationBox = { inner ->
                    if (url.isEmpty()) {
                        Text("https://...", color = Color.Gray)
                    }
                    inner()
                }
            )
        }

        Spacer(Modifier.height(15.dp))

        Text(
            if (platform.isNotEmpty()) "Platform terdeteksi: $platform"
            else "Platform: belum terdeteksi",
            color = Color(0xFFB7ACFF)
        )

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = {
                message = if (platform.isNotEmpty())
                    "$platform terdeteksi. Mesin unduhan belum terhubung."
                else "Masukkan tautan YouTube, TikTok, atau Instagram."
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6555E8)
            )
        ) {
            Text("PROSES LINK")
        }

        Spacer(Modifier.height(15.dp))
        Text(message, color = Color.LightGray, fontSize = 13.sp)

        Spacer(Modifier.weight(1f))
        Text("VIDEO DOWNLOADER HUB • v1.0",
            color = Color.Gray, fontSize = 11.sp)
    }
}
