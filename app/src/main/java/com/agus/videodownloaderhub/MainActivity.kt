package com.agus.videodownloaderhub

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import com.chaquo.python.Python
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DownloaderScreen { url, mode, update ->
                startDownload(url, mode, update)
            }
        }
    }

    private fun startDownload(
        url: String,
        mode: String,
        update: (String, Float, Boolean) -> Unit
    ) {
        val output = File(cacheDir, "video_downloads").apply { mkdirs() }
        val progressFile = File(cacheDir, "download_progress.json")
        progressFile.delete()

        update("Menyiapkan mesin unduhan...", 0f, true)

        val poller = object : Runnable {
            override fun run() {
                if (progressFile.exists()) {
                    try {
                        val data = JSONObject(progressFile.readText())
                        update(
                            data.optString("status", "Mengunduh..."),
                            data.optDouble("percent", 0.0).toFloat().coerceIn(0f, 100f),
                            true
                        )
                    } catch (_: Exception) { }
                }
                handler.postDelayed(this, 600)
            }
        }
        handler.post(poller)

        executor.execute {
            try {
                val module = Python.getInstance().getModule("downloader")
                val result = module.callAttr(
                    "download",
                    url,
                    output.absolutePath,
                    progressFile.absolutePath,
                    mode
                ).toString()

                val saved = saveToDownloads(File(result))
                handler.removeCallbacks(poller)
                runOnUiThread {
                    update("Berhasil disimpan: $saved", 100f, false)
                }
            } catch (e: Throwable) {
                handler.removeCallbacks(poller)
                runOnUiThread {
                    update(
                        "Gagal: ${e.message ?: "Periksa tautan dan koneksi internet."}",
                        0f,
                        false
                    )
                }
            }
        }
    }

    private fun saveToDownloads(source: File): String {
        if (!source.exists()) throw IOException("File unduhan tidak ditemukan.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mime = when (source.extension.lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                else -> "application/octet-stream"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Video Downloader Hub"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri: Uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IOException("Tidak bisa membuat file di folder Download.")

            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IOException("Tidak bisa menulis file.")

                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, done, null, null)
                source.delete()
                return "Download/Video Downloader Hub/${source.name}"
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        }

        val folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Folder penyimpanan tidak tersedia.")
        val target = File(folder, source.name)
        source.copyTo(target, overwrite = true)
        source.delete()
        return target.absolutePath
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

@Composable
fun DownloaderScreen(
    onDownload: (
        String, String, (String, Float, Boolean) -> Unit
    ) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Hub") }
    var message by remember { mutableStateOf("Tempel tautan video untuk memulai.") }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }

    val platform = when {
        url.contains("youtube.com") || url.contains("youtu.be") -> "YouTube"
        url.contains("tiktok.com") -> "TikTok"
        url.contains("instagram.com") -> "Instagram"
        else -> ""
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101116))
                .padding(22.dp)
        ) {
            Text("VIDEO DOWNLOADER", color = Color.White, fontSize = 25.sp)
            Text("HUB", color = Color(0xFF8B7CFF), fontSize = 25.sp)
            Spacer(Modifier.height(8.dp))
            Text("Mesin unduhan terintegrasi di aplikasi", color = Color.LightGray)

            Spacer(Modifier.height(25.dp))
            Text("PILIH VERSI", color = Color.Gray, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Lite", "Hub").forEach {
                    Button(
                        onClick = { mode = it },
                        enabled = !busy,
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
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White, fontSize = 14.sp
                    ),
                    decorationBox = { inner ->
                        if (url.isEmpty()) Text("https://...", color = Color.Gray)
                        inner()
                    }
                )
            }

            Spacer(Modifier.height(15.dp))
            Text(
                if (platform.isNotEmpty()) "Platform terdeteksi: $platform"
                else "Tempel tautan video yang didukung",
                color = Color(0xFFB7ACFF)
            )

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    onDownload(url.trim(), mode) { text, value, active ->
                        message = text
                        progress = value
                        busy = active
                    }
                },
                enabled = !busy && url.startsWith("http"),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6555E8)
                )
            ) {
                Text(if (busy) "SEDANG MENGUNDUH..." else "MULAI UNDUH")
            }

            if (busy || progress > 0f) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF8B7CFF)
                )
                Text("${progress.toInt()}%", color = Color.LightGray)
            }

            Spacer(Modifier.height(14.dp))
            Text(message, color = Color.LightGray, fontSize = 13.sp)

            Spacer(Modifier.weight(1f))
            Text("VIDEO DOWNLOADER HUB • v1.1", color = Color.Gray, fontSize = 11.sp)
        }
    }
}
