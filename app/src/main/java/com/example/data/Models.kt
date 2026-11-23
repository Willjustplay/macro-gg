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

/** What to do after the "not found" branch has been executed. */
@Serializable
enum class NotFoundMode(val label: String) {
    CONTINUE("Lanjut ke aksi berikutnya"),
    RESTART("Ulangi macro dari awal"),
    STOP("Hentikan macro")
}

/**
 * A single building block of a macro (Macrorify calls these "components").
 * Condition actions ([ImageAction], [ColorAction]) and [LoopAction] contain nested actions.
 */
@Serializable
sealed class Action {
    abstract val id: String
    abstract val enabled: Boolean
    /** Pause after the action finishes. */
    abstract val delayAfterMs: Long

    abstract fun withEnabled(value: Boolean): Action
    abstract fun withNewIds(): Action
}

@Serializable
@SerialName("click")
data class ClickAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    val x: Int,
    val y: Int,
    val durationMs: Long = 50,
    /** Number of taps fired for this point (auto-clicker burst). */
    val repeat: Int = 1,
    /** Pause between taps of the same burst. */
    val repeatIntervalMs: Long = 100
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("long_press")
data class LongPressAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    val x: Int,
    val y: Int,
    val durationMs: Long = 800
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("swipe")
data class SwipeAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Long = 350
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("wait")
data class WaitAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0,
    val durationMs: Long = 1000,
    /** Extra random 0..randomMs added on every run (human-like timing). */
    val randomMs: Long = 0
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("image")
data class ImageAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 300,
    /** File name of the template inside the image library (e.g. "tombol_ok.png"). */
    val imageName: String,
    /** Similarity 0.5..1.0 (normalized cross-correlation). */
    val threshold: Float = 0.85f,
    /** Keep searching up to this long. 0 = check only once. */
    val timeoutMs: Long = 3000,
    /** Restrict search area. null = whole screen. */
    val region: Region? = null,
    /** Tap the center of the found image automatically. */
    val clickOnFound: Boolean = true,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val thenActions: List<Action> = emptyList(),
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
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
    val x: Int,
    val y: Int,
    /** #RRGGBB */
    val color: String,
    val tolerance: Int = 15,
    val timeoutMs: Long = 3000,
    val clickOnFound: Boolean = true,
    val thenActions: List<Action> = emptyList(),
    val elseActions: List<Action> = emptyList(),
    val notFound: NotFoundMode = NotFoundMode.CONTINUE
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
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
    val count: Int = 3,
    val actions: List<Action> = emptyList()
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withNewIds() = copy(id = newId(), actions = actions.map { it.withNewIds() })
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
    val gestures: List<RecordedGesture> = emptyList(),
    /** Playback speed multiplier (2.0 = twice as fast). */
    val speed: Float = 1f
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("stop")
data class StopAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
    override fun withNewIds() = copy(id = newId())
}

@Serializable
@SerialName("restart")
data class RestartAction(
    override val id: String = newId(),
    override val enabled: Boolean = true,
    override val delayAfterMs: Long = 0
) : Action() {
    override fun withEnabled(value: Boolean) = copy(enabled = value)
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
    WAIT("Tunggu", "Jeda beberapa saat"),
    IMAGE("Deteksi Gambar", "Cari gambar di layar lalu klik"),
    COLOR("Deteksi Warna", "Cek warna piksel lalu klik"),
    LOOP("Ulangi Blok", "Ulangi beberapa aksi N kali"),
    RECORD("Rekaman", "Putar ulang sentuhan yang direkam"),
    RESTART("Ulang dari Awal", "Lompat ke awal macro"),
    STOP("Hentikan", "Hentikan macro")
}

val Action.kind: ActionKind
    get() = when (this) {
        is ClickAction -> ActionKind.CLICK
        is LongPressAction -> ActionKind.LONG_PRESS
        is SwipeAction -> ActionKind.SWIPE
        is WaitAction -> ActionKind.WAIT
        is ImageAction -> ActionKind.IMAGE
        is ColorAction -> ActionKind.COLOR
        is LoopAction -> ActionKind.LOOP
        is RecordAction -> ActionKind.RECORD
        is RestartAction -> ActionKind.RESTART
        is StopAction -> ActionKind.STOP
    }

/** Short human summary shown in lists and on the overlay log. */
fun Action.summary(): String = when (this) {
    is ClickAction -> "($x, $y)" + if (repeat > 1) " ×$repeat" else ""
    is LongPressAction -> "($x, $y) ${durationMs}ms"
    is SwipeAction -> "($x1,$y1) → ($x2,$y2)"
    is WaitAction -> "${durationMs}ms" + if (randomMs > 0) " +acak ${randomMs}ms" else ""
    is ImageAction -> "$imageName • ${(threshold * 100).toInt()}%" + if (clickOnFound) " • klik" else ""
    is ColorAction -> "$color di ($x, $y)" + if (clickOnFound) " • klik" else ""
    is LoopAction -> "${count}× (${actions.size} aksi)"
    is RecordAction -> "${gestures.size} gestur" + if (speed != 1f) " • ${speed}x" else ""
    is RestartAction -> "Kembali ke aksi pertama"
    is StopAction -> "Akhiri macro"
}

/** Create a sensible default action for the given kind (center of screen). */
fun ActionKind.create(screenW: Int, screenH: Int, imageName: String = ""): Action {
    val cx = screenW / 2
    val cy = screenH / 2
    return when (this) {
        ActionKind.CLICK -> ClickAction(x = cx, y = cy)
        ActionKind.LONG_PRESS -> LongPressAction(x = cx, y = cy)
        ActionKind.SWIPE -> SwipeAction(x1 = cx, y1 = cy + screenH / 6, x2 = cx, y2 = cy - screenH / 6)
        ActionKind.WAIT -> WaitAction()
        ActionKind.IMAGE -> ImageAction(imageName = imageName)
        ActionKind.COLOR -> ColorAction(x = cx, y = cy, color = "#FFFFFF")
        ActionKind.LOOP -> LoopAction()
        ActionKind.RECORD -> RecordAction()
        ActionKind.RESTART -> RestartAction()
        ActionKind.STOP -> StopAction()
    }
}
