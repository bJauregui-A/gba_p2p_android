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

class TouchControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onKeyMaskChanged: ((Int) -> Unit)? = null

    private var currentKeyMask: Int = 0
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // Paints
    private val dpadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#442C3042")
        style = Paint.Style.FILL
    }
    private val dpadHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99686DE0")
        style = Paint.Style.FILL
    }
    private val btnAPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAEB4D4B")
        style = Paint.Style.FILL
    }
    private val btnBPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAF0932B")
        style = Paint.Style.FILL
    }
    private val btnShoulderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8830336B")
        style = Paint.Style.FILL
    }
    private val btnMenuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#77535C68")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Geometry layout
    private var dpadCenterX = 0f
    private var dpadCenterY = 0f
    private var dpadRadius = 0f

    private var btnAX = 0f
    private var btnAY = 0f
    private var btnBX = 0f
    private var btnBY = 0f
    private var actionRadius = 0f

    private val rectL = RectF()
    private val rectR = RectF()
    private val rectSelect = RectF()
    private val rectStart = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val isPortrait = h >= w
        if (isPortrait) {
            // Portrait MyBoy gamepad layout (controls in lower portion of phone)
            val shoulderW = w * 0.38f
            val shoulderH = h * 0.12f
            rectL.set(24f, 20f, 24f + shoulderW, 20f + shoulderH)
            rectR.set(w - 24f - shoulderW, 20f, w - 24f, 20f + shoulderH)

            dpadRadius = w * 0.22f
            dpadCenterX = w * 0.28f
            dpadCenterY = h * 0.48f

            actionRadius = w * 0.12f
            btnAX = w - actionRadius * 1.5f
            btnAY = h * 0.42f
            btnBX = w - actionRadius * 2.8f
            btnBY = h * 0.54f

            val menuW = w * 0.18f
            val menuH = h * 0.08f
            val midX = w * 0.5f
            val midY = h * 0.78f
            rectSelect.set(midX - menuW * 1.2f, midY, midX - menuW * 0.2f, midY + menuH)
            rectStart.set(midX + menuW * 0.2f, midY, midX + menuW * 1.2f, midY + menuH)
        } else {
            // Landscape layout (controls overlaid on left & right of screen)
            dpadRadius = h * 0.22f
            dpadCenterX = dpadRadius * 1.3f
            dpadCenterY = h - dpadRadius * 1.3f

            actionRadius = h * 0.12f
            btnAX = w - actionRadius * 1.4f
            btnAY = h - actionRadius * 2.2f
            btnBX = w - actionRadius * 2.8f
            btnBY = h - actionRadius * 1.4f

            val shoulderW = w * 0.16f
            val shoulderH = h * 0.12f
            rectL.set(24f, 24f, 24f + shoulderW, 24f + shoulderH)
            rectR.set(w - 24f - shoulderW, 24f, w - 24f, 24f + shoulderH)

            val menuW = w * 0.09f
            val menuH = h * 0.07f
            val midX = w * 0.5f
            val midY = h - menuH * 1.6f
            rectSelect.set(midX - menuW * 1.2f, midY, midX - menuW * 0.2f, midY + menuH)
            rectStart.set(midX + menuW * 0.2f, midY, midX + menuW * 1.2f, midY + menuH)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw D-Pad
        canvas.drawCircle(dpadCenterX, dpadCenterY, dpadRadius, dpadPaint)
        // Cross shapes
        val armW = dpadRadius * 0.65f
        val armH = dpadRadius * 1.8f
        canvas.drawRoundRect(dpadCenterX - armW / 2, dpadCenterY - armH / 2, dpadCenterX + armW / 2, dpadCenterY + armH / 2, 16f, 16f, dpadPaint)
        canvas.drawRoundRect(dpadCenterX - armH / 2, dpadCenterY - armW / 2, dpadCenterX + armH / 2, dpadCenterY + armW / 2, 16f, 16f, dpadPaint)

        // D-Pad active highlights
        if (currentKeyMask and GbaNative.KEY_UP != 0) canvas.drawCircle(dpadCenterX, dpadCenterY - dpadRadius * 0.6f, dpadRadius * 0.3f, dpadHighlightPaint)
        if (currentKeyMask and GbaNative.KEY_DOWN != 0) canvas.drawCircle(dpadCenterX, dpadCenterY + dpadRadius * 0.6f, dpadRadius * 0.3f, dpadHighlightPaint)
        if (currentKeyMask and GbaNative.KEY_LEFT != 0) canvas.drawCircle(dpadCenterX - dpadRadius * 0.6f, dpadCenterY, dpadRadius * 0.3f, dpadHighlightPaint)
        if (currentKeyMask and GbaNative.KEY_RIGHT != 0) canvas.drawCircle(dpadCenterX + dpadRadius * 0.6f, dpadCenterY, dpadRadius * 0.3f, dpadHighlightPaint)

        // 2. Draw A and B buttons
        canvas.drawCircle(btnAX, btnAY, actionRadius, btnAPaint)
        canvas.drawText("A", btnAX, btnAY + 12f, textPaint)

        canvas.drawCircle(btnBX, btnBY, actionRadius, btnBPaint)
        canvas.drawText("B", btnBX, btnBY + 12f, textPaint)

        // 3. Draw L and R Bumpers
        canvas.drawRoundRect(rectL, 16f, 16f, btnShoulderPaint)
        canvas.drawText("L", rectL.centerX(), rectL.centerY() + 12f, textPaint)

        canvas.drawRoundRect(rectR, 16f, 16f, btnShoulderPaint)
        canvas.drawText("R", rectR.centerX(), rectR.centerY() + 12f, textPaint)

        // 4. Draw Select and Start
        canvas.drawRoundRect(rectSelect, 12f, 12f, btnMenuPaint)
        canvas.drawText("SELECT", rectSelect.centerX(), rectSelect.centerY() + 8f, textPaint.apply { textSize = 20f })

        canvas.drawRoundRect(rectStart, 12f, 12f, btnMenuPaint)
        canvas.drawText("START", rectStart.centerX(), rectStart.centerY() + 8f, textPaint.apply { textSize = 20f })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var newKeyMask = 0

        for (i in 0 until event.pointerCount) {
            if (event.actionMasked == MotionEvent.ACTION_UP && i == event.actionIndex) continue
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue

            val x = event.getX(i)
            val y = event.getY(i)

            // D-Pad check
            val dist = hypot((x - dpadCenterX).toDouble(), (y - dpadCenterY).toDouble()).toFloat()
            if (dist <= dpadRadius * 1.25f && dist >= dpadRadius * 0.15f) {
                val angle = Math.toDegrees(atan2((y - dpadCenterY).toDouble(), (x - dpadCenterX).toDouble()))
                when {
                    angle in -67.5..-22.5 -> newKeyMask = newKeyMask or GbaNative.KEY_UP or GbaNative.KEY_RIGHT
                    angle in -22.5..22.5   -> newKeyMask = newKeyMask or GbaNative.KEY_RIGHT
                    angle in 22.5..67.5   -> newKeyMask = newKeyMask or GbaNative.KEY_DOWN or GbaNative.KEY_RIGHT
                    angle in 67.5..112.5  -> newKeyMask = newKeyMask or GbaNative.KEY_DOWN
                    angle in 112.5..157.5 -> newKeyMask = newKeyMask or GbaNative.KEY_DOWN or GbaNative.KEY_LEFT
                    angle > 157.5 || angle < -157.5 -> newKeyMask = newKeyMask or GbaNative.KEY_LEFT
                    angle in -157.5..-112.5 -> newKeyMask = newKeyMask or GbaNative.KEY_UP or GbaNative.KEY_LEFT
                    angle in -112.5..-67.5  -> newKeyMask = newKeyMask or GbaNative.KEY_UP
                }
            }

            // Button A
            if (hypot((x - btnAX).toDouble(), (y - btnAY).toDouble()) <= actionRadius * 1.3f) {
                newKeyMask = newKeyMask or GbaNative.KEY_A
            }

            // Button B
            if (hypot((x - btnBX).toDouble(), (y - btnBY).toDouble()) <= actionRadius * 1.3f) {
                newKeyMask = newKeyMask or GbaNative.KEY_B
            }

            // Shoulders L and R
            if (rectL.contains(x, y)) newKeyMask = newKeyMask or GbaNative.KEY_L
            if (rectR.contains(x, y)) newKeyMask = newKeyMask or GbaNative.KEY_R

            // Select & Start
            if (rectSelect.contains(x, y)) newKeyMask = newKeyMask or GbaNative.KEY_SELECT
            if (rectStart.contains(x, y)) newKeyMask = newKeyMask or GbaNative.KEY_START
        }

        if (newKeyMask != currentKeyMask) {
            // Trigger haptic feedback when a new button is pressed
            if ((newKeyMask and currentKeyMask.inv()) != 0) {
                performHaptic()
            }
            currentKeyMask = newKeyMask
            onKeyMaskChanged?.invoke(currentKeyMask)
            invalidate()
        }

        return true
    }

    private fun performHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(12)
            }
        } catch (ignored: Exception) {
        }
    }
}
