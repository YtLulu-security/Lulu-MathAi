package com.mathai.studio

import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class TrainConfig(
    val epochs: Int = 30,
    val lr: Float = 0.002f,
    val batch: Int = 32,
    val h1: Int = 128,
    val h2: Int = 64
)

class TrainResult(
    val net: Mlp,
    val labels: List<String>,
    val valAcc: Float,
    val trainN: Int,
    val valN: Int
)

object Trainer {

    fun train(
        samples: List<Sample>,
        cfg: TrainConfig,
        stop: AtomicBoolean,
        log: (String) -> Unit,
        progress: (Int, Int) -> Unit
    ): TrainResult? {
        val labels = samples.map { it.label }.distinct().sorted()
        if (labels.size < 2) {
            log("Butuh minimal 2 kelas berbeda untuk melatih.")
            return null
        }
        val idx = HashMap<String, Int>()
        labels.forEachIndexed { i, s -> idx[s] = i }
        val rng = Random(42)

        // pembagian latih / validasi per kelas
        val byClass = Array(labels.size) { ArrayList<Int>() }
        samples.forEachIndexed { i, s -> byClass[idx[s.label]!!].add(i) }
        val trainIdx = ArrayList<Int>()
        val valIdx = ArrayList<Int>()
        for (lst in byClass) {
            java.util.Collections.shuffle(lst, rng)
            val nv = if (lst.size >= 5) max(1, (lst.size * 0.15f).toInt()) else 0
            for ((k, i) in lst.withIndex()) if (k < nv) valIdx.add(i) else trainIdx.add(i)
        }
        log("Kelas: ${labels.size} | latih: ${trainIdx.size} | validasi: ${valIdx.size}")

        val net = Mlp(intArrayOf(DIM, cfg.h1, cfg.h2, labels.size))
        net.initRandom(rng)
        val adam = Adam(net, cfg.lr)
        val gw = Array(net.nl) { FloatArray(net.w[it].size) }
        val gb = Array(net.nl) { FloatArray(net.b[it].size) }
        val acts = net.newActs()
        val deltas = net.newDeltas()

        val best = net.copy()
        var bestAcc = -1f
        var bestVLoss = Double.MAX_VALUE
        var epochsDone = 0

        for (ep in 1..cfg.epochs) {
            if (stop.get()) {
                log("Dihentikan.")
                break
            }
            val phase = (ep - 1).toDouble() / cfg.epochs
            adam.lr = cfg.lr * (0.05f + 0.95f * 0.5f * (1f + cos(PI * phase).toFloat()))
            java.util.Collections.shuffle(trainIdx, rng)

            var lossSum = 0.0
            var correct = 0
            var pos = 0
            while (pos < trainIdx.size) {
                if (stop.get()) break
                val end = min(pos + cfg.batch, trainIdx.size)
                for (l in 0 until net.nl) {
                    gw[l].fill(0f)
                    gb[l].fill(0f)
                }
                for (k in pos until end) {
                    val s = samples[trainIdx[k]]
                    fillInput(s.px, acts[0], rng.nextInt(5) - 2, rng.nextInt(5) - 2, rng)
                    net.forward(acts)
                    val t = idx[s.label]!!
                    lossSum += net.backward(acts, deltas, t, gw, gb)
                    if (argmax(acts[net.nl]) == t) correct++
                }
                adam.step(gw, gb, 1f / (end - pos))
                pos = end
            }
            if (stop.get() && pos < trainIdx.size) {
                log("Dihentikan.")
                break
            }

            var vc = 0
            var vLoss = 0.0
            for (i in valIdx) {
                val s = samples[i]
                fillInput(s.px, acts[0], 0, 0, null)
                net.forward(acts)
                val t = idx[s.label]!!
                if (argmax(acts[net.nl]) == t) vc++
                vLoss += -ln(max(acts[net.nl][t], 1e-7f).toDouble())
            }
            val vAcc = if (valIdx.isEmpty()) -1f else vc.toFloat() / valIdx.size
            val better = valIdx.isEmpty() || vAcc > bestAcc || (vAcc == bestAcc && vLoss < bestVLoss)
            if (better) {
                best.copyFrom(net)
                bestAcc = vAcc
                bestVLoss = vLoss
            }
            epochsDone = ep
            val trAcc = if (trainIdx.isEmpty()) 0f else correct.toFloat() / trainIdx.size
            val valTxt = if (valIdx.isEmpty()) "-" else "%.1f%%".format(vAcc * 100f)
            log("Ep %d/%d  loss %.3f  latih %.1f%%  val %s".format(
                ep, cfg.epochs, lossSum / max(1, trainIdx.size), trAcc * 100f, valTxt
            ))
            progress(ep, cfg.epochs)
        }

        if (epochsDone == 0) return null

        if (valIdx.isNotEmpty()) {
            val conf = HashMap<String, Int>()
            val perCls = IntArray(labels.size)
            val perOk = IntArray(labels.size)
            val a2 = best.newActs()
            for (i in valIdx) {
                val s = samples[i]
                fillInput(s.px, a2[0], 0, 0, null)
                best.forward(a2)
                val p = argmax(a2[best.nl])
                val t = idx[s.label]!!
                perCls[t]++
                if (p == t) perOk[t]++ else {
                    val key = "${labels[t]}->${labels[p]}"
                    conf[key] = (conf[key] ?: 0) + 1
                }
            }
            val top = conf.entries.sortedByDescending { it.value }.take(5)
            if (top.isNotEmpty()) {
                log("Sering tertukar: " + top.joinToString("  ") { "${it.key} (${it.value}x)" })
            }
            val weak = labels.indices
                .filter { perCls[it] > 0 && perOk[it] < perCls[it] }
                .sortedBy { perOk[it].toFloat() / perCls[it] }
                .take(5)
            if (weak.isNotEmpty()) {
                log("Kelas terlemah: " + weak.joinToString("  ") {
                    "${labels[it]} %.0f%%".format(100f * perOk[it] / perCls[it])
                })
            }
        }
        log("Selesai. Akurasi validasi terbaik: " + if (bestAcc < 0f) "-" else "%.1f%%".format(bestAcc * 100f))
        return TrainResult(best, labels, bestAcc, trainIdx.size, valIdx.size)
    }
}
