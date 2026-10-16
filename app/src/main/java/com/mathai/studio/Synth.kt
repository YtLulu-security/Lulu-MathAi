package com.mathai.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Random

/** Membuat sampel simbol dari berbagai font + rotasi, miring, skala, dan ketebalan acak. */
object Synth {
    const val DEFAULT_CHARS = "0123456789xyzabcn+-×÷=().,/"

    fun generate(
        chars: List<String>,
        perClass: Int,
        rng: Random,
        progress: (Int, Int) -> Unit
    ): List<Sample> {
        val bases = listOf(
            Typeface.SANS_SERIF,
            Typeface.SERIF,
            Typeface.MONOSPACE,
            Typeface.create("cursive", Typeface.NORMAL),
            Typeface.create("casual", Typeface.NORMAL)
        )
        val styles = intArrayOf(Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC)
        val size = 256
        val c0 = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER

        val out = ArrayList<Sample>()
        chars.forEachIndexed { ci, ch ->
            var made = 0
            var tries = 0
            while (made < perClass && tries < perClass * 4) {
                tries++
                canvas.drawColor(Color.WHITE)
                paint.typeface = Typeface.create(bases[rng.nextInt(bases.size)], styles[rng.nextInt(styles.size)])
                paint.textSize = 110f + rng.nextFloat() * 30f
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = rng.nextFloat() * 7f
                if (!paint.hasGlyph(ch)) continue

                canvas.save()
                canvas.rotate((rng.nextFloat() - 0.5f) * 20f, c0, c0)
                canvas.scale(0.85f + rng.nextFloat() * 0.3f, 0.85f + rng.nextFloat() * 0.3f, c0, c0)
                canvas.skew((rng.nextFloat() - 0.5f) * 0.4f, 0f)
                val fm = paint.fontMetrics
                val y = c0 - (fm.ascent + fm.descent) / 2f
                canvas.drawText(ch, c0, y, paint)
                canvas.restore()

                val ink = toInk(bmp, size)
                val box = wholeImageBox(ink) ?: continue
                out.add(Sample(ch, normalizeBox(ink, box)))
                made++
            }
            progress(ci + 1, chars.size)
        }
        return out
    }
}
