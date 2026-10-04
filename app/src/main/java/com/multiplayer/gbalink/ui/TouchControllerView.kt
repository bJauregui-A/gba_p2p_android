package com.multiplayer.gbalink.ui

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.multiplayer.gbalink.core.GbaNative
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * MyBoy-style virtual gamepad.
 *
 * Portrait: drawn on its own panel below the screen. Landscape: translucent overlay on both sides
 * of the game picture. Every size derives from one unit [u] so it scales with any phone.
 */
class TouchControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onKeyMaskChanged: ((Int) -> Unit)? = null

    private var currentKeyMask: Int = 0
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val density = resources.displayMetrics.density

    private var isPortrait = true
    private var u = 0f

    // ---- Geometry ----
    private var dpadX = 0f
    private var dpadY = 0f
    private var dpadArm = 0f            // half length of each arm
    private var dpadThick = 0f          // width of each arm
    private val dpadPath = Path()

    private var aX = 0f; private var aY = 0f
    private var bX = 0f; private var bY = 0f
    private var abRadius = 0f

    private val rectL = RectF()
    private val rectR = RectF()
    private val rectSelect = RectF()
    private val rectStart = RectF()

    // ---- Paints ----
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    private val tmpPath = Path()

    // ---- Palette (alpha depends on orientation: overlay must not hide the game) ----
    private val accent = Color.parseColor("#686DE0")
    private val colorA = Color.parseColor("#E8505B")
    private val colorB = Color.parseColor("#F0A030")

    private fun idleFill() = if (isPortrait) Color.argb(40, 255, 255, 255) else Color.argb(34, 255, 255, 255)
    private fun idleStroke() = if (isPortrait) Color.argb(70, 255, 255, 255) else Color.argb(90, 255, 255, 255)
    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        isPortrait = h >= w * 0.75f
        if (isPortrait) layoutPortrait(w.toFloat(), h.toFloat()) else layoutLandscape(w.toFloat(), h.toFloat())
        buildDpadPath()

        panelPaint.shader = if (isPortrait) LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#FF161A2B"), Color.parseColor("#FF0C0E16"), Shader.TileMode.CLAMP
        ) else null
        labelPaint.textSize = u * 0.62f
        captionPaint.textSize = u * 0.26f
    }

    private fun layoutPortrait(w: Float, h: Float) {
        u = min(w / 9f, h / 6.2f)
        val m = u * 0.35f

        // Shoulders hug the top corners
        val shW = u * 2.7f
        val shH = u * 0.85f
        rectL.set(m, m, m + shW, m + shH)
        rectR.set(w - m - shW, m, w - m, m + shH)

        // D-pad left, A/B right, both centred in the space between shoulders and start/select
        dpadArm = u * 1.55f
        dpadThick = u * 1.05f
        val midY = rectL.bottom + (h - rectL.bottom - u * 1.3f) * 0.5f
        dpadX = max(w * 0.26f, m + dpadArm + u * 0.3f)
        dpadY = midY

        abRadius = u * 0.82f
        aX = min(w * 0.83f, w - m - abRadius - u * 0.3f)
        aY = midY - u * 0.55f
        bX = aX - u * 2.0f
        bY = midY + u * 0.55f

        val pillW = u * 1.75f
        val pillH = u * 0.55f
        val pillY = h - m - u * 0.45f - pillH
        rectSelect.set(w / 2 - u * 0.25f - pillW, pillY, w / 2 - u * 0.25f, pillY + pillH)
        rectStart.set(w / 2 + u * 0.25f, pillY, w / 2 + u * 0.25f + pillW, pillY + pillH)
    }

    private fun layoutLandscape(w: Float, h: Float) {
        u = h / 6.6f
        val m = u * 0.35f

        val shW = u * 2.8f
        val shH = u * 0.85f
        rectL.set(m, m, m + shW, m + shH)
        rectR.set(w - m - shW, m, w - m, m + shH)

        dpadArm = u * 1.5f
        dpadThick = u * 1.0f
        dpadX = m + u * 0.5f + dpadArm
        dpadY = h - m - u * 0.6f - dpadArm

        abRadius = u * 0.8f
        aX = w - m - u * 0.4f - abRadius
        aY = h - m - u * 2.2f
        bX = aX - u * 1.95f
        bY = aY + u * 0.95f

        val pillW = u * 1.6f
        val pillH = u * 0.5f
        val pillY = h - m - pillH
        rectSelect.set(w / 2 - u * 0.2f - pillW, pillY, w / 2 - u * 0.2f, pillY + pillH)
        rectStart.set(w / 2 + u * 0.2f, pillY, w / 2 + u * 0.2f + pillW, pillY + pillH)
    }

    private fun buildDpadPath() {
        val r = dpadThick * 0.22f
        val horizontal = Path().apply {
            addRoundRect(dpadX - dpadArm, dpadY - dpadThick / 2, dpadX + dpadArm, dpadY + dpadThick / 2, r, r, Path.Direction.CW)
        }
        val vertical = Path().apply {
            addRoundRect(dpadX - dpadThick / 2, dpadY - dpadArm, dpadX + dpadThick / 2, dpadY + dpadArm, r, r, Path.Direction.CW)
        }
        dpadPath.reset()
        dpadPath.op(horizontal, vertical, Path.Op.UNION)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (u <= 0f) return

        if (isPortrait) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), panelPaint)

        drawDpad(canvas)
        drawRoundButton(canvas, aX, aY, colorA, "A", GbaNative.KEY_A)
        drawRoundButton(canvas, bX, bY, colorB, "B", GbaNative.KEY_B)
        drawShoulder(canvas, rectL, "L", GbaNative.KEY_L)
        drawShoulder(canvas, rectR, "R", GbaNative.KEY_R)
        drawPill(canvas, rectSelect, "SELECT", GbaNative.KEY_SELECT)
        drawPill(canvas, rectStart, "START", GbaNative.KEY_START)
    }

    private fun pressed(key: Int) = currentKeyMask and key != 0

    private fun drawDpad(canvas: Canvas) {
        // Soft circular base behind the cross
        fillPaint.color = if (isPortrait) Color.argb(22, 255, 255, 255) else Color.argb(14, 255, 255, 255)
        canvas.drawCircle(dpadX, dpadY, dpadArm * 1.12f, fillPaint)

        fillPaint.color = idleFill()
        canvas.drawPath(dpadPath, fillPaint)

        // Highlight pressed arms
        fillPaint.color = withAlpha(accent, 150)
        val half = dpadThick / 2
        val r = dpadThick * 0.22f
        if (pressed(GbaNative.KEY_UP)) canvas.drawRoundRect(dpadX - half, dpadY - dpadArm, dpadX + half, dpadY - half * 0.3f, r, r, fillPaint)
        if (pressed(GbaNative.KEY_DOWN)) canvas.drawRoundRect(dpadX - half, dpadY + half * 0.3f, dpadX + half, dpadY + dpadArm, r, r, fillPaint)
        if (pressed(GbaNative.KEY_LEFT)) canvas.drawRoundRect(dpadX - dpadArm, dpadY - half, dpadX - half * 0.3f, dpadY + half, r, r, fillPaint)
        if (pressed(GbaNative.KEY_RIGHT)) canvas.drawRoundRect(dpadX + half * 0.3f, dpadY - half, dpadX + dpadArm, dpadY + half, r, r, fillPaint)

        strokePaint.color = idleStroke()
        canvas.drawPath(dpadPath, strokePaint)

        // Centre dimple
        fillPaint.color = Color.argb(30, 0, 0, 0)
        canvas.drawCircle(dpadX, dpadY, dpadThick * 0.28f, fillPaint)

        // Direction arrows
        val s = dpadThick * 0.2f
        val off = dpadArm - dpadThick * 0.45f
        drawArrow(canvas, dpadX, dpadY - off, s, 0, pressed(GbaNative.KEY_UP))
        drawArrow(canvas, dpadX, dpadY + off, s, 2, pressed(GbaNative.KEY_DOWN))
        drawArrow(canvas, dpadX - off, dpadY, s, 3, pressed(GbaNative.KEY_LEFT))
        drawArrow(canvas, dpadX + off, dpadY, s, 1, pressed(GbaNative.KEY_RIGHT))
    }

    /** dir: 0 up, 1 right, 2 down, 3 left */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, s: Float, dir: Int, active: Boolean) {
        arrowPaint.color = if (active) Color.WHITE else Color.argb(150, 255, 255, 255)
        tmpPath.reset()
        when (dir) {
            0 -> { tmpPath.moveTo(cx, cy - s); tmpPath.lineTo(cx + s, cy + s * 0.6f); tmpPath.lineTo(cx - s, cy + s * 0.6f) }
            2 -> { tmpPath.moveTo(cx, cy + s); tmpPath.lineTo(cx + s, cy - s * 0.6f); tmpPath.lineTo(cx - s, cy - s * 0.6f) }
            3 -> { tmpPath.moveTo(cx - s, cy); tmpPath.lineTo(cx + s * 0.6f, cy - s); tmpPath.lineTo(cx + s * 0.6f, cy + s) }
            else -> { tmpPath.moveTo(cx + s, cy); tmpPath.lineTo(cx - s * 0.6f, cy - s); tmpPath.lineTo(cx - s * 0.6f, cy + s) }
        }
        tmpPath.close()
        canvas.drawPath(tmpPath, arrowPaint)
    }

    private fun drawRoundButton(canvas: Canvas, cx: Float, cy: Float, color: Int, label: String, key: Int) {
        val down = pressed(key)
        val r = if (down) abRadius * 0.94f else abRadius
        fillPaint.color = withAlpha(color, if (down) 200 else if (isPortrait) 85 else 60)
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = withAlpha(color, if (isPortrait) 220 else 170)
        canvas.drawCircle(cx, cy, r, strokePaint)
        labelPaint.alpha = if (down || isPortrait) 255 else 210
        canvas.drawText(label, cx, cy - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
    }

    private fun drawShoulder(canvas: Canvas, rect: RectF, label: String, key: Int) {
        val down = pressed(key)
        val r = rect.height() * 0.5f
        fillPaint.color = if (down) withAlpha(accent, 170) else idleFill()
        canvas.drawRoundRect(rect, r, r, fillPaint)
        strokePaint.color = idleStroke()
        canvas.drawRoundRect(rect, r, r, strokePaint)
        val old = labelPaint.textSize
        labelPaint.textSize = rect.height() * 0.5f
        labelPaint.alpha = 230
        canvas.drawText(label, rect.centerX(), rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
        labelPaint.textSize = old
    }

    private fun drawPill(canvas: Canvas, rect: RectF, label: String, key: Int) {
        val down = pressed(key)
        val r = rect.height() / 2
        fillPaint.color = if (down) withAlpha(accent, 170) else idleFill()
        canvas.drawRoundRect(rect, r, r, fillPaint)
        strokePaint.color = idleStroke()
        canvas.drawRoundRect(rect, r, r, strokePaint)
        captionPaint.color = if (down) Color.WHITE else Color.argb(170, 255, 255, 255)
        if (isPortrait) {
            // Caption under the pill, like the real GBA
            canvas.drawText(label, rect.centerX(), rect.bottom + captionPaint.textSize * 1.35f, captionPaint)
        } else {
            canvas.drawText(label, rect.centerX(), rect.centerY() - (captionPaint.descent() + captionPaint.ascent()) / 2, captionPaint)
        }
    }

    // ---- Input ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var newKeyMask = 0
        val action = event.actionMasked

        if (action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == event.actionIndex) continue
                newKeyMask = newKeyMask or keysAt(event.getX(i), event.getY(i))
            }
        }

        if (newKeyMask != currentKeyMask) {
            if ((newKeyMask and currentKeyMask.inv()) != 0) performHaptic()
            currentKeyMask = newKeyMask
            onKeyMaskChanged?.invoke(currentKeyMask)
            invalidate()
        }
        return true
    }

    private fun keysAt(x: Float, y: Float): Int {
        var mask = 0

        // D-pad with 8 directions; generous outer radius, small dead zone in the centre
        val dist = hypot(x - dpadX, y - dpadY)
        if (dist <= dpadArm * 1.35f && dist >= dpadThick * 0.18f) {
            val angle = Math.toDegrees(atan2((y - dpadY).toDouble(), (x - dpadX).toDouble()))
            mask = mask or when {
                angle >= -22.5 && angle < 22.5 -> GbaNative.KEY_RIGHT
                angle >= 22.5 && angle < 67.5 -> GbaNative.KEY_DOWN or GbaNative.KEY_RIGHT
                angle >= 67.5 && angle < 112.5 -> GbaNative.KEY_DOWN
                angle >= 112.5 && angle < 157.5 -> GbaNative.KEY_DOWN or GbaNative.KEY_LEFT
                angle >= -67.5 && angle < -22.5 -> GbaNative.KEY_UP or GbaNative.KEY_RIGHT
                angle >= -112.5 && angle < -67.5 -> GbaNative.KEY_UP
                angle >= -157.5 && angle < -112.5 -> GbaNative.KEY_UP or GbaNative.KEY_LEFT
                else -> GbaNative.KEY_LEFT
            }
            return mask
        }

        // A / B, plus pressing both when touching between them
        val dA = hypot(x - aX, y - aY)
        val dB = hypot(x - bX, y - bY)
        val midDist = hypot(x - (aX + bX) / 2, y - (aY + bY) / 2)
        if (midDist < abRadius * 0.45f) {
            mask = mask or GbaNative.KEY_A or GbaNative.KEY_B
        } else {
            if (dA <= abRadius * 1.25f && dA <= dB) mask = mask or GbaNative.KEY_A
            else if (dB <= abRadius * 1.25f) mask = mask or GbaNative.KEY_B
        }

        val slop = u * 0.3f
        if (expanded(rectL, slop).contains(x, y)) mask = mask or GbaNative.KEY_L
        if (expanded(rectR, slop).contains(x, y)) mask = mask or GbaNative.KEY_R
        if (expanded(rectSelect, slop).contains(x, y)) mask = mask or GbaNative.KEY_SELECT
        if (expanded(rectStart, slop).contains(x, y)) mask = mask or GbaNative.KEY_START
        return mask
    }

    private val tmpRect = RectF()
    private fun expanded(r: RectF, by: Float): RectF {
        tmpRect.set(r.left - by, r.top - by, r.right + by, r.bottom + by)
        return tmpRect
    }

    private fun performHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(10, 80))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(10)
            }
        } catch (ignored: Exception) {
        }
    }
}
