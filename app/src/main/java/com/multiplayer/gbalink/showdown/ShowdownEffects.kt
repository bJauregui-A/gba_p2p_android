package com.multiplayer.gbalink.showdown

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

object ShowdownEffects {

    /**
     * Shakes a Pokemon view violently when taking damage
     */
    fun shake(view: View) {
        val anim = ObjectAnimator.ofFloat(
            view, "translationX",
            0f, -20f, 20f, -15f, 15f, -10f, 10f, -5f, 5f, 0f
        ).apply {
            duration = 450
            interpolator = AccelerateDecelerateInterpolator()
        }
        anim.start()
    }

    /**
     * Flashes the Pokemon sprite red/white when hit
     */
    fun flashDamage(view: View) {
        val anim = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.2f, 1f, 0.2f, 1f).apply {
            duration = 350
        }
        anim.start()
    }

    /**
     * Lunges forward when executing an attack
     */
    fun lungeAttack(view: View, isOpponent: Boolean) {
        val direction = if (isOpponent) -1f else 1f
        val deltaX = 40f * direction
        val deltaY = 25f * direction

        val animX = ObjectAnimator.ofFloat(view, "translationX", 0f, deltaX, 0f)
        val animY = ObjectAnimator.ofFloat(view, "translationY", 0f, deltaY, 0f)

        AnimatorSet().apply {
            playTogether(animX, animY)
            duration = 300
            interpolator = OvershootInterpolator(2.5f)
            start()
        }
    }

    /**
     * Sinks down and fades out when fainting
     */
    fun faint(view: View) {
        val animY = ObjectAnimator.ofFloat(view, "translationY", 0f, 80f)
        val animAlpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f)

        AnimatorSet().apply {
            playTogether(animY, animAlpha)
            duration = 600
            start()
        }
    }

    /**
     * Pops up and scales in when switching into battle
     */
    fun enterBattle(view: View) {
        view.alpha = 0f
        view.scaleX = 0.2f
        view.scaleY = 0.2f
        view.translationY = 40f

        val animScaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.2f, 1f)
        val animScaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.2f, 1f)
        val animAlpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        val animY = ObjectAnimator.ofFloat(view, "translationY", 40f, 0f)

        AnimatorSet().apply {
            playTogether(animScaleX, animScaleY, animAlpha, animY)
            duration = 450
            interpolator = OvershootInterpolator(1.8f)
            start()
        }
    }

    /**
     * Smoothly drains / animates the HP progress bar
     */
    fun animateHp(
        progressBar: ProgressBar,
        txtHp: TextView?,
        fromHp: Int,
        toHp: Int,
        maxHp: Int,
        isPercent: Boolean
    ) {
        val fromPercent = if (maxHp > 0) ((fromHp.toFloat() / maxHp) * 100).toInt() else fromHp
        val toPercent = if (maxHp > 0) ((toHp.toFloat() / maxHp) * 100).toInt() else toHp

        val animator = ValueAnimator.ofInt(fromPercent, toPercent).apply {
            duration = 650
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val current = anim.animatedValue as Int
                progressBar.progress = current

                // Update health text
                if (txtHp != null) {
                    if (isPercent) {
                        txtHp.text = "$current%"
                    } else {
                        val approxHp = ((current.toFloat() / 100) * maxHp).toInt()
                        txtHp.text = "$approxHp/$maxHp"
                    }
                }

                // Change color based on health remaining
                val color = when {
                    current > 50 -> Color.parseColor("#2ECC71") // Green
                    current > 20 -> Color.parseColor("#F1C40F") // Yellow
                    else -> Color.parseColor("#E74C3C")         // Red
                }
                progressBar.progressTintList = ColorStateList.valueOf(color)
            }
        }
        animator.start()
    }

    /**
     * Floating text popup for damage / critical hits
     */
    fun showFloatingDamage(
        container: ViewGroup,
        text: String,
        targetView: View,
        color: Int = Color.YELLOW
    ) {
        val context = container.context
        val floatingText = TextView(context).apply {
            this.text = text
            textSize = 18f
            setTextColor(color)
            paint.isFakeBoldText = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            // Position near target
            x = targetView.x + targetView.width / 4f
            y = targetView.y - 20f
        }

        container.addView(floatingText)

        val animY = ObjectAnimator.ofFloat(floatingText, "translationY", 0f, -60f)
        val animAlpha = ObjectAnimator.ofFloat(floatingText, "alpha", 1f, 0f)

        AnimatorSet().apply {
            playTogether(animY, animAlpha)
            duration = 900
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    container.removeView(floatingText)
                }
            })
            start()
        }
    }
}
