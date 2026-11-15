package com.pyapps.host

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
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
import android.graphics.Rect
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
import android.util.Base64
import android.view.View
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

class PyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
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
            val obj = JSONObject().apply {
                put("text", t)
                put("desc", d)
                put("id", node.viewIdResourceName ?: "")
                put("clickable", node.isClickable)
                put("x", rect.centerX())
                put("y", rect.centerY())
            }
            arr.put(obj)
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioPath: String? = null
    private var pendingPhotoUri: Uri? = null

    private val REQ_IMPORT_PY = 1001
    private val REQ_TAKE_PICTURE = 1002
    private val REQ_PERMISSIONS = 1003

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

        webView.addJavascriptInterface(AndroidNativeBridge(), "AndroidNative")
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
                        corsHeaders(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }

                if (urlStr.startsWith("https://cdn.jsdelivr.net/pyodide/")) {
                    return serveCachedPyodideAsset(urlStr)
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

    private fun corsHeaders(): Map<String, String> = mapOf(
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

    private fun serveCachedPyodideAsset(urlStr: String): WebResourceResponse? {
        val safeKey = urlStr
            .substringAfter("https://cdn.jsdelivr.net/pyodide/")
            .substringBefore("?")
            .replace("/", "__")
            .replace("..", "_")
        val cached = File(pyodideCacheDir, safeKey)
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
                val tmp = File(pyodideCacheDir, "$safeKey.tmp")
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
            val name = queryFileName(uri).let { if (it.endsWith(".py")) it else "$it.py" }
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            File(appsDir, sanitizeFileName(name)).writeText(text)
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

    private fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (clean.endsWith(".py")) clean else "$clean.py"
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
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        super.onDestroy()
    }

    inner class AndroidNativeBridge {

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
        fun importPyFile() {
            mainHandler.post {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQ_IMPORT_PY)
            }
        }

        @JavascriptInterface
        fun requestPermissions() {
            mainHandler.post {
                val perms = mutableListOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                requestPermissions(perms.toTypedArray(), REQ_PERMISSIONS)
            }
        }

        @JavascriptInterface
        fun hasPermissions(): String {
            val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val acc = PyAccessibilityService.instance != null
            return JSONObject().apply {
                put("camera", cam)
                put("mic", mic)
                put("accessibility", acc)
            }.toString()
        }

        @JavascriptInterface
        fun listInstalledApps(): String {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val list = packageManager.queryIntentActivities(intent, 0)
            val arr = JSONArray()
            val seen = HashSet<String>()
            for (info in list) {
                val pkg = info.activityInfo.packageName
                if (seen.add(pkg)) {
                    arr.put(JSONObject().apply {
                        put("name", info.loadLabel(packageManager).toString())
                        put("packageName", pkg)
                    })
                }
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun openApp(packageName: String): Boolean {
            return try {
                val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
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
                startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun startAudioRecording(filename: String): String {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions()
                return JSONObject().put("ok", false).put("error", "Mic permission requested").toString()
            }
            return try {
                stopAudioRecordingInternal()
                val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
                if (!dir.exists()) dir.mkdirs()
                val cleanName = if (filename.isBlank()) "rec_${System.currentTimeMillis()}.m4a" else filename
                val outFile = File(dir, cleanName)
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@MainActivity)
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
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions()
                return false
            }
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
                    toast("Camera error: ${e.message}")
                }
            }
            return true
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
                startActivity(intent)
            }
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
        fun toast(msg: String) {
            mainHandler.post { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vib.vibrate(VibrationEffect.createOneShot(ms.coerceIn(10L, 2000L), VibrationEffect.DEFAULT_AMPLITUDE))
        }

        @JavascriptInterface
        fun setClipboard(text: String) {
            mainHandler.post {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PyApps", text))
            }
        }

        @JavascriptInterface
        fun getClipboard(): String {
            var res = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
            mainHandler.post {
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
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
      min-height: 100vh;
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
      margin-bottom: 12px;
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
      margin-bottom: 16px;
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
    #_host_stage { display: none; width: 100%; min-height: 100vh; }
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
      <button id="_host_perm_hw" class="_host_perm_btn" onclick="AndroidNative.requestPermissions(); setTimeout(refreshPerms, 1200);">Camera &amp; Mic</button>
      <button id="_host_perm_acc" class="_host_perm_btn" onclick="AndroidNative.openAccessibilitySettings();">Enable Automation</button>
    </div>

    <div class="_host_actions">
      <button class="_host_btn primary" onclick="AndroidNative.importPyFile()">+ Import .py</button>
      <button class="_host_btn" onclick="openEditor()">+ Paste Code</button>
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
      "  .log { background: #020617; border: 1px solid #334155; border-radius: 12px; padding: 12px; font-size: 12px; font-family: monospace; min-height: 70px; white-space: pre-wrap; }",
      "  #preview { width: 100%; border-radius: 12px; margin-top: 12px; display: none; }",
      "</style>",
      "<div class='wrap'>",
      "  <div class='title'>Native Control Demo</div>",
      "  <div class='grid'>",
      "    <button class='btn' onclick='pywebview.api.snap_photo()'>Take Picture</button>",
      "    <button class='btn red' onclick='pywebview.api.toggle_record()'>Record Audio</button>",
      "    <button class='btn green' onclick='pywebview.api.list_apps()'>Find Apps</button>",
      "    <button class='btn' onclick='pywebview.api.check_auto()'>Check Automation</button>",
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
      "def check_auto():",
      "    enabled = AndroidNative.isAccessibilityEnabled()",
      "    if enabled:",
      "        AndroidNative.vibrate(80)",
      "        log('Automation Engine is ACTIVE. Python can tap, swipe, and click buttons in any app.')",
      "    else:",
      "        log('Automation is OFF. Return to launcher and tap Enable Automation.')"
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

    function refreshPerms() {
      try {
        const p = JSON.parse(AndroidNative.hasPermissions());
        const hwBtn = document.getElementById("_host_perm_hw");
        const accBtn = document.getElementById("_host_perm_acc");
        if (p.camera && p.mic) {
          hwBtn.classList.add("on");
          hwBtn.innerText = "Camera & Mic: ON";
        } else {
          hwBtn.classList.remove("on");
          hwBtn.innerText = "Grant Camera & Mic";
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
