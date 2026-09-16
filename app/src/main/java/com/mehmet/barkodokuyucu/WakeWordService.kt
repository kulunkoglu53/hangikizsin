package com.mehmet.barkodokuyucu

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class WakeWordService : Service(), RecognitionListener {
    companion object {
        const val PREFS = "raphael_wake_service_v07"
        const val KEY_WAKE = "wake_word"
        const val KEY_PENDING_COMMAND = "pending_command"
        const val ACTION_UPDATE_WAKE = "com.raphael.assistant.UPDATE_WAKE"
        private const val CHANNEL_ID = "raphael_wake_channel"
        private const val NOTIFICATION_ID = 7070
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var mode = "wake"
    private var wakeWord = "raphael"
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> startWakeListening(450)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> stopRecognition()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeWord = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WAKE, "raphael") ?: "raphael"
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ekran kapalıyken “${wakeWord.uppercase(Locale("tr", "TR"))}” komutunu dinliyor"))
        initTts()
        registerScreenReceiver()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (!power.isInteractive) startWakeListening(600)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_WAKE) {
            wakeWord = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WAKE, "raphael") ?: "raphael"
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification("Ekran kapalıyken “${wakeWord.uppercase(Locale("tr", "TR"))}” komutunu dinliyor"))
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "RAPHAEL Arka Plan Dinleme", NotificationManager.IMPORTANCE_LOW).apply {
                description = "RAPHAEL uyandırma kelimesini ekran kapalıyken dinler."
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("RAPHAEL aktif")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (_: Exception) {
            try { SpeechRecognizer.createSpeechRecognizer(this) } catch (_: Exception) { null }
        }
    }

    private fun startWakeListening(delay: Long = 200) {
        main.postDelayed({
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (power.isInteractive || listening) return@postDelayed
            mode = "wake"
            beginRecognition()
        }, delay)
    }

    private fun startCommandListening(delay: Long = 250) {
        main.postDelayed({
            mode = "command"
            beginRecognition()
        }, delay)
    }

    private fun beginRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        stopRecognition()
        recognizer = createRecognizer()?.also { it.setRecognitionListener(this) }
        val sr = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            listening = true
            sr.startListening(intent)
        } catch (_: Exception) {
            listening = false
            restartAfterError()
        }
    }

    private fun stopRecognition() {
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun stripWake(text: String): String? {
        val low = text.lowercase(Locale("tr", "TR"))
        val aliases = mutableListOf(wakeWord.lowercase(Locale("tr", "TR")))
        if (aliases.firstOrNull() == "raphael") aliases.addAll(listOf("rafael", "rapel"))
        for (word in aliases.distinct()) {
            val i = low.indexOf(word)
            if (i >= 0) return text.substring(i + word.length).replace(Regex("^[,.:;\\s-]+"), "").trim()
        }
        return null
    }

    private fun processFinal(text: String) {
        listening = false
        if (mode == "wake") {
            val rest = stripWake(text)
            if (rest == null) {
                restartAfterError(250)
                return
            }
            if (rest.isNotBlank()) {
                deliverCommand(rest)
            } else {
                speakThenListen()
            }
        } else {
            if (text.isNotBlank()) deliverCommand(text) else restartAfterError(350)
        }
    }

    private fun speakThenListen() {
        val engine = tts
        if (engine == null) {
            startCommandListening(200)
            return
        }
        try {
            engine.speak("Dinliyorum.", TextToSpeech.QUEUE_FLUSH, null, "raphael-wake")
        } catch (_: Exception) {
            startCommandListening(200)
        }
    }

    private fun deliverCommand(command: String) {
        if (command.isBlank()) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PENDING_COMMAND, command).apply()
        acquireShortWakeLock()
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("from_wake_service", true)
            }
            startActivity(intent)
        } catch (_: Exception) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification("Komut alındı. RAPHAEL'i aç: $command"))
        }
        stopRecognition()
    }

    private fun acquireShortWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "RAPHAEL:WakeWord")
            lock.acquire(20_000)
        } catch (_: Exception) {}
    }

    private fun restartAfterError(delay: Long = 500) {
        main.postDelayed({
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (!power.isInteractive) startWakeListening(0)
        }, delay)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("tr", "TR"))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "raphael-wake") startCommandListening(180)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == "raphael-wake") startCommandListening(180)
                    }
                })
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {
        listening = false
        restartAfterError(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900 else 450)
    }
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        processFinal(text)
    }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        stopRecognition()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
