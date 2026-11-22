package com.example.videodownloader

import android.annotation.SuppressLint
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

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

        // Khởi tạo yt-dlp và FFmpeg ngầm khi mở app
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
                    val request = YoutubeDLRequest(url)
                    request.addOption("--user-agent", userAgent)

                    if (url.contains("douyin.com", ignoreCase = true)) {
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Đang tự động lấy Cookie Douyin..."
                        }
                        val cookieFile = fetchDouyinCookies(url)
                        if (cookieFile != null) {
                            request.addOption("--cookies", cookieFile.absolutePath)
                        }
                    }

                    val info = YoutubeDL.getInstance().getInfo(request)
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Tiêu đề: ${info.title}"
                        tvDirectLink.text = "Link gốc:\n${info.url}"
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

            tvStatus.text = "Đang chuẩn bị tải..."
            progressBar.progress = 0

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val tempDir = File(cacheDir, "dl_temp").apply {
                        if (exists()) deleteRecursively()
                        mkdirs()
                    }

                    val request = YoutubeDLRequest(url)
                    request.addOption("--user-agent", userAgent)

                    if (url.contains("douyin.com", ignoreCase = true)) {
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Đang tự động lấy Cookie Douyin..."
                        }
                        val cookieFile = fetchDouyinCookies(url)
                        if (cookieFile != null) {
                            request.addOption("--cookies", cookieFile.absolutePath)
                        }
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Đang tải video Douyin..."
                        }
                    }

                    request.addOption("-o", File(tempDir, "%(title)s.%(ext)s").absolutePath)
                    request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                    request.addOption("--merge-output-format", "mp4")

                    YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                        runOnUiThread {
                            progressBar.progress = progress.toInt()
                            tvStatus.text = "Đang tải: ${progress.toInt()}%"
                        }
                    }

                    val downloadedFile = tempDir.listFiles()?.firstOrNull()
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

    // Tự động mở ngầm trang Douyin bằng WebView để lấy Cookie mới lưu ra file chuẩn Netscape
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchDouyinCookies(url: String): File? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(12000L) {
            suspendCancellableCoroutine { cont ->
                val webView = WebView(this@MainActivity)
                var isFinished = false

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = userAgent

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (isFinished) return
                        // Chờ 3 giây để JavaScript của Douyin tạo đủ Cookie
                        view?.postDelayed({
                            if (isFinished) return@postDelayed
                            isFinished = true

                            val rawCookies = listOfNotNull(
                                cookieManager.getCookie("https://www.douyin.com"),
                                loadedUrl?.let { cookieManager.getCookie(it) }
                            ).joinToString("; ")

                            val cookieFile = File(cacheDir, "douyin_cookies.txt")
                            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
                            val seen = mutableSetOf<String>()

                            rawCookies.split(";").forEach { item ->
                                val parts = item.trim().split("=", limit = 2)
                                if (parts.size == 2 && seen.add(parts[0])) {
                                    sb.append(".douyin.com\tTRUE\t/\tFALSE\t2147483647\t${parts[0]}\t${parts[1]}\n")
                                }
                            }
                            cookieFile.writeText(sb.toString())
                            webView.destroy()

                            if (cont.isActive) cont.resume(cookieFile)
                        }, 3000L)
                    }
                }

                cont.invokeOnCancellation {
                    isFinished = true
                    webView.destroy()
                }

                webView.loadUrl(url)
            }
        }
    }

    // Tự động tách lấy link https://... khi dán văn bản có chữ thừa từ Douyin/TikTok
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
