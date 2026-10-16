package com.mathai.studio

import android.graphics.Bitmap
import android.graphics.Color
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Satu sampel: label (satu simbol) + gambar 28x28 (784 byte, 255 = tinta). */
class Sample(val label: String, val px: ByteArray)

class DataStore(private val file: File) {
    val samples = ArrayList<Sample>()

    @Synchronized
    fun load() {
        samples.clear()
        if (!file.exists()) return
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { din ->
            try {
                while (true) {
                    val len = din.readUnsignedShort()
                    val lb = ByteArray(len)
                    din.readFully(lb)
                    val px = ByteArray(DIM)
                    din.readFully(px)
                    samples.add(Sample(String(lb, Charsets.UTF_8), px))
                }
            } catch (e: EOFException) {
                // akhir file (atau rekaman terakhir terpotong)
            }
        }
    }

    @Synchronized
    fun add(list: List<Sample>) {
        if (list.isEmpty()) return
        DataOutputStream(BufferedOutputStream(FileOutputStream(file, true))).use { out ->
            for (s in list) writeOne(out, s)
        }
        samples.addAll(list)
    }

    private fun writeOne(out: DataOutputStream, s: Sample) {
        val lb = s.label.toByteArray(Charsets.UTF_8)
        out.writeShort(lb.size)
        out.write(lb)
        out.write(s.px)
    }

    @Synchronized
    private fun rewrite() {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file, false))).use { out ->
            for (s in samples) writeOne(out, s)
        }
    }

    @Synchronized
    fun clear() {
        samples.clear()
        file.delete()
    }

    @Synchronized
    fun removeLabel(label: String): Int {
        val before = samples.size
        samples.removeAll { it.label == label }
        rewrite()
        return before - samples.size
    }

    @Synchronized
    fun snapshot(): ArrayList<Sample> = ArrayList(samples)

    @Synchronized
    fun counts(): Map<String, Int> = samples.groupingBy { it.label }.eachCount().toSortedMap()

    /** Zip berisi images/NNNNNN.png (tinta hitam di latar putih), labels.csv, README.txt. */
    @Synchronized
    fun exportZip(out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            val csv = StringBuilder("file,label\n")
            val bmp = Bitmap.createBitmap(IMG, IMG, Bitmap.Config.ARGB_8888)
            val pix = IntArray(DIM)
            val bos = ByteArrayOutputStream()
            samples.forEachIndexed { i, s ->
                val name = "images/%06d.png".format(i)
                for (k in 0 until DIM) {
                    val g = 255 - (s.px[k].toInt() and 255)
                    pix[k] = Color.rgb(g, g, g)
                }
                bmp.setPixels(pix, 0, IMG, 0, 0, IMG, IMG)
                bos.reset()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                zip.putNextEntry(ZipEntry(name))
                zip.write(bos.toByteArray())
                zip.closeEntry()
                csv.append(name).append(",\"")
                    .append(s.label.replace("\"", "\"\"")).append("\"\n")
            }
            zip.putNextEntry(ZipEntry("labels.csv"))
            zip.write(csv.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val readme = "MathAI Studio dataset\n" +
                "Gambar 28x28 grayscale, tinta hitam di latar putih (ink = 255 - piksel).\n" +
                "labels.csv: file,label (label = satu simbol, UTF-8, diberi tanda kutip).\n" +
                "Jumlah sampel: ${samples.size}\n"
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(readme.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
