package com.example.jumpmeter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
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
    private lateinit var etMemo: EditText
    private lateinit var spGrade: Spinner
    private lateinit var etClass: EditText
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
        etMemo = findViewById(R.id.etMemo)
        spGrade = findViewById(R.id.spGrade)
        etClass = findViewById(R.id.etClass)
        spGrade.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            (1..6).map { "${it}학년" }
        )
        btnSwitch = findViewById(R.id.btnSwitch)
        usb = UsbCameraSource(usbView, analyzer) { tvStatus.text = it }

        val prefs = getSharedPreferences("jump", MODE_PRIVATE)
        spGrade.setSelection(prefs.getInt("grade", 1) - 1)
        prefs.getInt("class", 0).takeIf { it > 0 }?.let { etClass.setText(it.toString()) }
        source = runCatching { Source.valueOf(prefs.getString("source", "USB")!!) }
            .getOrDefault(Source.USB)
        btnSwitch.text = "입력: ${source.label}"

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            tvResult.text = ""
            last = null
            analyzer.start(170.0)   // 키 입력 없이 기본값 사용 (점프 높이는 체공시간으로 계산하므로 영향 없음)
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
            tvStatus.text = "[측정 시작]을 누르세요"
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

    private fun showResult(r: JumpResult) {
        last = r
        tvResult.text = "점프 높이\n%.1f cm".format(r.heightFlightCm)
    }

    private fun saveRecord() {
        val r = last ?: return toast("저장할 측정 결과가 없습니다")
        val classNo = etClass.text.toString().toIntOrNull() ?: 0
        if (classNo == 0) return toast("반 번호를 적어주세요")
        RecordStore.add(
            this,
            JumpRecord(
                System.currentTimeMillis(), r.flightSec, r.heightFlightCm, r.heightDispCm,
                0.0, 0.0, 0.0,
                etMemo.text.toString().trim().ifEmpty { "이름 없음" },
                spGrade.selectedItemPosition + 1,
                classNo
            )
        )
        getSharedPreferences("jump", MODE_PRIVATE).edit()
            .putInt("grade", spGrade.selectedItemPosition + 1)
            .putInt("class", classNo).apply()
        toast("기록을 저장했습니다")
        // 다음 사람을 위해 초기화
        last = null
        tvResult.text = ""
        etMemo.setText("")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
