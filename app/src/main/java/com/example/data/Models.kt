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
    RESTART("Ulangi macro dari awal"),
    FINISH("Selesaikan macro (ke macro aktif)"),
    STOP("Hentikan macro")
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
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val thenActions: List<Action> = emptyList(),
    val foundMode: NotFoundMode = NotFoundMode.CONTINUE,
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE
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
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE
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
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE
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
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE
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

@Serializable
data class Macro(
    val id: String = newId(),
    val name: String = "Macro Baru",
    val actions: List<Action> = emptyList(),
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
    IMAGE("Deteksi Gambar", "Cari gambar di layar + aksi If/Else"),
    TRACK_OBJECT("Kejar Objek Bergerak", "Lacak objek bergerak dari tangkapan gambar lalu Tap/Drag"),
    TEXT("Deteksi Teks & Nilai", "Baca teks / nilai (<, >, =) di layar + If/Else"),
    COLOR("Deteksi Warna", "Cek warna piksel + aksi If/Else"),
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
        is ImageAction -> "${imageName.substringAfterLast('/').ifBlank { "(belum dipilih)" }} • ${(threshold * 100).toInt()}%" + if (clickOnFound) " • klik" else ""
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
