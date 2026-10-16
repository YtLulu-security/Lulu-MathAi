package com.mathai.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

class DrawingView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var cv: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 5f * ctx.resources.displayMetrics.density
    }
    private val path = Path()
    private var lx = 0f
    private var ly = 0f

    var hasInk = false
        private set

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(nb)
        c.drawColor(Color.WHITE)
        bmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        bmp = nb
        cv = c
    }

    override fun onDraw(canvas: Canvas) {
        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                path.moveTo(x, y)
                path.lineTo(x + 0.1f, y)
                lx = x
                ly = y
            }
            MotionEvent.ACTION_MOVE -> {
                path.quadTo(lx, ly, (x + lx) / 2f, (y + ly) / 2f)
                lx = x
                ly = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                path.lineTo(x, y)
                cv?.drawPath(path, paint)
                path.reset()
                hasInk = true
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun clear() {
        cv?.drawColor(Color.WHITE)
        path.reset()
        hasInk = false
        invalidate()
    }

    fun snapshot(): Bitmap? = bmp?.copy(Bitmap.Config.ARGB_8888, false)
}
