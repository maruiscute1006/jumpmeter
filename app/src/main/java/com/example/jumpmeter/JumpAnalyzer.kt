package com.example.jumpmeter

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

data class JumpResult(
    val flightSec: Double,        // 체공시간(초)
    val heightFlightCm: Double,   // 체공시간 기반 점프 높이 (h = g*T^2/8)
    val heightDispCm: Double      // 발목 상승량 기반 점프 높이 (참고값)
)

/**
 * 측면(옆모습)에서 전신을 촬영한 영상에서 발목의 세로 위치를 추적해
 * 이륙/착지 시점을 찾고 점프 높이를 계산한다.
 * ML Kit 콜백은 메인 스레드에서 호출되므로 상태 변수는 메인 스레드에서만 접근한다.
 */
class JumpAnalyzer(
    private val onStatus: (String) -> Unit,
    private val onResult: (JumpResult) -> Unit
) : ImageAnalysis.Analyzer {

    private enum class Phase { IDLE, CALIBRATING, READY, AIRBORNE }

    companion object {
        const val G = 9.81
        const val TAKEOFF_RATIO = 0.03      // 몸 길이(px)의 3% 이상 올라가면 이륙으로 판단
        const val BODY_TO_HEIGHT = 0.85     // (코~발목 거리) ≈ 키 × 0.85
        const val CALIB_MS = 1200.0
    }

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private var phase = Phase.IDLE
    private var userHeightCm = 170.0
    private var startRequested = false

    private val ankleSamples = ArrayList<Double>()
    private val bodySamples = ArrayList<Double>()
    private var calStartMs = 0.0

    private var baselineY = 0.0
    private var bodyRefPx = 1.0
    private var cmPerPx = 0.0
    private var prevT = 0.0
    private var prevRise = 0.0
    private var takeoffT = 0.0
    private var maxRise = 0.0
    private var lastMsg = ""
    private val busy = AtomicBoolean(false)
    private var prevFrameT = 0.0
    private var avgDt = 0.0   // 분석된 프레임 간격(ms) 이동평균

    fun start(heightCm: Double) {
        userHeightCm = heightCm
        startRequested = true
    }

    fun reset() {
        phase = Phase.IDLE
        startRequested = false
        lastMsg = ""
        prevFrameT = 0.0
        avgDt = 0.0
    }

    fun isBusy() = busy.get()

    /** USB 웹캠처럼 NV21 바이트 배열로 들어오는 프레임을 분석한다. (tMs: 프레임 시각, ms) */
    fun analyzeNv21(data: ByteArray, width: Int, height: Int, rotation: Int, tMs: Double) {
        if (data.size < width * height * 3 / 2) return
        if (!busy.compareAndSet(false, true)) return   // 이전 프레임 분석 중이면 건너뜀
        val input = InputImage.fromByteArray(data, width, height, rotation, InputImage.IMAGE_FORMAT_NV21)
        detector.process(input)
            .addOnSuccessListener { pose -> handle(pose, tMs) }
            .addOnCompleteListener { busy.set(false) }
    }

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val tMs = image.imageInfo.timestamp / 1_000_000.0
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { pose -> handle(pose, tMs) }
            .addOnCompleteListener { image.close() }
    }

    private fun say(msg: String) {
        if (msg != lastMsg) {
            lastMsg = msg
            onStatus(msg)
        }
    }

    private fun handle(pose: Pose, tMs: Double) {
        if (startRequested) {
            startRequested = false
            phase = Phase.CALIBRATING
            ankleSamples.clear(); bodySamples.clear()
            calStartMs = tMs
            lastMsg = ""
        }
        if (phase == Phase.IDLE) return

        if (prevFrameT > 0) {
            val dt = tMs - prevFrameT
            if (dt in 1.0..500.0) avgDt = if (avgDt == 0.0) dt else avgDt * 0.9 + dt * 0.1
        }
        prevFrameT = tMs

        val la = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val ra = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        if (la == null || ra == null || nose == null ||
            minOf(la.inFrameLikelihood, ra.inFrameLikelihood, nose.inFrameLikelihood) < 0.6f
        ) {
            if (phase == Phase.CALIBRATING) {
                ankleSamples.clear(); bodySamples.clear(); calStartMs = tMs
            }
            say("머리부터 발까지 전신이 화면에 보이게 해주세요")
            return
        }

        val ankleY = (la.position.y + ra.position.y) / 2.0
        val bodyPx = ankleY - nose.position.y
        if (bodyPx < 50) return

        when (phase) {
            Phase.CALIBRATING -> {
                say("가만히 서 계세요… 보정 중")
                ankleSamples.add(ankleY); bodySamples.add(bodyPx)
                if (tMs - calStartMs >= CALIB_MS && ankleSamples.size >= 8) {
                    baselineY = median(ankleSamples)
                    bodyRefPx = median(bodySamples)
                    cmPerPx = BODY_TO_HEIGHT * userHeightCm / bodyRefPx
                    phase = Phase.READY
                    prevT = tMs; prevRise = 0.0
                    say(if (avgDt > 0) "준비 완료! (분석 %.0f fps) 점프하세요".format(1000.0 / avgDt) else "준비 완료! 점프하세요")
                }
            }

            Phase.READY -> {
                val rise = baselineY - ankleY
                val thr = TAKEOFF_RATIO * bodyRefPx
                if (rise > thr) {
                    val f = (thr - prevRise) / (rise - prevRise)
                    takeoffT = prevT + f * (tMs - prevT)
                    maxRise = rise
                    phase = Phase.AIRBORNE
                    say("체공 중…")
                }
                prevT = tMs; prevRise = rise
            }

            Phase.AIRBORNE -> {
                val rise = baselineY - ankleY
                val thr = TAKEOFF_RATIO * bodyRefPx
                if (rise > maxRise) maxRise = rise
                if (rise < thr) {
                    val f = (prevRise - thr) / (prevRise - rise)
                    val landT = prevT + f * (tMs - prevT)
                    finishJump((landT - takeoffT) / 1000.0)
                } else if (tMs - takeoffT > 1500) {
                    phase = Phase.READY
                    say("측정 실패. 다시 점프하세요")
                }
                prevT = tMs; prevRise = rise
            }

            else -> {}
        }
    }

    private fun finishJump(flightSec: Double) {
        phase = Phase.READY
        if (flightSec < 0.15 || flightSec > 1.3) {
            say("인식 오류. 다시 점프하세요")
            return
        }
        val hFlight = G * flightSec * flightSec / 8.0 * 100.0
        val hDisp = maxRise * cmPerPx
        lastMsg = ""
        say("측정 완료! 다시 점프하거나 [기록 저장]을 누르세요")
        onResult(JumpResult(flightSec, hFlight, hDisp))
    }

    private fun median(list: List<Double>): Double {
        val s = list.sorted()
        return s[s.size / 2]
    }
}
