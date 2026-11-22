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
import java.io.File
import java.io.FileInputStream

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
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener

            tvStatus.text = "Đang phân tích link..."
            tvDirectLink.text = ""

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val request = YoutubeDLRequest(url)
                    request.addOption("--user-agent", userAgent)
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
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener

            tvStatus.text = "Đang bắt đầu tải..."
            progressBar.progress = 0

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val tempDir = File(cacheDir, "dl_temp").apply {
                        if (exists()) deleteRecursively()
                        mkdirs()
                    }

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
                } catch (
