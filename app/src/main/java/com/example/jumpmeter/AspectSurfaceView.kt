package com.example.jumpmeter

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView

/** 웹캠 영상 비율(가로:세로)을 유지하는 SurfaceView */
class AspectSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    private var ratio = 0f

    fun setAspect(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            ratio = w.toFloat() / h
            requestLayout()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec)
        val maxH = MeasureSpec.getSize(heightSpec)
        if (ratio == 0f) {
            setMeasuredDimension(maxW, maxH)
            return
        }
        var w = maxW
        var h = (w / ratio).toInt()
        if (h > maxH) {
            h = maxH
            w = (h * ratio).toInt()
        }
        setMeasuredDimension(w, h)
    }
}
