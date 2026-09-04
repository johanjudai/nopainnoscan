package com.maitre.nopainnoscan.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.maitre.nopainnoscan.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Anneau de score 0-100 : piste tonale, arc coloré animé, chiffre Sora au centre. */
class ScoreRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val stroke = 10f * resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.tonal_2)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.coral)
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.ink)
        typeface = ResourcesCompat.getFont(context, R.font.sora_bold)
        textSize = 32f * resources.displayMetrics.scaledDensity
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.muted)
        typeface = ResourcesCompat.getFont(context, R.font.sora_semibold)
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val bounds = RectF()
    private var shown = 0f
    private var animator: ValueAnimator? = null
    private val legend = context.getString(R.string.score_over_100)

    fun set(score: Double, color: Int) {
        arc.color = color
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, score.toFloat()).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val inset = stroke / 2
        bounds.set(
            (width - size) / 2 + inset, (height - size) / 2 + inset,
            (width + size) / 2 - inset, (height + size) / 2 - inset,
        )
        canvas.drawOval(bounds, track)
        canvas.drawArc(bounds, -90f, 360f * (shown / 100f).coerceIn(0f, 1f), false, arc)

        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText(shown.roundToInt().toString(), cx, cy + number.textSize * 0.2f, number)
        canvas.drawText(legend, cx, cy + number.textSize * 0.2f + caption.textSize * 1.4f, caption)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
