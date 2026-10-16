package com.mathai.studio

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Format model.bin (semua little-endian):
 *   "MAIM" | int32 versi(=1) | int32 sisi gambar(=28) | int32 nLayer
 *   int32 ukuran[nLayer+1] | int32 jumlahKelas
 *   per kelas: int32 panjangByte + label UTF-8
 *   per layer: float W[out*in] (row-major [out][in]) lalu float b[out]
 * Aktivasi: ReLU di semua layer kecuali terakhir (softmax).
 */
object ModelIO {

    fun write(net: Mlp, labels: List<String>, out: OutputStream) {
        val lb = labels.map { it.toByteArray(Charsets.UTF_8) }
        var floats = 0
        for (l in 0 until net.nl) floats += net.w[l].size + net.b[l].size
        val size = 16 + 4 * (net.nl + 1) + 4 + lb.sumOf { 4 + it.size } + 4 * floats
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("MAIM".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(IMG)
        buf.putInt(net.nl)
        for (s in net.sizes) buf.putInt(s)
        buf.putInt(labels.size)
        for (b in lb) {
            buf.putInt(b.size)
            buf.put(b)
        }
        for (l in 0 until net.nl) {
            for (f in net.w[l]) buf.putFloat(f)
            for (f in net.b[l]) buf.putFloat(f)
        }
        out.write(buf.array())
        out.flush()
    }

    fun read(input: InputStream): Pair<Mlp, List<String>> {
        val buf = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        require(String(magic, Charsets.US_ASCII) == "MAIM") { "Bukan file model MathAI" }
        val ver = buf.getInt()
        val side = buf.getInt()
        require(ver == 1 && side == IMG) { "Versi model tidak cocok" }
        val nl = buf.getInt()
        val sizes = IntArray(nl + 1) { buf.getInt() }
        val nc = buf.getInt()
        val labels = ArrayList<String>()
        repeat(nc) {
            val n = buf.getInt()
            val b = ByteArray(n)
            buf.get(b)
            labels.add(String(b, Charsets.UTF_8))
        }
        val net = Mlp(sizes)
        for (l in 0 until nl) {
            for (i in net.w[l].indices) net.w[l][i] = buf.getFloat()
            for (i in net.b[l].indices) net.b[l][i] = buf.getFloat()
        }
        return Pair(net, labels)
    }
}
