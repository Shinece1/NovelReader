package com.dsh.novelreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a page bitmap "contain"-scaled (never cropped), with pinch zoom,
 * drag-to-pan while zoomed, double-tap zoom and a single-tap callback.
 * Used for scanned pages and for figures that own a reader page.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val mapped = RectF()
    private val base = RectF()

    /** Horizontal position of a confirmed tap, 0..1, for the turn zones. */
    var onTapFraction: ((Float) -> Unit)? = null

    private var minScale = 1f
    private var userScale = 1f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val target = (userScale * factor).coerceIn(1f, 6f)
                val applied = target / userScale
                userScale = target
                matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                clamp()
                invalidate()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (width > 0) onTapFraction?.invoke(e.x / width.toFloat())
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (userScale > 1.01f) {
                    matrix.postTranslate(-distanceX, -distanceY)
                    clamp()
                    invalidate()
                }
                return true
            }
        },
    )

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        if (width > 0 && height > 0) resetMatrix()
        invalidate()
    }

    private fun resetMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        base.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val scale = min(width / base.width(), height / base.height())
        minScale = scale
        userScale = 1f
        matrix.setScale(scale, scale)
        val dx = (width - base.width() * scale) / 2f
        val dy = (height - base.height() * scale) / 2f
        matrix.postTranslate(dx, dy)
        invalidate()
    }

    private fun toggleZoom(x: Float, y: Float) {
        if (bitmap == null) return
        if (userScale > 1.5f) {
            resetMatrix()
        } else {
            val target = 2.5f
            val applied = target / userScale
            userScale = target
            matrix.postScale(applied, applied, x, y)
            clamp()
            invalidate()
        }
    }

    private fun clamp() {
        val bmp = bitmap ?: return
        matrix.mapRect(mapped, base)
        if (mapped.width() <= width) {
            matrix.postTranslate((width - mapped.width()) / 2f - mapped.left, 0f)
        } else {
            val dx = when {
                mapped.left > 0f -> -mapped.left
                mapped.right < width -> width - mapped.right
                else -> 0f
            }
            if (dx != 0f) matrix.postTranslate(dx, 0f)
        }
        matrix.mapRect(mapped, base)
        if (mapped.height() <= height) {
            matrix.postTranslate(0f, (height - mapped.height()) / 2f - mapped.top)
        } else {
            val dy = when {
                mapped.top > 0f -> -mapped.top
                mapped.bottom < height -> height - mapped.bottom
                else -> 0f
            }
            if (dy != 0f) matrix.postTranslate(0f, dy)
        }
        // keep the bitmap from being dragged out of the screen
        val limit = max(width, height) * 2f
        matrix.mapRect(mapped, base)
        if (mapped.left > limit || mapped.right < -limit) resetMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, matrix, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
