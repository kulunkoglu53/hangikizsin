package com.mehmet.barkodokuyucu

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var unique: TextView
    private lateinit var total: TextView
    private lateinit var list: ListView
    private lateinit var export: Button
    private lateinit var torch: Button
    private lateinit var scanner: BarcodeScanner
    private val executor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var torchOn = false
    private val entries = LinkedHashMap<String, BarcodeEntry>()
    private val active = mutableMapOf<String, Long>()
    private val rows = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private val prefs by lazy { getSharedPreferences("barcode_store", Context.MODE_PRIVATE) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR"))
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Kamera izni gerekli."
    }

    private val saveExcel = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { XlsxExporter.writeWorkbook(it, entries.values.toList()) }
            Toast.makeText(this, "Excel dosyası kaydedildi.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Excel oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BarcodeScanning.getClient()
        buildUi(); restore(); refresh()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245,246,248)) }
        root.addView(TextView(this).apply {
            text = "Barkod Okuyucu"; textSize = 22f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(17,24,39)); setPadding(dp(16),dp(14),dp(16),dp(14))
        })
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10),dp(8),dp(10),dp(8)) }
        unique = metric("Benzersiz: 0"); total = metric("Toplam: 0")
        stats.addView(unique, LinearLayout.LayoutParams(0,dp(42),1f).apply{marginEnd=dp(5)})
        stats.addView(total, LinearLayout.LayoutParams(0,dp(42),1f).apply{marginStart=dp(5)})
        root.addView(stats)

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(ScanOverlayView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,0.42f))

        status = TextView(this).apply { text="Kamera hazırlanıyor…"; setPadding(dp(14),dp(8),dp(14),dp(8)); setTextColor(Color.rgb(21,128,61)) }
        root.addView(status)

        adapter = object: ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1, rows) {
            override fun getView(position:Int, convertView:android.view.View?, parent:ViewGroup):android.view.View {
                val v=super.getView(position,convertView,parent)
                val item=entries.values.sortedByDescending{it.lastSeen}.getOrNull(position)
                v.findViewById<TextView>(android.R.id.text1).apply { text=item?.value ?: ""; textSize=17f }
                v.findViewById<TextView>(android.R.id.text2).text=item?.let{"${it.format} • Adet: ${it.count} • ${timeFmt.format(Date(it.lastSeen))}"} ?: ""
                return v
            }
        }
        list = ListView(this).apply { adapter=this@MainActivity.adapter; dividerHeight=1; setBackgroundColor(Color.WHITE) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,0.58f))

        val buttons=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(8),dp(8),dp(8),dp(10)); setBackgroundColor(Color.WHITE) }
        torch=Button(this).apply { text="Fener"; setOnClickListener{ toggleTorch() } }
        export=Button(this).apply { text="Excel'e Aktar"; setOnClickListener{ exportExcel() } }
        val clear=Button(this).apply { text="Temizle"; setOnClickListener{ clearAll() } }
        buttons.addView(torch,LinearLayout.LayoutParams(0,dp(52),1f))
        buttons.addView(export,LinearLayout.LayoutParams(0,dp(52),1.4f))
        buttons.addView(clear,LinearLayout.LayoutParams(0,dp(52),1f))
        root.addView(buttons)
        setContentView(root)
    }

    private fun metric(t:String)=TextView(this).apply { text=t; gravity=Gravity.CENTER; textSize=16f; setBackgroundColor(Color.WHITE) }

    private fun startCamera() {
        val f=ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val provider=f.get()
                val p=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)}
                val a=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                a.setAnalyzer(executor){ proxy -> analyze(proxy) }
                provider.unbindAll()
                camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,a)
                status.text="Hazır — barkodu kameraya gösterin."
                torch.isEnabled=camera?.cameraInfo?.hasFlashUnit()==true
            } catch(e:Exception){ status.text="Kamera başlatılamadı: ${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy:ImageProxy) {
        val media=proxy.image ?: run { proxy.close(); return }
        val image=InputImage.fromMediaImage(media,proxy.imageInfo.rotationDegrees)
        scanner.process(image).addOnSuccessListener{ handle(it) }.addOnCompleteListener{ proxy.close() }
    }

    private fun handle(barcodes:List<Barcode>) {
        val now=System.currentTimeMillis(); active.entries.removeAll{now-it.value>2500}
        var changed=false; var last=""
        for(b in barcodes){
            val value=b.rawValue?.trim().orEmpty(); if(value.isBlank()) continue
            if(active.put(value,now)!=null) continue
            val old=entries[value]
            if(old==null) entries[value]=BarcodeEntry(value,formatName(b.format),1,now,now)
            else { old.count++; old.lastSeen=now; old.format=formatName(b.format) }
            changed=true; last=value
        }
        if(changed){ save(); runOnUiThread{ refresh(); status.text="Okundu: $last"; feedback() } }
    }

    private fun formatName(f:Int)=when(f){
        Barcode.FORMAT_EAN_13->"EAN-13"; Barcode.FORMAT_EAN_8->"EAN-8"; Barcode.FORMAT_UPC_A->"UPC-A"; Barcode.FORMAT_UPC_E->"UPC-E";
        Barcode.FORMAT_CODE_128->"CODE 128"; Barcode.FORMAT_CODE_39->"CODE 39"; Barcode.FORMAT_CODE_93->"CODE 93"; Barcode.FORMAT_CODABAR->"CODABAR";
        Barcode.FORMAT_ITF->"ITF"; Barcode.FORMAT_QR_CODE->"QR"; Barcode.FORMAT_DATA_MATRIX->"DATA MATRIX"; Barcode.FORMAT_PDF417->"PDF417"; Barcode.FORMAT_AZTEC->"AZTEC"; else->"BARKOD"
    }

    private fun feedback(){
        try{tone.startTone(ToneGenerator.TONE_PROP_BEEP,100)}catch(_:Exception){}
        try{ val v=getSystemService(VIBRATOR_SERVICE) as Vibrator; if(android.os.Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createOneShot(60,VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(60) }catch(_:Exception){}
    }

    private fun refresh(){ val s=entries.values.sortedByDescending{it.lastSeen}; rows.clear(); rows.addAll(s.map{it.value}); adapter.notifyDataSetChanged(); unique.text="Benzersiz: ${s.size}"; total.text="Toplam: ${s.sumOf{it.count}}"; export.isEnabled=s.isNotEmpty() }
    private fun toggleTorch(){ val c=camera?:return; torchOn=!torchOn; c.cameraControl.enableTorch(torchOn); torch.text=if(torchOn)"Fener Kapat" else "Fener" }
    private fun exportExcel(){ if(entries.isEmpty())return; val name=SimpleDateFormat("yyyyMMdd_HHmm",Locale.US).format(Date()); saveExcel.launch("Barkod_Listesi_$name.xlsx") }
    private fun clearAll(){ if(entries.isEmpty())return; AlertDialog.Builder(this).setTitle("Liste temizlensin mi?").setNegativeButton("Vazgeç",null).setPositiveButton("Temizle"){_,_->entries.clear();active.clear();save();refresh()}.show() }

    private fun save(){ val a=JSONArray(); entries.values.forEach{e->a.put(JSONObject().apply{put("value",e.value);put("format",e.format);put("count",e.count);put("firstSeen",e.firstSeen);put("lastSeen",e.lastSeen)})}; prefs.edit().putString("entries",a.toString()).apply() }
    private fun restore(){ try{ val raw=prefs.getString("entries",null)?:return; val a=JSONArray(raw); for(i in 0 until a.length()){val o=a.getJSONObject(i); val e=BarcodeEntry(o.getString("value"),o.optString("format","BARKOD"),o.optInt("count",1),o.optLong("firstSeen"),o.optLong("lastSeen")); entries[e.value]=e} }catch(_:Exception){} }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onDestroy(){ super.onDestroy(); scanner.close(); executor.shutdown(); if(tone.hashCode()!=0)tone.release() }
}
