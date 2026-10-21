package com.smali.fox

import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Debug
import java.net.InetSocketAddress
import java.net.Socket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : FlutterActivity() {
    private val channelName = "com.smali.fox/native"
    private var channel: MethodChannel? = null
    private var pendingText: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        pendingText = extractText(intent)
        val ch = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        channel = ch
        ch.setMethodCallHandler { call, result ->
            when (call.method) {
                "getInitialText" -> {
                    result.success(pendingText)
                    pendingText = null
                }
                "saveToDownloads" -> {
                    val path = call.argument<String>("path")
                    val name = call.argument<String>("name")
                    val mime = call.argument<String>("mime") ?: "video/mp4"
                    if (path == null || name == null) {
                        result.error("ARG", "path or name is missing", null)
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 4711)
                        result.success(false)
                    } else {
                        Thread {
                            val ok = try {
                                saveToDownloads(path, name, mime)
                            } catch (e: Exception) {
                                false
                            }
                            runOnUiThread { result.success(ok) }
                        }.start()
                    }
                }
                "securityRisks" -> {
                    Thread {
                        val risks = try { securityRisks() } catch (e: Exception) { emptyList<String>() }
                        runOnUiThread { result.success(risks) }
                    }.start()
                }
                "extractAudio", "thumbnail" -> {
                    val src = call.argument<String>("src")
                    val dst = call.argument<String>("dst")
                    if (src == null || dst == null) {
                        result.error("ARG", "src or dst is missing", null)
                    } else {
                        val isAudio = call.method == "extractAudio"
                        Thread {
                            val ok = try {
                                if (isAudio) extractAudioTrack(src, dst) else makeThumb(src, dst)
                            } catch (e: Exception) {
                                false
                            }
                            runOnUiThread { result.success(ok) }
                        }.start()
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val text = extractText(intent)
        if (text != null) channel?.invokeMethod("sharedText", text)
    }

    private fun extractText(i: Intent?): String? {
        if (i == null || i.action != Intent.ACTION_SEND) return null
        val type = i.type ?: return null
        if (!type.startsWith("text/")) return null
        return i.getStringExtra(Intent.EXTRA_TEXT)
    }

    private fun saveToDownloads(path: String, name: String, mime: String): Boolean {
        val src = File(path)
        if (!src.exists()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FlowDrop")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                val out = resolver.openOutputStream(uri)
                if (out == null) {
                    resolver.delete(uri, null, null)
                    return false
                }
                out.use { o -> FileInputStream(src).use { it.copyTo(o) } }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                return false
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return true
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FlowDrop")
            if (!dir.exists() && !dir.mkdirs()) return false
            src.copyTo(File(dir, name), overwrite = true)
            return true
        }
    }

    /** ينسخ مسار الصوت (AAC) من ملف فيديو إلى ملف M4A بدون إعادة ترميز. */
    private fun extractAudioTrack(src: String, dst: String): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src)
            var idx = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) { idx = i; break }
            }
            if (idx < 0) return false
            extractor.selectTrack(idx)
            muxer = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outIdx = muxer.addTrack(extractor.getTrackFormat(idx))
            muxer.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outIdx, buf, info)
                extractor.advance()
            }
            muxer.stop()
            return true
        } catch (e: Exception) {
            File(dst).delete()
            return false
        } finally {
            try { muxer?.release() } catch (e: Exception) {}
            extractor.release()
        }
    }

    /** يحفظ صورة مصغرة (JPEG) من إطار داخل الفيديو. */
    private fun makeThumb(src: String, dst: String): Boolean {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(src)
            val bmp = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0) ?: return false
            val w = 480
            val out = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
            FileOutputStream(dst).use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            r.release()
        }
    }

    /** يفحص بيئة التشغيل: VPN / وكيل / مصحح أخطاء / Frida. */
    private fun securityRisks(): List<String> {
        val risks = mutableListOf<String>()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) risks.add("vpn")
            if (cm.defaultProxy != null) risks.add("proxy")
        } catch (e: Exception) {}
        if (!System.getProperty("http.proxyHost").isNullOrEmpty() ||
            !System.getProperty("https.proxyHost").isNullOrEmpty()) {
            if (!risks.contains("proxy")) risks.add("proxy")
        }
        if (Debug.isDebuggerConnected()) risks.add("debugger")
        try {
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("TracerPid:") }?.let {
                if (it.substringAfter(":").trim() != "0") risks.add("debugger")
            }
        } catch (e: Exception) {}
        try {
            val maps = File("/proc/self/maps").readText()
            if (maps.contains("frida", ignoreCase = true) || maps.contains("gum-js-loop")) risks.add("frida")
        } catch (e: Exception) {}
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 27042), 150); risks.add("frida") }
        } catch (e: Exception) {}
        return risks.distinct()
    }
}
