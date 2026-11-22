package com.example.videodownloader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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

    private val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

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

        // Nút 1: Lấy link gốc
        btnGetInfo.setOnClickListener {
            val url = extractUrl(etUrl.text.toString())
            if (url.isEmpty()) return@setOnClickListener

            if (isDouyin(url)) {
                openDouyinWebDownloader(url)
                return@setOnClickListener
            }

            tvStatus.text = "Đang phân tích link..."
            tvDirectLink.text = ""

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (title, playUrl) = if (isTikTok(url)) {
                        fetchTikWmApi(url)
                    } else {
                        val req = YoutubeDLRequest(url)
                        req.addOption("--user-agent", desktopUa)
                        val info = YoutubeDL.getInstance().getInfo(req)
                        Pair(info.title ?: "Video", info.url ?: "")
                    }
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Tiêu đề: $title"
                        tvDirectLink.text = "Link gốc:\n$playUrl"
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

            // Luồng 1: Douyin -> Mở trình duyệt tải Douyin ngay trong app
            if (isDouyin(url)) {
                openDouyinWebDownloader(url)
                return@setOnClickListener
            }

            tvStatus.text = "Đang bắt đầu tải..."
            progressBar.progress = 0

            // Luồng 2 & 3: TikTok (API tikwm) & YouTube/Facebook (yt-dlp)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val tempDir = File(cacheDir, "dl_temp").apply {
                        if (exists()) deleteRecursively()
                        mkdirs()
                    }

                    val downloadedFile: File? = if (isTikTok(url)) {
                        withContext(Dispatchers.Main) { tvStatus.text = "Đang bóc tách TikTok không logo..." }
                        val (title, playUrl) = fetchTikWmApi(url)
                        val safeName = title.replace(Regex("[^a-zA-Z0-9\\u00C0-\\u1EF9\\s_-]"), "")
                            .trim().take(50).ifEmpty { "tiktok_${System.currentTimeMillis()}" }
                        val destFile = File(tempDir, "$safeName.mp4")
                        downloadDirectFile(playUrl, destFile, desktopUa) { p ->
                            runOnUiThread {
                                progressBar.progress = p
                                tvStatus.text = "Đang tải TikTok: $p%"
                            }
                        }
                        destFile
                    } else {
                        val req = YoutubeDLRequest(url)
                        req.addOption("--user-agent", desktopUa)
                        req.addOption("-o", File(tempDir, "%(title)s.%(ext)s").absolutePath)
                        req.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                        req.addOption("--merge-output-format", "mp4")
                        YoutubeDL.getInstance().execute(req) { progress, _, _ ->
                            runOnUiThread {
                                progressBar.progress = progress.toInt()
                                tvStatus.text = "Đang tải: ${progress.toInt()}%"
                            }
                        }
                        tempDir.listFiles()?.firstOrNull()
                    }

                    if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0) {
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

        // Nút 3: Cập nhật lõi yt-dlp
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

    // Tự động chép link Douyin vào Clipboard và mở SnapDouyin trong App để bắt file tải về Thư viện
    @SuppressLint("SetJavaScriptEnabled")
    private fun openDouyinWebDownloader(cleanUrl: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Douyin URL", cleanUrl))
        Toast.makeText(this, "Đã copy sẵn link Douyin! Chỉ cần bấm Dán và Tải.", Toast.LENGTH_LONG).show()

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1200
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Tải Douyin (Bấm Dán -> Tải)")
            .setView(webView)
            .setNegativeButton("Đóng") { d, _ ->
                webView.destroy()
                d.dismiss()
            }
            .create()

        webView.webViewClient = WebViewClient()

        // Khi người dùng bấm nút Tải trên web, app tự bắt link file MP4 để tải về Thư viện
        webView.setDownloadListener { downloadUrl, userAgent, _, _, _ ->
            val cookies = cookieManager.getCookie(downloadUrl)
            webView.destroy()
            dialog.dismiss()
            startDirectGalleryDownload(downloadUrl, userAgent, cookies, "douyin_${System.currentTimeMillis()}.mp4")
        }

        dialog.show()
        webView.loadUrl("https://snapdouyin.app/vi")
    }

    private fun startDirectGalleryDownload(fileUrl: String, ua: String, cookies: String?, fileName: String) {
        tvStatus.text = "Đang tải video Douyin về Thư viện..."
        progressBar.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "dl_temp").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }
                val destFile = File(tempDir, fileName)
                downloadDirectFile(fileUrl, destFile, ua, cookies) { p ->
                    runOnUiThread {
                        progressBar.progress = p
                        tvStatus.text = "Đang tải Douyin: $p%"
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
                        tvStatus.text = "Lỗi: File tải về bị rỗng."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Lỗi tải file Douyin: ${e.message}"
                }
            }
        }
    }

    private fun isTikTok(url: String) = url.lowercase().contains("tiktok.com")
    private fun isDouyin(url: String) = url.lowercase().contains("douyin.com")

    // Gọi API tikwm.com cho TikTok quốc tế
    private fun fetchTikWmApi(videoUrl: String): Pair<String, String> {
        val encodedUrl = URLEncoder.encode(videoUrl, "UTF-8")
        val conn = openWithRedirects("https://www.tikwm.com/api/?url=$encodedUrl&hd=1", desktopUa)
        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(responseText)
        if (json.optInt("code", -1) != 0) {
            throw Exception(json.optString("msg", "Không thể lấy video từ API"))
        }
        val data = json.getJSONObject("data")
        val title = data.optString("title", "Video")
        val playUrl = data.optString("hdplay").ifEmpty { data.optString("play") }
        if (playUrl.isEmpty()) throw Exception("Không tìm thấy link video")
        return Pair(title, playUrl)
    }

    private fun openWithRedirects(startUrl: String, ua: String, cookies: String? = null): HttpURLConnection {
        var currentUrl = startUrl
        repeat(6) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", ua)
            if (!cookies.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookies)
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw Exception("Quá nhiều lượt chuyển hướng")
    }

    private fun downloadDirectFile(
        fileUrl: String,
        destFile: File,
        ua: String,
        cookies: String? = null,
        onProgress: (Int) -> Unit
    ) {
        val conn = openWithRedirects(fileUrl, ua, cookies)
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
                        onProgress(((downloadedBytes * 100) / totalBytes).toInt())
                    }
                }
            }
        }
        conn.disconnect()
    }

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
