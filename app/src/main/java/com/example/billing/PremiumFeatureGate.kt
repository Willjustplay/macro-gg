package com.example.billing

import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.Macro
import com.example.data.childList
import com.example.data.kind
import com.example.data.slots
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure Kotlin feature-gating policy for Wjust macro Android (Free vs PRO subscription).
 *
 * Free tier rules:
 * - Allowed actions ONLY: [ActionKind.CLICK], [ActionKind.SWIPE], [ActionKind.IMAGE].
 * - Maximum [FREE_MAX_MACROS] (3) saved macros.
 * - All other actions (including Wait, Loop, Track Object, Text, Color, Record, etc.) and
 *   advanced studio features (Macro Sets, Export/Import, Security Lock/Expiry/Passive,
 *   Auto-Scale, Anti-Macet Watchdog, Custom Macro Logo, Lua Studio, Touch Recorder, Color Spy)
 *   require an active Wjust macro PRO subscription.
 */
enum class PremiumFeature(val title: String, val subtitle: String) {
    MACRO_LIMIT(
        title = "Batas 3 Macro Gratis Tercapai",
        subtitle = "Versi gratis dibatasi maksimal 3 macro. Upgrade ke PRO untuk membuat macro tanpa batas."
    ),
    ACTION_LOCKED(
        title = "Aksi Khusus Wjust macro PRO",
        subtitle = "Versi gratis hanya mendukung Klik, Swipe, dan Deteksi Gambar. Upgrade ke PRO untuk membuka semua 18 aksi (Tunggu, Loop, Teks, Warna, Rekam, dll)."
    ),
    MACRO_SET(
        title = "Macro Set Khusus PRO",
        subtitle = "Kelompokkan, kunci, dan kelola banyak macro dalam 1 Macro Set dengan Wjust macro PRO."
    ),
    EXPORT_IMPORT(
        title = "Ekspor & Impor Khusus PRO",
        subtitle = "Ekspor/impor macro .zip, Macro Set, backup penuh, dan ekspor gambar tersedia di Wjust macro PRO."
    ),
    SECURITY_LOCK(
        title = "Kunci Sandi, Tenggat & Pasif (PRO)",
        subtitle = "Proteksi sandi, batas waktu macro, dan mode pasif tersedia di Wjust macro PRO."
    ),
    AUTO_SCALE(
        title = "Auto-Scale Resolusi (PRO)",
        subtitle = "Penyesuaian koordinat lintas resolusi layar otomatis tersedia di Wjust macro PRO."
    ),
    WATCHDOG(
        title = "Anti-Macet / Watchdog (PRO)",
        subtitle = "Deteksi & pemulihan otomatis saat macro macet tersedia di Wjust macro PRO."
    ),
    CUSTOM_LOGO(
        title = "Logo Macro Kustom (PRO)",
        subtitle = "Pasang foto atau logo kustom pada kartu macro dengan Wjust macro PRO."
    ),
    LUA_STUDIO(
        title = "Lua Studio Khusus PRO",
        subtitle = "Tulis, uji, dan jalankan script Lua tingkat lanjut dengan Wjust macro PRO."
    ),
    TOUCH_RECORDER(
        title = "Rekam Sentuhan Khusus PRO",
        subtitle = "Rekam dan putar ulang gerakan sentuhan layar langsung dengan Wjust macro PRO."
    ),
    COLOR_SPY(
        title = "Color Spy & Deteksi Warna (PRO)",
        subtitle = "Inspeksi dan deteksi warna piksel layar tersedia di Wjust macro PRO."
    ),
    UPGRADE_GENERAL(
        title = "Wjust macro PRO",
        subtitle = "Buka semua 18 jenis aksi, macro tanpa batas, Macro Set, Ekspor/Impor, Anti-Macet, dan Lua Studio."
    )
}

data class MacroRunDenial(
    val feature: PremiumFeature,
    val reason: String,
    val blockedKind: ActionKind? = null
)

object PremiumFeatureGate {
    const val FREE_MAX_MACROS = 3

    val FREE_ACTION_KINDS: Set<ActionKind> = setOf(
        ActionKind.CLICK,
        ActionKind.SWIPE,
        ActionKind.IMAGE
    )

    fun isActionKindFree(kind: ActionKind): Boolean = kind in FREE_ACTION_KINDS

    fun isActionFree(action: Action): Boolean = isActionKindFree(action.kind)

    fun canCreateMacro(currentMacroCount: Int, isPremium: Boolean): Boolean =
        isPremium || currentMacroCount < FREE_MAX_MACROS

    fun remainingFreeMacroSlots(currentMacroCount: Int): Int =
        (FREE_MAX_MACROS - currentMacroCount).coerceAtLeast(0)

    /**
     * Recursively searches [actions] (including nested Then/Else/Loop child branches)
     * and returns the first [Action] whose [ActionKind] requires PRO, or null if all are free.
     */
    fun findFirstBlockedAction(actions: List<Action>): Action? {
        for (action in actions) {
            if (!isActionFree(action)) return action
            for (slot in action.slots()) {
                val nested = findFirstBlockedAction(action.childList(slot))
                if (nested != null) return nested
            }
        }
        return null
    }

    /**
     * Checks whether [macro] can be executed under the current subscription tier.
     * Returns null when allowed, or a [MacroRunDenial] describing which PRO feature blocks execution.
     */
    fun checkMacroRun(macro: Macro, isPremium: Boolean): MacroRunDenial? {
        if (isPremium) return null

        val blocked = findFirstBlockedAction(macro.actions)
        if (blocked != null) {
            return MacroRunDenial(
                feature = PremiumFeature.ACTION_LOCKED,
                reason = "Aksi '${blocked.kind.label}' khusus Wjust macro PRO. Versi gratis mendukung Klik, Swipe, dan Deteksi Gambar.",
                blockedKind = blocked.kind
            )
        }

        if (macro.stuckActions.isNotEmpty() || macro.autoRecoverStuckSec > 0) {
            return MacroRunDenial(
                feature = PremiumFeature.WATCHDOG,
                reason = "Fitur Anti-Macet (Watchdog) khusus Wjust macro PRO."
            )
        }

        return null
    }
}

/**
 * Process-wide live entitlement state shared across UI, [com.example.automation.AutomationCoordinator],
 * and [com.example.automation.FloatingOverlayService].
 */
object PremiumStateHolder {
    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    fun update(active: Boolean) {
        _isPremium.value = active
    }
}
