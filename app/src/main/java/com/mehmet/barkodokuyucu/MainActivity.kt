package com.mehmet.barkodokuyucu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity(), RecognitionListener {
    companion object {
        private const val REQ_MIC = 7001
        private const val REQ_ASSISTANT = 7002
        private const val KEY_ALIAS = "raphael_openai_key"
        private const val PREFS = "raphael_secure_v05"
        private const val NOTES_KEY = "notes"
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
        window.statusBarColor = android.graphics.Color.rgb(4, 10, 17)
        window.navigationBarColor = android.graphics.Color.rgb(4, 10, 17)

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
                    override fun onStart(utteranceId: String?) { }
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

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasMic(): Boolean = hasPermission(Manifest.permission.RECORD_AUDIO)

    private fun requestMic() = runOnUiThread {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun assistantPermissionList(): Array<String> {
        val p = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.READ_MEDIA_IMAGES
        else p += Manifest.permission.READ_EXTERNAL_STORAGE
        return p.distinct().toTypedArray()
    }

    private fun requestAssistantPermissions() = runOnUiThread {
        val missing = assistantPermissionList().filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            js("window.__raphaelNativePermissions&&window.__raphaelNativePermissions(${q(permissionSummary())});")
        } else {
            requestPermissions(missing.toTypedArray(), REQ_ASSISTANT)
        }
    }

    private fun permissionSummary(): String {
        val o = JSONObject()
        o.put("microphone", hasPermission(Manifest.permission.RECORD_AUDIO))
        o.put("contacts", hasPermission(Manifest.permission.READ_CONTACTS))
        o.put("phone", hasPermission(Manifest.permission.CALL_PHONE))
        o.put("calendarRead", hasPermission(Manifest.permission.READ_CALENDAR))
        o.put("calendarWrite", hasPermission(Manifest.permission.WRITE_CALENDAR))
        o.put(
            "photos",
            if (Build.VERSION.SDK_INT >= 33) hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
            else hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
        return o.toString()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            js("window.__raphaelNativeMicPermission&&window.__raphaelNativeMicPermission($granted);")
        }
        if (requestCode == REQ_ASSISTANT) {
            js("window.__raphaelNativePermissions&&window.__raphaelNativePermissions(${q(permissionSummary())});")
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
        try { sr.cancel() } catch (_: Exception) { }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.ifBlank { "tr-TR" })
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        try {
            sr.startListening(intent)
        } catch (_: Exception) {
            js("window.__raphaelNativeSpeechError&&window.__raphaelNativeSpeechError('start-failed');")
            js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
        }
    }

    private fun stopListening(cancel: Boolean) = runOnUiThread {
        try {
            if (cancel) recognizer?.cancel() else recognizer?.stopListening()
        } catch (_: Exception) { }
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

    private fun getOrCreateSecretKey(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun saveApiKeySecure(value: String): Boolean {
        val keyText = value.trim()
        if (!keyText.startsWith("sk-")) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(keyText.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString("api_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("api_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readApiKey(): String? {
        val ivText = prefs.getString("api_iv", null) ?: return null
        val dataText = prefs.getString("api_data", null) ?: return null
        return try {
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val data = Base64.decode(dataText, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearApiKey() {
        prefs.edit().remove("api_iv").remove("api_data").apply()
    }

    private fun aiInstructions(): String = """
Sen RAPHAEL'sin. Mehmet'in Android telefonundaki kişisel yapay zeka asistanısın.
Türkçe konuş. Kısa, doğal ve eylem odaklı ol. Kullanıcının cihazında yalnızca aşağıdaki kontrollü araçlar var.
Her yanıtında SADECE geçerli bir JSON nesnesi döndür. Markdown, kod bloğu veya ek metin kullanma.
Şema:
{
  "reply":"Kullanıcıya söylenecek kısa Türkçe cevap",
  "action":{
    "name":"none|call_number|call_contact|create_calendar|search_photos|save_note|list_notes|open_app",
    "args":{},
    "requires_confirmation":false
  }
}
Kurallar:
- Tek yanıtta en fazla bir cihaz eylemi seç.
- call_number args: {"number":"..."}; requires_confirmation her zaman true.
- call_contact args: {"name":"..."}; requires_confirmation her zaman true.
- create_calendar args: {"title":"...","start_local":"ISO-8601","end_local":"ISO-8601","notes":"..."}. Kullanıcı bitiş belirtmezse 1 saat varsay. Geçerli tarih/saat üret.
- search_photos args: {"keyword":"","since_local":"ISO-8601 veya boş","until_local":"ISO-8601 veya boş"}. Tarih veya dosya adı üzerinden arama yap.
- save_note args: {"text":"..."}.
- list_notes args: {}.
- open_app args: {"app":"whatsapp|gmail|maps|youtube|chrome|camera|gallery|calendar|contacts|phone"}.
- Kullanıcı yalnızca sohbet ediyorsa action.name=none.
- Desteklenmeyen cihaz işlemlerini yaptığını iddia etme.
- Eylem gerçekten tamamlanmadan 'yaptım', 'aradım', 'ekledim' deme; önce niyetini söyle. Uygulama sonucu ayrıca bildirecek.
- Kritik veya geri döndürülemez işlem uydurma. Ödeme, mesaj gönderme, dosya silme gibi araçlar bu sürümde yok.
- DEVICE_CONTEXT içinde saat, geçmiş konuşma, görevler ve hafıza notları bulunabilir. Gerektiğinde bunlardan yararlan.
""".trimIndent()

    private fun askAi(text: String, contextJson: String) {
        val key = readApiKey()
        if (key.isNullOrBlank()) {
            js("window.__raphaelAiError&&window.__raphaelAiError('API_KEY_MISSING');")
            return
        }
        network.execute {
            try {
                val payload = JSONObject()
                payload.put("model", "gpt-5.6-luna")
                payload.put("instructions", aiInstructions())
                payload.put("input", text + "\n\nDEVICE_CONTEXT:\n" + contextJson)
                payload.put("max_output_tokens", 900)

                val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val msg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP $code"
                    } catch (_: Exception) { "HTTP $code" }
                    js("window.__raphaelAiError&&window.__raphaelAiError(${q(msg)});")
                } else {
                    val output = extractOutputText(JSONObject(body))
                    if (output.isBlank()) {
                        js("window.__raphaelAiError&&window.__raphaelAiError('AI_EMPTY_RESPONSE');")
                    } else {
                        js("window.__raphaelAiResult&&window.__raphaelAiResult(${q(output)});")
                    }
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

    private fun result(ok: Boolean, message: String = "", extra: JSONObject? = null): String {
        val o = extra ?: JSONObject()
        o.put("ok", ok)
        if (message.isNotBlank()) o.put("message", message)
        return o.toString()
    }

    private fun findContactEntries(name: String, limit: Int = 5): JSONArray {
        val arr = JSONArray()
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return arr
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%${name.trim()}%")
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { c ->
            val n = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val p = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext() && arr.length() < limit) {
                val o = JSONObject()
                o.put("name", if (n >= 0) c.getString(n) ?: "" else "")
                o.put("number", if (p >= 0) c.getString(p) ?: "" else "")
                arr.put(o)
            }
        }
        return arr
    }

    private fun callNumberInternal(number: String): String {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) return result(false, "permission_phone")
        val clean = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (clean.isBlank()) return result(false, "invalid_number")
        return try {
            runOnUiThread {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}")))
            }
            result(true, "call_started", JSONObject().put("number", clean))
        } catch (e: Exception) {
            result(false, e.message ?: "call_failed")
        }
    }

    private fun callContactInternal(name: String): String {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return result(false, "permission_contacts")
        val matches = findContactEntries(name, 5)
        if (matches.length() == 0) return result(false, "contact_not_found")
        val first = matches.optJSONObject(0) ?: return result(false, "contact_not_found")
        val r = JSONObject(callNumberInternal(first.optString("number")))
        r.put("contact", first.optString("name"))
        return r.toString()
    }

    private fun findWritableCalendar(): Long? {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) return null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        val selection = "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndex(CalendarContract.Calendars._ID)
            if (c.moveToFirst() && idCol >= 0) return c.getLong(idCol)
        }
        return null
    }

    private fun createCalendarEventInternal(
        title: String,
        startMillisText: String,
        endMillisText: String,
        notes: String
    ): String {
        if (!hasPermission(Manifest.permission.READ_CALENDAR) || !hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return result(false, "permission_calendar")
        }
        val start = startMillisText.toLongOrNull() ?: return result(false, "invalid_start")
        val end = endMillisText.toLongOrNull()?.takeIf { it > start } ?: (start + 60 * 60 * 1000)
        val calId = findWritableCalendar() ?: return result(false, "no_writable_calendar")
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.TITLE, title.ifBlank { "RAPHAEL etkinliği" })
                put(CalendarContract.Events.DESCRIPTION, notes)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) result(false, "calendar_insert_failed")
            else result(true, "calendar_created", JSONObject().put("eventUri", uri.toString()))
        } catch (e: Exception) {
            result(false, e.message ?: "calendar_failed")
        }
    }

    private fun searchPhotosInternal(keyword: String, sinceText: String, untilText: String, limit: Int): String {
        val photoPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!hasPermission(photoPermission)) return result(false, "permission_photos")
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN
        )
        if (Build.VERSION.SDK_INT >= 29) projection += MediaStore.Images.Media.RELATIVE_PATH
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (keyword.isNotBlank()) {
            clauses += "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            args += "%${keyword.trim()}%"
        }
        sinceText.toLongOrNull()?.takeIf { it > 0 }?.let {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN}>=?"
            args += it.toString()
        }
        untilText.toLongOrNull()?.takeIf { it > 0 }?.let {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN}<=?"
            args += it.toString()
        }
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val arr = JSONArray()
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                args.takeIf { it.isNotEmpty() }?.toTypedArray(),
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val pathCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1
                val max = limit.coerceIn(1, 50)
                while (c.moveToNext() && arr.length() < max) {
                    val id = if (idCol >= 0) c.getLong(idCol) else 0L
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val o = JSONObject()
                    o.put("id", id)
                    o.put("name", if (nameCol >= 0) c.getString(nameCol) ?: "" else "")
                    o.put("dateTaken", if (dateCol >= 0) c.getLong(dateCol) else 0L)
                    o.put("path", if (pathCol >= 0) c.getString(pathCol) ?: "" else "")
                    o.put("uri", uri.toString())
                    arr.put(o)
                }
            }
            return result(true, "photos_found", JSONObject().put("items", arr).put("count", arr.length()))
        } catch (e: Exception) {
            return result(false, e.message ?: "photo_search_failed")
        }
    }

    private fun openPhotoInternal(uriText: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriText), "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread { startActivity(intent) }
            result(true, "photo_opened")
        } catch (e: Exception) {
            result(false, e.message ?: "photo_open_failed")
        }
    }

    private fun notesArray(): JSONArray {
        return try { JSONArray(prefs.getString(NOTES_KEY, "[]") ?: "[]") }
        catch (_: Exception) { JSONArray() }
    }

    private fun saveNoteInternal(text: String): String {
        val t = text.trim()
        if (t.isBlank()) return result(false, "empty_note")
        return try {
            val arr = notesArray()
            val o = JSONObject()
            o.put("id", System.currentTimeMillis().toString())
            o.put("text", t)
            o.put("createdAt", System.currentTimeMillis())
            arr.put(o)
            prefs.edit().putString(NOTES_KEY, arr.toString()).apply()
            result(true, "note_saved", JSONObject().put("note", o))
        } catch (e: Exception) {
            result(false, e.message ?: "note_failed")
        }
    }

    private fun openAppInternal(nameRaw: String): String {
        val name = nameRaw.trim().lowercase(Locale("tr", "TR"))
        return try {
            val intent: Intent? = when (name) {
                "whatsapp" -> packageManager.getLaunchIntentForPackage("com.whatsapp")
                "gmail" -> packageManager.getLaunchIntentForPackage("com.google.android.gm")
                "maps", "haritalar" -> packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                "youtube" -> packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                "chrome" -> packageManager.getLaunchIntentForPackage("com.android.chrome")
                "camera", "kamera" -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                "gallery", "galeri" -> Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                "calendar", "takvim" -> Intent(Intent.ACTION_VIEW, CalendarContract.Events.CONTENT_URI)
                "contacts", "rehber" -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                "phone", "telefon" -> Intent(Intent.ACTION_DIAL)
                else -> null
            }
            if (intent == null || intent.resolveActivity(packageManager) == null) {
                result(false, "app_not_found")
            } else {
                runOnUiThread { startActivity(intent) }
                result(true, "app_opened", JSONObject().put("app", name))
            }
        } catch (e: Exception) {
            result(false, e.message ?: "app_open_failed")
        }
    }

    private fun deviceContext(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = TimeZone.getDefault()
        val o = JSONObject()
        o.put("localDateTime", f.format(Date()))
        o.put("timezone", TimeZone.getDefault().id)
        o.put("locale", Locale.getDefault().toLanguageTag())
        o.put("androidSdk", Build.VERSION.SDK_INT)
        o.put("permissions", JSONObject(permissionSummary()))
        return o.toString()
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

    override fun onReadyForSpeech(params: Bundle?) {
        js("window.__raphaelNativeSpeechStart&&window.__raphaelNativeSpeechStart();")
    }
    override fun onBeginningOfSpeech() {
        js("window.__raphaelNativeSpeechBeginning&&window.__raphaelNativeSpeechBeginning();")
    }
    override fun onRmsChanged(rmsdB: Float) { }
    override fun onBufferReceived(buffer: ByteArray?) { }
    override fun onEndOfSpeech() {
        js("window.__raphaelNativeSpeechEndOfSpeech&&window.__raphaelNativeSpeechEndOfSpeech();")
    }
    override fun onError(error: Int) {
        js("window.__raphaelNativeSpeechError&&window.__raphaelNativeSpeechError(${q(errName(error))});")
        js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
    }
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            js("window.__raphaelNativeSpeechResult&&window.__raphaelNativeSpeechResult(${q(text)},true);")
        }
        js("window.__raphaelNativeSpeechEnd&&window.__raphaelNativeSpeechEnd();")
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            js("window.__raphaelNativeSpeechResult&&window.__raphaelNativeSpeechResult(${q(text)},false);")
        }
    }
    override fun onEvent(eventType: Int, params: Bundle?) { }

    inner class NativeBridge {
        @JavascriptInterface fun hasMicrophonePermission(): Boolean = hasMic()
        @JavascriptInterface fun requestMicrophonePermission() = requestMic()
        @JavascriptInterface fun requestAssistantPermissions() = this@MainActivity.requestAssistantPermissions()
        @JavascriptInterface fun assistantPermissions(): String = permissionSummary()
        @JavascriptInterface fun startListening(language: String) = this@MainActivity.startListening(language)
        @JavascriptInterface fun stopListening() = this@MainActivity.stopListening(false)
        @JavascriptInterface fun cancelListening() = this@MainActivity.stopListening(true)
        @JavascriptInterface fun speak(text: String) = this@MainActivity.speak(text)
        @JavascriptInterface fun setHandsFreeActive(active: Boolean) = runOnUiThread {
            if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        @JavascriptInterface fun saveApiKey(key: String): Boolean = saveApiKeySecure(key)
        @JavascriptInterface fun hasApiKey(): Boolean = !readApiKey().isNullOrBlank()
        @JavascriptInterface fun clearApiKey() = this@MainActivity.clearApiKey()
        @JavascriptInterface fun askAI(text: String, contextJson: String) = this@MainActivity.askAi(text, contextJson)
        @JavascriptInterface fun deviceContext(): String = this@MainActivity.deviceContext()

        @JavascriptInterface fun findContacts(name: String): String {
            if (!hasPermission(Manifest.permission.READ_CONTACTS)) return result(false, "permission_contacts")
            val items = findContactEntries(name, 10)
            return result(true, "contacts_found", JSONObject().put("items", items).put("count", items.length()))
        }
        @JavascriptInterface fun callNumber(number: String): String = callNumberInternal(number)
        @JavascriptInterface fun callContact(name: String): String = callContactInternal(name)
        @JavascriptInterface fun createCalendarEvent(title: String, startMillis: String, endMillis: String, notes: String): String =
            createCalendarEventInternal(title, startMillis, endMillis, notes)
        @JavascriptInterface fun searchPhotos(keyword: String, sinceMillis: String, untilMillis: String, limit: Int): String =
            searchPhotosInternal(keyword, sinceMillis, untilMillis, limit)
        @JavascriptInterface fun openPhoto(uri: String): String = openPhotoInternal(uri)
        @JavascriptInterface fun saveNote(text: String): String = saveNoteInternal(text)
        @JavascriptInterface fun listNotes(): String = result(true, "notes", JSONObject().put("items", notesArray()))
        @JavascriptInterface fun openApp(name: String): String = openAppInternal(name)
    }

    override fun onDestroy() {
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null
        network.shutdownNow()
        try { webView.removeJavascriptInterface("RAPHAELNative"); webView.destroy() } catch (_: Exception) { }
        super.onDestroy()
    }
}
