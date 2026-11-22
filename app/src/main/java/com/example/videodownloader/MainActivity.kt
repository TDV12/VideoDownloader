package com.example.videodownloader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

    private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

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

            if (isDouyin(url)) {
                showDouyinWebViewDialog(url) { videoStreamUrl, _ ->
                    tvStatus.text = "Đã bắt được luồng video Douyin!"
                    tvDirectLink.text = "Link gốc:\n$videoStreamUrl"
                }
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (isTikTok(url)) {
                        val (title, playUrl) = fetchTikWmApi(url)
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Tiêu đề: $title"
                            tvDirectLink.text = "Link gốc (Không logo):\n$playUrl"
                        }
                    } else {
                        val request = YoutubeDLRequest(url)
                        request.addOption("--user-agent", desktopUserAgent)
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

            // Luồng 1: Douyin -> Bật WebView bắt luồng video trực tiếp
            if (isDouyin(url)) {
                tvStatus.text = "Đang mở Douyin để bắt link video..."
                showDouyinWebViewDialog(url) { videoStreamUrl, cookies ->
                    downloadDouyinStream(videoStreamUrl, cookies)
                }
                return@setOnClickListener
            }

            // Luồng 2 & 3: TikTok (API tikwm) & YouTube/Facebook (yt-dlp)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val tempDir = File(cacheDir, "dl_temp").apply {
                        if (exists()) deleteRecursively()
                        mkdirs()
                    }

                    val downloadedFile: File? = if (isTikTok(url)) {
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Đang bóc tách video TikTok không logo..."
                        }
                        val (title, playUrl) = fetchTikWmApi(url)
                        val safeName = title.replace(Regex("[^a-zA-Z0-9\\u00C0-\\u1EF9\\s_-]"), "")
                            .trim()
                            .take(50)
                            .ifEmpty { "tiktok_${System.currentTimeMillis()}" }
                        val destFile = File(tempDir, "$safeName.mp4")

                        downloadDirectFile(playUrl, destFile) { progress ->
                            runOnUiThread {
                                progressBar.progress = progress
                                tvStatus.text = "Đang tải: $progress%"
                            }
                        }
                        destFile
                    } else {
                        val request = YoutubeDLRequest(url)
                        request.addOption("--user-agent", desktopUserAgent)
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

    // Bật cửa sổ WebView cho Douyin để tự bắt luồng video đang phát
    @SuppressLint("SetJavaScriptEnabled")
    private fun showDouyinWebViewDialog(url: String, onVideoFound: (String, String?) -> Unit) {
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1000
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = mobileUserAgent
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        var found = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Đang mở Douyin (Kéo Captcha nếu có)")
            .setView(webView)
            .setNegativeButton("Hủy") { d, _ ->
                found = true
                webView.destroy()
                d.dismiss()
                tvStatus.text = "Đã hủy tải Douyin."
            }
            .setCancelable(false)
            .create()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (!found && isDouyinVideoStream(reqUrl)) {
                    found = true
                    val cookies = cookieManager.getCookie(reqUrl) ?: cookieManager.getCookie("https://www.douyin.com")
                    runOnUiThread {
                        webView.destroy()
                        dialog.dismiss()
                        onVideoFound(reqUrl, cookies)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        dialog.show()
        webView.loadUrl(url)
    }

    private fun downloadDouyinStream(videoStreamUrl: String, cookies: String?) {
        tvStatus.text = "Đã bắt được video! Đang tải về máy..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "dl_temp").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }
                val destFile = File(tempDir, "douyin_${System.currentTimeMillis()}.mp4")
                downloadDirectFile(
                    fileUrl = videoStreamUrl,
                    destFile = destFile,
                    cookies = cookies,
                    referer = "https://www.douyin.com/",
                    customUa = mobileUserAgent
                ) { progress ->
                    runOnUiThread {
                        progressBar.progress = progress
                        tvStatus.text = "Đang tải Douyin: $progress%"
                    }
                }

                if (destFile.exists() && destFile.length() > 0) {
                    saveVideoToGallery(destFile)
                    destFile.delete()
                    withContext(Dispatchers.Main) {
                        progressBar.progress = 100
                        tvStatus.text = "Tải xong Douyin! Đã lưu vào Thư viện (Movies/VideoDownloader)."
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "File tải về bị rỗng."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Lỗi tải Douyin: ${e.message}"
                }
            }
        }
    }

    private fun isDouyinVideoStream(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp")) {
            return false
        }
        return lower.contains("douyinvod.com") ||
                lower.contains("aweme/v1/play") ||
                lower.contains("mime_type=video_mp4")
    }

    private fun isTikTok(url: String): Boolean = url.lowercase().contains("tiktok.com")
    private fun isDouyin(url: String): Boolean = url.lowercase().contains("douyin.com")

    // Gọi API tikwm.com lấy link MP4 không logo cho TikTok quốc tế
    private fun fetchTikWmApi(videoUrl: String): Pair<String, String> {
        val encodedUrl = URLEncoder.encode(videoUrl, "UTF-8")
        val apiUrl = "
