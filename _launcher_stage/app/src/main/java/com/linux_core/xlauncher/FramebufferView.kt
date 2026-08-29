package com.linux_core.xlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min

/**
 * Renders the remote framebuffer to a SurfaceView and translates touch events
 * into VNC pointer events.
 *
 * Foundation renderer uses a Canvas-drawn Bitmap (deterministic, robust). The
 * intended production renderer is a GLES texture upload (see plan); that is a
 * localized swap behind [drawFrame].
 */
class FramebufferView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    interface PointerListener {
        /** @param mask 1=left, 2=mid, 4=right, 0=up */
        fun onPointer(fbX: Int, fbY: Int, mask: Int)
    }

    var pointerListener: PointerListener? = null

    @Volatile var bitmap: Bitmap? = null
    @Volatile var fbWidth = 0
        private set
    @Volatile var fbHeight = 0
        private set

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawFrame()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    fun setFramebufferSize(w: Int, h: Int) {
        fbWidth = w
        fbHeight = h
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        post { drawFrame() }
    }

    fun updateRegion(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
        val bmp = bitmap ?: return
        if (x < 0 || y < 0 || x + w > bmp.width || y + h > bmp.height) return
        bmp.setPixels(pixels, 0, w, x, y, w, h)
        post { drawFrame() }
    }

    private fun drawFrame() {
        val bmp = bitmap ?: return
        val holder = holder
        if (!holder.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas() ?: return
            val vw = width.toFloat()
            val vh = height.toFloat()
            val scale = min(vw / bmp.width, vh / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dx = (vw - dw) / 2f
            val dy = (vh - dh) / 2f
            canvas.drawRGB(0, 0, 0)
            canvas.drawBitmap(
                bmp, null,
                Rect(dx.toInt(), dy.toInt(), (dx + dw).toInt(), (dy + dh).toInt()),
                null
            )
        } finally {
            canvas?.let { holder.unlockCanvasAndPost(it) }
        }
    }

    private fun toFramebuffer(event: MotionEvent): Pair<Int, Int> {
        val bmp = bitmap ?: return Pair(0, 0)
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = min(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dx = (vw - dw) / 2f
        val dy = (vh - dh) / 2f
        val fx = ((event.x - dx) / scale).coerceIn(0f, (bmp.width - 1).toFloat())
        val fy = ((event.y - dy) / scale).coerceIn(0f, (bmp.height - 1).toFloat())
        return Pair(fx.toInt(), fy.toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val (fx, fy) = toFramebuffer(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pointerListener?.onPointer(fx, fy, 1)
            MotionEvent.ACTION_MOVE -> pointerListener?.onPointer(fx, fy, 1)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerListener?.onPointer(fx, fy, 0)
        }
        return true
    }
}
