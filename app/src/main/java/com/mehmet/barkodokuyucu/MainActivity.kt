package com.mehmet.barkodokuyucu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), RecognitionListener {
    companion object { private const val REQ_MIC = 7001 }

    private lateinit var webView: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(5, 11, 18)
        window.navigationBarColor = android.graphics.Color.rgb(5, 11, 18)

        webView = WebView(this)
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBridge(), "RAPHAELNative")

        initSpeech()
        initTts()
        webView.loadUrl("file:///android_asset/index.html")
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
                val result = engine.setLanguage(Locale("tr", "TR"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setSpeechRate(1.0f)
                engine.setPitch(1.0f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = js("window.__raphaelNativeTtsDone&&window.__raphaelNativeTtsDone();")
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = js("window.__raphaelNativeTtsDone&&window.__raphaelNativeTtsDone();")
                })
            }
        }
    }

    private fun hasMic(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() = runOnUiThread {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            js("window.__raphaelNativeMicPermission&&window.__raphaelNativeMicPermission($granted);")
        }
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
            js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
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
            js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
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
            val id = "raphael-${System.currentTimeMillis()}"
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (_: Exception) {
            js("window.__raphaelNativeTtsDone&&window.__raphaelNativeTtsDone();")
        }
    }

    private fun q(value: String): String = JSONObject.quote(value)
    private fun js(code: String) {
        main.post { if (!isFinishing) webView.evaluateJavascript(code, null) }
    }

    private fun errName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        SpeechRecognizer.ERROR_CLIENT -> "aborted"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer-busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        else -> "unknown"
    }

    override fun onReadyForSpeech(params: Bundle?) = js("window.__raphaelNativeSpeechStart&&window.__raphaelNativeSpeechStart();")
    override fun onBeginningOfSpeech() = js("window.__raphaelNativeSpeechBeginning&&window.__raphaelNativeSpeechBeginning();")
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = js("window.__raphaelNativeSpeechEndOfSpeech&&window.__raphaelNativeSpeechEndOfSpeech();")
    override fun onError(error: Int) {
        js("window.__raphaelNativeSpeechError&&window.__raphaelNativeSpeechError(${q(errName(error))});")
        js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
    }
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) js("window.__raphaelNativeSpeechResult&&window.__raphaelNativeSpeechResult(${q(text)},true);")
        js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) js("window.__raphaelNativeSpeechResult&&window.__raphaelNativeSpeechResult(${q(text)},false);")
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    inner class NativeBridge {
        @JavascriptInterface fun hasMicrophonePermission(): Boolean = hasMic()
        @JavascriptInterface fun requestMicrophonePermission() = requestMic()
        @JavascriptInterface fun startListening(language: String) = this@MainActivity.startListening(language)
        @JavascriptInterface fun stopListening() = this@MainActivity.stopListening(false)
        @JavascriptInterface fun cancelListening() = this@MainActivity.stopListening(true)
        @JavascriptInterface fun speak(text: String) = this@MainActivity.speak(text)
        @JavascriptInterface fun setHandsFreeActive(active: Boolean) = runOnUiThread {
            if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        try { webView.removeJavascriptInterface("RAPHAELNative"); webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
