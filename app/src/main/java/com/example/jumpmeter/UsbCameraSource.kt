package com.example.jumpmeter

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
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
    }

    private var helper: CameraHelper? = null
    private var width = 0
    private var height = 0

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
            val sz = h.previewSize
            if (sz != null) {
                width = sz.width
                height = sz.height
                view.setAspect(width, height)
            }
            attachSurface(h)
            h.setFrameCallback(IFrameCallback { buf -> onFrame(buf) }, UVCCamera.PIXEL_FORMAT_NV21)
            onStatus("USB 웹캠 연결됨 (${width}x${height}). 키를 입력하고 [측정 시작]을 누르세요")
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
        val w = width
        val h = height
        val size = w * h * 3 / 2
        if (w == 0 || analyzer.isBusy()) return
        buf.rewind()
        if (buf.remaining() < size) return
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
