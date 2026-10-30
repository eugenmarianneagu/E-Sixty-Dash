package com.bmw.carlauncher

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.sqrt

/**
 * AgamaBandButton: Custom automotive horizontal band button with:
 * - Inner concave circular edge hugging the central gauge dial.
 * - Outer curved edge following the dashboard gauge cluster with stitched seam.
 * - 3D top/bottom relief grooves.
 * - Hardware-accelerated canvas clipping for child views (text, icon, LED strip).
 * - Full BMW Amber focus/press glow for remote BLE HID and touch control.
 */
class AgamaBandButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var isRightSide: Boolean = false
        set(value) {
            field = value
            rebuildPaths()
            invalidate()
        }

    var bandPosition: Int = 1 // 0 = top, 1 = middle, 2 = bottom
        set(value) {
            field = value
            rebuildPaths()
            invalidate()
        }

    private val bandPath = Path()
    private val innerCurvePath = Path()
    private val outerCurvePath = Path()
    private val outerStitchPath = Path()
    private val innerStitchPath = Path()

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val topBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#464A53")
    }
    private val bottomShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#090A0C")
    }
    private val innerCurveBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#646A78")
    }
    private val outerBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#32353C")
    }
    private val stitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#444952")
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val innerStitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#383C44")
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val focusBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF7500") // BMW Amber
    }
    private val focusGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#28FF7500")
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isClickable = true

        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.AgamaBandButton)
            isRightSide = typedArray.getBoolean(R.styleable.AgamaBandButton_isRightSide, false)
            bandPosition = typedArray.getInt(R.styleable.AgamaBandButton_bandPosition, 1)
            typedArray.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPaths()
    }

    private fun rebuildPaths() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        bandPath.reset()
        innerCurvePath.reset()
        outerCurvePath.reset()
        outerStitchPath.reset()
        innerStitchPath.reset()

        // Cluster center Y is middle of middle band (pos 1)
        val cyCluster = 1.5f * h
        val radiusInner = 1.15f * h
        val radiusOuter = 4.2f * h

        // Density scale for dp offsets
        val density = context.resources.displayMetrics.density
        val baseOuterMargin = 12f * density

        fun getInnerInset(yLocal: Float): Float {
            val dy = yLocal + (bandPosition * h) - cyCluster
            return if (kotlin.math.abs(dy) < radiusInner) {
                sqrt(max(0f, radiusInner * radiusInner - dy * dy))
            } else {
                0f
            }
        }

        fun getOuterCurveOffset(yLocal: Float): Float {
            val dy = yLocal + (bandPosition * h) - cyCluster
            val delta = radiusOuter - sqrt(max(0f, radiusOuter * radiusOuter - dy * dy))
            return baseOuterMargin + delta * 0.85f
        }

        val steps = 30

        if (!isRightSide) {
            // ================= LEFT BAND =================
            // 1. Top Outer point
            val xTopOuter = getOuterCurveOffset(0f)
            val xTopInner = w - getInnerInset(0f)
            bandPath.moveTo(xTopOuter, 0f)

            // 2. Top horizontal edge to inner circle
            bandPath.lineTo(xTopInner, 0f)

            // 3. Inner concave circular arc from y=0 to y=h
            innerCurvePath.moveTo(xTopInner, 0f)
            for (i in 1..steps) {
                val y = h * (i.toFloat() / steps)
                val x = w - getInnerInset(y)
                bandPath.lineTo(x, y)
                innerCurvePath.lineTo(x, y)
            }

            // 4. Bottom horizontal edge from inner circle to outer edge
            val xBotOuter = getOuterCurveOffset(h)
            bandPath.lineTo(xBotOuter, h)

            // 5. Outer curved edge from y=h back to y=0
            outerCurvePath.moveTo(xBotOuter, h)
            for (i in 1..steps) {
                val y = h * (1f - (i.toFloat() / steps))
                val x = getOuterCurveOffset(y)
                bandPath.lineTo(x, y)
                outerCurvePath.lineTo(x, y)
            }
            bandPath.close()

            // Outer Stitch Path (offset 8dp inside outer edge)
            val stitchOffset = 8f * density
            outerStitchPath.moveTo(xTopOuter + stitchOffset, 0f)
            for (i in 1..steps) {
                val y = h * (i.toFloat() / steps)
                val x = getOuterCurveOffset(y) + stitchOffset
                outerStitchPath.lineTo(x, y)
            }

            // Inner vertical stitch (separating text from icon)
            val iconSeparationX = w - getInnerInset(h * 0.5f) - (58f * density)
            innerStitchPath.moveTo(iconSeparationX, 2f)
            innerStitchPath.lineTo(iconSeparationX, h - 2f)

        } else {
            // ================= RIGHT BAND =================
            // 1. Top Inner point (starting at circle)
            val xTopInner = getInnerInset(0f)
            val xTopOuter = w - getOuterCurveOffset(0f)
            bandPath.moveTo(xTopInner, 0f)

            // 2. Top horizontal edge to outer edge
            bandPath.lineTo(xTopOuter, 0f)

            // 3. Outer curved edge from y=0 to y=h
            outerCurvePath.moveTo(xTopOuter, 0f)
            for (i in 1..steps) {
                val y = h * (i.toFloat() / steps)
                val x = w - getOuterCurveOffset(y)
                bandPath.lineTo(x, y)
                outerCurvePath.lineTo(x, y)
            }

            // 4. Bottom horizontal edge to inner circle
            val xBotInner = getInnerInset(h)
            bandPath.lineTo(xBotInner, h)

            // 5. Inner concave circular arc from y=h back to y=0
            innerCurvePath.moveTo(xBotInner, h)
            for (i in 1..steps) {
                val y = h * (1f - (i.toFloat() / steps))
                val x = getInnerInset(y)
                bandPath.lineTo(x, y)
                innerCurvePath.lineTo(x, y)
            }
            bandPath.close()

            // Outer Stitch Path
            val stitchOffset = 8f * density
            outerStitchPath.moveTo(xTopOuter - stitchOffset, 0f)
            for (i in 1..steps) {
                val y = h * (i.toFloat() / steps)
                val x = w - getOuterCurveOffset(y) - stitchOffset
                outerStitchPath.lineTo(x, y)
            }

            // Inner vertical stitch
            val iconSeparationX = getInnerInset(h * 0.5f) + (58f * density)
            innerStitchPath.moveTo(iconSeparationX, 2f)
            innerStitchPath.lineTo(iconSeparationX, h - 2f)
        }

        // Setup background shader with 3D metallic sheen
        val gradient = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#27292E"),
                Color.parseColor("#1C1D21"),
                Color.parseColor("#141518")
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bandPath.isEmpty) return

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.save()
        canvas.clipPath(bandPath)

        // 1. Draw base band polygon
        canvas.drawPath(bandPath, bgPaint)

        // 2. Focus or Pressed state glow
        if (isFocused || isPressed) {
            canvas.drawPath(bandPath, focusGlowPaint)
            canvas.drawPath(bandPath, focusBorderPaint)
        } else {
            // 3. Normal 3D Bevel and Seams
            // Top bevel line
            if (bandPosition != 0) {
                canvas.drawLine(0f, 1f, w, 1f, topBevelPaint)
            }
            // Bottom shadow line
            if (bandPosition != 2) {
                canvas.drawLine(0f, h - 1f, w, h - 1f, bottomShadowPaint)
            }

            // Inner concave circular edge 3D bevel
            canvas.drawPath(innerCurvePath, innerCurveBevelPaint)

            // Outer dashboard bevel
            canvas.drawPath(outerCurvePath, outerBevelPaint)

            // Dashed stitches
            canvas.drawPath(outerStitchPath, stitchPaint)
        }
        canvas.restore()
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Hardware accelerated clipping to ensure children (text, icon, LED slot) stay within band
        if (!bandPath.isEmpty) {
            canvas.save()
            canvas.clipPath(bandPath)
            super.dispatchDraw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }
}
