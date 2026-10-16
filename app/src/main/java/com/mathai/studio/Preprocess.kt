package com.mathai.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val IMG = 28
const val DIM = IMG * IMG
const val BOX = 20
const val INK_ON = 90

/** Citra tinta: 0 = latar, 255 = tinta. */
class InkImage(val w: Int, val h: Int, val v: IntArray)

/** Kotak simbol. r dan b eksklusif. */
class Box(var l: Int, var t: Int, var r: Int, var b: Int, var area: Int) {
    val w: Int get() = r - l
    val h: Int get() = b - t
}

private fun otsu(hist: IntArray, total: Int): Int {
    var sum = 0.0
    for (i in 0..255) sum += i * hist[i].toDouble()
    var sumB = 0.0
    var wB = 0
    var best = 0.0
    var thr = 127
    for (t in 0..255) {
        wB += hist[t]
        if (wB == 0) continue
        val wF = total - wB
        if (wF == 0) break
        sumB += t * hist[t].toDouble()
        val mB = sumB / wB
        val mF = (sum - sumB) / wF
        val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
        if (between > best) {
            best = between
            thr = t
        }
    }
    return thr
}

/** Bitmap (kanvas / foto) -> citra tinta. Latar apa pun, tinta gelap atau terang. */
fun toInk(bmp: Bitmap, maxDim: Int = 800): InkImage {
    val s = max(bmp.width, bmp.height).toFloat()
    val sc = if (s > maxDim) maxDim / s else 1f
    val scaled = if (sc < 1f) {
        Bitmap.createScaledBitmap(
            bmp, max(1, (bmp.width * sc).toInt()), max(1, (bmp.height * sc).toInt()), true
        )
    } else bmp
    val w = scaled.width
    val h = scaled.height
    // ratakan di atas latar putih (PNG transparan)
    val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(flat)
    cv.drawColor(Color.WHITE)
    cv.drawBitmap(scaled, 0f, 0f, null)
    val px = IntArray(w * h)
    flat.getPixels(px, 0, w, 0, 0, w, h)

    val g = IntArray(w * h)
    val hist = IntArray(256)
    for (i in px.indices) {
        val c = px[i]
        val r = (c shr 16) and 255
        val gg = (c shr 8) and 255
        val bl = c and 255
        val y = (299 * r + 587 * gg + 114 * bl) / 1000
        g[i] = y
        hist[y]++
    }
    val thr = otsu(hist, w * h)
    var lowSum = 0L
    var lowN = 0
    var hiSum = 0L
    var hiN = 0
    for (y in g) {
        if (y <= thr) { lowSum += y; lowN++ } else { hiSum += y; hiN++ }
    }
    if (lowN == 0 || hiN == 0) return InkImage(w, h, IntArray(w * h))
    val lowMean = lowSum.toFloat() / lowN
    val hiMean = hiSum.toFloat() / hiN
    if (hiMean - lowMean < 25f) return InkImage(w, h, IntArray(w * h))
    val darkInk = lowN <= hiN
    val range = hiMean - lowMean
    val out = IntArray(w * h)
    for (i in g.indices) {
        val t = if (darkInk) (hiMean - g[i]) / range else (g[i] - lowMean) / range
        out[i] = (t.coerceIn(0f, 1f) * 255f).toInt()
    }
    return InkImage(w, h, out)
}

/** Pisahkan simbol: komponen terhubung (8 arah), gabung yang tumpang tindih horizontal. */
fun segment(img: InkImage): List<Box> {
    val w = img.w
    val h = img.h
    val lab = IntArray(w * h)
    val stack = IntArray(w * h)
    val raw = ArrayList<Box>()
    var id = 0
    for (s in 0 until w * h) {
        if (img.v[s] <= INK_ON || lab[s] != 0) continue
        id++
        var l = w
        var t = h
        var r = -1
        var b = -1
        var area = 0
        var sp = 0
        stack[sp++] = s
        lab[s] = id
        while (sp > 0) {
            val p = stack[--sp]
            val x = p % w
            val y = p / w
            area++
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (img.v[q] > INK_ON && lab[q] == 0) {
                        lab[q] = id
                        stack[sp++] = q
                    }
                }
            }
        }
        raw.add(Box(l, t, r + 1, b + 1, area))
    }
    if (raw.isEmpty()) return raw

    // buang bintik noise
    val maxH = raw.maxOf { it.h }
    val minArea = max(4, (0.004f * maxH * maxH).toInt())
    val list = raw.filter { it.area >= minArea }.sortedBy { it.l }.toMutableList()

    // gabungkan bagian simbol (=, ÷, +, i) yang rentang x-nya tumpang tindih
    var changed = true
    while (changed) {
        changed = false
        loop@ for (i in 0 until list.size) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val c = list[j]
                val ov = min(a.r, c.r) - max(a.l, c.l)
                val mw = min(a.w, c.w)
                if (ov > 0 && ov * 2 >= mw) {
                    a.l = min(a.l, c.l)
                    a.r = max(a.r, c.r)
                    a.t = min(a.t, c.t)
                    a.b = max(a.b, c.b)
                    a.area += c.area
                    list.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
    }
    return list.sortedBy { it.l }
}

/** Kotak yang membungkus semua tinta (untuk glyph tunggal). */
fun wholeImageBox(img: InkImage): Box? {
    var l = img.w
    var t = img.h
    var r = -1
    var b = -1
    var area = 0
    for (y in 0 until img.h) {
        val row = y * img.w
        for (x in 0 until img.w) {
            if (img.v[row + x] > INK_ON) {
                area++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
    }
    return if (r < 0) null else Box(l, t, r + 1, b + 1, area)
}

/**
 * Normalisasi satu simbol ke 28x28 (784 byte, 0..255, 255 = tinta):
 * potong sesuai kotak, skala proporsional agar sisi terpanjang = 20 px
 * (rata-rata area), taruh di tengah kanvas 28x28.
 */
fun normalizeBox(img: InkImage, bx: Box): ByteArray {
    val bw = bx.w
    val bh = bx.h
    val sc = BOX.toFloat() / max(bw, bh)
    val tw = max(1, (bw * sc).roundToInt())
    val th = max(1, (bh * sc).roundToInt())
    val ox = (IMG - tw) / 2
    val oy = (IMG - th) / 2
    val out = ByteArray(DIM)
    for (ty in 0 until th) {
        val sy0 = bx.t + ty * bh.toFloat() / th
        val sy1 = bx.t + (ty + 1) * bh.toFloat() / th
        val y0 = floor(sy0).toInt().coerceIn(bx.t, bx.b - 1)
        val y1 = max(y0 + 1, ceil(sy1).toInt()).coerceAtMost(bx.b)
        for (tx in 0 until tw) {
            val sx0 = bx.l + tx * bw.toFloat() / tw
            val sx1 = bx.l + (tx + 1) * bw.toFloat() / tw
            val x0 = floor(sx0).toInt().coerceIn(bx.l, bx.r - 1)
            val x1 = max(x0 + 1, ceil(sx1).toInt()).coerceAtMost(bx.r)
            var sum = 0
            var n = 0
            for (yy in y0 until y1) {
                val row = yy * img.w
                for (xx in x0 until x1) {
                    sum += img.v[row + xx]
                    n++
                }
            }
            out[(oy + ty) * IMG + (ox + tx)] = (sum / max(1, n)).coerceIn(0, 255).toByte()
        }
    }
    return out
}

/** Pecah teks jadi daftar karakter (code point), spasi diabaikan. */
fun splitChars(s: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = Character.charCount(cp)
        if (!Character.isWhitespace(cp)) out.add(String(Character.toChars(cp)))
        i += n
    }
    return out
}
