package com.example.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(12)

/** Rectangle on screen in real pixels (used as search area for image detection). */
@Serializable
data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** Flow action taken after a condition branch (Found / Not Found) finishes its child actions. */
@Serializable
enum class NotFoundMode(val label: String) {
    CONTINUE("Lanjut ke aksi berikutnya"),
    SWITCH_BRANCH("Pindah ke jalur cabang (permanen)"),
    CALL_BRANCH_RETURN("Singgah ke jalur cabang lalu kembali"),
    RESTART("Ulangi macro dari awal"),
    FINISH("Selesaikan macro (ke macro aktif)"),
    STOP("Hentikan macro")
}

/** How [ImageAction] handles banned/forbidden images that must NOT be clicked. */
@Serializable
enum class BannedImageMode(val label: String, val shortLabel: String, val description: String) {
    AVOID_MATCH_AREA(
        "Hindari Area Gambar Terlarang",
        "Hindari Area",
        "Jangan klik target yang dekat/tumpang tindih dengan gambar terlarang — otomatis cari target lain yang aman."
    ),
    BLOCK_IF_PRESENT(
        "Batalkan Jika Muncul di Layar",
        "Blokir Layar",
        "Jika gambar terlarang terdeteksi di mana saja pada area pencarian, batalkan klik dan langsung ke cabang Tidak Ditemukan."
    )
}

/** How [GoToBranchAction] routes execution to a named sequence branch. */
@Serializable
enum class BranchNavigationMode(val label: String, val shortLabel: String, val description: String) {
    SWITCH(
        "Pindah Permanen ke Jalur Tujuan",
        "Pindah",
        "Mengubah arah langsung ke jalur tujuan sampai selesai."
    ),
    CALL_AND_RETURN(
        "Singgah ke Jalur Tujuan lalu Kembali",
        "Singgah & Kembali",
        "Jalankan seluruh langkah di jalur tujuan lalu otomatis kembali melanjutkan aksi berikutnya."
    )
}

/** Condition type evaluated inside a [BranchRouteRule]. */
@Serializable
enum class BranchConditionType(val label: String) {
    IMAGE("Gambar"),
    TEXT("Teks / Nilai"),
    COLOR("Warna Piksel"),
    VARIABLE("Variabel / Hitungan")
}

/** Anti-detection Humanizer preset levels for natural human-like touch & timing behavior. */
@Serializable
enum class HumanizerPreset(val label: String, val shortLabel: String, val description: String) {
    OFF(
        "Mati (Presisi Penuh)",
        "Mati",
        "Klik lurus tepat di titik target & jeda tetap (paling cepat)."
    ),
    NATURAL(
        "Natural (Ringan & Aman)",
        "Natural",
        "Sebaran klik Gaussian halus, kurva swipe Bezier tipis, dan variasi jeda ±15%."
    ),
    STEALTH(
        "Stealth (Anti-Deteksi Ketat)",
        "Stealth",
        "Gaussian + kurva Bezier dinamis + mikro-hesitasi + Istirahat Alami (Human Break) berkala."
    ),
    CUSTOM(
        "Kustom (Atur Sendiri)",
        "Kustom",
        "Sesuaikan radius Gaussian, lengkungan swipe, variasi jeda, dan Human Break secara manual."
    )
}

/** Operation performed by [VariableAction] without writing code/Lua. */
@Serializable
enum class VariableOperation(val label: String, val shortLabel: String) {
    SET("Atur Nilai (=)", "="),
    ADD("Tambah (+)", "+"),
    SUBTRACT("Kurangi (−)", "−"),
    MULTIPLY("Kali (×)", "×"),
    READ_OCR_NUMBER("Baca Angka OCR ke Variabel", "OCR→Var"),
    RESET_ALL("Reset Semua Variabel", "Reset")
}

/** Lifetime & isolation scope for visual macro variables. */
@Serializable
enum class VariableScope(val label: String, val shortLabel: String) {
    SESSION("Global Sesi (Semua Akun)", "Sesi"),
    PER_ACCOUNT("Per Akun (Terpisah Tiap Akun)", "Per Akun")
}

/** Numeric comparison operators for [CheckVariableAction] and variable [BranchRouteRule]. */
@Serializable
enum class VariableCompareOp(val label: String, val symbol: String) {
    EQUALS("Sama dengan (=)", "="),
    NOT_EQUALS("Tidak sama (≠)", "≠"),
    LESS_THAN("Lebih kecil (<)", "<"),
    LESS_OR_EQUAL("Kecil / sama (≤)", "≤"),
    GREATER_THAN("Lebih besar (>)", ">"),
    GREATER_OR_EQUAL("Besar / sama (≥)", "≥");

    fun evaluate(actual: Double, expected: Double): Boolean {
        val eps = 1e-6
        return when (this) {
            EQUALS -> kotlin.math.abs(actual - expected) <= eps
            NOT_EQUALS -> kotlin.math.abs(actual - expected) > eps
            LESS_THAN -> actual < expected - eps
            LESS_OR_EQUAL -> actual <= expected + eps
            GREATER_THAN -> actual > expected + eps
            GREATER_OR_EQUAL -> actual >= expected - eps
        }
    }
}

/** Severity level for [NotifyAction] and remote Telegram/Discord notifications. */
@Serializable
enum class NotifyLevel(val label: String, val icon: String) {
    INFO("Info", "ℹ️"),
    SUCCESS("Sukses", "✅"),
    WARNING("Peringatan", "⚠️"),
    CRITICAL("Darurat / Alarm", "🚨")
}

/** Action taken when a [BranchRouteRule] matches (or when no rule matches as fallback). */
@Serializable
enum class BranchOutcomeAction(val label: String, val shortLabel: String) {
    CONTINUE("Lanjut langkah berikutnya", "Lanjut"),
    SWITCH_BRANCH("Pindah jalur (permanen)", "Pindah Jalur"),
    CALL_BRANCH_RETURN("Singgah jalur lalu kembali", "Singgah"),
    RESTART_MACRO("Ulangi macro dari awal", "Ulangi"),
    STOP_MACRO("Hentikan macro", "Stop")
}

/** Universal game account switching method supported without root. */
@Serializable
enum class AccountSwitchMethod(val label: String, val shortLabel: String, val description: String) {
    OCR_LIST(
        "OCR Daftar Akun + Auto-Scroll",
        "OCR + Scroll",
        "Cari nama/email akun di layar via OCR dengan auto-scroll lalu klik (tanpa perlu password)."
    ),
    CLONE_APP(
        "Ganti Clone App / Dual App",
        "Clone App",
        "Tutup aplikasi akun sebelumnya lalu buka aplikasi clone / dual app milik akun giliran."
    ),
    HYBRID(
        "Hybrid (Clone App + OCR)",
        "Hybrid",
        "Otomatis pindah clone app jika paket berbeda, lalu pilih akun di dalam game via OCR + Auto-Scroll."
    )
}

/** Order in which enabled accounts are cycled. */
@Serializable
enum class AccountRotationMode(val label: String, val shortLabel: String) {
    SEQUENTIAL_LOOP("Berurutan Berulang (1→2→3→1)", "Berulang"),
    ONCE_ALL_ACCOUNTS("1× Semua Akun lalu Stop", "1× Putaran"),
    RANDOM("Acak (Tanpa Ulang Langsung)", "Acak")
}

/** When automatic account rotation triggers during macro execution. */
@Serializable
enum class AccountTriggerMode(val label: String, val shortLabel: String, val description: String) {
    AFTER_LOOPS(
        "Setiap N Loop",
        "N Loop",
        "Otomatis pindah ke akun berikutnya setelah N putaran jalur utama selesai."
    ),
    AFTER_MINUTES(
        "Setiap X Menit",
        "X Menit",
        "Otomatis pindah ke akun berikutnya setelah durasi X menit berjalan pada akun saat ini."
    ),
    LOOPS_OR_MINUTES(
        "N Loop atau X Menit",
        "Loop / Menit",
        "Pindah akun otomatis mana saja yang lebih dulu tercapai."
    ),
    MANUAL_ACTION_ONLY(
        "Manual via Aksi",
        "Manual Aksi",
        "Hanya pindah akun saat aksi 'Ganti Akun Berikutnya' dijalankan (mis. dari Cek Hasil & Pilih Arah)."
    )
}

/** Auto-scroll direction when scanning an account list via OCR. */
@Serializable
enum class AccountScrollDirection(val label: String, val shortLabel: String) {
    VERTICAL("Vertikal (Atas/Bawah)", "Vertikal"),
    HORIZONTAL("Horizontal (Kiri/Kanan)", "Horizontal")
}

/** Text matching strategy when locating an account keyword on screen via OCR. */
@Serializable
enum class AccountOcrMatchMode(val label: String, val shortLabel: String) {
    CONTAINS("Mengandung Teks (Rekomendasi)", "Mengandung"),
    EXACT("Persis Sama", "Persis")
}

/** Behavior when an account cannot be located/opened during switching. */
@Serializable
enum class AccountFailBehavior(val label: String, val shortLabel: String) {
    SKIP_TO_NEXT("Lompat ke Akun Berikutnya", "Lompat Akun"),
    CONTINUE_CURRENT("Lanjut Tanpa Pindah", "Lanjut"),
    STOP_MACRO("Hentikan Macro", "Stop")
}

/** How [FinishMacroAction] hands control back to an active macro without stopping the running engine. */
@Serializable
enum class FinishMacroMode(val label: String, val shortLabel: String, val description: String) {
    RETURN_TO_ACTIVE_CONTINUE(
        "Kembali & Lanjutkan Macro Aktif (Rekomendasi)",
        "Lanjut Aktif",
        "Selesaikan macro/pasif ini lalu langsung kembali ke macro aktif pemanggil di aksi berikutnya tanpa berhenti."
    ),
    RETURN_TO_ACTIVE_RESTART(
        "Kembali & Ulangi Macro Aktif dari Awal",
        "Ulangi Aktif",
        "Selesaikan macro/pasif ini lalu langsung kembali ke macro aktif dan ulangi dari langkah pertama tanpa berhenti."
    ),
    SWITCH_TO_SPECIFIC_ACTIVE(
        "Pindah ke Macro Aktif Tertentu",
        "Pindah Aktif",
        "Selesaikan macro ini lalu langsung jalankan macro aktif lain pilihan Anda tanpa menghentikan eksekusi."
    )
}

/** How [JumpAction] jumps forward past actions or backward to an earlier action. */
@Serializable
enum class JumpMode(val label: String, val shortLabel: String, val description: String) {
    SKIP_AFTER_ACTION(
        "Loncat Melewati Aksi Tujuan",
        "Lewati Aksi",
        "Meloncat maju melewati aksi yang dipilih lalu lanjut di aksi tepat sesudahnya."
    ),
    JUMP_TO_ACTION(
        "Loncat ke Aksi Tujuan (Mundur / Maju)",
        "Ke Aksi",
        "Meloncat kembali ke belakang (atau maju) langsung ke aksi yang dipilih lalu lanjut dari sana."
    ),
    SKIP_FORWARD_STEPS(
        "Loncat Lewati N Aksi ke Depan",
        "+N Maju",
        "Melewati sejumlah N aksi di depan lalu lanjut di aksi berikutnya."
    ),
    JUMP_BACKWARD_STEPS(
        "Loncat Kembali N Aksi ke Belakang",
        "-N Mundur",
        "Meloncat mundur sejumlah N langkah ke aksi sebelumnya."
    )
}

/** Strategy used when the stuck-macro watchdog triggers. */
@Serializable
enum class StuckRecoveryMode(val label: String) {
    RUN_WATCHDOG_ACTIONS("Jalankan Aksi Anti-Macet"),
    RESTART_MACRO("Ulangi macro dari awal"),
    TAP_CENTER_AND_RESTART("Klik tengah layar lalu ulangi"),
    STOP_MACRO("Hentikan macro")
}

/** How [ExitAppAction] leaves or closes the foreground application. */
@Serializable
enum class ExitAppMode(val label: String, val description: String) {
    CLOSE_RECENT_AND_HOME(
        "Tutup Recent + Hapus Latar Belakang (Rekomendasi)",
        "Tutup aplikasi dari Recent Apps lalu paksa berhenti prosesnya di latar belakang agar benar-benar bersih."
    ),
    FORCE_STOP_APP(
        "Paksa Berhenti Langsung (Info Aplikasi)",
        "Buka Info Aplikasi sistem lalu klik Paksa Berhenti → OK agar aplikasi 100% terhapus dari latar belakang."
    ),
    HOME_ONLY(
        "Keluar ke Layar Utama (Home)",
        "Langsung keluar ke layar beranda (Home) tanpa menutup paksa di latar belakang."
    ),
    BACK_THEN_HOME(
        "Tekan Kembali lalu ke Home",
        "Tekan tombol Back beberapa kali lalu keluar ke layar beranda."
    )
}

/** Swipe direction used when dismissing the active app card inside Recent Apps. */
@Serializable
enum class RecentsSwipeDirection(val label: String, val shortLabel: String) {
    AUTO("Otomatis (Ikuti Rotasi Layar)", "Auto"),
    SWIPE_UP("Geser Ke Atas (Vertikal)", "Atas"),
    SWIPE_RIGHT("Geser Ke Kanan", "Kanan"),
    SWIPE_LEFT("Geser Ke Kiri", "Kiri")
}

/** How [TrackObjectAction] interacts with a moving object once located. */
@Serializable
enum class TrackInteractionMode(val label: String, val shortLabel: String, val description: String) {
    TAP_ONCE(
        "Tap Sekali",
        "Tap 1×",
        "Kejar objek, tap 1× tepat ke posisi objek (dengan prediksi gerak), lalu lanjut."
    ),
    TAP_CONTINUOUS(
        "Kejar & Tap Terus",
        "Tap Terus",
        "Terus melacak dan mengetuk objek selama masih terlihat hingga hilang atau batas waktu habis."
    ),
    DRAG_TO_POINT(
        "Drag Objek → Titik Tujuan",
        "Drag → Titik",
        "Tangkap objek bergerak lalu seret ke koordinat tujuan yang ditentukan."
    ),
    DRAG_BY_OFFSET(
        "Drag Objek Sejauh (dX, dY)",
        "Drag dX/dY",
        "Tangkap objek bergerak lalu geser sejauh jarak relatif X/Y dari posisi objek."
    ),
    DRAG_FROM_POINT_TO_OBJECT(
        "Drag dari Titik → Objek",
        "Titik → Objek",
        "Seret dari titik awal (misal skill/kartu/joystick) langsung ke posisi objek bergerak."
    )
}

/** Unit for macro expiration duration. */
@Serializable
enum class ExpiryUnit(val label: String) {
    DAYS("Hari"),
    MONTHS("Bulan")
}

/** How coordinates, gestures, regions & templates scale across ALL device screen resolutions & aspect ratios. */
@Serializable
enum class ResolutionScaleMode(val label: String, val description: String) {
    ADAPTIVE(
        "Adaptif Semua Layar (Rekomendasi)",
        "Menjaga tombol pinggir (HUD) tetap akurat & tengah seimbang di semua layar: HP (16:9–21:9), Tablet (16:10/4:3), Foldable, Emulator & CloudPhone."
    ),
    PROPORTIONAL(
        "Proporsional Penuh",
        "Skala linier penuh sesuai persentase lebar & tinggi layar target (cocok untuk antarmuka melar penuh)."
    ),
    FIT_INSIDE(
        "Fit Dalam Layar (Anti Terpotong)",
        "Skala seragam mengikuti sisi terketat agar seluruh titik tetap masuk layar di rasio ekstrem (HP ↔ Tablet / Foldable)."
    ),
    CENTER_FIT(
        "Skala Tengah (Rasio Tetap)",
        "Skala seragam dari tengah layar mengikuti sisi pendek (cocok untuk game berbingkai hitam / letterbox)."
    )
}

/**
 * A single building block of a macro (Macrorify-style component).
 * Condition actions ([ImageAction], [TextAction], [ColorAction]) and [LoopAction] contain nested actions.
 */
@Serializable
sealed class Action {
    abstract val id: String
    abstract val enabled: Boolean
    /** Pause after the action finishes. */
    abstract val delayAfterMs: Long
    /** Execution success probability 0..100% for random events (100 = always runs). */
    abstract val chancePercent: Int

    abstract fun withEnabled(value: Boolean): Action
    abstract fun withChancePercent(value: Int): Action
    abstract fun withNewIds(): Action
}

@Serializable
@SerialName("click")
data class ClickAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    val x: Int,
    val y: Int,
    val durationMs: Long = 80,
    /** Number of taps fired for this point (auto-clicker burst). */
    val repeat: Int = 1,
    /** Pause between taps of the same burst. */
    val repeatIntervalMs: Long = 100
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("long_press")
data class LongPressAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    val x: Int,
    val y: Int,
    val durationMs: Long = 800
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("swipe")
data class SwipeAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Long = 350
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/** Two-finger pinch gesture: [zoomIn] = true spreads fingers apart, false pinches together. */
@Serializable
@SerialName("zoom")
data class ZoomAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    val cx: Int,
    val cy: Int,
    val zoomIn: Boolean = true,
    val distancePx: Int = 260,
    val durationMs: Long = 400
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("wait")
data class WaitAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0,
    override val chancePercent: Int = 100,
    val durationMs: Long = 1000,
    /** Extra random 0..randomMs added on every run (human-like timing). */
    val randomMs: Long = 0
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("image")
data class ImageAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    /** File name of the template inside the image library (e.g. "tombol_ok.png"). */
    val imageName: String,
    /** Similarity 0.5..1.0 (normalized cross-correlation). */
    val threshold: Float = 0.80f,
    /** Keep searching up to this long. 0 = check only once. */
    val timeoutMs: Long = 3000,
    /** Restrict search area. null = whole screen. */
    val region: Region? = null,
    /** Tap the center of the found image automatically. */
    val clickOnFound: Boolean = true,
    /** Number of repeated clicks when [clickOnFound] is true (1..50). */
    val clickCount: Int = 1,
    /** Interval delay in ms between repeated clicks when [clickCount] > 1. */
    val clickIntervalMs: Long = 100L,
    /** Press hold duration in ms for each click on the matched image. */
    val clickDurationMs: Long = 50L,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    /** Optional list of forbidden/banned image template names to avoid clicking. */
    val bannedImages: List<String> = emptyList(),
    /** Similarity threshold 0.5..1.0 for detecting [bannedImages]. */
    val bannedThreshold: Float = 0.80f,
    /** Strategy when any image in [bannedImages] is detected on screen. */
    val bannedMode: BannedImageMode = BannedImageMode.AVOID_MATCH_AREA,
    /** Safety avoidance radius (authoring px) around each detected banned image in AVOID_MATCH_AREA mode. */
    val bannedAvoidRadiusPx: Int = 60,
    val thenActions: List<Action> = emptyList(),
    val foundMode: NotFoundMode = NotFoundMode.CONTINUE,
    /** Target branch ID when [foundMode] is SWITCH_BRANCH or CALL_BRANCH_RETURN (empty = Jalur Utama). */
    val foundBranchId: String = "",
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE,
    /** Target branch ID when [notFound] is SWITCH_BRANCH or CALL_BRANCH_RETURN (empty = Jalur Utama). */
    val notFoundBranchId: String = ""
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(
        id = newId(),
        thenActions = thenActions.map { it.withNewIds() },
        elseActions = elseActions.map { it.withNewIds() }
    )
}

/**
 * High-speed moving-object tracker configured with 1..4 captured template images.
 * Supports dynamic local ROI tracking, velocity lead prediction, horizontal mirror matching,
 * edge-ignoring center focus, and Tap or Drag interactions on the tracked moving object.
 */
@Serializable
@SerialName("track_object")
data class TrackObjectAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 150,
    override val chancePercent: Int = 100,
    /** Primary captured image of the moving object. */
    val imageName: String = "",
    /** Up to 3 optional extra pose/animation frames for the moving object. */
    val extraImageNames: List<String> = emptyList(),
    /** Similarity threshold 0.45..1.0. */
    val threshold: Float = 0.65f,
    /** Max time (ms) to search initially for the object before timing out. */
    val timeoutMs: Long = 5000,
    /** Optional restricted scan region (null = full screen). */
    val region: Region? = null,
    /** How to act upon the tracked moving object (Tap or Drag). */
    val interactionMode: TrackInteractionMode = TrackInteractionMode.TAP_ONCE,
    /** Relative offset from the tracked object's center (authoring px). */
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    /** Fixed destination (DRAG_TO_POINT) or origin (DRAG_FROM_POINT_TO_OBJECT) in authoring px. */
    val targetX: Int = 540,
    val targetY: Int = 960,
    /** Relative drag vector for DRAG_BY_OFFSET in authoring px. */
    val dragOffsetX: Int = 0,
    val dragOffsetY: Int = -250,
    /** Tap touch duration (ms) for TAP_ONCE / TAP_CONTINUOUS. */
    val tapDurationMs: Long = 80,
    /** Swipe/drag gesture duration (ms) for DRAG_* modes. */
    val dragDurationMs: Long = 260,
    /** Minimum interval (ms) between taps while chasing in TAP_CONTINUOUS mode. */
    val repeatIntervalMs: Long = 160,
    /** Max continuous tracking duration (ms) in TAP_CONTINUOUS mode. */
    val maxTrackDurationMs: Long = 8000,
    /** Also match horizontally flipped (left<->right) template automatically. */
    val matchMirrorX: Boolean = true,
    /** Focus on central core pixels so changing backgrounds around edges don't drop score. */
    val ignoreBackgroundEdges: Boolean = true,
    /** Track ANY moving troop/object by motion alone (color-free, no template image required — ideal for mixed COC armies/skins). */
    val trackAnyMotion: Boolean = false,
    /** Match foreground HSV/RGB colors (auto-suppressing crop border background like grass) — essential for rotating/animated COC troops. */
    val useColorMatch: Boolean = true,
    /** Use frame-to-frame motion detection to prioritize moving/animating units and ignore static buildings. */
    val prioritizeMotion: Boolean = true,
    /** Velocity lead prediction (ms) to aim slightly ahead of moving objects (0 = off). */
    val leadPredictionMs: Long = 110,
    /** Local search half-radius (authoring px) around the last known position for fast tracking. */
    val trackRadiusPx: Int = 240,
    val thenActions: List<Action> = emptyList(),
    val foundMode: NotFoundMode = NotFoundMode.CONTINUE,
    val foundBranchId: String = "",
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE,
    val notFoundBranchId: String = ""
) : Action() {
    /** All configured unique non-blank template image names (primary + extra animation frames). */
    fun allTemplateNames(): List<String> =
        (listOf(imageName) + extraImageNames).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)

    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(
        id = newId(),
        thenActions = thenActions.map { it.withNewIds() },
        elseActions = elseActions.map { it.withNewIds() }
    )
}

@Serializable
@SerialName("color")
data class ColorAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    val x: Int,
    val y: Int,
    /** #RRGGBB */
    val color: String,
    val tolerance: Int = 15,
    val timeoutMs: Long = 3000,
    val clickOnFound: Boolean = true,
    val thenActions: List<Action> = emptyList(),
    val foundMode: NotFoundMode = NotFoundMode.CONTINUE,
    val foundBranchId: String = "",
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE,
    val notFoundBranchId: String = ""
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(
        id = newId(),
        thenActions = thenActions.map { it.withNewIds() },
        elseActions = elseActions.map { it.withNewIds() }
    )
}

/** Comparison mode for [TextAction] (text match or numeric less-than / greater-than comparison). */
@Serializable
enum class TextCompareMode(val label: String, val shortLabel: String, val isNumeric: Boolean) {
    CONTAINS("Mengandung Teks", "Mengandung", false),
    EQUALS("Teks Sama Persis", "Sama persis", false),
    LESS_THAN("Nilai Kurang Dari (<)", "<", true),
    LESS_OR_EQUAL("Nilai ≤ (Kurang/Sama)", "≤", true),
    GREATER_THAN("Nilai Lebih Dari (>)", ">", true),
    GREATER_OR_EQUAL("Nilai ≥ (Lebih/Sama)", "≥", true),
    NUMBER_EQUALS("Nilai Sama Dengan (=)", "=", true)
}

@Serializable
@SerialName("text")
data class TextAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    /** Expected text substring/exact value when [compareMode] is text-based. */
    val query: String = "",
    /** Threshold number when [compareMode] is numeric (<, <=, >, >=, =). */
    val targetNumber: Double = 0.0,
    val compareMode: TextCompareMode = TextCompareMode.CONTAINS,
    /** Screen area to read OCR text from. null = whole screen. */
    val region: Region? = null,
    val timeoutMs: Long = 3000,
    val clickOnFound: Boolean = false,
    val thenActions: List<Action> = emptyList(),
    val foundMode: NotFoundMode = NotFoundMode.CONTINUE,
    val foundBranchId: String = "",
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE,
    val notFoundBranchId: String = ""
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(
        id = newId(),
        thenActions = thenActions.map { it.withNewIds() },
        elseActions = elseActions.map { it.withNewIds() }
    )
}

@Serializable
@SerialName("loop")
data class LoopAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0,
    override val chancePercent: Int = 100,
    val count: Int = 3,
    val actions: List<Action> = emptyList()
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId(), actions = actions.map { it.withNewIds() })
}

/** Links/chains execution to another saved macro. */
@Serializable
@SerialName("run_macro")
data class RunMacroAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    val targetMacroId: String = "",
    val targetMacroName: String = "",
    /** If true, returns to continue the caller macro after target completes; if false, switches permanently. */
    val returnAfter: Boolean = true
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/** One recorded touch stroke. [points] is a flat list x0,y0,x1,y1,... sampled evenly over [durationMs]. */
@Serializable
data class RecordedStroke(
    val startMs: Long,
    val durationMs: Long,
    val points: List<Int>
)

/** One gesture (1..n simultaneous strokes) preceded by a pause. */
@Serializable
data class RecordedGesture(
    val delayBeforeMs: Long,
    val strokes: List<RecordedStroke>
)

@Serializable
@SerialName("record")
data class RecordAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    override val chancePercent: Int = 100,
    val gestures: List<RecordedGesture> = emptyList(),
    /** Playback speed multiplier (2.0 = twice as fast). */
    val speed: Float = 1f
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("stop")
data class StopAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0,
    override val chancePercent: Int = 100
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("restart")
data class RestartAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0,
    override val chancePercent: Int = 100
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/**
 * Finishes the currently running (passive or linked) macro early once its desired goal is reached,
 * immediately returning/switching control to the active macro WITHOUT stopping the running engine.
 */
@Serializable
@SerialName("finish_macro")
data class FinishMacroAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0,
    override val chancePercent: Int = 100,
    val mode: FinishMacroMode = FinishMacroMode.RETURN_TO_ACTIVE_CONTINUE,
    /** Only used when [mode] == [FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE]. */
    val targetMacroId: String = "",
    val targetMacroName: String = ""
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/**
 * Jumps execution forward past a specified action/step-count or backward to an earlier action/step-count
 * within the current macro (or enclosing loop/watchdog list).
 */
@Serializable
@SerialName("jump")
data class JumpAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0,
    override val chancePercent: Int = 100,
    val mode: JumpMode = JumpMode.SKIP_AFTER_ACTION,
    /** Target action ID when [mode] is [JumpMode.SKIP_AFTER_ACTION] or [JumpMode.JUMP_TO_ACTION]. */
    val targetActionId: String = "",
    /** Cached human-readable label of the target action for summary display. */
    val targetActionLabel: String = "",
    /** Number of steps (>= 1) when [mode] is [JumpMode.SKIP_FORWARD_STEPS] or [JumpMode.JUMP_BACKWARD_STEPS]. */
    val stepCount: Int = 1
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/** Exits or closes the currently active application via Accessibility global actions (+ optional cleanup). */
@Serializable
@SerialName("exit_app")
data class ExitAppAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 1000,
    override val chancePercent: Int = 100,
    val mode: ExitAppMode = ExitAppMode.CLOSE_RECENT_AND_HOME,
    /** Optional package name to also request background process cleanup after leaving the app. */
    val packageName: String = "",
    val appLabel: String = "",
    /** Swipe direction to close the app card in Recent Apps (AUTO adapts to Portrait & Landscape). */
    val recentsSwipeDirection: RecentsSwipeDirection = RecentsSwipeDirection.AUTO,
    /** Also Force Stop the app via Android App Info after leaving/closing Recents so it is completely removed from background. */
    val forceStopBackground: Boolean = true
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/** Opens / switches into an installed Android application or game by package name. */
@Serializable
@SerialName("launch_app")
data class LaunchAppAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 3000,
    override val chancePercent: Int = 100,
    val packageName: String = "",
    val appLabel: String = "",
    /** If true, adds FLAG_ACTIVITY_CLEAR_TASK to reload the target app cleanly from start. */
    val freshStart: Boolean = false
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/**
 * Directly routes execution to another named [MacroBranch] (or back to Jalur Utama when [targetBranchId] is blank).
 */
@Serializable
@SerialName("goto_branch")
data class GoToBranchAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0,
    override val chancePercent: Int = 100,
    /** Target [MacroBranch.id], or empty string for Jalur Utama. */
    val targetBranchId: String = "",
    /** Cached human-readable branch name for summary display. */
    val targetBranchName: String = "",
    val mode: BranchNavigationMode = BranchNavigationMode.SWITCH
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/** Single multi-condition routing rule evaluated inside [BranchRouterAction]. */
@Serializable
data class BranchRouteRule(
    val id: String = newId(),
    val label: String = "",
    val conditionType: BranchConditionType = BranchConditionType.IMAGE,
    /** Template filename when [conditionType] == IMAGE. */
    val imageName: String = "",
    val threshold: Float = 0.80f,
    /** Tap matched target automatically when this rule matches. */
    val clickOnFound: Boolean = false,
    /** Expected text when [conditionType] == TEXT and text compare mode. */
    val textQuery: String = "",
    /** Numeric threshold when [conditionType] == TEXT and numeric compare mode. */
    val targetNumber: Double = 0.0,
    val compareMode: TextCompareMode = TextCompareMode.CONTAINS,
    /** Pixel coordinates & hex color when [conditionType] == COLOR. */
    val x: Int = 540,
    val y: Int = 960,
    val color: String = "#FFFFFF",
    val tolerance: Int = 15,
    /** Optional restricted scan region (null = full screen). */
    val region: Region? = null,
    /** Variable condition fields when [conditionType] == VARIABLE. */
    val variableName: String = "",
    val variableOp: VariableCompareOp = VariableCompareOp.GREATER_OR_EQUAL,
    val variableTargetValue: Double = 1.0,
    /** Optional second variable name; when non-blank compares [variableName] against [variableCompareToVar]. */
    val variableCompareToVar: String = "",
    val outcome: BranchOutcomeAction = BranchOutcomeAction.SWITCH_BRANCH,
    /** Target [MacroBranch.id] (empty = Jalur Utama) when [outcome] is SWITCH_BRANCH or CALL_BRANCH_RETURN. */
    val targetBranchId: String = "",
    val targetBranchName: String = ""
) {
    fun withNewId(): BranchRouteRule = copy(id = newId())
}

/**
 * Multi-condition sequence router: evaluates all [rules] against the same screenshot frame and
 * immediately steers execution to the matching branch — or triggers [fallbackOutcome]/[fallbackBranchId]
 * when the screen result does not match any rule within [timeoutMs].
 */
@Serializable
@SerialName("branch_router")
data class BranchRouterAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 200,
    override val chancePercent: Int = 100,
    val rules: List<BranchRouteRule> = emptyList(),
    val timeoutMs: Long = 3000L,
    val fallbackOutcome: BranchOutcomeAction = BranchOutcomeAction.SWITCH_BRANCH,
    /** Target [MacroBranch.id] (empty = Jalur Utama) when none of [rules] match. */
    val fallbackBranchId: String = "",
    val fallbackBranchName: String = ""
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId(), rules = rules.map { it.withNewId() })
}

/**
 * Triggers advancing to the next ready account in [Macro.accountSwitch] (or [specificAccountId])
 * and executes [AccountSwitchConfig.switchActions].
 */
@Serializable
@SerialName("switch_account")
data class SwitchAccountAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 500,
    override val chancePercent: Int = 100,
    /** Empty = advance to next ready account in rotation; non-blank = switch to specific [AccountProfile.id]. */
    val specificAccountId: String = "",
    val specificAccountLabel: String = "",
    /** True = restart Jalur Utama from step 1 after switching account; false = continue next action. */
    val restartAfterSwitch: Boolean = true
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/**
 * Universal game account selector used inside [AccountSwitchConfig.switchActions] (or directly in a macro):
 * - OCR_LIST: scans screen/[region] for the active account's [AccountProfile.ocrKeyword] with automatic
 *   vertical/horizontal scrolling (plus optional [AccountProfile.fallbackImageName]) and taps it.
 * - CLONE_APP: exits the previous app and launches [AccountProfile.packageName].
 * - HYBRID: switches clone app if the target account's package differs, then runs OCR + Auto-Scroll.
 */
@Serializable
@SerialName("select_account")
data class SelectAccountAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 1000,
    override val chancePercent: Int = 100,
    /** Null = inherit [AccountSwitchConfig.method]; non-null = override method for this action. */
    val methodOverride: AccountSwitchMethod? = null,
    /** Optional restricted scan/scroll box on screen (null = full screen). */
    val region: Region? = null,
    val matchMode: AccountOcrMatchMode = AccountOcrMatchMode.CONTAINS,
    val scrollDirection: AccountScrollDirection = AccountScrollDirection.VERTICAL,
    val maxScrolls: Int = 6,
    /** Fraction (0.15..0.85) of region/screen height (or width) swiped per auto-scroll step. */
    val scrollDistanceRatio: Float = 0.45f,
    val scrollDurationMs: Long = 380L,
    val scrollSettleMs: Long = 550L,
    /** Automatically reverse scroll direction when reaching the end of the account list. */
    val reverseOnEnd: Boolean = true,
    val clickOffsetX: Int = 0,
    val clickOffsetY: Int = 0,
    /** Threshold for optional [AccountProfile.fallbackImageName] template match. */
    val fallbackThreshold: Float = 0.80f,
    /** Whether to close the previous app before opening the target clone package. */
    val exitPreviousApp: Boolean = true,
    val exitMode: ExitAppMode = ExitAppMode.CLOSE_RECENT_AND_HOME,
    val recentsSwipeDirection: RecentsSwipeDirection = RecentsSwipeDirection.AUTO,
    val exitWaitMs: Long = 1200L,
    val launchWaitMs: Long = 5000L,
    val thenActions: List<Action> = emptyList(),
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE,
    val notFoundBranchId: String = ""
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(
        id = newId(),
        thenActions = thenActions.map { it.withNewIds() },
        elseActions = elseActions.map { it.withNewIds() }
    )
}

/**
 * Modifies a named numeric variable (set, add, subtract, multiply, reset, or capture an OCR number
 * from screen) without writing any Lua code.
 */
@Serializable
@SerialName("variable")
data class VariableAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 100,
    override val chancePercent: Int = 100,
    val variableName: String = "hitung",
    val operation: VariableOperation = VariableOperation.ADD,
    val value: Double = 1.0,
    val scope: VariableScope = VariableScope.SESSION,
    /** Optional scan region when [operation] == READ_OCR_NUMBER (null = full screen). */
    val region: Region? = null,
    val ocrTimeoutMs: Long = 2000L,
    /** When true and OCR fails to read a number, stores [fallbackValue]; when false keeps previous value. */
    val useFallbackOnOcrFail: Boolean = false,
    val fallbackValue: Double = 0.0
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/**
 * Evaluates a numeric condition on a visual macro variable (against a fixed number or another variable)
 * and branches execution cleanly into [thenActions] / [elseActions] or named macro branches.
 */
@Serializable
@SerialName("check_variable")
data class CheckVariableAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 100,
    override val chancePercent: Int = 100,
    val variableName: String = "hitung",
    val operator: VariableCompareOp = VariableCompareOp.GREATER_OR_EQUAL,
    val targetValue: Double = 3.0,
    /** When non-blank, compares [variableName] against the live value of [compareToVariable] instead of [targetValue]. */
    val compareToVariable: String = "",
    val scope: VariableScope = VariableScope.SESSION,
    val thenActions: List<Action> = emptyList(),
    val elseActions: List<Action> = emptyList(),
    val foundMode: NotFoundMode = NotFoundMode.CONTINUE,
    val foundBranchId: String = "",
    val notFound: NotFoundMode = NotFoundMode.CONTINUE,
    val notFoundBranchId: String = ""
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(
        id = newId(),
        thenActions = thenActions.map { it.withNewIds() },
        elseActions = elseActions.map { it.withNewIds() }
    )
}

/**
 * Dispatches a notification to the user's locally configured Telegram Bot / Discord Webhook
 * (never embedding credentials in the macro!) and/or rings the local Phone Alarm (sound + vibrate).
 * Supports dynamic placeholders: `{macro}`, `{loop}`, `{actions}`, `{account}`, `{var:name}`.
 */
@Serializable
@SerialName("notify")
data class NotifyAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 200,
    override val chancePercent: Int = 100,
    val title: String = "Notifikasi Macro",
    val message: String = "Kondisi tercapai di {macro} (Loop #{loop})!",
    val level: NotifyLevel = NotifyLevel.INFO,
    val sendRemote: Boolean = true,
    val includeScreenshot: Boolean = false,
    val triggerPhoneAlarm: Boolean = false
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withChancePercent(value: Int) = copy(chancePercent = value.coerceIn(0, 100))
    override fun withNewIds() = copy(id = newId())
}

/**
 * Humanizer Engine configuration: makes taps, swipes, timing, and periodic AFK pauses look natural
 * like a real human hand while staying within safe target bounds.
 */
@Serializable
data class HumanizerConfig(
    val preset: HumanizerPreset = HumanizerPreset.OFF,
    /** Max Gaussian click offset radius in authoring px (sigma ≈ radius / 2.2). */
    val gaussianClickRadiusPx: Int = 8,
    /** Minimum randomized tap hold duration (ms) for single clicks. */
    val tapDurationMinMs: Long = 45L,
    /** Maximum randomized tap hold duration (ms) for single clicks. */
    val tapDurationMaxMs: Long = 95L,
    /** Use quadratic Bezier curves for swipes & auto-scrolls instead of perfectly straight lines. */
    val bezierSwipeEnabled: Boolean = true,
    /** Lateral Bezier deflection as fraction of swipe length (0.04..0.35). */
    val bezierCurveFactor: Float = 0.14f,
    /** Percentage (±%) natural timing variance applied to action delays. */
    val delayVariancePercent: Int = 15,
    /** Probability (0..40%) of brief natural micro-hesitation (140..380ms) before an action. */
    val microHesitationChancePercent: Int = 6,
    /** Enable periodic natural AFK rests ("Human Break"). */
    val humanBreakEnabled: Boolean = false,
    val breakEveryMinMinutes: Int = 25,
    val breakEveryMaxMinutes: Int = 45,
    val breakDurationMinSec: Int = 90,
    val breakDurationMaxSec: Int = 240
) {
    /** Resolves concrete runtime parameters from [preset] (or raw custom values when [HumanizerPreset.CUSTOM]). */
    fun effective(): HumanizerConfig = when (preset) {
        HumanizerPreset.OFF -> HumanizerConfig(
            preset = HumanizerPreset.OFF,
            gaussianClickRadiusPx = 0,
            tapDurationMinMs = 0L,
            tapDurationMaxMs = 0L,
            bezierSwipeEnabled = false,
            bezierCurveFactor = 0f,
            delayVariancePercent = 0,
            microHesitationChancePercent = 0,
            humanBreakEnabled = false
        )
        HumanizerPreset.NATURAL -> HumanizerConfig(
            preset = HumanizerPreset.NATURAL,
            gaussianClickRadiusPx = 7,
            tapDurationMinMs = 45L,
            tapDurationMaxMs = 95L,
            bezierSwipeEnabled = true,
            bezierCurveFactor = 0.12f,
            delayVariancePercent = 15,
            microHesitationChancePercent = 5,
            humanBreakEnabled = false
        )
        HumanizerPreset.STEALTH -> HumanizerConfig(
            preset = HumanizerPreset.STEALTH,
            gaussianClickRadiusPx = 11,
            tapDurationMinMs = 50L,
            tapDurationMaxMs = 130L,
            bezierSwipeEnabled = true,
            bezierCurveFactor = 0.19f,
            delayVariancePercent = 25,
            microHesitationChancePercent = 12,
            humanBreakEnabled = true,
            breakEveryMinMinutes = breakEveryMinMinutes.coerceIn(5, 240),
            breakEveryMaxMinutes = breakEveryMaxMinutes.coerceIn(breakEveryMinMinutes.coerceIn(5, 240), 360),
            breakDurationMinSec = breakDurationMinSec.coerceIn(15, 1800),
            breakDurationMaxSec = breakDurationMaxSec.coerceIn(breakDurationMinSec.coerceIn(15, 1800), 3600)
        )
        HumanizerPreset.CUSTOM -> this
    }
}

/** Single account entry in a macro's multi-account rotation queue (stripped on export for privacy). */
@Serializable
data class AccountProfile(
    val id: String = newId(),
    val label: String = "Akun 1",
    /** Text/email/nickname displayed on screen for OCR + Auto-Scroll matching. */
    val ocrKeyword: String = "",
    /** Optional cropped image filename fallback when a game uses stylized fonts that OCR cannot read. */
    val fallbackImageName: String = "",
    /** Optional cloned/dual app package name for Clone App or Hybrid switching. */
    val packageName: String = "",
    val appLabel: String = "",
    val enabled: Boolean = true,
    /** Cooldown in minutes after finishing this account before rotating back to it (0 = none). */
    val cooldownMinutes: Int = 0,
    /** Epoch ms when this account was last used/completed (0 = never). */
    val lastUsedAt: Long = 0L
)

/** Macro-level multi-account switching configuration (works alongside Watchdog & Banned Guard). */
@Serializable
data class AccountSwitchConfig(
    val enabled: Boolean = false,
    val method: AccountSwitchMethod = AccountSwitchMethod.OCR_LIST,
    val rotationMode: AccountRotationMode = AccountRotationMode.SEQUENTIAL_LOOP,
    val triggerMode: AccountTriggerMode = AccountTriggerMode.AFTER_LOOPS,
    val loopsPerAccount: Int = 1,
    val minutesPerAccount: Int = 15,
    val accounts: List<AccountProfile> = emptyList(),
    /** Reusable account-switching step list (runs whenever switching to the next account). */
    val switchActions: List<Action> = emptyList(),
    /** Persisted ID of the last active account so restarting resumes cleanly without starting over. */
    val lastAccountId: String = "",
    val restartMainAfterSwitch: Boolean = true,
    val onFail: AccountFailBehavior = AccountFailBehavior.SKIP_TO_NEXT,
    /** When true, runs [switchActions] once right at macro start to ensure the active account is selected. */
    val switchOnStart: Boolean = false
)

/** Named sequence branch stored inside a [Macro] alongside its main [Macro.actions] list. */
@Serializable
data class MacroBranch(
    val id: String = newId(),
    val name: String = "Cabang Baru",
    val colorHex: String = "#F59E0B",
    val actions: List<Action> = emptyList()
)

@Serializable
data class Macro(
    val id: String = newId(),
    val name: String = "Macro Baru",
    val actions: List<Action> = emptyList(),
    /** Optional named sequence branches inside this macro for clean multi-way flow routing. */
    val branches: List<MacroBranch> = emptyList(),
    /** 0 = repeat forever. */
    val loopCount: Int = 1,
    val loopDelayMs: Long = 500,
    /** Random tap offset radius in px (anti-detection / stability). */
    val jitterPx: Int = 0,
    /** Random extra delay 0..n ms added after every action. */
    val randomDelayMs: Long = 0,
    /** Auto stop after N minutes (0 = off). */
    val maxDurationMin: Int = 0,
    /** Auto-recover if no progress/action succeeds for N seconds (0 = off). */
    val autoRecoverStuckSec: Int = 0,
    val stuckRecoveryMode: StuckRecoveryMode = StuckRecoveryMode.RESTART_MACRO,
    /** Out-of-band Watchdog actions executed automatically whenever the main macro gets stuck. */
    val stuckActions: List<Action> = emptyList(),
    /** After [stuckActions] finish: true = restart main macro from step 1, false = continue where left off. */
    val stuckRestartAfter: Boolean = true,
    /** Always-active Banned Image Guard (like Watchdog): blocks clicking onto any image in [bannedImages] across all actions. */
    val bannedGuardEnabled: Boolean = false,
    /** Global list of forbidden/banned image template names blocked from being clicked anywhere in this macro. */
    val bannedImages: List<String> = emptyList(),
    /** Similarity threshold 0.5..1.0 for detecting global [bannedImages]. */
    val bannedThreshold: Float = 0.80f,
    /** Strategy for global [bannedImages]: AVOID_MATCH_AREA blocks clicks near/on banned image, BLOCK_IF_PRESENT blocks all clicks while visible. */
    val bannedMode: BannedImageMode = BannedImageMode.AVOID_MATCH_AREA,
    /** Safety blocked radius (authoring px) around each global banned image in AVOID_MATCH_AREA mode. */
    val bannedAvoidRadiusPx: Int = 60,
    /** Universal multi-account rotation & switching configuration (OCR + Auto-Scroll / Clone App / Hybrid). */
    val accountSwitch: AccountSwitchConfig = AccountSwitchConfig(),
    /** Anti-detection Humanizer Engine configuration (Gaussian taps, Bezier swipes, timing variance, Human Break). */
    val humanizer: HumanizerConfig = HumanizerConfig(),
    /** Custom logo photo filename stored in macro_icons/ (empty = default icon). */
    val logoImage: String = "",
    /** Optional [MacroSet.id] grouping this macro into a Macro Set (empty = ungrouped). */
    val setId: String = "",
    /** Prevents viewing/editing/duplicating macro actions without password. */
    val isLocked: Boolean = false,
    /** Salted SHA-256 password hash guarding lock, expiry, and passive mode settings. */
    val lockPasswordHash: String = "",
    val lockSalt: String = "",
    /** Optional UTC epoch ms deadline after which the macro cannot be used (0 = unlimited). */
    val expiresAtEpochMs: Long = 0L,
    /** Human-readable duration label configured for expiry (e.g. "7 Hari", "1 Bulan"). */
    val expiryLabel: String = "",
    /** Configured duration amount & unit so overwriting the same macro stacks deadlines accurately. */
    val expiryDurationAmount: Int = 0,
    val expiryDurationUnit: ExpiryUnit = ExpiryUnit.DAYS,
    /** Passive mode: cannot run directly; only runnable when called by an active macro via RunMacroAction. */
    val isPassive: Boolean = false,
    /** When [isPassive] is true: true = ON (can be pulled by active macros), false = OFF (skipped safely). */
    val passiveEnabled: Boolean = true,
    /** Stable origin macro ID preserved across ZIP export/import to recognize & overwrite the same macro. */
    val originId: String = "",
    /** Integrity seal over protection fields to detect manual JSON tampering. */
    val securitySeal: String = "",
    /** Non-blank when exported as a single-use (1× import) bundle; consumed permanently upon import. */
    val oneTimeImportToken: String = "",
    /** Integrity seal verifying [oneTimeImportToken] was issued legitimately during export. */
    val oneTimeImportSeal: String = "",
    /** Real-pixel screen resolution where this macro's coordinates were created (0 = unspecified/legacy). */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** Automatically scale clicks, swipes, gestures, regions & image templates to the active device screen. */
    val autoScaleResolution: Boolean = true,
    /** Projection mode used when source and target aspect ratios differ across any device. */
    val scaleMode: ResolutionScaleMode = ResolutionScaleMode.ADAPTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Named group of related macros (e.g. an active macro + its passive sub-macros) that can be
 * organized together, locked with a password & optional deadline, and exported/imported as one portable .zip set.
 */
@Serializable
data class MacroSet(
    val id: String = newId(),
    val name: String = "Set Baru",
    val description: String = "",
    /** Accent color hex for visual grouping badge (e.g. "#22D3EE"). */
    val colorHex: String = "#22D3EE",
    /** Optional custom logo photo filename stored in macro_icons/ (empty = default icon). */
    val logoImage: String = "",
    /** Locks the set and its member macros against editing, member changes, or duplication without password. */
    val isLocked: Boolean = false,
    /** Salted SHA-256 password hash guarding set lock, expiry, and member management. */
    val lockPasswordHash: String = "",
    val lockSalt: String = "",
    /** Optional UTC epoch ms deadline after which macros in this set cannot be used (0 = unlimited). */
    val expiresAtEpochMs: Long = 0L,
    /** Human-readable duration label configured for set expiry (e.g. "7 Hari", "1 Bulan"). */
    val expiryLabel: String = "",
    /** Configured duration amount & unit so overwriting the same set stacks deadlines accurately. */
    val expiryDurationAmount: Int = 0,
    val expiryDurationUnit: ExpiryUnit = ExpiryUnit.DAYS,
    /** Stable origin set ID preserved across ZIP export/import to recognize & overwrite the same set. */
    val originId: String = "",
    /** Integrity seal over set protection fields to detect manual JSON tampering. */
    val securitySeal: String = "",
    /** Non-blank when exported as a single-use (1× import) set bundle; consumed permanently upon import. */
    val oneTimeImportToken: String = "",
    /** Integrity seal verifying [oneTimeImportToken] was issued legitimately during export. */
    val oneTimeImportSeal: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class LuaScript(
    val id: String = newId(),
    val name: String = "Script Baru",
    val code: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Kinds that the "+" pickers offer. */
enum class ActionKind(val label: String, val description: String) {
    CLICK("Klik", "Ketuk satu titik"),
    LONG_PRESS("Tahan", "Tekan lama satu titik"),
    SWIPE("Swipe", "Geser dari A ke B"),
    ZOOM("Zoom In/Out", "Cubit / perbesar 2 jari di layar"),
    WAIT("Tunggu", "Jeda beberapa saat"),
    IMAGE("Deteksi Gambar", "Cari gambar + klik berulang + hindari gambar terlarang"),
    TRACK_OBJECT("Kejar Objek Bergerak", "Lacak objek bergerak dari tangkapan gambar lalu Tap/Drag"),
    TEXT("Deteksi Teks & Nilai", "Baca teks / nilai (<, >, =) di layar + If/Else"),
    COLOR("Deteksi Warna", "Cek warna piksel + aksi If/Else"),
    BRANCH_ROUTER("Cek Hasil & Pilih Arah", "Cek banyak kondisi sekaligus lalu ubah arah jalur jika hasil tidak sesuai"),
    GOTO_BRANCH("Pindah Jalur Cabang", "Pindah permanen atau singgah ke jalur cabang lalu kembali"),
    SWITCH_ACCOUNT("Ganti Akun Berikutnya", "Pindah ke akun giliran berikutnya & jalankan Alur Ganti Akun"),
    SELECT_ACCOUNT("Pilih / Buka Akun Giliran", "Cari & klik akun via OCR + Auto-Scroll dan/atau buka Clone App akun giliran"),
    VARIABLE("Atur Variabel / Hitungan", "Simpan angka, tambah/kurang counter, atau baca angka OCR ke variabel tanpa coding"),
    CHECK_VARIABLE("Cek Variabel / Hitungan", "Bandingkan nilai variabel (=, <, >, dll) lalu jalankan cabang If/Else atau pindah arah"),
    NOTIFY("Kirim Notifikasi / Alarm", "Kirim pesan & screenshot ke Telegram/Discord atau bunyikan alarm HP"),
    LOOP("Ulangi Blok", "Ulangi beberapa aksi N kali"),
    RUN_MACRO("Hubungkan Macro", "Jalankan atau pindah ke macro lain"),
    FINISH_MACRO("Selesaikan Macro", "Selesaikan macro pasif/sub-macro lalu switch ke macro aktif tanpa berhenti"),
    JUMP("Loncat Aksi", "Loncat melewati aksi ke depan atau kembali ke aksi sebelumnya"),
    EXIT_APP("Keluar Aplikasi", "Keluar atau tutup aplikasi yang sedang terbuka"),
    LAUNCH_APP("Masuk Aplikasi", "Buka / jalankan aplikasi atau game tertentu"),
    RECORD("Rekaman", "Putar ulang sentuhan yang direkam"),
    RESTART("Ulang dari Awal", "Lompat ke awal macro"),
    STOP("Hentikan", "Hentikan macro")
}

val Action.kind: ActionKind
    get() = when (this) {
        is ClickAction -> ActionKind.CLICK
        is LongPressAction -> ActionKind.LONG_PRESS
        is SwipeAction -> ActionKind.SWIPE
        is ZoomAction -> ActionKind.ZOOM
        is WaitAction -> ActionKind.WAIT
        is ImageAction -> ActionKind.IMAGE
        is TrackObjectAction -> ActionKind.TRACK_OBJECT
        is TextAction -> ActionKind.TEXT
        is ColorAction -> ActionKind.COLOR
        is BranchRouterAction -> ActionKind.BRANCH_ROUTER
        is GoToBranchAction -> ActionKind.GOTO_BRANCH
        is SwitchAccountAction -> ActionKind.SWITCH_ACCOUNT
        is SelectAccountAction -> ActionKind.SELECT_ACCOUNT
        is VariableAction -> ActionKind.VARIABLE
        is CheckVariableAction -> ActionKind.CHECK_VARIABLE
        is NotifyAction -> ActionKind.NOTIFY
        is LoopAction -> ActionKind.LOOP
        is RunMacroAction -> ActionKind.RUN_MACRO
        is FinishMacroAction -> ActionKind.FINISH_MACRO
        is JumpAction -> ActionKind.JUMP
        is ExitAppAction -> ActionKind.EXIT_APP
        is LaunchAppAction -> ActionKind.LAUNCH_APP
        is RecordAction -> ActionKind.RECORD
        is RestartAction -> ActionKind.RESTART
        is StopAction -> ActionKind.STOP
    }

private fun formatTargetNum(v: Double): String =
    if (v == v.toLong().toDouble()) {
        v.toLong().toString()
    } else {
        java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    }

/** Short human summary shown in lists and on the overlay log. */
fun Action.summary(): String {
    val base = when (this) {
        is ClickAction -> "($x, $y)" + if (repeat > 1) " ×$repeat" else ""
        is LongPressAction -> "($x, $y) ${durationMs}ms"
        is SwipeAction -> "($x1,$y1) → ($x2,$y2)"
        is ZoomAction -> (if (zoomIn) "Zoom In" else "Zoom Out") + " ($cx,$cy) ${distancePx}px"
        is WaitAction -> "${durationMs}ms" + if (randomMs > 0) " +acak ${randomMs}ms" else ""
        is ImageAction -> buildString {
            append("${imageName.substringAfterLast('/').ifBlank { "(belum dipilih)" }} • ${(threshold * 100).toInt()}%")
            if (clickOnFound) {
                append(if (clickCount > 1) " • klik ${clickCount}×" else " • klik")
            }
            if (bannedImages.isNotEmpty()) {
                append(" • ⛔${bannedImages.size} dilarang")
            }
        }
        is TrackObjectAction -> {
            if (trackAnyMotion) {
                val area = if (region != null) " • area" else ""
                "Semua Gerakan (Bebas Warna) • ${interactionMode.shortLabel}$area"
            } else {
                val frames = allTemplateNames()
                val primary = frames.firstOrNull()?.substringAfterLast('/') ?: "(belum dipilih)"
                val extraTag = if (frames.size > 1) " +${frames.size - 1}" else ""
                "$primary$extraTag • ${interactionMode.shortLabel} • ${(threshold * 100).toInt()}%"
            }
        }
        is TextAction -> {
            val cond = if (compareMode.isNumeric) {
                "Nilai ${compareMode.shortLabel} ${formatTargetNum(targetNumber)}"
            } else {
                "${compareMode.shortLabel} \"${query.ifBlank { "..." }}\""
            }
            val area = if (region != null) " • area" else ""
            "$cond$area" + if (clickOnFound) " • klik" else ""
        }
        is ColorAction -> "$color di ($x, $y)" + if (clickOnFound) " • klik" else ""
        is BranchRouterAction -> {
            val fbTarget = fallbackBranchName.ifBlank { if (fallbackBranchId.isBlank()) "Utama" else "Cabang" }
            val fbText = when (fallbackOutcome) {
                BranchOutcomeAction.SWITCH_BRANCH -> "gagal→$fbTarget"
                BranchOutcomeAction.CALL_BRANCH_RETURN -> "gagal→singgah $fbTarget"
                else -> "gagal→${fallbackOutcome.shortLabel}"
            }
            "${rules.size} kondisi • $fbText"
        }
        is GoToBranchAction -> {
            val dest = targetBranchName.ifBlank { if (targetBranchId.isBlank()) "Jalur Utama" else "Cabang" }
            "${mode.shortLabel} → $dest"
        }
        is SwitchAccountAction -> {
            val target = if (specificAccountId.isBlank()) "Akun Berikutnya" else specificAccountLabel.ifBlank { "Akun Khusus" }
            "$target • " + if (restartAfterSwitch) "ulang awal" else "lanjut"
        }
        is SelectAccountAction -> {
            val methodLabel = methodOverride?.shortLabel ?: "Ikuti Setelan Akun"
            val scrollPart = if (methodOverride != AccountSwitchMethod.CLONE_APP) {
                " • ${scrollDirection.shortLabel} maks ${maxScrolls}×"
            } else ""
            val area = if (region != null && methodOverride != AccountSwitchMethod.CLONE_APP) " • area" else ""
            "$methodLabel$scrollPart$area"
        }
        is VariableAction -> {
            val vName = variableName.trim().ifBlank { "hitung" }
            val scopeTag = if (scope == VariableScope.PER_ACCOUNT) " [Akun]" else ""
            when (operation) {
                VariableOperation.RESET_ALL -> "Reset semua variabel$scopeTag"
                VariableOperation.READ_OCR_NUMBER -> "$vName ← OCR Angka" + (if (region != null) " (area)" else "") + scopeTag
                else -> "$vName ${operation.shortLabel} ${formatTargetNum(value)}$scopeTag"
            }
        }
        is CheckVariableAction -> {
            val vName = variableName.trim().ifBlank { "hitung" }
            val rhs = compareToVariable.trim().ifBlank { formatTargetNum(targetValue) }
            val scopeTag = if (scope == VariableScope.PER_ACCOUNT) " [Akun]" else ""
            "$vName ${operator.symbol} $rhs$scopeTag"
        }
        is NotifyAction -> buildString {
            append("${level.icon} ${title.ifBlank { message }.take(28)}")
            if (includeScreenshot) append(" • 📷")
            if (triggerPhoneAlarm) append(" • 🔊")
        }
        is LoopAction -> "${count}× (${actions.size} aksi)"
        is RunMacroAction -> "${targetMacroName.ifBlank { "(pilih macro)" }} • " + if (returnAfter) "panggil" else "pindah"
        is FinishMacroAction -> when (mode) {
            FinishMacroMode.RETURN_TO_ACTIVE_CONTINUE -> "Ke macro aktif • lanjut"
            FinishMacroMode.RETURN_TO_ACTIVE_RESTART -> "Ke macro aktif • ulang awal"
            FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE -> "Switch ke ${targetMacroName.ifBlank { "(pilih macro aktif)" }}"
        }
        is JumpAction -> when (mode) {
            JumpMode.SKIP_AFTER_ACTION -> "Lewati ${targetActionLabel.ifBlank { "(pilih aksi)" }}"
            JumpMode.JUMP_TO_ACTION -> "Ke ${targetActionLabel.ifBlank { "(pilih aksi)" }}"
            JumpMode.SKIP_FORWARD_STEPS -> "Lewati +${stepCount.coerceAtLeast(1)} aksi ke depan"
            JumpMode.JUMP_BACKWARD_STEPS -> "Kembali -${stepCount.coerceAtLeast(1)} aksi ke belakang"
        }
        is ExitAppAction -> {
            val target = appLabel.ifBlank { packageName }.let { if (it.isNotBlank()) " ($it)" else "" }
            val modeShort = when (mode) {
                ExitAppMode.CLOSE_RECENT_AND_HOME -> {
                    val dirTag = if (recentsSwipeDirection == RecentsSwipeDirection.AUTO) "" else " [${recentsSwipeDirection.shortLabel}]"
                    val fsTag = if (forceStopBackground) " + Paksa Berhenti" else ""
                    "Tutup Recent$dirTag$fsTag"
                }
                ExitAppMode.FORCE_STOP_APP -> "Paksa Berhenti (Info App)"
                ExitAppMode.HOME_ONLY -> "Ke Home"
                ExitAppMode.BACK_THEN_HOME -> "Back + Home"
            }
            "$modeShort$target"
        }
        is LaunchAppAction -> {
            val target = appLabel.ifBlank { packageName.ifBlank { "(pilih aplikasi)" } }
            target + if (freshStart) " • fresh" else ""
        }
        is RecordAction -> "${gestures.size} gestur" + if (speed != 1f) " • ${speed}x" else ""
        is RestartAction -> "Kembali ke aksi pertama"
        is StopAction -> "Akhiri macro"
    }
    return if (chancePercent in 0..99) "$base • 🎲 ${chancePercent}%" else base
}

/** Create a sensible default action for the given kind (center of screen). */
fun ActionKind.create(screenW: Int, screenH: Int, imageName: String = ""): Action {
    val cx = screenW / 2
    val cy = screenH / 2
    return when (this) {
        ActionKind.CLICK -> ClickAction(x = cx, y = cy)
        ActionKind.LONG_PRESS -> LongPressAction(x = cx, y = cy)
        ActionKind.SWIPE -> SwipeAction(x1 = cx, y1 = cy + screenH / 6, x2 = cx, y2 = cy - screenH / 6)
        ActionKind.ZOOM -> ZoomAction(cx = cx, cy = cy, zoomIn = true)
        ActionKind.WAIT -> WaitAction()
        ActionKind.IMAGE -> ImageAction(imageName = imageName)
        ActionKind.TRACK_OBJECT -> TrackObjectAction(imageName = imageName, targetX = cx, targetY = cy)
        ActionKind.TEXT -> TextAction(region = null)
        ActionKind.COLOR -> ColorAction(x = cx, y = cy, color = "#FFFFFF")
        ActionKind.BRANCH_ROUTER -> BranchRouterAction(
            rules = listOf(
                BranchRouteRule(
                    label = "Kondisi 1",
                    conditionType = BranchConditionType.IMAGE,
                    imageName = imageName,
                    x = cx,
                    y = cy,
                    outcome = BranchOutcomeAction.CONTINUE
                )
            )
        )
        ActionKind.GOTO_BRANCH -> GoToBranchAction()
        ActionKind.SWITCH_ACCOUNT -> SwitchAccountAction()
        ActionKind.SELECT_ACCOUNT -> SelectAccountAction()
        ActionKind.VARIABLE -> VariableAction()
        ActionKind.CHECK_VARIABLE -> CheckVariableAction()
        ActionKind.NOTIFY -> NotifyAction()
        ActionKind.LOOP -> LoopAction()
        ActionKind.RUN_MACRO -> RunMacroAction()
        ActionKind.FINISH_MACRO -> FinishMacroAction()
        ActionKind.JUMP -> JumpAction()
        ActionKind.EXIT_APP -> ExitAppAction()
        ActionKind.LAUNCH_APP -> LaunchAppAction()
        ActionKind.RECORD -> RecordAction()
        ActionKind.RESTART -> RestartAction()
        ActionKind.STOP -> StopAction()
    }
}

/** Rebuilds an action tree replacing every action's ID via [idMap] and preserving internal [JumpAction] target links. */
internal fun Action.withMappedIds(idMap: Map<String, String>): Action {
    val mappedId = idMap[id] ?: newId()
    return when (this) {
        is ClickAction -> copy(id = mappedId)
        is LongPressAction -> copy(id = mappedId)
        is SwipeAction -> copy(id = mappedId)
        is ZoomAction -> copy(id = mappedId)
        is WaitAction -> copy(id = mappedId)
        is ImageAction -> copy(
            id = mappedId,
            thenActions = thenActions.map { it.withMappedIds(idMap) },
            elseActions = elseActions.map { it.withMappedIds(idMap) }
        )
        is TrackObjectAction -> copy(
            id = mappedId,
            thenActions = thenActions.map { it.withMappedIds(idMap) },
            elseActions = elseActions.map { it.withMappedIds(idMap) }
        )
        is TextAction -> copy(
            id = mappedId,
            thenActions = thenActions.map { it.withMappedIds(idMap) },
            elseActions = elseActions.map { it.withMappedIds(idMap) }
        )
        is ColorAction -> copy(
            id = mappedId,
            thenActions = thenActions.map { it.withMappedIds(idMap) },
            elseActions = elseActions.map { it.withMappedIds(idMap) }
        )
        is BranchRouterAction -> copy(
            id = mappedId,
            rules = rules.map { it.withNewId() }
        )
        is GoToBranchAction -> copy(id = mappedId)
        is SwitchAccountAction -> copy(id = mappedId)
        is SelectAccountAction -> copy(
            id = mappedId,
            thenActions = thenActions.map { it.withMappedIds(idMap) },
            elseActions = elseActions.map { it.withMappedIds(idMap) }
        )
        is VariableAction -> copy(id = mappedId)
        is CheckVariableAction -> copy(
            id = mappedId,
            thenActions = thenActions.map { it.withMappedIds(idMap) },
            elseActions = elseActions.map { it.withMappedIds(idMap) }
        )
        is NotifyAction -> copy(id = mappedId)
        is LoopAction -> copy(
            id = mappedId,
            actions = actions.map { it.withMappedIds(idMap) }
        )
        is RunMacroAction -> copy(id = mappedId)
        is FinishMacroAction -> copy(id = mappedId)
        is JumpAction -> copy(
            id = mappedId,
            targetActionId = idMap[targetActionId] ?: targetActionId
        )
        is ExitAppAction -> copy(id = mappedId)
        is LaunchAppAction -> copy(id = mappedId)
        is RecordAction -> copy(id = mappedId)
        is RestartAction -> copy(id = mappedId)
        is StopAction -> copy(id = mappedId)
    }
}
