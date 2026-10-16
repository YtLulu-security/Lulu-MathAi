package com.mathai.studio

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

fun argmax(a: FloatArray): Int {
    var best = 0
    for (i in 1 until a.size) if (a[i] > a[best]) best = i
    return best
}

/** Isi input jaringan dari 784 byte. dx/dy = geser (augmentasi), rng != null = tambah noise kecil. */
fun fillInput(px: ByteArray, out: FloatArray, dx: Int, dy: Int, rng: Random?) {
    for (y in 0 until IMG) {
        val sy = y - dy
        for (x in 0 until IMG) {
            val sx = x - dx
            var v = if (sx in 0 until IMG && sy in 0 until IMG) {
                (px[sy * IMG + sx].toInt() and 255) / 255f
            } else 0f
            if (rng != null) v = (v + (rng.nextFloat() - 0.5f) * 0.08f).coerceIn(0f, 1f)
            out[y * IMG + x] = v
        }
    }
}

/** Jaringan dense: lapisan tersembunyi ReLU, keluaran softmax. Bobot [out][in] row-major. */
class Mlp(val sizes: IntArray) {
    val nl = sizes.size - 1
    val w = Array(nl) { FloatArray(sizes[it] * sizes[it + 1]) }
    val b = Array(nl) { FloatArray(sizes[it + 1]) }

    fun initRandom(rng: Random) {
        for (l in 0 until nl) {
            val std = sqrt(2.0 / sizes[l]).toFloat()
            for (i in w[l].indices) w[l][i] = (rng.nextGaussian() * std).toFloat()
            b[l].fill(0f)
        }
    }

    fun newActs() = Array(sizes.size) { FloatArray(sizes[it]) }
    fun newDeltas() = Array(nl) { FloatArray(sizes[it + 1]) }

    fun copyFrom(o: Mlp) {
        for (l in 0 until nl) {
            System.arraycopy(o.w[l], 0, w[l], 0, w[l].size)
            System.arraycopy(o.b[l], 0, b[l], 0, b[l].size)
        }
    }

    fun copy(): Mlp {
        val m = Mlp(sizes)
        m.copyFrom(this)
        return m
    }

    /** acts[0] harus sudah berisi input. Hasil softmax ada di acts[nl]. */
    fun forward(acts: Array<FloatArray>) {
        for (l in 0 until nl) {
            val inD = sizes[l]
            val outD = sizes[l + 1]
            val a = acts[l]
            val o = acts[l + 1]
            val wl = w[l]
            val bl = b[l]
            for (j in 0 until outD) {
                var s = bl[j]
                val off = j * inD
                for (i in 0 until inD) s += wl[off + i] * a[i]
                o[j] = s
            }
            if (l < nl - 1) {
                for (j in 0 until outD) if (o[j] < 0f) o[j] = 0f
            } else {
                var mx = o[0]
                for (j in 1 until outD) mx = max(mx, o[j])
                var sum = 0f
                for (j in 0 until outD) {
                    o[j] = exp(o[j] - mx)
                    sum += o[j]
                }
                for (j in 0 until outD) o[j] /= sum
            }
        }
    }

    fun predict(x: FloatArray): FloatArray {
        val acts = newActs()
        System.arraycopy(x, 0, acts[0], 0, sizes[0])
        forward(acts)
        return acts[nl]
    }

    /** Backprop satu sampel (cross-entropy). Gradien ditambahkan ke gw/gb. Mengembalikan loss. */
    fun backward(
        acts: Array<FloatArray>,
        deltas: Array<FloatArray>,
        target: Int,
        gw: Array<FloatArray>,
        gb: Array<FloatArray>
    ): Double {
        val out = acts[nl]
        val loss = -ln(max(out[target], 1e-7f).toDouble())
        val dl = deltas[nl - 1]
        for (j in dl.indices) dl[j] = out[j] - (if (j == target) 1f else 0f)

        for (l in nl - 1 downTo 0) {
            val d = deltas[l]
            val a = acts[l]
            val inD = sizes[l]
            val outD = sizes[l + 1]
            val wl = w[l]
            val gwl = gw[l]
            val gbl = gb[l]
            for (j in 0 until outD) {
                val dj = d[j]
                if (dj == 0f) continue
                gbl[j] += dj
                val off = j * inD
                for (i in 0 until inD) gwl[off + i] += dj * a[i]
            }
            if (l > 0) {
                val dp = deltas[l - 1]
                java.util.Arrays.fill(dp, 0f)
                for (j in 0 until outD) {
                    val dj = d[j]
                    if (dj == 0f) continue
                    val off = j * inD
                    for (i in 0 until inD) dp[i] += wl[off + i] * dj
                }
                for (i in 0 until inD) if (a[i] <= 0f) dp[i] = 0f
            }
        }
        return loss
    }
}

class Adam(private val net: Mlp, var lr: Float) {
    private val mw = Array(net.nl) { FloatArray(net.w[it].size) }
    private val vw = Array(net.nl) { FloatArray(net.w[it].size) }
    private val mb = Array(net.nl) { FloatArray(net.b[it].size) }
    private val vb = Array(net.nl) { FloatArray(net.b[it].size) }
    private var t = 0

    fun step(gw: Array<FloatArray>, gb: Array<FloatArray>, scale: Float) {
        t++
        val b1 = 0.9f
        val b2 = 0.999f
        val eps = 1e-8f
        val wd = 1e-4f
        val c1 = 1f - Math.pow(b1.toDouble(), t.toDouble()).toFloat()
        val c2 = 1f - Math.pow(b2.toDouble(), t.toDouble()).toFloat()
        for (l in 0 until net.nl) {
            val w = net.w[l]
            val g = gw[l]
            val m = mw[l]
            val v = vw[l]
            for (i in w.indices) {
                val gi = g[i] * scale + wd * w[i]
                m[i] = b1 * m[i] + (1f - b1) * gi
                v[i] = b2 * v[i] + (1f - b2) * gi * gi
                w[i] -= lr * (m[i] / c1) / (sqrt(v[i] / c2) + eps)
            }
            val bb = net.b[l]
            val gbl = gb[l]
            val m2 = mb[l]
            val v2 = vb[l]
            for (i in bb.indices) {
                val gi = gbl[i] * scale
                m2[i] = b1 * m2[i] + (1f - b1) * gi
                v2[i] = b2 * v2[i] + (1f - b2) * gi * gi
                bb[i] -= lr * (m2[i] / c1) / (sqrt(v2[i] / c2) + eps)
            }
        }
    }
}
