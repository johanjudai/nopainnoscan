package com.maitre.nopainnoscan.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView avec pincement (1× à 5×), déplacement et double-tap ; une frappe simple hors zoom
 * remonte à [onTap]. Assez pour lire une étiquette, sans dépendance supplémentaire.
 */
class ZoomImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatImageView(context, attrs) {

    var onTap: (() -> Unit)? = null

    private val zoomMatrix = Matrix()
    private val base = Matrix() // image ajustée dans la vue (fit center), point de départ du zoom
    private var scale = 1f
    // Faux pendant le constructeur parent (qui peut déjà appeler setImageDrawable) : champs pas encore créés.
    private var ready = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1f) return false
            zoomMatrix.postTranslate(-dx, -dy)
            clamp()
            imageMatrix = zoomMatrix
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1f) reset() else zoomBy(2.5f, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (scale <= 1f) onTap?.invoke()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        ready = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun fit() {
        if (!ready) return
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        base.setRectToRect(
            RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Matrix.ScaleToFit.CENTER,
        )
        reset()
    }

    private fun reset() {
        scale = 1f
        zoomMatrix.set(base)
        imageMatrix = zoomMatrix
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val target = (scale * factor).coerceIn(1f, MAX_SCALE)
        val applied = target / scale
        scale = target
        zoomMatrix.postScale(applied, applied, fx, fy)
        clamp()
        imageMatrix = zoomMatrix
    }

    /** L'image ne laisse pas de bord vide tant qu'elle déborde de la vue ; sinon elle reste centrée. */
    private fun clamp() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        zoomMatrix.mapRect(rect)
        val dx = when {
            rect.width() <= width -> (width - rect.width()) / 2 - rect.left
            rect.left > 0 -> -rect.left
            rect.right < width -> width - rect.right
            else -> 0f
        }
        val dy = when {
            rect.height() <= height -> (height - rect.height()) / 2 - rect.top
            rect.top > 0 -> -rect.top
            rect.bottom < height -> height - rect.bottom
            else -> 0f
        }
        zoomMatrix.postTranslate(dx, dy)
    }

    private companion object {
        const val MAX_SCALE = 5f
    }
}
