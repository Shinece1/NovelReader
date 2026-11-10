package com.dsh.novelreader.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Draws one already-paginated page: a small header band, the body text laid out
 * with the SAME paint used for pagination, and a footer with the position.
 *
 * Taps and long presses are reported with the character offset they hit (plus
 * the horizontal fraction for the left/right turn zones), which is what text
 * selection and annotation hit-testing need.
 */
class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onMetricsChanged: (() -> Unit)? = null
    var onTap: ((fraction: Float, offset: Int?) -> Unit)? = null
    var onLongPress: ((offset: Int) -> Unit)? = null

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // Typeface.DEFAULT follows the system font (including fonts chosen in
        // system settings); a hard-coded SERIF family ignored that choice.
        typeface = Typeface.DEFAULT
        isSubpixelText = true
    }
    private val chromePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
    }

    private var marginPx = dp(22f).toInt()
    private var lineSpacing = 1.6f
    private var bgColor = 0xFFFDFBF7.toInt()
    private var textColor = 0xFF1E1E1E.toInt()
    private var secondaryColor = 0xFF8A8A8A.toInt()

    private var body: CharSequence = ""
    private var headerLeft = ""
    private var headerRight = ""
    private var footer = ""
    private var layout: StaticLayout? = null

    private val topPadPx = dp(20f).toInt()
    private val bottomPadPx = dp(12f).toInt()
    private val headerHeightPx = dp(34f).toInt()
    private val footerHeightPx = dp(30f).toInt()

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val offset = offsetAt(e.x, e.y) ?: return
                onLongPress?.invoke(offset)
            }
        },
    )

    val contentWidth: Int get() = (width - marginPx * 2).coerceAtLeast(1)

    val contentHeight: Int
        get() = (height - topPadPx - bottomPadPx - headerHeightPx - footerHeightPx).coerceAtLeast(1)

    /** Page area occupied by body content, used to place image pages. */
    fun contentRect(): android.graphics.Rect = android.graphics.Rect(
        marginPx,
        topPadPx + headerHeightPx,
        (width - marginPx).coerceAtLeast(marginPx + 1),
        (height - bottomPadPx - footerHeightPx).coerceAtLeast(topPadPx + headerHeightPx + 1),
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    fun textPaint(): TextPaint = bodyPaint

    fun applyStyle(
        textSizeSp: Float,
        lineSpacingMultiplier: Float,
        marginDp: Int,
        bodyColor: Int,
        secondary: Int,
        background: Int,
    ) {
        bodyPaint.textSize = textSizeSp * resources.displayMetrics.scaledDensity
        bodyPaint.color = bodyColor
        lineSpacing = lineSpacingMultiplier
        marginPx = dp(marginDp.toFloat()).toInt()
        textColor = bodyColor
        secondaryColor = secondary
        bgColor = background
        rebuild()
        invalidate()
    }

    fun setChrome(left: String, right: String, footerText: String) {
        headerLeft = left
        headerRight = right
        footer = footerText
        invalidate()
    }

    fun setBody(text: CharSequence) {
        body = text
        rebuild()
        invalidate()
    }

    /** Character offset under a view coordinate, or null outside the text. */
    fun offsetAt(x: Float, y: Float): Int? {
        val static = layout ?: return null
        val localY = y - (topPadPx + headerHeightPx)
        if (localY < 0f || localY > static.height) return null
        val line = static.getLineForVertical(localY.toInt().coerceAtLeast(0))
        val offset = static.getOffsetForHorizontal(line, (x - marginPx).coerceAtLeast(0f))
        return offset.coerceIn(0, body.length)
    }

    private fun rebuild() {
        layout = if (body.isEmpty() || width == 0) {
            null
        } else {
            StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, contentWidth)
                .setLineSpacing(0f, lineSpacing)
                .setIncludePad(false)
                .build()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
        onMetricsChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)
        if (width == 0) return

        chromePaint.color = secondaryColor
        val baseline = topPadPx + chromePaint.textSize
        val rightWidth = chromePaint.measureText(headerRight)
        val leftAvail = (contentWidth - rightWidth - dp(10f)).coerceAtLeast(dp(20f))
        val left = TextUtils.ellipsize(headerLeft, chromePaint, leftAvail, TextUtils.TruncateAt.END)
        canvas.drawText(left, 0, left.length, marginPx.toFloat(), baseline, chromePaint)
        canvas.drawText(headerRight, (width - marginPx - rightWidth), baseline, chromePaint)

        layout?.let { static ->
            canvas.save()
            canvas.translate(marginPx.toFloat(), (topPadPx + headerHeightPx).toFloat())
            static.draw(canvas)
            canvas.restore()
        }

        val footerWidth = chromePaint.measureText(footer)
        canvas.drawText(
            footer,
            (width - footerWidth) / 2f,
            (height - bottomPadPx).toFloat(),
            chromePaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                val quick = System.currentTimeMillis() - downAt < 600
                if (dx < dp(16f) && dy < dp(16f) && quick && width > 0) {
                    onTap?.invoke(event.x / width.toFloat(), offsetAt(event.x, event.y))
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
