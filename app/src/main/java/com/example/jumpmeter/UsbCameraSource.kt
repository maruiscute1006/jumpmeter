package com.example.jumpmeter

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer

/**
 * OTG로 연결한 USB 웹캠(UVC)에서 프레임을 받아 미리보기를 보여주고
 * NV21 프레임을 JumpAnalyzer로 넘긴다.
 */
class UsbCameraSource(
    private val view: AspectSurfaceView,
    private val analyzer: JumpAnalyzer,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val ROTATION = 0          // 웹캠이 옆으로 누워 있으면 90/270으로 변경
        const val TARGET_MIN_WIDTH = 640 // 분석 속도를 위해 640 이상 중 가장 작은 해상도 선택

        // 영상 크기를 못 알아낼 때, 프레임 바이트 수로 크기를 추측하는 목록
        private val KNOWN_SIZES = listOf(
            320 to 240, 640 to 360, 640 to 480, 800 to 600, 960 to 540,
            1024 to 768, 1280 to 720, 1280 to 960, 1600 to 1200, 1920 to 1080
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var helper: CameraHelper? = null

    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var frameCount = 0
    @Volatile private var announced = false
    @Volatile private var sizeErrorShown = false

    private val callback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            if (isVideoDevice(device)) helper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            helper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val h = helper ?: return
            try {
                val sizes = h.supportedSizeList
                val target = sizes?.filter { it.width >= TARGET_MIN_WIDTH }
                    ?.minByOrNull { it.width * it.height }
                if (target != null) h.setPreviewSize(target)
            } catch (e: Exception) { /* 기본 해상도 사용 */ }

            h.startPreview()
            frameCount = 0
            announced = false
            sizeErrorShown = false
            try {
                val sz = h.previewSize
                if (sz != null) {
                    width = sz.width
                    height = sz.height
                    view.setAspect(width, height)
                }
            } catch (e: Exception) { /* 프레임이 오면 크기를 추측함 */ }
            attachSurface(h)
            h.setFrameCallback(IFrameCallback { buf -> onFrame(buf) }, UVCCamera.PIXEL_FORMAT_NV21)
            onStatus("USB 웹캠 연결됨. [측정 시작]을 누르세요")

            // 4초 안에 분석용 프레임이 하나도 안 오면 알려준다
            main.postDelayed({
                if (helper != null && frameCount == 0) {
                    onStatus("웹캠 영상은 보이지만 분석용 프레임이 안 들어와요")
                }
            }, 4000)
        }

        override fun onCameraClose(device: UsbDevice) {
            try { helper?.removeSurface(view.holder.surface) } catch (e: Exception) {}
        }

        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) { onStatus("USB 웹캠 연결이 끊겼습니다") }
        override fun onCancel(device: UsbDevice) { onStatus("USB 권한이 거부되었습니다. 다시 연결해 주세요") }
    }

    fun start() {
        if (helper != null) return
        helper = CameraHelper().apply { setStateCallback(callback) }
    }

    fun stop() {
        val h = helper ?: return
        try { h.removeSurface(view.holder.surface) } catch (e: Exception) {}
        try { h.release() } catch (e: Exception) {}
        helper = null
        width = 0; height = 0
    }

    private fun attachSurface(h: CameraHelper) {
        val holder = view.holder
        val s = holder.surface
        if (s != null && s.isValid) {
            h.addSurface(s, false)
        } else {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(hd: SurfaceHolder) {
                    hd.removeCallback(this)
                    helper?.addSurface(hd.surface, false)
                }
                override fun surfaceChanged(hd: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(hd: SurfaceHolder) {}
            })
        }
    }

    private fun onFrame(buf: ByteBuffer) {
        frameCount++
        buf.rewind()
        val remaining = buf.remaining()
        var w = width
        var h = height

        // 크기를 모르거나 프레임이 너무 작으면 크기를 다시 알아낸다
        if (w == 0 || h == 0 || remaining < w * h * 3 / 2) {
            try {
                helper?.previewSize?.let { w = it.width; h = it.height }
            } catch (e: Exception) {}
            if (w == 0 || h == 0 || remaining < w * h * 3 / 2) {
                val guess = KNOWN_SIZES.firstOrNull { it.first * it.second * 3 / 2 == remaining }
                if (guess != null) {
                    w = guess.first
                    h = guess.second
                } else {
                    if (!sizeErrorShown) {
                        sizeErrorShown = true
                        main.post { onStatus("프레임 크기를 알 수 없어요 ($remaining 바이트)") }
                    }
                    return
                }
            }
            width = w
            height = h
            main.post { view.setAspect(w, h) }
        }

        if (!announced) {
            announced = true
            main.post { onStatus("웹캠 프레임 수신 시작 (${w}x${h}). [측정 시작]을 누르세요") }
        }

        if (analyzer.isBusy()) return
        val size = w * h * 3 / 2
        val t = SystemClock.elapsedRealtimeNanos() / 1_000_000.0
        val bytes = ByteArray(size)
        buf.get(bytes, 0, size)
        analyzer.analyzeNv21(bytes, w, h, ROTATION, t)
    }

    private fun isVideoDevice(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }
}
