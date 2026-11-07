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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DownloaderScreen { url, mode, cookiesPath, update ->
                startDownload(url, mode, cookiesPath, update)
            }
        }
    }

    private fun startDownload(
        url: String,
        mode: String,
        cookiesPath: String,
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
                val qjsPath = File(
                    applicationInfo.nativeLibraryDir,
                    "libqjs.so"
                ).absolutePath

                val result = module.callAttr(
                    "download",
                    url,
                    output.absolutePath,
                    progressFile.absolutePath,
                    mode,
                    qjsPath,
                    cookiesPath
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
        String, String, String, (String, Float, Boolean) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val cookiesFile = remember { File(context.filesDir, "instagram_cookies.txt") }
    var cookiesReady by remember { mutableStateOf(cookiesFile.exists() && cookiesFile.length() > 0) }
    var cookiesMessage by remember {
        mutableStateOf(if (cookiesReady) "Cookies Instagram tersimpan di perangkat." else "Cookies Instagram belum diimpor.")
    }
    val cookiePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val tempFile = File(context.filesDir, "instagram_cookies.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("File tidak dapat dibaca.")

                val rawText = tempFile.readText(Charsets.UTF_8)
                    .removePrefix("\uFEFF")
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .trim()
                if (rawText.isBlank()) {
                    tempFile.delete()
                    throw IOException("File cookies kosong.")
                }

                // Netscape cookie files are tab-delimited by specification, but
                // some exporters/editors replace tabs with spaces. Parse both.
                val normalized = if (rawText.startsWith("[") || rawText.startsWith("{")) {
                    val array = if (rawText.startsWith("[")) JSONArray(rawText)
                    else JSONObject(rawText).optJSONArray("cookies")
                        ?: throw IOException("JSON tidak berisi daftar cookies.")
                    val lines = mutableListOf("# Netscape HTTP Cookie File")
                    var hasInstagramSession = false
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val domain = item.optString("domain").trim()
                        val name = item.optString("name").trim()
                        val value = item.optString("value")
                        if (!domain.contains("instagram.com", ignoreCase = true) ||
                            name.isBlank() || value.isBlank()) continue
                        if (name.equals("sessionid", ignoreCase = true)) hasInstagramSession = true
                        val hostOnly = item.optBoolean("hostOnly", false)
                        val includeSubdomains = if (hostOnly) "FALSE" else "TRUE"
                        val path = item.optString("path", "/").ifBlank { "/" }
                        val secure = if (item.optBoolean("secure", false)) "TRUE" else "FALSE"
                        val expiryValue = item.optDouble("expirationDate", 0.0).toLong()
                        val expiry = expiryValue.toString()
                        val cookieDomain = if (item.optBoolean("httpOnly", false))
                            "#HttpOnly_$domain" else domain
                        lines.add(listOf(cookieDomain, includeSubdomains, path, secure, expiry, name, value).joinToString("\t"))
                    }
                    if (!hasInstagramSession) throw IOException("JSON terbaca, tetapi cookie sessionid Instagram tidak ditemukan. Ekspor cookies saat sudah login ke instagram.com.")
                    lines.joinToString("\n")
                } else {
                    val lines = rawText.lines()
                    val cookieRows = lines.mapNotNull { original ->
                        val line = original.trim()
                        if (line.isBlank() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) return@mapNotNull null
                        val data = if (line.startsWith("#HttpOnly_")) line.removePrefix("#HttpOnly_") else line
                        val fields = data.split(Regex("\\s+"), limit = 7)
                        if (fields.size < 7) null else fields
                    }
                    val hasInstagramSession = cookieRows.any { fields ->
                        fields[0].contains("instagram.com", ignoreCase = true) &&
                            fields[5].equals("sessionid", ignoreCase = true) &&
                            fields[6].isNotBlank()
                    }
                    if (!hasInstagramSession) {
                        val hasNetscapeHeader = lines.any {
                            it.contains("Netscape HTTP Cookie File", ignoreCase = true) ||
                            it.contains("HTTP Cookie File", ignoreCase = true)
                        }
                        if (!hasNetscapeHeader && cookieRows.isEmpty()) {
                            throw IOException("Format file tidak dikenali. Pilih file cookies.txt hasil ekspor, bukan halaman HTML atau file ZIP.")
                        }
                        throw IOException("File terbaca sebagai TXT cookies, tetapi sessionid Instagram tidak ditemukan. Buka instagram.com, pastikan login, lalu ekspor ulang khusus domain instagram.com.")
                    }
                    val output = mutableListOf("# Netscape HTTP Cookie File")
                    for (fields in cookieRows) {
                        if (fields[0].contains("instagram.com", ignoreCase = true)) {
                            output.add(fields.joinToString("\t"))
                        }
                    }
                    output.joinToString("\n")
                }
                cookiesFile.writeText(normalized, Charsets.UTF_8)
                tempFile.delete()
                cookiesReady = true
                cookiesMessage = "Cookies Instagram diterima (sessionid ditemukan) dan tersimpan privat di perangkat."
            } catch (e: Exception) {
                cookiesMessage = "Gagal impor: ${e.message ?: "file tidak valid"}"
            }
        }
    }

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

            if (platform == "Instagram") {
                Spacer(Modifier.height(14.dp))
                Text("LOGIN INSTAGRAM (OPSIONAL)", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(cookiesMessage, color = Color.LightGray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { cookiePicker.launch(arrayOf("*/*")) },
                        enabled = !busy
                    ) { Text(if (cookiesReady) "GANTI COOKIES" else "IMPOR COOKIES") }
                    if (cookiesReady) {
                        OutlinedButton(
                            onClick = {
                                cookiesFile.delete()
                                cookiesReady = false
                                cookiesMessage = "Cookies Instagram sudah dihapus dari perangkat."
                            },
                            enabled = !busy
                        ) { Text("HAPUS") }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    onDownload(url.trim(), mode, if (cookiesReady) cookiesFile.absolutePath else "") { text, value, active ->
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
            Text("VIDEO DOWNLOADER HUB • v1.5", color = Color.Gray, fontSize = 11.sp)
        }
    }
}
