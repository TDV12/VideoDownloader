package com.example.videodownloader

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvDirectLink: TextView
    private lateinit var progressBar: ProgressBar

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        tvStatus = findViewById(R.id.tvStatus)
        tvDirectLink = findViewById(R.id.tvDirectLink)
        progressBar = findViewById(R.id.progressBar)

        val btnGetInfo = findViewById<Button>(R.id.btnGetInfo)
        val btnDownload = findViewById<Button>(R.id.btnDownload)
        val btnUpdate = findViewById<Button>(R.id.btnUpdate)

        // Khởi tạo yt-dlp và FFmpeg ngầm cho YouTube/Facebook
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(this@MainActivity)
                FFmpeg.getInstance().init(this@MainActivity)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Lỗi khởi tạo: ${e.message}"
                }
            }
        }

        // Nút 1: Lấy tiêu đề và link tải trực tiếp
        btnGetInfo.setOnClickListener {
            val url = extractUrl(etUrl.text.toString())
            if (url.isEmpty()) return@setOnClickListener

            tvStatus.text = "Đang phân tích link..."
            tvDirectLink.text = ""

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (isTikTokOrDouyin(url)) {
                        val (title, playUrl) = fetchTikWmApi(url)
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Tiêu đề: $title"
                            tvDirectLink.text = "Link gốc (Không logo):\n$playUrl"
                        }
                    } else {
                        val request = YoutubeDLRequest(url)
                        request.addOption("--user-agent", userAgent)
                        val info = YoutubeDL.getInstance().getInfo(request)
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Tiêu đề: ${info.title}"
                            tvDirectLink.text = "Link gốc:\n${info.url}"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Lỗi lấy link: ${e.message}"
                    }
                }
            }
        }

        // Nút 2: Tải video lưu thẳng vào Thư viện ảnh
        btnDownload.setOnClickListener {
            val url = extractUrl(etUrl.text.toString())
            if (url.isEmpty()) return@setOnClickListener

            tvStatus.text = "Đang bắt đầu tải..."
            progressBar.progress = 0

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val tempDir = File(cacheDir, "dl_temp").apply {
                        if (exists()) deleteRecursively()
                        mkdirs()
                    }

                    val downloadedFile: File? = if (isTikTokOrDouyin(url)) {
                        // Dùng API ngoài cho TikTok & Douyin
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Đang bóc tách video không logo..."
                        }
                        val (title, playUrl) = fetchTikWmApi(url)
                        val safeName = title.replace(Regex("[^a-zA-Z0-9\\u00C0-\\u1EF9\\s_-]"), "")
                            .trim()
                            .take(50)
                            .ifEmpty { "video_${System.currentTimeMillis()}" }
                        val destFile = File(tempDir, "$safeName.mp4")

                        downloadDirectFile(playUrl, destFile) { progress ->
                            runOnUiThread {
                                progressBar.progress = progress
                                tvStatus.text = "Đang tải: $progress%"
                            }
                        }
                        destFile
                    } else {
                        // Dùng yt-dlp cho YouTube, Facebook và các trang khác
                        val request = YoutubeDLRequest(url)
                        request.addOption("--user-agent", userAgent)
                        request.addOption("-o", File(tempDir, "%(title)s.%(ext)s").absolutePath)
                        request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                        request.addOption("--merge-output-format", "mp4")

                        YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                            runOnUiThread {
                                progressBar.progress = progress.toInt()
                                tvStatus.text = "Đang tải: ${progress.toInt()}%"
                            }
                        }
                        tempDir.listFiles()?.firstOrNull()
                    }

                    if (downloadedFile != null && downloadedFile.exists()) {
                        saveVideoToGallery(downloadedFile)
                        downloadedFile.delete()
                        withContext(Dispatchers.Main) {
                            progressBar.progress = 100
                            tvStatus.text = "Tải xong! Đã lưu vào Thư viện (Movies/VideoDownloader)."
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Không tìm thấy file sau khi tải."
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Lỗi tải video: ${e.message}"
                    }
                }
            }
        }

        // Nút 3: Cập nhật lõi yt-dlp bản Nightly mới nhất hàng ngày
        btnUpdate.setOnClickListener {
            tvStatus.text = "Đang cập nhật lõi yt-dlp (Nightly)..."
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val status = YoutubeDL.getInstance().updateYoutubeDL(
                        this@MainActivity,
                        YoutubeDL.UpdateChannel._NIGHTLY
                    )
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Cập nhật hoàn tất: $status"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Lỗi cập nhật: ${e.message}"
                    }
                }
            }
        }
    }

    private fun isTikTokOrDouyin(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com") || lower.contains("douyin.com")
    }

    // Gọi API tikwm.com lấy link MP4 không logo cho TikTok và Douyin
    private fun fetchTikWmApi(videoUrl: String): Pair<String, String> {
        val encodedUrl = URLEncoder.encode(videoUrl, "UTF-8")
        val apiUrl = "https://www.tikwm.com/api/?url=$encodedUrl&hd=1"
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", userAgent)
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        if (json.optInt("code", -1) != 0) {
            throw Exception(json.optString("msg", "Không thể lấy video từ API"))
        }

        val data = json.getJSONObject("data")
        val title = data.optString("title", "Video")
        val playUrl = data.optString("hdplay").ifEmpty { data.optString("play") }
        if (playUrl.isEmpty()) {
            throw Exception("Không tìm thấy link video (có thể đây là bài đăng ảnh)")
        }
        return Pair(title, playUrl)
    }

    // Tải trực tiếp file MP4 từ link API về máy
    private fun downloadDirectFile(fileUrl: String, destFile: File, onProgress: (Int) -> Unit) {
        val conn = URL(fileUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.connect()

        val totalBytes = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                        onProgress(progress)
                    }
                }
            }
        }
    }

    // Tự động tách lấy link https://... khi dán văn bản có chữ thừa
    private fun extractUrl(input: String): String {
        val regex = Regex("https?://[^\\s]+")
        return regex.find(input)?.value ?: input.trim()
    }

    private fun saveVideoToGallery(videoFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoDownloader")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outStream ->
                    FileInputStream(videoFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } else {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val destFile = File(moviesDir, videoFile.name)
            videoFile.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)
        }
    }
}
