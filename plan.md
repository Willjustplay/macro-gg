# TapLua — Rencana Arsitektur & Spesifikasi Aplikasi Otomasi Layar Android

Dokumen ini menjelaskan arsitektur menyeluruh aplikasi **TapLua** (aplikasi otomasi sentuhan layar Android bergaya Macrorify) yang mendukung **Visual Macro (Klik, Long Press, Swipe, Wait Color)**, **Engine Script Lua Kustom ter-sandbox**, **Deteksi Warna Piksel Layar real-time**, **Floating Overlay interaktif dengan Live Log**, serta **Sinkronisasi Cloud berbasis Firebase Firestore & Google Sign-In**.

---

## 1. Arsitektur Sistem Menyeluruh

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                     PRESENTATION LAYER (Jetpack Compose M3)                  │
│  ┌──────────────┐  ┌─────────────────┐  ┌───────────────┐  ┌──────────────┐  │
│  │ AuthScreen   │  │ Dashboard &     │  │ Visual Macro  │  │ Lua Studio   │  │
│  │ (Credential  │  │ Permission Hub  │  │ Builder &     │  │ IDE + Syntax │  │
│  │  Manager)    │  │ + Quick Launch  │  │ Step Editor   │  │ Highlighting │  │
│  └──────┬───────┘  └────────┬────────┘  └───────┬───────┘  └──────┬───────┘  │
│         └───────────────────┴─────────┬─────────┴─────────────────┘          │
│                                       ▼                                      │
│                    ┌────────────────────────────────────┐                    │
│                    │  TapLuaViewModel (StateFlow MVVM)  │                    │
│                    └─────────────────┬──────────────────┘                    │
└──────────────────────────────────────┼───────────────────────────────────────┘
                                       │
          ┌────────────────────────────┴────────────────────────────┐
          ▼                                                         ▼
┌──────────────────────────────────────────┐   ┌───────────────────────────────┐
│          CLOUD DATA & AUTH LAYER         │   │    AUTOMATION CORE ENGINE     │
│  ┌────────────────────────────────────┐  │   │  ┌─────────────────────────┐  │
│  │ GoogleAuthHelper                   │  │   │  │ AutomationCoordinator   │  │
│  │ (CredentialManager + FirebaseAuth) │  │   │  │ (IDLE/RUNNING/PAUSED/   │  │
│  └────────────────────────────────────┘  │   │  │  ERROR State Machine)   │  │
│  ┌────────────────────────────────────┐  │   │  └─────┬─────────────┬─────┘  │
│  │ TapLuaRepository                   │  │   │        ▼             ▼        │
│  │ (Firestore Named DB + Offline      │  │   │  ┌───────────┐ ┌───────────┐  │
│  │  Cache + Zero-Trust Rules)         │  │   │  │VisualMacro│ │Sandboxed  │  │
│  └────────────────────────────────────┘  │   │  │Runner     │ │LuaJ Engine│  │
│                                          │   │  └─────┬─────┘ └─────┬─────┘  │
└──────────────────────────────────────────┘   │        └──────┬──────┘        │
                                               │               ▼               │
                                               │  ┌─────────────────────────┐  │
                                               │  │ ExecutionLogBus         │  │
                                               │  │ (Ring Buffer StateFlow) │  │
                                               │  └────────────┬────────────┘  │
                                               └───────────────┼───────────────┘
                                                               │
┌──────────────────────────────────────────────────────────────┼───────────────┐
│                         ANDROID SYSTEM SERVICES              ▼               │
│  ┌────────────────────────┐  ┌─────────────────────────┐  ┌───────────────┐  │
│  │ TapLuaAccessibility    │  │ ScreenColorSampler      │  │ Floating      │  │
│  │ Service                │  │ Service (FGS)           │  │ OverlayService│  │
│  │ - dispatchGesture()    │  │ - MediaProjection       │  │ - Control Bar │  │
│  │ - Click / Long / Swipe │  │ - VirtualDisplay        │  │ - Draggable   │  │
│  └────────────────────────┘  │ - ImageReader RGBA_8888 │  │   Targets     │  │
│                              │ - getColor(x, y)        │  │ - Live Log    │  │
│                              └─────────────────────────┘  │ - Color Spy   │  │
│                                                           └───────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Modul Inti & Tanggung Jawab

### A. Automation Core Engine
- **AutomationCoordinator**: Pengelola *state machine* tunggal (`IDLE`, `RUNNING`, `PAUSED`, `ERROR`) untuk menjamin tidak ada dua sesi otomasi bertabrakan, memicu pergantian mode marker overlay menjadi tembus sentuhan (*pass-through*) saat berjalan, serta mencatat ringkasan eksekusi.
- **VisualMacroRunner**: Mengeksekusi daftar langkah terurut (`CLICK`, `LONG_PRESS`, `SWIPE`, `WAIT`, `WAIT_COLOR`) dengan dukungan pengulangan (`loopCount = 0` untuk tak terbatas), jeda antar loop, dan *random touch jitter* untuk stabilitas klik.
- **SandboxedLuaEngine (LuaJ Pure JVM)**:
  - Menggunakan `org.luaj:luaj-jse` dengan *sandbox* ketat (`BaseLib`, `TableLib`, `StringLib`, `MathLib`, `Bit32Lib`, `CoroutineLib` — **menonaktifkan** `LuajavaLib`, `OsLib`, `IoLib`).
  - Memasang `DebugLib` *instruction hook* setiap 100 instruksi bytecode agar *infinite loop* (`while true do end`) dapat di-Pause atau di-Stop dalam hitungan milidetik.
  - Mendukung *Dry Syntax Validation* langsung di Lua Studio sebelum dijalankan.
- **ExecutionLogBus**: *Ring buffer* reaktif (200 entri terakhir) untuk menyalurkan log `INFO`, `ACTION`, `COLOR`, `WARN`, `ERROR`, dan `LUA` secara real-time ke Floating Overlay maupun layar Logs di aplikasi.

### B. Android System Services
- **TapLuaAccessibilityService**: Mengirim gestur sentuhan nyata lintas aplikasi via `dispatchGesture()` dan `GestureDescription.StrokeDescription` (API 24–36).
- **ScreenColorSamplerService** (`foregroundServiceType="mediaProjection"`): Mengelola sesi `MediaProjection` + `VirtualDisplay` + `ImageReader(RGBA_8888)` untuk membaca warna piksel `(x, y)` dalam memori RAM (<5ms) tanpa menulis file screenshot ke penyimpanan.
- **FloatingOverlayService** (`foregroundServiceType="specialUse"`):
  - **Floating Control Bar**: Panel ringkas yang bisa digeser dengan tombol Play/Pause, Stop, Tambah Titik Target, Eyedropper Color Spy, Toggle Live Log, dan Tutup.
  - **Visual Target Markers**: Penanda bernomor yang dapat diseret saat *IDLE*, lalu otomatis memakai `FLAG_NOT_TOUCHABLE` saat *RUNNING* agar tidak menghalangi klik layar.
  - **Collapsible Live Log Console**: Jendela konsol semi-transparan di atas layar target untuk memantau output `log(...)` Lua dan koordinat aksi.

### C. Cloud Persistence & Security (Firebase Firestore + Credential Manager)
- **Autentikasi**: Wajib menggunakan **Google Sign-In via Jetpack Credential Manager** (`GetSignInWithGoogleOption` untuk tombol interaktif, `GetGoogleIdOption` untuk *silent auto sign-in*).
- **Isolasi Multi-User**: Seluruh data disimpan di subkoleksi `/users/{userId}/...` pada named database Firestore dengan aturan keamanan *zero-trust* (`firestore.rules`):
  - `/users/{userId}` — Profil & preferensi tema/sentuhan.
  - `/users/{userId}/macros/{macroId}` — Koleksi Visual Macro.
  - `/users/{userId}/scripts/{scriptId}` — Koleksi Script Lua kustom.
  - `/users/{userId}/runHistory/{runId}` — Riwayat eksekusi *append-only*.

---

## 3. Referensi API Lua Kustom (TapLua Standard Library)

| Fungsi | Signature | Kegunaan |
| :--- | :--- | :--- |
| `click` | `click(x, y, [durationMs])` | Klik layar di koordinat `(x, y)` (durasi default 50ms). |
| `longClick` | `longClick(x, y, [durationMs])` | Tahan sentuhan di `(x, y)` (default 600ms). |
| `swipe` | `swipe(x1, y1, x2, y2, [durationMs])` | Geser dari `(x1, y1)` ke `(x2, y2)` (default 350ms). |
| `sleep` | `sleep(ms)` | Tunggu secara kooperatif (langsung berhenti saat Stop ditekan). |
| `getColor` | `hex, r, g, b = getColor(x, y)` | Membaca warna piksel layar saat ini dalam format `#RRGGBB` dan RGB. |
| `matchColor` | `ok = matchColor(x, y, hex, [tol])` | Mengecek kecocokan warna piksel dengan toleransi per kanal (default 15). |
| `waitForColor` | `ok = waitForColor(x, y, hex, [tol], [timeoutMs])` | Menunggu hingga warna cocok atau melewati `timeoutMs`. |
| `getScreenSize` | `w, h = getScreenSize()` | Mengembalikan resolusi layar perangkat aktif dalam piksel. |
| `log` | `log(message)` | Mencetak pesan ke Live Log Console. |
| `isRunning` | `active = isRunning()` | Mengembalikan `true` selama sesi belum dihentikan. |
| `stop` | `stop()` | Menghentikan eksekusi script dengan status selesai bersih. |

### Contoh Script Lua
```lua
local w, h = getScreenSize()
log("Mulai otomasi pada layar " .. w .. "x" .. h)

for i = 1, 5 do
    if not isRunning() then break end
    if waitForColor(540, 1280, "#2DD4BF", 20, 4000) then
        log("Target ditemukan pada loop #" .. i)
        click(540, 1280, 60)
        sleep(800)
    else
        log("Timeout menunggu warna target")
    end
end
log("Selesai!")
```
