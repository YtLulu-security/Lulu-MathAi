# MathAI Studio v0.1

App pelatih untuk pembacaan soal matematika (fase 1: **baca**, belum menjawab).
Murni Kotlin, tanpa library eksternal. Training berjalan di HP, hasilnya diekspor sebagai `model.bin` untuk app final.

## Build
**GitHub Actions (disarankan):** push folder ini ke repo GitHub, buka tab Actions, jalankan "Build APK", unduh artifact `MathAI-Studio-debug`.

**Android Studio:** Open folder ini, tunggu sync, Run.

## Alur pakai
1. Tab **Latih** → *Generate data cetak* → *Mulai latih*.
2. Tab **Baca** → tulis ekspresi satu baris (atau impor foto) → *Baca*.
3. Cek kotak merah di gambar. Koreksi teks hasil bacaan → *Simpan sebagai data latih*.
   (Sebelum ada model: ketik label tiap simbol berurutan kiri ke kanan.)
4. Ulangi, lalu *Mulai latih* lagi. Tab **Data**: ekspor `model.bin` dan `dataset.zip`.

Aturan penyimpanan koreksi: jumlah karakter di teks harus sama dengan jumlah kotak merah.

## Batasan fase ini
- Level simbol: satu baris, simbol terpisah. Pecahan/pangkat/akar bertumpuk belum terbaca benar
  (komponen yang x-nya tumpang tindih digabung jadi satu simbol).
- Model: MLP 784-128-64-C. Data cetak saja tidak cukup untuk tulisan tangan; kumpulkan koreksi tulisan tanganmu.
- Level maksimal (image → LaTeX) butuh training di PC memakai `dataset.zip` dari app ini.

## Spesifikasi preprocessing (harus identik di app final)
1. Citra → grayscale → ambang Otsu → tinta = kelas minoritas, diskalakan 0..255.
2. Simbol = komponen terhubung 8 arah (tinta > 90). Noise kecil dibuang. Komponen yang rentang x-nya
   tumpang tindih ≥ 50% dari lebar yang lebih sempit digabung (untuk `=`, `÷`, `+`).
3. Tiap simbol: potong bounding box, skala proporsional (rata-rata area) hingga sisi terpanjang 20 px,
   taruh di tengah kanvas 28x28. Input jaringan = piksel / 255.

## Format model.bin (little-endian)
```
"MAIM" | int32 versi=1 | int32 sisi=28 | int32 nLayer
int32 ukuran[nLayer+1] | int32 jumlahKelas
per kelas: int32 panjangByte + label UTF-8
per layer: float W[out*in] (row-major [out][in]) lalu float b[out]
```
ReLU di semua layer kecuali terakhir (softmax). Mudah dibaca ulang di C++ untuk app final.

## Struktur
```
app/src/main/java/com/mathai/studio/
  Preprocess.kt   Otsu, segmentasi, normalisasi 28x28
  Mlp.kt          forward, backprop, Adam
  Trainer.kt      loop training + validasi + laporan simbol tertukar
  Synth.kt        generator data cetak dari font
  DataStore.kt    dataset lokal + ekspor zip
  ModelIO.kt      baca/tulis model.bin
  DrawingView.kt  kanvas tulis
  MainActivity.kt tiga tab: Baca, Latih, Data
```
