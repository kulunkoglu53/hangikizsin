package com.mehmet.barkodokuyucu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity(), RecognitionListener {
    companion object {
        private const val REQ_PERMISSIONS = 8101
        private const val KEY_ALIAS = "raphael_openai_key_v06"
        private const val PREFS = "raphael_secure_v06"
    }

    private lateinit var webView: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("from_wake_service", false) == true) {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
        window.statusBarColor = android.graphics.Color.rgb(6, 9, 14)
        window.navigationBarColor = android.graphics.Color.rgb(6, 9, 14)

        webView = WebView(this)
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                js("window.__raphaelNativeReady&&window.__raphaelNativeReady();")
            }
        }
        webView.addJavascriptInterface(NativeBridge(), "RAPHAELNative")

        initSpeech()
        initTts()
        if (hasMic()) startWakeWordService()
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("from_wake_service", false) == true && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun initSpeech() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val lang = engine.setLanguage(Locale("tr", "TR"))
                ttsReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setSpeechRate(1.0f)
                engine.setPitch(1.0f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        js("window.__raphaelNativeTtsDone&&window.__raphaelNativeTtsDone();")
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        js("window.__raphaelNativeTtsDone&&window.__raphaelNativeTtsDone();")
                    }
                })
            }
        }
    }

    private fun hasMic(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestCorePermissions() = runOnUiThread {
        val missing = mutableListOf<String>()
        if (!hasMic()) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications()) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isEmpty()) {
            startWakeWordService()
            js("window.__raphaelNativePermissions&&window.__raphaelNativePermissions(true);")
        } else {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (hasMic()) startWakeWordService()
            js("window.__raphaelNativePermissions&&window.__raphaelNativePermissions(${hasMic()});")
        }
    }

    private fun startWakeWordService() {
        if (!hasMic()) return
        try {
            val i = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun setWakeWord(value: String) {
        val word = value.trim().lowercase(Locale("tr", "TR")).ifBlank { "raphael" }
        getSharedPreferences(WakeWordService.PREFS, MODE_PRIVATE)
            .edit()
            .putString(WakeWordService.KEY_WAKE, word)
            .apply()
        try {
            val i = Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_UPDATE_WAKE }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun consumeBackgroundCommand(): String {
        val p = getSharedPreferences(WakeWordService.PREFS, MODE_PRIVATE)
        val command = p.getString(WakeWordService.KEY_PENDING_COMMAND, "").orEmpty()
        if (command.isNotBlank()) p.edit().remove(WakeWordService.KEY_PENDING_COMMAND).apply()
        return command
    }

    private fun startListening(language: String) = runOnUiThread {
        if (!hasMic()) {
            js("window.__raphaelNativeSpeechError&&window.__raphaelNativeSpeechError('not-allowed');")
            js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
            return@runOnUiThread
        }
        val sr = recognizer
        if (sr == null) {
            js("window.__raphaelNativeSpeechError&&window.__raphaelNativeSpeechError('service-not-available');")
            return@runOnUiThread
        }
        try { sr.cancel() } catch (_: Exception) {}
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.ifBlank { "tr-TR" })
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        try { sr.startListening(intent) }
        catch (_: Exception) {
            js("window.__raphaelNativeSpeechError&&window.__raphaelNativeSpeechError('start-failed');")
        }
    }

    private fun stopListening(cancel: Boolean) = runOnUiThread {
        try { if (cancel) recognizer?.cancel() else recognizer?.stopListening() } catch (_: Exception) {}
    }

    private fun speak(text: String) = runOnUiThread {
        val engine = tts
        if (engine == null || !ttsReady || text.isBlank()) {
            js("window.__raphaelNativeTtsDone&&window.__raphaelNativeTtsDone();")
            return@runOnUiThread
        }
        try {
            engine.stop()
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "raphael-${System.currentTimeMillis()}")
        } catch (_: Exception) {
            js("window.__raphaelNativeTtsDone&&window.__raphaelNativeTtsDone();")
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun saveApiKeySecure(value: String): Boolean {
        val text = value.trim()
        if (!text.startsWith("sk-")) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString("api_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("api_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
            true
        } catch (_: Exception) { false }
    }

    private fun readApiKey(): String? {
        val ivText = prefs.getString("api_iv", null) ?: return null
        val dataText = prefs.getString("api_data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun clearApiKey() {
        prefs.edit().remove("api_iv").remove("api_data").apply()
    }

    private fun askAi(text: String, contextJson: String, research: Boolean) {
        val key = readApiKey()
        if (key.isNullOrBlank()) {
            js("window.__raphaelAiError&&window.__raphaelAiError('API_KEY_MISSING');")
            return
        }
        network.execute {
            try {
                val payload = JSONObject().apply {
                    put("model", "gpt-5.6-luna")
                    put("instructions", "Sen RAPHAEL adlı Türkçe kişisel asistansın. Kısa, net ve eylem odaklı cevap ver. Kullanıcının görev ve plan bağlamını dikkate al. Araştırma istenirse güncel kaynaklara dayan ve sonucu Türkçe özetle.")
                    put("input", text + "\n\nRAPHAEL_CONTEXT:\n" + contextJson)
                    put("max_output_tokens", if (research) 1200 else 700)
                    if (research) put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                }
                val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 70_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP $code"
                    } catch (_: Exception) { "HTTP $code" }
                    js("window.__raphaelAiError&&window.__raphaelAiError(${q(message)});")
                } else {
                    val output = extractOutputText(JSONObject(body))
                    if (output.isBlank()) js("window.__raphaelAiError&&window.__raphaelAiError('AI_EMPTY_RESPONSE');")
                    else js("window.__raphaelAiResult&&window.__raphaelAiResult(${q(output)});")
                }
                connection.disconnect()
            } catch (e: Exception) {
                js("window.__raphaelAiError&&window.__raphaelAiError(${q(e.message ?: "AI_CONNECTION_ERROR")});")
            }
        }
    }

    private fun extractOutputText(root: JSONObject): String {
        val direct = root.optString("output_text")
        if (direct.isNotBlank()) return direct
        val out = root.optJSONArray("output") ?: return ""
        for (i in 0 until out.length()) {
            val item = out.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun scheduleReminder(id: String, title: String, body: String, whenMillis: Long): Boolean {
        if (whenMillis <= System.currentTimeMillis()) return false
        return try {
            val intent = Intent(this, ReminderReceiver::class.java).apply {
                putExtra("id", id)
                putExtra("title", title)
                putExtra("body", body)
            }
            val requestCode = id.hashCode()
            val pending = PendingIntent.getBroadcast(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
            true
        } catch (_: Exception) { false }
    }

    private fun cancelReminder(id: String) {
        val intent = Intent(this, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
    }

    private fun openChatGPTResearch(query: String): Boolean {
        if (query.isBlank()) return false
        val prompt = "Şunu araştır ve Türkçe, kısa ama kaynaklı şekilde özetle: $query"
        return try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
                setPackage("com.openai.chatgpt")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (share.resolveActivity(packageManager) != null) {
                startActivity(share)
            } else {
                val url = "https://chatgpt.com/?q=" + URLEncoder.encode(prompt, "UTF-8")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            true
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")))
                true
            } catch (_: Exception) { false }
        }
    }

    private fun openApp(name: String): Boolean {
        val packageName = when (name.lowercase(Locale.ROOT)) {
            "chatgpt", "chat gpt" -> "com.openai.chatgpt"
            "whatsapp" -> "com.whatsapp"
            "gmail" -> "com.google.android.gm"
            "youtube" -> "com.google.android.youtube"
            "maps", "haritalar" -> "com.google.android.apps.maps"
            else -> ""
        }
        return try {
            if (packageName.isNotBlank()) {
                val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
                startActivity(launch)
                true
            } else false
        } catch (_: Exception) { false }
    }

    private fun q(value: String): String = JSONObject.quote(value)
    private fun js(code: String) { main.post { if (!isFinishing) webView.evaluateJavascript(code, null) } }

    override fun onReadyForSpeech(params: Bundle?) {
        js("window.__raphaelNativeSpeechStart&&window.__raphaelNativeSpeechStart();")
    }
    override fun onBeginningOfSpeech() {
        js("window.__raphaelNativeSpeechBeginning&&window.__raphaelNativeSpeechBeginning();")
    }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        js("window.__raphaelNativeSpeechEndOfSpeech&&window.__raphaelNativeSpeechEndOfSpeech();")
    }
    override fun onError(error: Int) {
        val name = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer-busy"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
            else -> "speech-error-$error"
        }
        js("window.__raphaelNativeSpeechError&&window.__raphaelNativeSpeechError(${q(name)});")
        js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
    }
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) js("window.__raphaelNativeSpeechResult&&window.__raphaelNativeSpeechResult(${q(text)},true);")
        js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) js("window.__raphaelNativeSpeechResult&&window.__raphaelNativeSpeechResult(${q(text)},false);")
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}

    inner class NativeBridge {
        @JavascriptInterface fun hasMicrophonePermission(): Boolean = hasMic()
        @JavascriptInterface fun requestCorePermissions() { this@MainActivity.requestCorePermissions() }
        @JavascriptInterface fun startListening(language: String) = this@MainActivity.startListening(language)
        @JavascriptInterface fun stopListening() = this@MainActivity.stopListening(false)
        @JavascriptInterface fun cancelListening() = this@MainActivity.stopListening(true)
        @JavascriptInterface fun speak(text: String) = this@MainActivity.speak(text)
        @JavascriptInterface fun saveApiKey(value: String): Boolean = saveApiKeySecure(value)
        @JavascriptInterface fun hasApiKey(): Boolean = !readApiKey().isNullOrBlank()
        @JavascriptInterface fun clearApiKey() { this@MainActivity.clearApiKey() }
        @JavascriptInterface fun askAi(text: String, contextJson: String, research: Boolean) = this@MainActivity.askAi(text, contextJson, research)
        @JavascriptInterface fun scheduleReminder(id: String, title: String, body: String, whenMillis: Long): Boolean = this@MainActivity.scheduleReminder(id, title, body, whenMillis)
        @JavascriptInterface fun cancelReminder(id: String) = this@MainActivity.cancelReminder(id)
        @JavascriptInterface fun openChatGPTResearch(query: String): Boolean = this@MainActivity.openChatGPTResearch(query)
        @JavascriptInterface fun openApp(name: String): Boolean = this@MainActivity.openApp(name)
        @JavascriptInterface fun setWakeWord(value: String) { this@MainActivity.setWakeWord(value) }
        @JavascriptInterface fun consumeBackgroundCommand(): String = this@MainActivity.consumeBackgroundCommand()
        @JavascriptInterface fun now(): Long = System.currentTimeMillis()
    }

    override fun onDestroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        network.shutdownNow()
        try { webView.removeJavascriptInterface("RAPHAELNative"); webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
