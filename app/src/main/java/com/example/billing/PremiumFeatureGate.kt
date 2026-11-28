package com.example.billing

import com.example.data.Action
import com.example.data.ActionKind
import com.example.data.Macro
import com.example.data.childList
import com.example.data.kind
import com.example.data.slots
import com.example.data.walk
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
        subtitle = "Versi gratis mendukung Klik, Swipe, Deteksi Gambar, dan Ganti Akun (maks 2 akun). Upgrade ke PRO untuk membuka semua 22 aksi."
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
    BRANCH_ROUTING(
        title = "Cabang Urutan & Cek Arah (PRO)",
        subtitle = "Buat jalur cabang alternatif, Cek Hasil & Pilih Arah otomatis, dan Pindah/Singgah cabang dengan Wjust macro PRO."
    ),
    MULTI_ACCOUNT(
        title = "Multi-Akun Tanpa Batas (PRO)",
        subtitle = "Versi gratis mendukung hingga 2 akun per macro. Upgrade ke Wjust macro PRO untuk rotasi akun tanpa batas."
    ),
    HUMANIZER_PRO(
        title = "Humanizer Stealth, Break & Kustom (PRO)",
        subtitle = "Mode Humanizer Natural gratis! Upgrade ke PRO untuk mode Stealth, Istirahat Alami (Human Break), dan pengaturan Gaussian/Bezier kustom."
    ),
    VISUAL_VARIABLES(
        title = "Variabel & Penghitung Visual (PRO)",
        subtitle = "Simpan hitungan, baca angka OCR ke variabel, dan cek kondisi variabel tanpa kode tersedia di Wjust macro PRO."
    ),
    REMOTE_NOTIFICATIONS(
        title = "Notifikasi & Kontrol Bot Telegram/Discord (PRO)",
        subtitle = "Statistik lokal & Alarm HP gratis! Upgrade ke PRO untuk notifikasi, screenshot layar, serta Kontrol Bot 2-Arah (ID Makro Aktif/Pasif & Aksi Live) via Telegram/Discord."
    ),
    SCHEDULER_LIMIT(
        title = "Jadwal Otomatis Tanpa Batas (PRO)",
        subtitle = "Versi gratis mendukung 1 jadwal otomatis + Layar Hitam AFK & Guard HP penuh. Upgrade ke PRO untuk jadwal tanpa batas."
    ),
    UPGRADE_GENERAL(
        title = "Wjust macro PRO",
        subtitle = "Buka semua 25 aksi, Variabel Visual, Humanizer Stealth + Break, Notifikasi & Kontrol Bot Telegram/Discord 2-Arah, Jadwal & Multi-Akun tanpa batas."
    )
}

data class MacroRunDenial(
    val feature: PremiumFeature,
    val reason: String,
    val blockedKind: ActionKind? = null
)

object PremiumFeatureGate {
    const val FREE_MAX_MACROS = 3
    const val FREE_MAX_ACCOUNTS = 2
    const val FREE_MAX_SCHEDULES = 1

    val FREE_ACTION_KINDS: Set<ActionKind> = setOf(
        ActionKind.CLICK,
        ActionKind.SWIPE,
        ActionKind.IMAGE,
        ActionKind.SWITCH_ACCOUNT,
        ActionKind.SELECT_ACCOUNT
    )

    fun isActionKindFree(kind: ActionKind): Boolean = kind in FREE_ACTION_KINDS

    fun isActionFree(action: Action): Boolean = isActionKindFree(action.kind)

    fun canCreateMacro(currentMacroCount: Int, isPremium: Boolean): Boolean =
        isPremium || currentMacroCount < FREE_MAX_MACROS

    fun canAddAccount(currentAccountCount: Int, isPremium: Boolean): Boolean =
        isPremium || currentAccountCount < FREE_MAX_ACCOUNTS

    fun canAddSchedule(currentScheduleCount: Int, isPremium: Boolean): Boolean =
        isPremium || currentScheduleCount < FREE_MAX_SCHEDULES

    fun canUseRemoteBotControl(isPremium: Boolean): Boolean = isPremium

    fun isHumanizerConfigFree(config: com.example.data.HumanizerConfig): Boolean =
        config.preset == com.example.data.HumanizerPreset.OFF ||
            config.preset == com.example.data.HumanizerPreset.NATURAL

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
            ?: if (macro.accountSwitch.enabled) findFirstBlockedAction(macro.accountSwitch.switchActions) else null
        if (blocked != null) {
            val feat = when (blocked.kind) {
                ActionKind.VARIABLE, ActionKind.CHECK_VARIABLE -> PremiumFeature.VISUAL_VARIABLES
                ActionKind.NOTIFY -> PremiumFeature.REMOTE_NOTIFICATIONS
                else -> PremiumFeature.ACTION_LOCKED
            }
            return MacroRunDenial(
                feature = feat,
                reason = "Aksi '${blocked.kind.label}' khusus Wjust macro PRO. Versi gratis mendukung Klik, Swipe, Deteksi Gambar, dan Ganti Akun (maks $FREE_MAX_ACCOUNTS akun).",
                blockedKind = blocked.kind
            )
        }

        if (!isHumanizerConfigFree(macro.humanizer)) {
            return MacroRunDenial(
                feature = PremiumFeature.HUMANIZER_PRO,
                reason = "Humanizer ${macro.humanizer.preset.shortLabel} / Human Break khusus Wjust macro PRO. Mode Natural tersedia gratis."
            )
        }

        if (macro.accountSwitch.enabled && macro.accountSwitch.accounts.count { it.enabled } > FREE_MAX_ACCOUNTS) {
            return MacroRunDenial(
                feature = PremiumFeature.MULTI_ACCOUNT,
                reason = "Versi gratis mendukung maksimal $FREE_MAX_ACCOUNTS akun aktif per macro. Upgrade ke PRO untuk akun tanpa batas."
            )
        }

        if (macro.branches.isNotEmpty()) {
            return MacroRunDenial(
                feature = PremiumFeature.BRANCH_ROUTING,
                reason = "Fitur Cabang Urutan khusus Wjust macro PRO."
            )
        }

        val usesBranchSwitch = macro.actions.walk().any { a ->
            a is com.example.data.ImageAction && (
                a.foundMode == com.example.data.NotFoundMode.SWITCH_BRANCH ||
                    a.foundMode == com.example.data.NotFoundMode.CALL_BRANCH_RETURN ||
                    a.notFound == com.example.data.NotFoundMode.SWITCH_BRANCH ||
                    a.notFound == com.example.data.NotFoundMode.CALL_BRANCH_RETURN
                )
        }
        if (usesBranchSwitch) {
            return MacroRunDenial(
                feature = PremiumFeature.BRANCH_ROUTING,
                reason = "Perpindahan jalur cabang urutan khusus Wjust macro PRO."
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
