package com.linux_core.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object WidgetCubeRenderer {

    fun generateRotationFrames(
        width: Int,
        height: Int,
        fromFace: Bitmap,
        toFace: Bitmap,
        direction: Int,
        frameCount: Int = 10,
        density: Float = 3.0f
    ): List<Bitmap> {
        if (width <= 0 || height <= 0 || frameCount < 2) return emptyList()

        val scaledFrom = if (fromFace.width != width || fromFace.height != height)
            Bitmap.createScaledBitmap(fromFace, width, height, true)
        else
            fromFace

        val scaledTo = if (toFace.width != width || toFace.height != height)
            Bitmap.createScaledBitmap(toFace, width, height, true)
        else
            toFace

        val frames = mutableListOf<Bitmap>()
        val centerX = width / 2f
        val centerY = height / 2f

        for (i in 0 until frameCount) {
            val t = i.toFloat() / (frameCount - 1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            canvas.drawColor(Color.parseColor("#0A0A0A"))

            if (t <= 0.5f) {
                val scaleX = 1.0f - t * 2.0f
                val alpha = (255 * (1.0f - t * 2.0f)).toInt().coerceIn(0, 255)

                canvas.save()
                canvas.translate(width * t * direction, 0f)
                canvas.scale(scaleX, 1.0f, centerX, centerY)

                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.alpha = alpha
                canvas.drawBitmap(scaledFrom, 0f, 0f, paint)
                canvas.restore()
            }

            if (t >= 0.5f) {
                val scaleX = (t - 0.5f) * 2.0f
                val alpha = (255 * ((t - 0.5f) * 2.0f)).toInt().coerceIn(0, 255)

                canvas.save()
                canvas.translate(width * (1.0f - (t - 0.5f) * 2.0f) * (-direction), 0f)
                canvas.scale(scaleX, 1.0f, centerX, centerY)

                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.alpha = alpha
                canvas.drawBitmap(scaledTo, 0f, 0f, paint)
                canvas.restore()
            }

            val glossAlpha = (128 * kotlin.math.abs(t - 0.5f) * 2).toInt().coerceIn(0, 80)
            if (glossAlpha > 0) {
                val glossPaint = Paint()
                glossPaint.color = Color.argb(glossAlpha, 255, 255, 255)
                val glossWidth = (1 * density).toInt().coerceAtLeast(1)
                canvas.drawRect(
                    centerX - glossWidth / 2f,
                    0f,
                    centerX + glossWidth / 2f,
                    height.toFloat(),
                    glossPaint
                )
            }

            frames.add(bitmap)
        }

        if (scaledFrom !== fromFace) scaledFrom.recycle()
        if (scaledTo !== toFace) scaledTo.recycle()

        return frames
    }
}
