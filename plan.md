# MacroGG — Aplikasi Otomasi Layar Android (Macrorify-Style)

Aplikasi otomasi sentuhan layar Android **100% offline (tanpa login/Firebase)** bergaya **Macrorify** dengan fitur lengkap namun tetap simpel dan mudah digunakan.

## Fitur Utama

1. **Image Detection (Template Matching) & Auto Click**:
   - Engine deteksi gambar pure-Kotlin (`TemplateMatcher` berbasis *coarse-to-fine Normalized Cross-Correlation* + *integral images*, cepat tanpa butuh native OpenCV).
   - Mendukung slider kemiripan (55%–99%), area pencarian opsional (`Region`), offset klik, dan kotak hijau (*match highlight*) real-time saat gambar ditemukan.
2. **Screenshot & Crop Langsung dari Floating Overlay**:
   - Tekan tombol `✂Img` di Floating Bar untuk mengambil layar saat ini, menyeret kotak pilihan, lalu **Simpan** ke Pustaka Gambar atau **Simpan + Klik** agar langsung menjadi aksi di macro aktif.
3. **Multi-Point Auto Clicker**:
   - Tambah titik klik (`+Klik`) & swipe (`+Swp`) yang bisa diseret langsung di layar.
   - Ketuk penanda untuk mengedit cepat durasi, jeda, jumlah klik (*burst repeat*), atau hapus.
   - Mendukung loop terbatas/tak terbatas (`∞`), acak posisi (`±px`), acak jeda, dan batas waktu berhenti otomatis.
4. **Rekam & Putar Ulang Sentuhan (Record & Replay)**:
   - Tekan `●Rec` di Floating Bar untuk merekam ketukan/geseran secara langsung (dengan opsi *Tembus: ON* agar aplikasi di bawah tetap merespons saat merekam), lalu putar ulang dengan kecepatan `0.5x–3.0x`.
5. **Kondisi Bercabang (If / Else & Loop)**:
   - Aksi **Deteksi Gambar** dan **Deteksi Warna** memiliki cabang **Jika ditemukan (Then)**, **Jika tidak ditemukan (Else)**, dan mode lanjutan (*Lanjut*, *Ulang dari awal*, *Hentikan*).
   - Mendukung blok **Ulangi N× (`LoopAction`)**, **Ulang dari Awal**, dan **Hentikan**.
6. **Lua Script Studio (Ter-Sandbox + Vision API)**:
   - API lengkap: `findImage`, `findAllImages`, `waitImage`, `clickImage`, `click`, `longClick`, `swipe`, `getColor`, `matchColor`, `waitForColor`, `sleep`, `log`, `isRunning`, `stop`.
7. **100% Offline + Export/Import `.zip`**:
   - Semua macro, gambar target PNG, dan script Lua disimpan lokal di perangkat.
   - Ekspor satu macro (`.zip` berisi `macro.json` + semua PNG terkait) atau backup penuh seluruh data agar mudah dibagikan/dipindahkan ke perangkat lain.
