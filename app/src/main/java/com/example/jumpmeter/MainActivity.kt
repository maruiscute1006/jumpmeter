package com.example.jumpmeter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Source(val label: String) {
        USB("USB 웹캠"), BACK("내장 후면"), FRONT("내장 전면")
    }

    private lateinit var previewView: PreviewView
    private lateinit var usbView: AspectSurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var etHeight: EditText
    private lateinit var etWeight: EditText
    private lateinit var etMemo: EditText
    private lateinit var btnSwitch: Button
    private lateinit var usb: UsbCameraSource

    private var source = Source.USB
    private var lens = CameraSelector.DEFAULT_BACK_CAMERA
    private var last: JumpResult? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val analyzer = JumpAnalyzer(
        onStatus = { tvStatus.text = it },
        onResult = { showResult(it) }
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) applySource() else tvStatus.text = "카메라 권한이 필요합니다 (USB 웹캠에도 필요)"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        usbView = findViewById(R.id.usbView)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        etHeight = findViewById(R.id.etHeight)
        etWeight = findViewById(R.id.etWeight)
        etMemo = findViewById(R.id.etMemo)
        btnSwitch = findViewById(R.id.btnSwitch)
        usb = UsbCameraSource(usbView, analyzer) { tvStatus.text = it }

        val prefs = getSharedPreferences("jump", MODE_PRIVATE)
        prefs.getString("height", null)?.let { etHeight.setText(it) }
        prefs.getString("weight", null)?.let { etWeight.setText(it) }
        source = runCatching { Source.valueOf(prefs.getString("source", "USB")!!) }
            .getOrDefault(Source.USB)
        btnSwitch.text = "입력: ${source.label}"

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val h = etHeight.text.toString().toDoubleOrNull()
            if (h == null || h < 100 || h > 230) {
                toast("키를 100~230cm 사이로 입력하세요")
                return@setOnClickListener
            }
            prefs.edit()
                .putString("height", etHeight.text.toString())
                .putString("weight", etWeight.text.toString())
                .apply()
            tvResult.text = ""
            last = null
            analyzer.start(h)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveRecord() }
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        btnSwitch.setOnClickListener {
            source = Source.values()[(source.ordinal + 1) % Source.values().size]
            prefs.edit().putString("source", source.name).apply()
            applySource()
        }

        if (!hasCameraPermission()) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) applySource()
    }

    override fun onStop() {
        super.onStop()
        usb.stop()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** 현재 선택된 입력(USB 웹캠 / 내장 카메라)으로 전환한다. */
    private fun applySource() {
        analyzer.reset()
        btnSwitch.text = "입력: ${source.label}"
        if (source == Source.USB) {
            unbindBuiltIn()
            previewView.visibility = View.GONE
            usbView.visibility = View.VISIBLE
            tvStatus.text = "USB 웹캠을 OTG로 연결하세요 (권한 팝업이 뜨면 허용)"
            usb.start()
        } else {
            usb.stop()
            usbView.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            lens = if (source == Source.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
            tvStatus.text = "키를 입력하고 [측정 시작]을 누르세요"
            bindCamera()
        }
    }

    private fun unbindBuiltIn() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ f.get().unbindAll() }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, analyzer)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, lens, preview, analysis)
            } catch (e: Exception) {
                tvStatus.text = "카메라를 열 수 없습니다: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun power(heightCm: Double, weightKg: Double): Double =
        if (weightKg > 0) 60.7 * heightCm + 45.3 * weightKg - 2055.0 else 0.0  // Sayers 공식(추정)

    private fun showResult(r: JumpResult) {
        last = r
        val w = etWeight.text.toString().toDoubleOrNull() ?: 0.0
        val p = power(r.heightFlightCm, w)
        tvResult.text = buildString {
            append("점프 높이  %.1f cm\n".format(r.heightFlightCm))
            append("체공시간  %.3f s\n".format(r.flightSec))
            append("발목 상승량 기준  %.1f cm".format(r.heightDispCm))
            if (p > 0) append("\n추정 최대파워  %.0f W".format(p))
        }
    }

    private fun saveRecord() {
        val r = last ?: return toast("저장할 측정 결과가 없습니다")
        val h = etHeight.text.toString().toDoubleOrNull() ?: 0.0
        val w = etWeight.text.toString().toDoubleOrNull() ?: 0.0
        RecordStore.add(
            this,
            JumpRecord(
                System.currentTimeMillis(), r.flightSec, r.heightFlightCm, r.heightDispCm,
                h, w, power(r.heightFlightCm, w),
                "[${source.label}] " + etMemo.text.toString().trim()
            )
        )
        toast("기록을 저장했습니다")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
