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
    STOP("Hentikan macro")
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
        "Tutup dari Recent & ke Home (Rekomendasi)",
        "Buka Recent Apps, geser ke atas untuk menutup aplikasi sepenuhnya, lalu kembali ke Home."
    ),
    HOME_ONLY(
        "Keluar ke Layar Utama (Home)",
        "Langsung keluar ke layar beranda (Home) tanpa menutup paksa dari Recent."
    ),
    BACK_THEN_HOME(
        "Tekan Kembali lalu ke Home",
        "Tekan tombol Back beberapa kali lalu keluar ke layar beranda."
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
    val durationMs: Long = 50,
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
    val appLabel: String = ""
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
    TEXT("Deteksi Teks & Nilai", "Baca teks / nilai (<, >, =) di layar + If/Else"),
    COLOR("Deteksi Warna", "Cek warna piksel + aksi If/Else"),
    LOOP("Ulangi Blok", "Ulangi beberapa aksi N kali"),
    RUN_MACRO("Hubungkan Macro", "Jalankan atau pindah ke macro lain"),
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
        is TextAction -> ActionKind.TEXT
        is ColorAction -> ActionKind.COLOR
        is LoopAction -> ActionKind.LOOP
        is RunMacroAction -> ActionKind.RUN_MACRO
        is ExitAppAction -> ActionKind.EXIT_APP
        is LaunchAppAction -> ActionKind.LAUNCH_APP
        is RecordAction -> ActionKind.RECORD
        is RestartAction -> ActionKind.RESTART
        is StopAction -> ActionKind.STOP
    }

private fun formatTargetNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/** Short human summary shown in lists and on the overlay log. */
fun Action.summary(): String {
    val base = when (this) {
        is ClickAction -> "($x, $y)" + if (repeat > 1) " ×$repeat" else ""
        is LongPressAction -> "($x, $y) ${durationMs}ms"
        is SwipeAction -> "($x1,$y1) → ($x2,$y2)"
        is ZoomAction -> (if (zoomIn) "Zoom In" else "Zoom Out") + " ($cx,$cy) ${distancePx}px"
        is WaitAction -> "${durationMs}ms" + if (randomMs > 0) " +acak ${randomMs}ms" else ""
        is ImageAction -> "${imageName.substringAfterLast('/').ifBlank { "(belum dipilih)" }} • ${(threshold * 100).toInt()}%" + if (clickOnFound) " • klik" else ""
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
        is ExitAppAction -> {
            val target = appLabel.ifBlank { packageName }.let { if (it.isNotBlank()) " ($it)" else "" }
            val modeShort = when (mode) {
                ExitAppMode.CLOSE_RECENT_AND_HOME -> "Tutup Recent + Home"
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
        ActionKind.TEXT -> TextAction(
            region = Region(
                left = (screenW * 0.20f).toInt(),
                top = (screenH * 0.35f).toInt(),
                right = (screenW * 0.80f).toInt(),
                bottom = (screenH * 0.50f).toInt()
            )
        )
        ActionKind.COLOR -> ColorAction(x = cx, y = cy, color = "#FFFFFF")
        ActionKind.LOOP -> LoopAction()
        ActionKind.RUN_MACRO -> RunMacroAction()
        ActionKind.EXIT_APP -> ExitAppAction()
        ActionKind.LAUNCH_APP -> LaunchAppAction()
        ActionKind.RECORD -> RecordAction()
        ActionKind.RESTART -> RestartAction()
        ActionKind.STOP -> StopAction()
    }
}
