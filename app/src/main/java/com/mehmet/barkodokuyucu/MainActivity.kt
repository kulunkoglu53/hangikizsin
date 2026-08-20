package com.mehmet.barkodokuyucu

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private enum class ScanMode { LINEAR, QR }

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var unique: TextView
    private lateinit var total: TextView
    private lateinit var list: ListView
    private lateinit var export: Button
    private lateinit var torch: Button
    private lateinit var scanButton: Button
    private lateinit var quantityInput: EditText
    private lateinit var linearRadio: RadioButton
    private lateinit var qrRadio: RadioButton
    private lateinit var scanner: BarcodeScanner

    private val executor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var torchOn = false
    private var scanningActive = false
    private var scanConsumed = false
    private var successfulScan = false
    private var currentMode = ScanMode.LINEAR
    private var pendingQuantity = 1

    private val entries = LinkedHashMap<String, BarcodeEntry>()
    private val rows = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private val prefs by lazy { getSharedPreferences("barcode_store", Context.MODE_PRIVATE) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR"))
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Kamera izni gerekli."
    }

    private val saveXlsx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { XlsxExporter.writeWorkbook(it, entries.values.toList()) }
            Toast.makeText(this, "XLSX dosyası kaydedildi.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "XLSX oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val saveXls = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.ms-excel")
    ) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { LegacyExporters.writeXls(it, entries.values.toList()) }
            Toast.makeText(this, "XLS dosyası kaydedildi.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "XLS oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val saveTxt = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { LegacyExporters.writeTxt(it, entries.values.toList()) }
            Toast.makeText(this, "TXT dosyası kaydedildi.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "TXT oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = createScanner(ScanMode.LINEAR)
        buildUi()
        restore()
        refresh()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 246, 248))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(17, 24, 39))
            setPadding(dp(16), dp(9), dp(16), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "EMIRA HOME  •  İPEKTAÇ"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            letterSpacing = 0.08f
        })
        header.addView(TextView(this).apply {
            text = "Barkod Okuma Cihazı"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(header)

        val modePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        modePanel.addView(TextView(this).apply {
            text = "Okuma türü"
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 81))
        })

        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        linearRadio = RadioButton(this).apply {
            text = "EAN / Düz Barkod"
            isChecked = true
        }
        qrRadio = RadioButton(this).apply { text = "QR" }
        modeGroup.addView(linearRadio, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modeGroup.addView(qrRadio, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modePanel.addView(modeGroup)
        root.addView(modePanel)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        unique = metric("Benzersiz: 0")
        total = metric("Toplam Adet: 0")
        stats.addView(unique, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        stats.addView(total, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
        root.addView(stats)

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(ScanOverlayView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.38f))

        status = TextView(this).apply {
            text = "Kamera hazırlanıyor…"
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(Color.rgb(21, 128, 61))
        }
        root.addView(status)

        val scanControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        val qtyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(6), 0)
        }
        qtyBox.addView(TextView(this).apply {
            text = "Adet"
            textSize = 12f
            setTextColor(Color.rgb(75, 85, 99))
        })
        quantityInput = EditText(this).apply {
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 18f
            setSelectAllOnFocus(true)
        }
        qtyBox.addView(quantityInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        scanControls.addView(qtyBox, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))

        scanButton = Button(this).apply {
            text = "Barkodu Oku — Basılı Tut"
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        beginSingleScan()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        endSingleScan()
                        performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        scanControls.addView(scanButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(scanControls)

        root.addView(TextView(this).apply {
            text = "Örnek: Aynı üründen 10 tane saydıysan Adet = 10 yaz. Barkodu bir kez okut; sistem +10 adet kaydeder."
            textSize = 12f
            setTextColor(Color.rgb(107, 114, 128))
            setPadding(dp(12), 0, dp(12), dp(3))
        })

        root.addView(TextView(this).apply {
            text = "Kayıt düzeltmek veya silmek için listedeki barkodun üzerine basılı tut."
            textSize = 12f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(dp(12), 0, dp(12), dp(6))
        })

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1, rows) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                val item = entries.values.sortedByDescending { it.lastSeen }.getOrNull(position)
                v.findViewById<TextView>(android.R.id.text1).apply {
                    text = item?.value ?: ""
                    textSize = 17f
                }
                v.findViewById<TextView>(android.R.id.text2).text = item?.let {
                    "${it.format} • Adet: ${it.count} • ${timeFmt.format(Date(it.lastSeen))}"
                } ?: ""
                return v
            }
        }

        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            dividerHeight = 1
            setBackgroundColor(Color.WHITE)
            setOnItemLongClickListener { _, _, position, _ ->
                val item = entries.values.sortedByDescending { it.lastSeen }.getOrNull(position)
                if (item != null) showEntryActions(item)
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.62f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(30))
            setBackgroundColor(Color.WHITE)
        }
        torch = Button(this).apply {
            text = "Fener"
            setOnClickListener { toggleTorch() }
        }
        export = Button(this).apply {
            text = "Dışa Aktar"
            setOnClickListener { showExportOptions() }
        }
        val clear = Button(this).apply {
            text = "Temizle"
            setOnClickListener { clearAll() }
        }
        buttons.addView(torch, LinearLayout.LayoutParams(0, dp(52), 1f))
        buttons.addView(export, LinearLayout.LayoutParams(0, dp(52), 1.3f))
        buttons.addView(clear, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(buttons)

        setContentView(root)
    }

    private fun metric(t: String) = TextView(this).apply {
        text = t
        gravity = Gravity.CENTER
        textSize = 16f
        setBackgroundColor(Color.WHITE)
    }

    private fun createScanner(mode: ScanMode): BarcodeScanner {
        val options = if (mode == ScanMode.QR) {
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        } else {
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_ITF
                )
                .build()
        }
        return BarcodeScanning.getClient(options)
    }

    private fun beginSingleScan() {
        if (scanningActive) return

        val qty = quantityInput.text.toString().trim().toIntOrNull()
        if (qty == null || qty < 1 || qty > 99999) {
            Toast.makeText(this, "Adet 1 ile 99999 arasında olmalı.", Toast.LENGTH_SHORT).show()
            quantityInput.requestFocus()
            return
        }

        val selectedMode = if (qrRadio.isChecked) ScanMode.QR else ScanMode.LINEAR
        if (selectedMode != currentMode) {
            try { scanner.close() } catch (_: Exception) {}
            currentMode = selectedMode
            scanner = createScanner(currentMode)
        }

        pendingQuantity = qty
        scanConsumed = false
        successfulScan = false
        scanningActive = true
        quantityInput.isEnabled = false
        linearRadio.isEnabled = false
        qrRadio.isEnabled = false
        scanButton.text = "Okunuyor… İlk barkodda durur"
        status.text = "Barkod aranıyor… Yalnızca 1 sağlam okuma kabul edilecek."
    }

    private fun endSingleScan() {
        scanningActive = false
        quantityInput.isEnabled = true
        linearRadio.isEnabled = true
        qrRadio.isEnabled = true
        scanButton.text = "Barkodu Oku — Basılı Tut"

        if (!successfulScan) {
            status.text = "Okuma durdu; barkod kabul edilmedi. Tekrar deneyin."
        }
        scanConsumed = false
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val provider = f.get()
                val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val a = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                a.setAnalyzer(executor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, a)
                status.text = "Hazır — Adet'i girin, sonra Barkodu Oku tuşuna basılı tutun."
                torch.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            } catch (e: Exception) {
                status.text = "Kamera başlatılamadı: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        if (!scanningActive || scanConsumed) {
            proxy.close()
            return
        }

        val media = proxy.image ?: run {
            proxy.close()
            return
        }

        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener {
                if (scanningActive && !scanConsumed) handleSingle(it)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handleSingle(barcodes: List<Barcode>) {
        if (!scanningActive || scanConsumed) return

        val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() } ?: return
        val value = barcode.rawValue?.trim().orEmpty()
        if (value.isBlank()) return

        scanConsumed = true
        successfulScan = true
        scanningActive = false

        val qty = pendingQuantity.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val old = entries[value]

        if (old == null) {
            entries[value] = BarcodeEntry(value, formatName(barcode.format), qty, now, now)
        } else {
            old.count += qty
            old.lastSeen = now
            old.format = formatName(barcode.format)
        }

        save()
        runOnUiThread {
            refresh()
            feedback()
            status.text = "Okundu: $value  •  +$qty adet eklendi"
            quantityInput.setText("1")
            quantityInput.selectAll()
            quantityInput.isEnabled = true
            linearRadio.isEnabled = true
            qrRadio.isEnabled = true
        }
    }

    private fun dialogPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
        }
    }

    private fun showPanelDialog(panel: LinearLayout): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    private fun dialogTitle(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 21f
        setTextColor(Color.rgb(17, 24, 39))
        setPadding(0, 0, 0, dp(10))
    }

    private fun dialogInfo(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 16f
        setTextColor(Color.rgb(55, 65, 81))
        setPadding(0, 0, 0, dp(14))
    }

    private fun dialogButton(textValue: String): Button {
        return Button(this).apply {
            text = textValue
            textSize = 16f
            minHeight = dp(52)
        }
    }

    private fun showEntryActions(item: BarcodeEntry) {
        val panel = dialogPanel()
        panel.addView(dialogTitle("Barkod işlemleri"))
        panel.addView(dialogInfo("${item.value}\n${item.format} • Mevcut adet: ${item.count}"))

        val edit = dialogButton("ADEDİ DÜZENLE")
        val delete = dialogButton("BARKODU SİL").apply {
            setTextColor(Color.rgb(185, 28, 28))
        }
        val cancel = dialogButton("VAZGEÇ")

        panel.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(8) })
        panel.addView(delete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(8) })
        panel.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        val dialog = showPanelDialog(panel)
        edit.setOnClickListener {
            dialog.dismiss()
            editEntryCount(item)
        }
        delete.setOnClickListener {
            dialog.dismiss()
            confirmDeleteEntry(item)
        }
        cancel.setOnClickListener { dialog.dismiss() }
    }

    private fun editEntryCount(item: BarcodeEntry) {
        val panel = dialogPanel()
        panel.addView(dialogTitle("Adedi düzenle"))
        panel.addView(dialogInfo("Barkod: ${item.value}\nMevcut adet: ${item.count}"))

        panel.addView(TextView(this).apply {
            text = "Yeni adet"
            textSize = 14f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, 0, 0, dp(4))
        })

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(item.count.toString())
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.BLACK)
            setSelectAllOnFocus(true)
            selectAll()
        }
        panel.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(12) })

        val saveButton = dialogButton("KAYDET")
        val cancelButton = dialogButton("VAZGEÇ")
        panel.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(8) })
        panel.addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        val dialog = showPanelDialog(panel)
        input.requestFocus()

        saveButton.setOnClickListener {
            val newCount = input.text.toString().trim().toIntOrNull()
            if (newCount == null || newCount < 1 || newCount > 99999) {
                input.error = "1 ile 99999 arasında bir adet girin"
                return@setOnClickListener
            }

            val oldCount = item.count
            item.count = newCount
            item.lastSeen = System.currentTimeMillis()
            save()
            refresh()
            status.text = "Düzeltildi: ${item.value} • $oldCount → $newCount adet"
            dialog.dismiss()
        }

        cancelButton.setOnClickListener { dialog.dismiss() }
    }

    private fun confirmDeleteEntry(item: BarcodeEntry) {
        val panel = dialogPanel()
        panel.addView(dialogTitle("Barkodu sil"))
        panel.addView(dialogInfo("${item.value}\n${item.count} adet olan bu kayıt tamamen silinecek.\n\nBu işlem geri alınamaz."))

        val deleteButton = dialogButton("EVET, BARKODU SİL").apply {
            setTextColor(Color.rgb(185, 28, 28))
        }
        val cancelButton = dialogButton("VAZGEÇ")
        panel.addView(deleteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(8) })
        panel.addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        val dialog = showPanelDialog(panel)
        deleteButton.setOnClickListener {
            entries.remove(item.value)
            save()
            refresh()
            status.text = "Silindi: ${item.value}"
            dialog.dismiss()
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
    }

    private fun formatName(f: Int) = when (f) {
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "CODE 128"
        Barcode.FORMAT_CODE_39 -> "CODE 39"
        Barcode.FORMAT_CODE_93 -> "CODE 93"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_QR_CODE -> "QR"
        else -> "BARKOD"
    }

    private fun feedback() {
        try { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) } catch (_: Exception) {}
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(90)
            }
        } catch (_: Exception) {}
    }

    private fun refresh() {
        val s = entries.values.sortedByDescending { it.lastSeen }
        rows.clear()
        rows.addAll(s.map { it.value })
        adapter.notifyDataSetChanged()
        unique.text = "Benzersiz: ${s.size}"
        total.text = "Toplam Adet: ${s.sumOf { it.count }}"
        export.isEnabled = s.isNotEmpty()
    }

    private fun toggleTorch() {
        val c = camera ?: return
        torchOn = !torchOn
        c.cameraControl.enableTorch(torchOn)
        torch.text = if (torchOn) "Fener Kapat" else "Fener"
    }

    private fun showExportOptions() {
        if (entries.isEmpty()) return
        val options = arrayOf("Excel (.xlsx)", "Excel (.xls)", "Metin (.txt)")
        AlertDialog.Builder(this)
            .setTitle("Dışa aktarma formatı")
            .setItems(options) { _, which ->
                val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                when (which) {
                    0 -> saveXlsx.launch("Barkod_Listesi_$name.xlsx")
                    1 -> saveXls.launch("Barkod_Listesi_$name.xls")
                    2 -> saveTxt.launch("Barkod_Listesi_$name.txt")
                }
            }
            .show()
    }

    private fun clearAll() {
        if (entries.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Liste temizlensin mi?")
            .setMessage("Okunan tüm barkodlar ve adetleri silinecek.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Temizle") { _, _ ->
                entries.clear()
                save()
                refresh()
                status.text = "Liste temizlendi."
            }
            .show()
    }

    private fun save() {
        val a = JSONArray()
        entries.values.forEach { e ->
            a.put(JSONObject().apply {
                put("value", e.value)
                put("format", e.format)
                put("count", e.count)
                put("firstSeen", e.firstSeen)
                put("lastSeen", e.lastSeen)
            })
        }
        prefs.edit().putString("entries", a.toString()).apply()
    }

    private fun restore() {
        try {
            val raw = prefs.getString("entries", null) ?: return
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val e = BarcodeEntry(
                    o.getString("value"),
                    o.optString("format", "BARKOD"),
                    o.optInt("count", 1),
                    o.optLong("firstSeen"),
                    o.optLong("lastSeen")
                )
                entries[e.value] = e
            }
        } catch (_: Exception) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        try { scanner.close() } catch (_: Exception) {}
        executor.shutdown()
        try { tone.release() } catch (_: Exception) {}
    }
}
