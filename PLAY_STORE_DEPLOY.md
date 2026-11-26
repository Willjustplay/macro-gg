# Panduan Deploy Google Play Store — Wjust macro (Android)

## 1. Identitas Aplikasi
- **Branch Repository**: `playstore` (khusus Android Play Store, terpisah dari rilis PC di `main`)
- **Package Name (`applicationId`)**: `com.willjustplay.wjustmacro`
- **Versi Saat Ini**: `1.0.0` (`versionCode = 1`)
- **Min SDK / Target SDK**: API 24 (Android 7.0) / API 36 (Android 16)
- **Minifikasi Release**: R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) sudah aktif dengan aturan ProGuard di `app/proguard-rules.pro`.

## 2. Konfigurasi Paket Langganan di Google Play Console
Buka **Google Play Console → Monetize → Products → Subscriptions**, lalu buat **1 Subscription Product** dengan **2 Base Plans**:

| Pengaturan | Nilai Exact di Kode |
|---|---|
| **Subscription Product ID** | `wjust_macro_premium` |
| **Base Plan 1 (Bulanan)** | `monthly` (Billing period: 1 Month, Auto-renewing) |
| **Base Plan 2 (Tahunan)** | `yearly` (Billing period: 1 Year, Auto-renewing) |

> **Catatan**: Harga dan mata uang (IDR, USD, dll.) diatur langsung di Google Play Console dan otomatis tampil di Paywall aplikasi melalui Google Play Billing Library v7.

## 3. Batasan Fitur Gratis vs PRO
- **Gratis (Selamanya)**:
  - Maksimal **3 macro**
  - Aksi yang dibuka: **Klik**, **Swipe**, dan **Deteksi Gambar** (+ crop layar & pustaka gambar target)
- **Wjust macro PRO (Bulanan / Tahunan)**:
  - Macro **tanpa batas**
  - Semua **18 jenis aksi** (termasuk Tunggu, Loop, Kejar Objek, Teks OCR, Deteksi Warna, Rekam Sentuhan, Keluar/Masuk Aplikasi, Loncat, Hubungkan Macro, dll.)
  - **Ekspor & Impor** (`.zip`, Macro Set, Backup penuh, Ekspor Gambar)
  - **Macro Set**, **Kunci Sandi / Tenggat / Mode Pasif**, **Logo Kustom**
  - **Auto-Scale Resolusi**, **Watchdog Anti-Macet**, **Color Spy**, dan **Lua Studio**

## 4. Build Android App Bundle (`.aab`) untuk Play Store
Google Play mewajibkan format **`.aab`** (bukan `.apk`) untuk rilis baru:

```bash
export KEYSTORE_PATH=/path/to/my-upload-key.jks
export STORE_PASSWORD="password_keystore"
export KEY_PASSWORD="password_key"

./gradlew :app:bundleRelease
```
Hasil file siap upload berada di:
`app/build/outputs/bundle/release/app-release.aab`

## 5. Checklist Wajib Lolos Review Google Play
1. **Prominent Disclosure Aksesibilitas**: Sudah terpasang otomatis (`AccessibilityDisclosureDialog`) sebelum membuka Pengaturan Aksesibilitas.
2. **Video Demo Aksesibilitas (YouTube Unlisted)**: Rekam layar HP yang menampilkan:
   - Dialog pengungkapan (Prominent Disclosure) saat mengetuk izin Aksesibilitas → user klik *Setuju & Lanjutkan* → mengaktifkan `Wjust macro`.
   - Menjalankan macro Klik/Swipe sederhana di layar.
   - Upload ke YouTube (Unlisted) lalu tempel link di **Play Console → App Content → Accessibility Service Declaration**.
3. **Kebijakan Privasi (Privacy Policy URL)**: Host halaman HTML/Google Sites/GitHub Pages yang menyatakan aplikasi berjalan 100% lokal/offline, tidak mengumpulkan data layar/sandi/pribadi, dan pembayaran diproses oleh Google Play Billing.
4. **Deklarasi `QUERY_ALL_PACKAGES` & `FOREGROUND_SERVICE`**:
   - `QUERY_ALL_PACKAGES`: dipakai oleh aksi *Masuk Aplikasi / Keluar Aplikasi* (PRO) agar user dapat memilih aplikasi/game yang ingin dibuka oleh macro.
   - `FOREGROUND_SERVICE_MEDIA_PROJECTION` & `FOREGROUND_SERVICE_SPECIAL_USE`: dipakai untuk tangkap layar Deteksi Gambar dan kontrol overlay melayang.
5. **Uji Pembelian (License Testing)**:
   - Tambahkan email Google Anda di **Play Console → Setup → License Testing** dan upload `.aab` ke track **Internal Testing** agar dapat menguji pembayaran Bulanan & Tahunan secara gratis tanpa biaya nyata.
