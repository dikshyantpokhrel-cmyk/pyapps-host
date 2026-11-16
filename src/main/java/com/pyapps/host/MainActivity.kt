package com.pyapps.host

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch

object PyodideAssets {
    fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, OPTIONS",
        "Access-Control-Allow-Headers" to "*",
        "Cache-Control" to "public, max-age=31536000"
    )

    private fun mimeForUrl(url: String): String {
        val clean = url.substringBefore("?").lowercase()
        return when {
            clean.endsWith(".wasm") -> "application/wasm"
            clean.endsWith(".js") || clean.endsWith(".mjs") -> "application/javascript"
            clean.endsWith(".json") -> "application/json"
            clean.endsWith(".zip") || clean.endsWith(".whl") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    fun serveCached(cacheDir: File, urlStr: String): WebResourceResponse? {
        val safeKey = urlStr
            .substringAfter("https://cdn.jsdelivr.net/pyodide/")
            .substringBefore("?")
            .replace("/", "__")
            .replace("..", "_")
        val cached = File(cacheDir, safeKey)
        val mime = mimeForUrl(urlStr)

        if (cached.exists() && cached.length() > 0) {
            return WebResourceResponse(mime, "UTF-8", 200, "OK", corsHeaders(), FileInputStream(cached))
        }

        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            if (conn.responseCode == 200) {
                val tmp = File(cacheDir, "$safeKey.tmp")
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        input.copyTo(out)
                    }
                }
                if (cached.exists()) cached.delete()
                tmp.renameTo(cached)
                WebResourceResponse(mime, "UTF-8", 200, "OK", corsHeaders(), FileInputStream(cached))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

class NativeCore(
    private val context: Context,
    private val activity: Activity?
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioPath: String? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTtsText: String? = null

    private var activeCode: String = ""

    private val appsDir: File by lazy {
        File(context.filesDir, "apps").apply { if (!exists()) mkdirs() }
    }

    private val workspaceDir: File by lazy {
        val base = context.getExternalFilesDir("workspace") ?: File(context.filesDir, "workspace")
        base.apply { if (!exists()) mkdirs() }
    }

    fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (clean.endsWith(".py")) clean else "$clean.py"
    }

    private fun resolveWorkspaceFile(relPath: String): File? {
        val clean = relPath.trim().trimStart('/')
        if (clean.isEmpty()) return null
        val target = File(workspaceDir, clean)
        return try {
            val rootCanon = workspaceDir.canonicalPath
            val targetCanon = target.canonicalPath
            if (targetCanon == rootCanon || targetCanon.startsWith(rootCanon + File.separator)) {
                target
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun release() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    @JavascriptInterface
    fun listApps(): String {
        val files = appsDir.listFiles { f -> f.isFile && f.name.endsWith(".py") } ?: emptyArray()
        val sorted = files.sortedByDescending { it.lastModified() }
        val arr = JSONArray()
        for (f in sorted) {
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("code", f.readText())
                put("updatedAt", f.lastModified())
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun saveApp(name: String, code: String): Boolean {
        return try {
            File(appsDir, sanitizeFileName(name)).writeText(code)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun deleteApp(name: String): Boolean {
        return File(appsDir, sanitizeFileName(name)).delete()
    }

    @JavascriptInterface
    fun setActiveCode(code: String) {
        activeCode = code
    }

    @JavascriptInterface
    fun importPyFile() {
        val act = activity as? MainActivity
        if (act == null) {
            toast("Open PyApps Host to import files")
            return
        }
        act.launchFilePicker()
    }

    @JavascriptInterface
    fun backupAllApps(): String {
        return exportToDownloads("pyapps_backup_${System.currentTimeMillis()}.json", listApps())
    }

    @JavascriptInterface
    fun writeFile(relPath: String, content: String): Boolean {
        val f = resolveWorkspaceFile(relPath) ?: return false
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun appendFile(relPath: String, content: String): Boolean {
        val f = resolveWorkspaceFile(relPath) ?: return false
        return try {
            f.parentFile?.mkdirs()
            f.appendText(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun readFile(relPath: String): String {
        val f = resolveWorkspaceFile(relPath) ?: return ""
        return try {
            if (f.exists() && f.isFile) f.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun deleteFile(relPath: String): Boolean {
        val f = resolveWorkspaceFile(relPath) ?: return false
        return try { f.delete() } catch (_: Exception) { false }
    }

    @JavascriptInterface
    fun listFiles(): String {
        val arr = JSONArray()
        try {
            workspaceDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    arr.put(JSONObject().apply {
                        put("path", f.relativeTo(workspaceDir).path)
                        put("size", f.length())
                        put("updatedAt", f.lastModified())
                    })
                }
            }
        } catch (_: Exception) {}
        return arr.toString()
    }

    @JavascriptInterface
    fun exportToDownloads(filename: String, content: String): String {
        val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "export_${System.currentTimeMillis()}.txt" }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PyApps")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return JSONObject().put("ok", false).put("error", "MediaStore insert failed").toString()
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                JSONObject().put("ok", true).put("path", "Downloads/PyApps/$safeName").toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PyApps")
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, safeName)
                out.writeText(content)
                JSONObject().put("ok", true).put("path", out.absolutePath).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "Export failed").toString()
        }
    }

    @JavascriptInterface
    fun httpRequest(urlStr: String, method: String, headersJson: String, body: String): String {
        return try {
            val upperMethod = method.ifBlank { "GET" }.uppercase()
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = upperMethod
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
            }
            if (headersJson.isNotBlank()) {
                val hObj = JSONObject(headersJson)
                val keys = hObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    conn.setRequestProperty(k, hObj.optString(k))
                }
            }
            if (body.isNotEmpty() && upperMethod in listOf("POST", "PUT", "PATCH", "DELETE")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val respHeaders = JSONObject()
            conn.headerFields?.forEach { (k, v) ->
                if (k != null && v != null) respHeaders.put(k, v.joinToString(", "))
            }
            JSONObject().apply {
                put("ok", code in 200..299)
                put("status", code)
                put("body", respBody)
                put("headers", respHeaders)
                put("error", "")
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("status", 0)
                put("body", "")
                put("headers", JSONObject())
                put("error", e.message ?: "HTTP failed")
            }.toString()
        }
    }

    @JavascriptInterface
    fun requestPermissions() {
        val act = activity ?: return
        mainHandler.post {
            val perms = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            act.requestPermissions(perms.toTypedArray(), 1003)
        }
    }

    @JavascriptInterface
    fun hasPermissions(): String {
        val cam = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val acc = PyAccessibilityService.instance != null
        return JSONObject().apply {
            put("camera", cam)
            put("mic", mic)
            put("notifications", notif)
            put("accessibility", acc)
        }.toString()
    }

    @JavascriptInterface
    fun listInstalledApps(): String {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val list = context.packageManager.queryIntentActivities(intent, 0)
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (info in list) {
            val pkg = info.activityInfo.packageName
            if (seen.add(pkg)) {
                arr.put(JSONObject().apply {
                    put("name", info.loadLabel(context.packageManager).toString())
                    put("packageName", pkg)
                })
            }
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun openApp(packageName: String): Boolean {
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun fireIntent(
        action: String,
        dataUri: String,
        packageName: String,
        mimeType: String,
        extrasJson: String
    ): Boolean {
        return try {
            val intent = Intent()
            if (action.isNotBlank()) intent.action = action
            if (packageName.isNotBlank()) intent.setPackage(packageName)
            if (dataUri.isNotBlank() && mimeType.isNotBlank()) {
                intent.setDataAndType(Uri.parse(dataUri), mimeType)
            } else if (dataUri.isNotBlank()) {
                intent.data = Uri.parse(dataUri)
            } else if (mimeType.isNotBlank()) {
                intent.type = mimeType
            }
            if (extrasJson.isNotBlank()) {
                val obj = JSONObject(extrasJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    when (val v = obj.get(k)) {
                        is Boolean -> intent.putExtra(k, v)
                        is Int -> intent.putExtra(k, v)
                        is Double -> intent.putExtra(k, v)
                        else -> intent.putExtra(k, v.toString())
                    }
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun startAudioRecording(filename: String): String {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
            return JSONObject().put("ok", false).put("error", "Mic permission required").toString()
        }
        return try {
            stopAudioRecordingInternal()
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val cleanName = if (filename.isBlank()) "rec_${System.currentTimeMillis()}.m4a" else filename
            val outFile = File(dir, cleanName)
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128000)
            rec.setAudioSamplingRate(44100)
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()
            rec.start()
            mediaRecorder = rec
            currentAudioPath = outFile.absolutePath
            JSONObject().put("ok", true).put("path", outFile.absolutePath).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "Record failed").toString()
        }
    }

    @JavascriptInterface
    fun stopAudioRecording(): String {
        val savedPath = currentAudioPath ?: ""
        val ok = stopAudioRecordingInternal()
        return JSONObject().put("ok", ok).put("path", savedPath).toString()
    }

    private fun stopAudioRecordingInternal(): Boolean {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            true
        } catch (_: Exception) {
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            false
        }
    }

    @JavascriptInterface
    fun takePicture(): Boolean {
        val act = activity as? MainActivity
        if (act == null) {
            toast("Open PyApps Host to take picture")
            return false
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
            return false
        }
        return act.launchCamera()
    }

    @JavascriptInterface
    fun isAccessibilityEnabled(): Boolean {
        return PyAccessibilityService.instance != null
    }

    @JavascriptInterface
    fun openAccessibilitySettings() {
        mainHandler.post {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun startFloating(codeOrHtml: String, widthDp: Int, heightDp: Int): Boolean {
        val svc = PyAccessibilityService.instance
        if (svc == null) {
            toast("Enable Automation in launcher first")
            return false
        }
        val payload = if (codeOrHtml.isBlank()) activeCode else codeOrHtml
        if (payload.isBlank()) return false
        svc.showFloating(payload, widthDp, heightDp)
        return true
    }

    @JavascriptInterface
    fun floatCurrentApp(widthDp: Int, heightDp: Int): Boolean {
        val svc = PyAccessibilityService.instance
        if (svc == null) {
            toast("Enable Automation in launcher first")
            return false
        }
        if (activeCode.isBlank()) return false
        svc.showFloating(activeCode, widthDp, heightDp)
        mainHandler.post { activity?.moveTaskToBack(true) }
        return true
    }

    @JavascriptInterface
    fun closeFloating() {
        PyAccessibilityService.instance?.closeFloating()
    }

    @JavascriptInterface
    fun resizeFloating(widthDp: Int, heightDp: Int): Boolean {
        return PyAccessibilityService.instance?.resizeFloating(widthDp, heightDp) ?: false
    }

    @JavascriptInterface
    fun moveFloating(xPx: Int, yPx: Int): Boolean {
        return PyAccessibilityService.instance?.moveFloating(xPx, yPx) ?: false
    }

    @JavascriptInterface
    fun autoTap(x: Float, y: Float): Boolean {
        return PyAccessibilityService.instance?.tap(x, y) ?: false
    }

    @JavascriptInterface
    fun autoSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        return PyAccessibilityService.instance?.swipe(x1, y1, x2, y2, durationMs) ?: false
    }

    @JavascriptInterface
    fun autoClickText(text: String): Boolean {
        return PyAccessibilityService.instance?.clickByText(text) ?: false
    }

    @JavascriptInterface
    fun autoInputText(text: String): Boolean {
        return PyAccessibilityService.instance?.inputText(text) ?: false
    }

    @JavascriptInterface
    fun autoReadScreen(): String {
        return PyAccessibilityService.instance?.readScreenJson() ?: "[]"
    }

    @JavascriptInterface
    fun autoGlobal(action: String): Boolean {
        val svc = PyAccessibilityService.instance ?: return false
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            } else return false
            else -> return false
        }
        return svc.performGlobalAction(code)
    }

    @JavascriptInterface
    fun showNotification(title: String, body: String): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "pyapps_alerts"
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "PyApps Alerts", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val iconRes = if (context.applicationInfo.icon != 0) context.applicationInfo.icon else android.R.drawable.ic_dialog_info
            val notif = Notification.Builder(context, chId)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun speak(text: String) {
        mainHandler.post {
            if (tts == null) {
                pendingTtsText = text
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.getDefault()
                        ttsReady = true
                        pendingTtsText?.let {
                            tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "py_tts")
                            pendingTtsText = null
                        }
                    }
                }
            } else if (ttsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "py_tts")
            } else {
                pendingTtsText = text
            }
        }
    }

    @JavascriptInterface
    fun setTorch(enabled: Boolean): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val hasFlash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cm.setTorchMode(id, enabled)
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun keepScreenOn(enabled: Boolean) {
        mainHandler.post {
            activity?.let {
                if (enabled) it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            PyAccessibilityService.instance?.setFloatingKeepScreenOn(enabled)
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun vibrate(ms: Long) {
        @Suppress("DEPRECATION")
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        vib.vibrate(VibrationEffect.createOneShot(ms.coerceIn(10L, 2000L), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @JavascriptInterface
    fun setClipboard(text: String) {
        mainHandler.post {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PyApps", text))
        }
    }

    @JavascriptInterface
    fun getClipboard(): String {
        var res = ""
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                res = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            } finally {
                latch.countDown()
            }
        }
        try { latch.await() } catch (_: Exception) {}
        return res
    }

    @JavascriptInterface
    fun setFullscreen(enabled: Boolean) {
        val act = activity ?: return
        mainHandler.post {
            if (enabled) {
                act.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                @Suppress("DEPRECATION")
                act.window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
            } else {
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                @Suppress("DEPRECATION")
                act.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }
}

class PyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PyAccessibilityService? = null

        private val FLOAT_HTML = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <style>
    html, body {
      margin: 0;
      padding: 0;
      background: transparent;
      color: #fafafa;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      height: 100%;
      overflow: hidden;
    }
    #_float_stage {
      width: 100%;
      height: 100%;
      overflow-y: auto;
      overflow-x: hidden;
      -webkit-overflow-scrolling: touch;
    }
  </style>
</head>
<body>
  <div id="_float_stage"></div>
  <script src="https://cdn.jsdelivr.net/pyodide/v0.27.8/full/pyodide.js"></script>
  <script>
    const PYODIDE_URL = "https://cdn.jsdelivr.net/pyodide/v0.27.8/full/";
    let activeNamespace = null;
    let pyodide = null;

    async function callPythonFunction(name, args) {
      if (!activeNamespace || !activeNamespace.has(name)) {
        throw new Error("Python function '" + name + "' not found");
      }
      const fn = activeNamespace.get(name);
      try {
        let res = fn(...args);
        if (res && typeof res.then === "function") res = await res;
        if (res && typeof res.toJs === "function") {
          const jsVal = res.toJs({ dict_converter: Object.fromEntries });
          res.destroy();
          return jsVal;
        }
        return res;
      } finally {
        if (fn && typeof fn.destroy === "function") fn.destroy();
      }
    }

    window.py_call = (name, ...args) => callPythonFunction(name, args);
    window.pywebview = {
      api: new Proxy({}, {
        get(_, prop) {
          return (...args) => callPythonFunction(String(prop), args);
        }
      })
    };

    function mountHtml(htmlStr) {
      const stage = document.getElementById("_float_stage");
      document.querySelectorAll('[data-user-app="true"]').forEach(el => el.remove());
      const doc = new DOMParser().parseFromString(htmlStr, "text/html");
      doc.querySelectorAll("style, link[rel='stylesheet']").forEach(node => {
        const clone = document.createElement(node.tagName.toLowerCase());
        for (const attr of node.attributes) clone.setAttribute(attr.name, attr.value);
        clone.setAttribute("data-user-app", "true");
        clone.textContent = node.textContent;
        document.head.appendChild(clone);
      });
      const scripts = Array.from(doc.querySelectorAll("script"));
      scripts.forEach(s => s.remove());
      stage.innerHTML = doc.body.innerHTML;
      for (const s of scripts) {
        const ns = document.createElement("script");
        for (const attr of s.attributes) ns.setAttribute(attr.name, attr.value);
        ns.setAttribute("data-user-app", "true");
        if (!s.src) ns.textContent = s.textContent;
        stage.appendChild(ns);
      }
    }

    window._bootFloat = async (payload) => {
      const looksLikePy = payload.includes("HTML =") || payload.includes("html =") || payload.includes("def ") || payload.includes("import ");
      if (!looksLikePy) {
        mountHtml(payload);
        return;
      }
      const stage = document.getElementById("_float_stage");
      stage.innerHTML = "<div style='padding:10px;font-size:12px;color:#a1a1aa;'>Starting floating Python...</div>";
      try {
        if (!pyodide) {
          pyodide = await loadPyodide({ indexURL: PYODIDE_URL });
          pyodide.runPython([
            "import ast",
            "def _host_extract_html(code_str):",
            "    try:",
            "        tree = ast.parse(code_str)",
            "        for node in tree.body:",
            "            if isinstance(node, ast.Assign):",
            "                for t in node.targets:",
            "                    if isinstance(t, ast.Name) and t.id in ('HTML', 'html'):",
            "                        if isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):",
            "                            return node.value.value",
            "    except Exception:",
            "        pass",
            "    return None"
          ].join("\n"));
        }
        const extractor = pyodide.globals.get("_host_extract_html");
        const staticHtml = extractor(payload);
        extractor.destroy();
        let mounted = false;
        if (typeof staticHtml === "string") {
          mountHtml(staticHtml);
          mounted = true;
        }
        if (activeNamespace) activeNamespace.destroy();
        activeNamespace = pyodide.runPython("{}");
        try { await pyodide.loadPackagesFromImports(payload); } catch (_) {}
        await pyodide.runPythonAsync(payload, { globals: activeNamespace });
        if (!mounted) {
          const dynKey = activeNamespace.has("HTML") ? "HTML" : (activeNamespace.has("html") ? "html" : null);
          if (dynKey) mountHtml(activeNamespace.get(dynKey));
        }
      } catch (err) {
        stage.innerHTML = "<div style='padding:10px;color:#fca5a5;font-family:monospace;font-size:11px;white-space:pre-wrap;'>" + String(err).replace(/</g, "&lt;") + "</div>";
      }
    };
  </script>
</body>
</html>
        """
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var floatContainer: LinearLayout? = null
    private var floatWebView: WebView? = null
    private var floatParams: WindowManager.LayoutParams? = null
    private var nativeCore: NativeCore? = null

    private val pyodideCacheDir: File by lazy {
        File(filesDir, "pyodide_cache").apply { if (!exists()) mkdirs() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        nativeCore = NativeCore(this, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        closeFloating()
        nativeCore?.release()
        nativeCore = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        closeFloating()
        nativeCore?.release()
        nativeCore = null
        instance = null
        super.onDestroy()
    }

    fun showFloating(codeOrHtml: String, widthDp: Int, heightDp: Int) {
        mainHandler.post {
            removeFloatingInternal()
            val windowMgr = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            wm = windowMgr
            val core = nativeCore ?: NativeCore(this, null).also { nativeCore = it }
            core.setActiveCode(codeOrHtml)

            val density = resources.displayMetrics.density
            val wPx = (widthDp.coerceIn(140, 440) * density).toInt()
            val hPx = (heightDp.coerceIn(80, 700) * density).toInt()

            val params = WindowManager.LayoutParams(
                wPx,
                hPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (16 * density).toInt()
                y = (120 * density).toInt()
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E609090b"))
                    cornerRadius = 14 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#3f3f46"))
                }
                clipToOutline = true
            }

            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#F218181b"))
                setPadding((8 * density).toInt(), 0, (6 * density).toInt(), 0)
            }

            val dragLabel = TextView(this).apply {
                text = "⋮⋮ Drag"
                textSize = 11f
                setTextColor(Color.parseColor("#a1a1aa"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val focusBtn = TextView(this).apply {
                text = "⌨"
                textSize = 12f
                setTextColor(Color.parseColor("#a1a1aa"))
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }

            var focusable = false
            focusBtn.setOnClickListener {
                focusable = !focusable
                if (focusable) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    focusBtn.setTextColor(Color.parseColor("#38bdf8"))
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    focusBtn.setTextColor(Color.parseColor("#a1a1aa"))
                }
                try { windowMgr.updateViewLayout(container, params) } catch (_: Exception) {}
            }

            val closeBtn = TextView(this).apply {
                text = "✕"
                textSize = 12f
                setTextColor(Color.parseColor("#fca5a5"))
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener { removeFloatingInternal() }
            }

            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            topBar.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        try { windowMgr.updateViewLayout(container, params) } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }

            topBar.addView(dragLabel)
            topBar.addView(focusBtn)
            topBar.addView(closeBtn)

            val wv = WebView(this).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                addJavascriptInterface(core, "AndroidNative")
                webChromeClient = WebChromeClient()

                var booted = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val req = request ?: return null
                        val urlStr = req.url.toString()
                        if (req.method.equals("OPTIONS", true) && urlStr.startsWith("https://cdn.jsdelivr.net/pyodide/")) {
                            return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", PyodideAssets.corsHeaders(), ByteArrayInputStream(ByteArray(0)))
                        }
                        if (urlStr.startsWith("https://cdn.jsdelivr.net/pyodide/")) {
                            return PyodideAssets.serveCached(pyodideCacheDir, urlStr)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!booted) {
                            booted = true
                            view?.evaluateJavascript("window._bootFloat(${JSONObject.quote(codeOrHtml)});", null)
                        }
                    }
                }
            }

            container.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (26 * density).toInt()))
            container.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            floatContainer = container
            floatWebView = wv
            floatParams = params

            windowMgr.addView(container, params)
            wv.loadDataWithBaseURL("https://pyapps.local/", FLOAT_HTML, "text/html", "UTF-8", "https://pyapps.local/")
        }
    }

    fun closeFloating() {
        mainHandler.post { removeFloatingInternal() }
    }

    private fun removeFloatingInternal() {
        try {
            floatWebView?.destroy()
        } catch (_: Exception) {}
        floatWebView = null
        floatContainer?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
        }
        floatContainer = null
        floatParams = null
    }

    fun resizeFloating(widthDp: Int, heightDp: Int): Boolean {
        val params = floatParams ?: return false
        val container = floatContainer ?: return false
        val density = resources.displayMetrics.density
        mainHandler.post {
            params.width = (widthDp.coerceIn(140, 440) * density).toInt()
            params.height = (heightDp.coerceIn(80, 700) * density).toInt()
            try { wm?.updateViewLayout(container, params) } catch (_: Exception) {}
        }
        return true
    }

    fun moveFloating(xPx: Int, yPx: Int): Boolean {
        val params = floatParams ?: return false
        val container = floatContainer ?: return false
        mainHandler.post {
            params.x = xPx
            params.y = yPx
            try { wm?.updateViewLayout(container, params) } catch (_: Exception) {}
        }
        return true
    }

    fun setFloatingKeepScreenOn(enabled: Boolean) {
        val params = floatParams ?: return
        val container = floatContainer ?: return
        if (enabled) params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        try { wm?.updateViewLayout(container, params) } catch (_: Exception) {}
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, query) ?: return false
        var current: AccessibilityNodeInfo? = target
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = if (focused != null && focused.isEditable) focused else findEditableNode(root)
        target ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun readScreenJson(): String {
        val arr = JSONArray()
        val root = rootInActiveWindow ?: return arr.toString()
        collectNodes(root, arr, 150)
        return arr.toString()
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        if (t.contains(query, ignoreCase = true) || d.contains(query, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findNodeByText(child, query)
            if (hit != null) return hit
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findEditableNode(child)
            if (hit != null) return hit
        }
        return null
    }

    private fun collectNodes(node: AccessibilityNodeInfo, arr: JSONArray, limit: Int) {
        if (arr.length() >= limit) return
        val t = node.text?.toString()?.trim() ?: ""
        val d = node.contentDescription?.toString()?.trim() ?: ""
        if (t.isNotEmpty() || d.isNotEmpty() || node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            arr.put(JSONObject().apply {
                put("text", t)
                put("desc", d)
                put("id", node.viewIdResourceName ?: "")
                put("clickable", node.isClickable)
                put("x", rect.centerX())
                put("y", rect.centerY())
            })
        }
        for (i in 0 until node.childCount) {
            if (arr.length() >= limit) break
            val child = node.getChild(i) ?: continue
            collectNodes(child, arr, limit)
        }
    }
}

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var nativeCore: NativeCore
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPhotoUri: Uri? = null

    private val REQ_IMPORT_PY = 1001
    private val REQ_TAKE_PICTURE = 1002

    private val appsDir: File by lazy {
        File(filesDir, "apps").apply { if (!exists()) mkdirs() }
    }

    private val pyodideCacheDir: File by lazy {
        File(filesDir, "pyodide_cache").apply { if (!exists()) mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#09090b")
        window.navigationBarColor = Color.parseColor("#09090b")

        nativeCore = NativeCore(this, this)

        webView = WebView(this)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setBackgroundColor(Color.parseColor("#09090b"))

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(nativeCore, "AndroidNative")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val req = request ?: return null
                val urlStr = req.url.toString()

                if (req.method.equals("OPTIONS", ignoreCase = true) &&
                    urlStr.startsWith("https://cdn.jsdelivr.net/pyodide/")
                ) {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        204,
                        "No Content",
                        PyodideAssets.corsHeaders(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }

                if (urlStr.startsWith("https://cdn.jsdelivr.net/pyodide/")) {
                    return PyodideAssets.serveCached(pyodideCacheDir, urlStr)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        setContentView(webView)
        webView.loadDataWithBaseURL(
            "https://pyapps.local/",
            HOST_HTML,
            "text/html",
            "UTF-8",
            "https://pyapps.local/"
        )
    }

    fun launchFilePicker() {
        mainHandler.post {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_IMPORT_PY)
        }
    }

    fun launchCamera(): Boolean {
        mainHandler.post {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "PyApp_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PyApps")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    pendingPhotoUri = uri
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    }
                    startActivityForResult(intent, REQ_TAKE_PICTURE)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("window._onNativeBack ? window._onNativeBack() : false") { result ->
            if (result != "true") {
                mainHandler.post { super.onBackPressed() }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_IMPORT_PY && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val name = queryFileName(uri)
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            if (name.lowercase().endsWith(".json")) {
                try {
                    val arr = JSONArray(text)
                    var count = 0
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val appName = obj.optString("name")
                        val appCode = obj.optString("code")
                        if (appName.isNotBlank()) {
                            File(appsDir, nativeCore.sanitizeFileName(appName)).writeText(appCode)
                            count++
                        }
                    }
                    Toast.makeText(this, "Restored $count apps", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Invalid backup JSON", Toast.LENGTH_SHORT).show()
                }
            } else {
                val pyName = if (name.endsWith(".py")) name else "$name.py"
                File(appsDir, nativeCore.sanitizeFileName(pyName)).writeText(text)
            }
            val js = "window._onNativeImport && window._onNativeImport(${JSONObject.quote(name)});"
            mainHandler.post { webView.evaluateJavascript(js, null) }
        }

        if (requestCode == REQ_TAKE_PICTURE) {
            val uri = pendingPhotoUri
            pendingPhotoUri = null
            if (resultCode == RESULT_OK && uri != null) {
                Thread {
                    val b64 = encodeResizedImageDataUrl(uri)
                    val js = "window._onNativePicture && window._onNativePicture(${JSONObject.quote(uri.toString())}, ${JSONObject.quote(b64)});"
                    mainHandler.post { webView.evaluateJavascript(js, null) }
                }.start()
            }
        }
    }

    private fun queryFileName(uri: Uri): String {
        var result = "imported_app.py"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                result = cursor.getString(idx) ?: result
            }
        }
        return result
    }

    private fun encodeResizedImageDataUrl(uri: Uri): String {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            while (maxDim / sample > 1024) sample *= 2

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return ""
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            bmp.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    override fun onDestroy() {
        nativeCore.release()
        super.onDestroy()
    }

    companion object {
        private val HOST_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>PyApps Host</title>
  <style>
    :root {
      --bg: #09090b;
      --card: #18181b;
      --border: #27272a;
      --text: #fafafa;
      --muted: #a1a1aa;
      --accent: #3b82f6;
      --ok: #22c55e;
      --warn: #eab308;
    }
    html, body {
      margin: 0;
      padding: 0;
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      overscroll-behavior: none;
      -webkit-tap-highlight-color: transparent;
      min-height: 100dvh;
      overflow-y: auto;
    }
    #_host_launcher {
      max-width: 680px;
      margin: 0 auto;
      padding: 16px 16px 88px 16px;
      box-sizing: border-box;
    }
    ._host_header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 6px 0 12px 0;
      gap: 10px;
    }
    ._host_title {
      font-size: 22px;
      font-weight: 700;
      margin: 0;
    }
    ._host_badge {
      font-size: 12px;
      padding: 5px 10px;
      border-radius: 999px;
      background: var(--card);
      border: 1px solid var(--border);
      color: var(--muted);
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    ._host_dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--warn);
    }
    ._host_dot.ready { background: var(--ok); }
    ._host_perms {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
      margin-bottom: 10px;
    }
    ._host_perm_btn {
      background: #141418;
      color: var(--muted);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 9px 10px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      text-align: center;
    }
    ._host_perm_btn.on {
      color: #86efac;
      border-color: rgba(34, 197, 94, 0.4);
    }
    ._host_actions {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 10px;
      margin-bottom: 10px;
    }
    ._host_utils {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
      margin-bottom: 16px;
    }
    ._host_util_btn {
      background: transparent;
      color: var(--muted);
      border: 1px dashed var(--border);
      border-radius: 10px;
      padding: 8px 10px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
    ._host_btn {
      background: var(--card);
      color: var(--text);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 13px 14px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
    }
    ._host_btn.primary {
      background: var(--accent);
      border-color: var(--accent);
    }
    ._host_list {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    ._host_card {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 14px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
    }
    ._host_card_info { flex: 1; min-width: 0; cursor: pointer; }
    ._host_card_name {
      font-size: 16px;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    ._host_card_meta { font-size: 12px; color: var(--muted); margin-top: 3px; }
    ._host_card_btns { display: flex; gap: 6px; }
    ._host_icon_btn {
      background: #27272a;
      color: var(--text);
      border: none;
      border-radius: 8px;
      padding: 8px 10px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
    ._host_icon_btn.del { color: #fca5a5; }
    #_host_stage {
      display: none;
      width: 100%;
      height: 100dvh;
      overflow-y: auto;
      overflow-x: hidden;
      -webkit-overflow-scrolling: touch;
    }
    #_host_back_pill {
      display: none;
      position: fixed;
      top: 10px;
      right: 12px;
      z-index: 2147483647;
      background: rgba(24, 24, 27, 0.85);
      color: #fafafa;
      border: 1px solid rgba(255, 255, 255, 0.16);
      border-radius: 999px;
      padding: 6px 12px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
    #_host_modal {
      display: none;
      position: fixed;
      inset: 0;
      z-index: 2147483646;
      background: rgba(0, 0, 0, 0.84);
      padding: 16px;
      box-sizing: border-box;
      align-items: center;
      justify-content: center;
    }
    #_host_modal.open { display: flex; }
    ._host_modal_box {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 16px;
      width: 100%;
      max-width: 600px;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    ._host_input, ._host_textarea {
      width: 100%;
      box-sizing: border-box;
      background: #09090b;
      color: var(--text);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 10px 12px;
      font-family: monospace;
      font-size: 13px;
    }
    ._host_textarea { min-height: 280px; resize: vertical; }
    ._host_modal_footer { display: flex; gap: 10px; justify-content: flex-end; }
  </style>
</head>
<body>
  <div id="_host_launcher">
    <div class="_host_header">
      <h1 class="_host_title">PyApps Native</h1>
      <div class="_host_badge">
        <span id="_host_dot" class="_host_dot"></span>
        <span id="_host_status">Loading Python...</span>
      </div>
    </div>

    <div class="_host_perms">
      <button id="_host_perm_hw" class="_host_perm_btn" onclick="AndroidNative.requestPermissions(); setTimeout(refreshPerms, 1200);">Permissions</button>
      <button id="_host_perm_acc" class="_host_perm_btn" onclick="AndroidNative.openAccessibilitySettings();">Enable Automation</button>
    </div>

    <div class="_host_actions">
      <button class="_host_btn primary" onclick="AndroidNative.importPyFile()">+ Import .py / Backup</button>
      <button class="_host_btn" onclick="openEditor()">+ Paste Code</button>
    </div>

    <div class="_host_utils">
      <button class="_host_util_btn" onclick="backupAll()">Backup All to Downloads</button>
      <button class="_host_util_btn" onclick="AndroidNative.closeFloating()">Close Floating App</button>
    </div>

    <div id="_host_list" class="_host_list"></div>
  </div>

  <button id="_host_back_pill" onclick="closeApp()">← Apps</button>
  <div id="_host_stage"></div>

  <div id="_host_modal">
    <div class="_host_modal_box">
      <input id="_host_ed_name" class="_host_input" placeholder="my_app.py" />
      <textarea id="_host_ed_code" class="_host_textarea" placeholder="HTML = '''...'''"></textarea>
      <div class="_host_modal_footer">
        <button class="_host_btn" onclick="closeEditor()">Cancel</button>
        <button class="_host_btn primary" onclick="saveFromEditor()">Save App</button>
      </div>
    </div>
  </div>

  <script src="https://cdn.jsdelivr.net/pyodide/v0.27.8/full/pyodide.js"></script>
  <script>
    const PYODIDE_URL = "https://cdn.jsdelivr.net/pyodide/v0.27.8/full/";
    let pyodide = null;
    let activeNamespace = null;
    let isAppRunning = false;

    const DEMO_NAME = "demo_native_control.py";
    const DEMO_CODE = [
      "HTML = '''",
      "<style>",
      "  .wrap { min-height: 100vh; background: #0f172a; color: #f8fafc; padding: 20px; box-sizing: border-box; }",
      "  .title { font-size: 20px; font-weight: 700; margin-bottom: 14px; }",
      "  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 14px; }",
      "  .btn { background: #2563eb; color: #fff; border: none; border-radius: 12px; padding: 14px 10px; font-size: 14px; font-weight: 600; cursor: pointer; }",
      "  .btn.red { background: #dc2626; }",
      "  .btn.green { background: #16a34a; }",
      "  .btn.purple { background: #7c3aed; }",
      "  .log { background: #020617; border: 1px solid #334155; border-radius: 12px; padding: 12px; font-size: 12px; font-family: monospace; min-height: 70px; white-space: pre-wrap; }",
      "  #preview { width: 100%; border-radius: 12px; margin-top: 12px; display: none; }",
      "</style>",
      "<div class='wrap'>",
      "  <div class='title'>Native Control Demo</div>",
      "  <div class='grid'>",
      "    <button class='btn' onclick='pywebview.api.snap_photo()'>Take Picture</button>",
      "    <button class='btn red' onclick='pywebview.api.toggle_record()'>Record Audio</button>",
      "    <button class='btn green' onclick='pywebview.api.list_apps()'>Find Apps</button>",
      "    <button class='btn purple' onclick='pywebview.api.float_demo()'>Test Floating</button>",
      "  </div>",
      "  <div id='log' class='log'>Ready. Tap any native control above.</div>",
      "  <img id='preview' />",
      "</div>",
      "'''",
      "",
      "import json",
      "from js import AndroidNative, document",
      "",
      "recording = False",
      "",
      "def log(msg):",
      "    document.getElementById('log').innerText = str(msg)",
      "",
      "def snap_photo():",
      "    ok = AndroidNative.takePicture()",
      "    if not ok:",
      "        log('Grant Camera permission when prompted, then tap again.')",
      "",
      "def on_picture(uri, data_url):",
      "    img = document.getElementById('preview')",
      "    img.src = data_url",
      "    img.style.display = 'block'",
      "    log('Saved photo to Gallery:\\n' + uri)",
      "",
      "def toggle_record():",
      "    global recording",
      "    if not recording:",
      "        res = json.loads(AndroidNative.startAudioRecording(''))",
      "        if res.get('ok'):",
      "            recording = True",
      "            log('Recording mic... Tap Record Audio again to stop.')",
      "        else:",
      "            log(res.get('error', 'Failed'))",
      "    else:",
      "        res = json.loads(AndroidNative.stopAudioRecording())",
      "        recording = False",
      "        log('Audio saved to:\\n' + res.get('path', ''))",
      "",
      "def list_apps():",
      "    apps = json.loads(AndroidNative.listInstalledApps())",
      "    names = [a['name'] + ' (' + a['packageName'] + ')' for a in apps[:8]]",
      "    log('First 8 installed apps:\\n' + '\\n'.join(names))",
      "",
      "def float_demo():",
      "    ok = AndroidNative.startFloating(\"<div style='padding:10px;font-size:13px;font-weight:700;'>Floating Python Ready! Drag me over any app.</div>\", 230, 95)",
      "    if ok:",
      "        log('Floating widget opened! Drag the top bar around your screen.')",
      "    else:",
      "        log('Enable Automation first in launcher.')"
    ].join("\n");

    async function callPythonFunction(name, args) {
      if (!activeNamespace || !activeNamespace.has(name)) {
        throw new Error("Python function '" + name + "' not found");
      }
      const fn = activeNamespace.get(name);
      try {
        let res = fn(...args);
        if (res && typeof res.then === "function") {
          res = await res;
        }
        if (res && typeof res.toJs === "function") {
          const jsVal = res.toJs({ dict_converter: Object.fromEntries });
          res.destroy();
          return jsVal;
        }
        return res;
      } finally {
        if (fn && typeof fn.destroy === "function") fn.destroy();
      }
    }

    window.py_call = (name, ...args) => callPythonFunction(name, args);
    window.pywebview = {
      api: new Proxy({}, {
        get(_, prop) {
          return (...args) => callPythonFunction(String(prop), args);
        }
      })
    };

    window._onNativeImport = () => renderAppList();
    window._onNativePicture = (uri, dataUrl) => {
      if (activeNamespace && activeNamespace.has("on_picture")) {
        callPythonFunction("on_picture", [uri, dataUrl]).catch(console.error);
      }
      if (typeof window.onNativePicture === "function") {
        window.onNativePicture(uri, dataUrl);
      }
    };
    window._onNativeBack = () => {
      if (isAppRunning) {
        closeApp();
        return true;
      }
      return false;
    };

    function backupAll() {
      const res = JSON.parse(AndroidNative.backupAllApps());
      if (res.ok) {
        AndroidNative.toast("Backed up to " + res.path);
      } else {
        AndroidNative.toast("Backup failed: " + res.error);
      }
    }

    function refreshPerms() {
      try {
        const p = JSON.parse(AndroidNative.hasPermissions());
        const hwBtn = document.getElementById("_host_perm_hw");
        const accBtn = document.getElementById("_host_perm_acc");
        if (p.camera && p.mic && p.notifications) {
          hwBtn.classList.add("on");
          hwBtn.innerText = "Permissions: ON";
        } else {
          hwBtn.classList.remove("on");
          hwBtn.innerText = "Grant Permissions";
        }
        if (p.accessibility) {
          accBtn.classList.add("on");
          accBtn.innerText = "Automation: ON";
        } else {
          accBtn.classList.remove("on");
          accBtn.innerText = "Enable Automation";
        }
      } catch (_) {}
    }

    async function initRuntime() {
      const dot = document.getElementById("_host_dot");
      const status = document.getElementById("_host_status");
      try {
        pyodide = await loadPyodide({ indexURL: PYODIDE_URL });
        pyodide.runPython([
          "import ast",
          "def _host_extract_html(code_str):",
          "    try:",
          "        tree = ast.parse(code_str)",
          "        for node in tree.body:",
          "            if isinstance(node, ast.Assign):",
          "                for t in node.targets:",
          "                    if isinstance(t, ast.Name) and t.id in ('HTML', 'html'):",
          "                        if isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):",
          "                            return node.value.value",
          "    except Exception:",
          "        pass",
          "    return None"
        ].join("\n"));
        dot.classList.add("ready");
        status.innerText = "Engine Ready (Offline)";
      } catch (err) {
        status.innerText = "Engine Error";
        console.error(err);
      }
    }

    function renderAppList() {
      let apps = JSON.parse(AndroidNative.listApps());
      if (apps.length === 0) {
        AndroidNative.saveApp(DEMO_NAME, DEMO_CODE);
        apps = JSON.parse(AndroidNative.listApps());
      }
      const list = document.getElementById("_host_list");
      list.innerHTML = "";
      for (const app of apps) {
        const card = document.createElement("div");
        card.className = "_host_card";

        const info = document.createElement("div");
        info.className = "_host_card_info";
        info.onclick = () => launchApp(app.name, app.code);

        const title = document.createElement("div");
        title.className = "_host_card_name";
        title.innerText = app.name;

        const meta = document.createElement("div");
        meta.className = "_host_card_meta";
        meta.innerText = app.code.split("\n").length + " lines • Tap to launch";

        info.appendChild(title);
        info.appendChild(meta);

        const btns = document.createElement("div");
        btns.className = "_host_card_btns";

        const editBtn = document.createElement("button");
        editBtn.className = "_host_icon_btn";
        editBtn.innerText = "Edit";
        editBtn.onclick = () => openEditor(app.name, app.code);

        const delBtn = document.createElement("button");
        delBtn.className = "_host_icon_btn del";
        delBtn.innerText = "Del";
        delBtn.onclick = () => {
          AndroidNative.deleteApp(app.name);
          renderAppList();
        };

        btns.appendChild(editBtn);
        btns.appendChild(delBtn);

        card.appendChild(info);
        card.appendChild(btns);
        list.appendChild(card);
      }
    }

    function mountUserHTML(htmlStr) {
      const stage = document.getElementById("_host_stage");
      document.querySelectorAll('[data-user-app="true"]').forEach(el => el.remove());

      const parser = new DOMParser();
      const doc = parser.parseFromString(htmlStr, "text/html");

      doc.querySelectorAll("style, link[rel='stylesheet']").forEach(node => {
        const clone = document.createElement(node.tagName.toLowerCase());
        for (const attr of node.attributes) {
          clone.setAttribute(attr.name, attr.value);
        }
        clone.setAttribute("data-user-app", "true");
        clone.textContent = node.textContent;
        document.head.appendChild(clone);
      });

      const scripts = Array.from(doc.querySelectorAll("script"));
      scripts.forEach(s => s.remove());

      stage.innerHTML = doc.body.innerHTML;

      for (const s of scripts) {
        const newScript = document.createElement("script");
        for (const attr of s.attributes) {
          newScript.setAttribute(attr.name, attr.value);
        }
        newScript.setAttribute("data-user-app", "true");
        if (!s.src) {
          newScript.textContent = s.textContent;
        }
        stage.appendChild(newScript);
      }
    }

    async function launchApp(name, code) {
      if (!pyodide) {
        AndroidNative.toast("Python engine is loading, wait a moment.");
        return;
      }

      AndroidNative.setActiveCode(code);
      document.getElementById("_host_launcher").style.display = "none";
      const stage = document.getElementById("_host_stage");
      stage.style.display = "block";
      stage.innerHTML = "<div style='padding:24px;color:#a1a1aa;'>Starting app...</div>";
      document.getElementById("_host_back_pill").style.display = "flex";
      isAppRunning = true;

      try {
        const extractor = pyodide.globals.get("_host_extract_html");
        const staticHtml = extractor(code);
        extractor.destroy();

        let mounted = false;
        if (typeof staticHtml === "string") {
          mountUserHTML(staticHtml);
          mounted = true;
        }

        if (activeNamespace) {
          activeNamespace.destroy();
          activeNamespace = null;
        }
        activeNamespace = pyodide.runPython("{}");

        try {
          await pyodide.loadPackagesFromImports(code);
        } catch (_) {}

        await pyodide.runPythonAsync(code, { globals: activeNamespace });

        if (!mounted) {
          const dynKey = activeNamespace.has("HTML") ? "HTML" : (activeNamespace.has("html") ? "html" : null);
          if (dynKey) {
            mountUserHTML(activeNamespace.get(dynKey));
          }
        }
      } catch (err) {
        stage.innerHTML =
          "<div style='padding:20px;color:#fca5a5;font-family:monospace;white-space:pre-wrap;font-size:13px;'>" +
          "<h3 style='margin-top:0;color:#ef4444;'>Error in " + name + "</h3>" +
          String(err).replace(/</g, "&lt;") +
          "</div>";
      }
    }

    function closeApp() {
      isAppRunning = false;
      AndroidNative.setFullscreen(false);
      AndroidNative.keepScreenOn(false);
      document.getElementById("_host_stage").innerHTML = "";
      document.getElementById("_host_stage").style.display = "none";
      document.getElementById("_host_back_pill").style.display = "none";
      document.querySelectorAll('[data-user-app="true"]').forEach(el => el.remove());
      if (activeNamespace) {
        activeNamespace.destroy();
        activeNamespace = null;
      }
      document.getElementById("_host_launcher").style.display = "block";
      refreshPerms();
    }

    function openEditor(name = "", code = "") {
      document.getElementById("_host_ed_name").value = name || "my_app.py";
      document.getElementById("_host_ed_code").value = code;
      document.getElementById("_host_modal").classList.add("open");
    }

    function closeEditor() {
      document.getElementById("_host_modal").classList.remove("open");
    }

    function saveFromEditor() {
      let name = document.getElementById("_host_ed_name").value.trim() || "untitled.py";
      if (!name.endsWith(".py")) name += ".py";
      const code = document.getElementById("_host_ed_code").value;
      AndroidNative.saveApp(name, code);
      closeEditor();
      renderAppList();
    }

    window.addEventListener("focus", refreshPerms);
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) refreshPerms();
    });

    renderAppList();
    refreshPerms();
    initRuntime();
  </script>
</body>
</html>
        """
    }
}
