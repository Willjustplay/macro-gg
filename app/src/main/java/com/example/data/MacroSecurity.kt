package com.example.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Reason a macro is blocked from executing. */
enum class RunDenialReason {
    TAMPERED,
    EXPIRED,
    PASSIVE_DIRECT,
    PASSIVE_OFF
}

/**
 * Pure-Kotlin offline security helper for Wjust macro:
 * - Salted SHA-256 password hashing & verification for Lock, Expiry, and changing Active/Passive mode
 * - Calendar-accurate expiration deadlines in Days or Months (+ stacking on macro overwrite)
 * - Passive mode ON (callable by active macros) / OFF (safely skipped by active macros) — switchable without password
 * - Integrity sealing so manual JSON edits to lock/expiry/passive fields are detected
 */
object MacroSecurity {

    private const val SEAL_PEPPER = "WjustMacro::v2.7::Seal::9f3a8c41d2e7"
    private const val HASH_ITERATIONS = 1024
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L

    fun hasAnyProtection(macro: Macro): Boolean =
        macro.isLocked || macro.isPassive || macro.expiresAtEpochMs > 0L

    fun hasPassword(macro: Macro): Boolean =
        macro.lockPasswordHash.isNotBlank() && macro.lockSalt.isNotBlank()

    fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    fun hashPassword(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var state = "$salt:$password:$SEAL_PEPPER".toByteArray(Charsets.UTF_8)
        repeat(HASH_ITERATIONS) {
            md.reset()
            state = md.digest(state)
        }
        return state.toHex()
    }

    fun verifyPassword(macro: Macro, candidatePassword: String): Boolean {
        if (!hasPassword(macro)) return true
        val expected = macro.lockPasswordHash
        val actual = hashPassword(candidatePassword, macro.lockSalt)
        return constantTimeEquals(expected, actual)
    }

    private fun computeLegacySealV27(macro: Macro): String {
        val payload = buildString {
            append(SEAL_PEPPER)
            append("|locked=").append(macro.isLocked)
            append("|passive=").append(macro.isPassive)
            append("|exp=").append(macro.expiresAtEpochMs)
            append("|hash=").append(macro.lockPasswordHash)
            append("|salt=").append(macro.lockSalt)
        }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Computes an integrity seal independent of [Macro.id] so ZIP import ID remapping keeps the seal intact. */
    fun computeSeal(macro: Macro): String {
        if (!hasAnyProtection(macro) && !hasPassword(macro)) return ""
        val payload = buildString {
            append(SEAL_PEPPER)
            append("|locked=").append(macro.isLocked)
            append("|passive=").append(macro.isPassive)
            append("|passiveOn=").append(macro.passiveEnabled)
            append("|exp=").append(macro.expiresAtEpochMs)
            append("|hash=").append(macro.lockPasswordHash)
            append("|salt=").append(macro.lockSalt)
        }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun isTampered(macro: Macro): Boolean {
        if (macro.securitySeal.isBlank()) return false
        if (constantTimeEquals(macro.securitySeal, computeSeal(macro))) return false
        // Backward compatibility with v2.7.0 exports (where passiveEnabled defaulted to true)
        if (macro.passiveEnabled && constantTimeEquals(macro.securitySeal, computeLegacySealV27(macro))) return false
        return true
    }

    fun isExpired(macro: Macro, nowMs: Long = System.currentTimeMillis()): Boolean =
        macro.expiresAtEpochMs > 0L && nowMs >= macro.expiresAtEpochMs

    /** Computes future UTC epoch deadline from [nowMs] + [amount] days or calendar months. */
    fun computeExpiryEpochMs(nowMs: Long, amount: Int, unit: ExpiryUnit): Long {
        val safeAmount = amount.coerceAtLeast(1)
        return when (unit) {
            ExpiryUnit.DAYS -> nowMs + safeAmount.toLong() * DAY_MS
            ExpiryUnit.MONTHS -> {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = nowMs
                cal.add(Calendar.MONTH, safeAmount)
                cal.timeInMillis
            }
        }
    }

    fun formatDurationLabel(amount: Int, unit: ExpiryUnit): String =
        "${amount.coerceAtLeast(1)} ${unit.label}"

    fun formatDeadlineDate(epochMs: Long): String {
        if (epochMs <= 0L) return "Tanpa batas"
        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return fmt.format(Date(epochMs))
    }

    /** Short badge text such as "Sisa 6 hari", "Sisa 2 bulan", "Sisa 5 jam", or "Kedaluwarsa". */
    fun remainingTimeLabel(macro: Macro, nowMs: Long = System.currentTimeMillis()): String? {
        if (macro.expiresAtEpochMs <= 0L) return null
        val diff = macro.expiresAtEpochMs - nowMs
        if (diff <= 0L) return "Kedaluwarsa"
        return when {
            diff >= 60L * DAY_MS -> "Sisa ${diff / (30L * DAY_MS)} bln"
            diff >= DAY_MS -> "Sisa ${(diff + DAY_MS - 1) / DAY_MS} hari"
            diff >= HOUR_MS -> "Sisa ${(diff + HOUR_MS - 1) / HOUR_MS} jam"
            else -> "Sisa ${((diff + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1)} mnt"
        }
    }

    /** Checks whether [macro] may be started directly by the user. */
    fun checkDirectRun(macro: Macro, nowMs: Long = System.currentTimeMillis()): RunDenialReason? = when {
        isTampered(macro) -> RunDenialReason.TAMPERED
        isExpired(macro, nowMs) -> RunDenialReason.EXPIRED
        macro.isPassive -> RunDenialReason.PASSIVE_DIRECT
        else -> null
    }

    /**
     * Checks whether [macro] may be executed when pulled/called by another active macro via [RunMacroAction].
     * Returns [RunDenialReason.PASSIVE_OFF] when passive mode is switched OFF so the caller skips cleanly.
     */
    fun checkLinkedRun(macro: Macro, nowMs: Long = System.currentTimeMillis()): RunDenialReason? = when {
        isTampered(macro) -> RunDenialReason.TAMPERED
        isExpired(macro, nowMs) -> RunDenialReason.EXPIRED
        macro.isPassive && !macro.passiveEnabled -> RunDenialReason.PASSIVE_OFF
        else -> null
    }

    fun denialMessage(macro: Macro, reason: RunDenialReason): String = when (reason) {
        RunDenialReason.TAMPERED ->
            "Macro '${macro.name}' tidak valid (segel keamanan rusak) dan tidak dapat dijalankan."
        RunDenialReason.EXPIRED ->
            "Macro '${macro.name}' sudah habis masa berlakunya (${formatDeadlineDate(macro.expiresAtEpochMs)}) dan tidak dapat digunakan."
        RunDenialReason.PASSIVE_DIRECT -> {
            val status = if (macro.passiveEnabled) "ON (siap ditarik macro aktif)" else "OFF (tidak dapat ditarik)"
            "Macro '${macro.name}' dalam Mode Pasif $status. Tidak dapat dijalankan langsung."
        }
        RunDenialReason.PASSIVE_OFF ->
            "Macro pasif '${macro.name}' sedang OFF (tidak dapat ditarik) — dilewati oleh macro aktif."
    }

    /**
     * Applies updated Lock, Passive (with ON/OFF pull switch), Expiry, and Password settings
     * and recomputes the integrity seal.
     */
    fun applySecurity(
        macro: Macro,
        isLocked: Boolean,
        isPassive: Boolean,
        expiresAtEpochMs: Long,
        expiryLabel: String,
        newPassword: String? = null,
        passiveEnabled: Boolean = macro.passiveEnabled,
        expiryDurationAmount: Int = macro.expiryDurationAmount,
        expiryDurationUnit: ExpiryUnit = macro.expiryDurationUnit
    ): Macro {
        val safeExp = expiresAtEpochMs.coerceAtLeast(0L)
        val anyProtected = isLocked || isPassive || safeExp > 0L
        if (!anyProtected) {
            return macro.copy(
                isLocked = false,
                isPassive = false,
                passiveEnabled = true,
                expiresAtEpochMs = 0L,
                expiryLabel = "",
                expiryDurationAmount = 0,
                expiryDurationUnit = ExpiryUnit.DAYS,
                lockPasswordHash = "",
                lockSalt = "",
                securitySeal = ""
            )
        }

        val (salt, hash) = if (!newPassword.isNullOrBlank()) {
            val s = generateSalt()
            s to hashPassword(newPassword, s)
        } else {
            macro.lockSalt to macro.lockPasswordHash
        }

        val updated = macro.copy(
            isLocked = isLocked,
            isPassive = isPassive,
            passiveEnabled = if (isPassive) passiveEnabled else true,
            expiresAtEpochMs = safeExp,
            expiryLabel = if (safeExp > 0L) expiryLabel else "",
            expiryDurationAmount = if (safeExp > 0L) expiryDurationAmount.coerceAtLeast(0) else 0,
            expiryDurationUnit = expiryDurationUnit,
            lockSalt = salt,
            lockPasswordHash = hash
        )
        return updated.copy(securitySeal = computeSeal(updated))
    }

    /**
     * Quickly flips passive ON (callable) / OFF (skipped) without requiring a password,
     * while refusing to modify tampered macros and preserving password & valid integrity seal.
     */
    fun togglePassiveEnabled(macro: Macro, enabled: Boolean): Macro {
        if (!macro.isPassive || isTampered(macro)) return macro
        val updated = macro.copy(passiveEnabled = enabled)
        return updated.copy(securitySeal = computeSeal(updated))
    }

    /**
     * Updates Auto-Scale screen resolution settings WITHOUT requiring a password even when [macro.isLocked]
     * is true, while refusing tampered macros and preserving all locked actions & security seal intact.
     */
    fun updateAutoScale(
        macro: Macro,
        autoScaleResolution: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        scaleMode: ResolutionScaleMode
    ): Macro {
        if (isTampered(macro)) return macro
        return macro.copy(
            autoScaleResolution = autoScaleResolution,
            sourceWidth = sourceWidth.coerceIn(0, 10000),
            sourceHeight = sourceHeight.coerceIn(0, 10000),
            scaleMode = scaleMode
        )
    }

    data class OverwriteResult(
        val merged: Macro,
        val deadlineExtended: Boolean
    )

    /**
     * Overwrites [existing] with an imported [incoming] copy of the same macro.
     * If both [existing] and [incoming] have active (non-expired) deadlines and [incoming] is untampered,
     * the incoming duration is ADDED onto [existing.expiresAtEpochMs] so remaining time is never lost!
     */
    fun mergeOverwrittenMacro(existing: Macro, incoming: Macro, nowMs: Long): OverwriteResult {
        val incomingUntampered = !isTampered(incoming)
        val existingHasActiveDeadline = !isTampered(existing) && existing.expiresAtEpochMs > nowMs
        val incomingHasActiveDeadline = incomingUntampered && incoming.expiresAtEpochMs > nowMs

        val preservedOrigin = incoming.originId.ifBlank { existing.originId.ifBlank { existing.id } }
        val resolvedLogo = incoming.logoImage.ifBlank { existing.logoImage }

        if (existingHasActiveDeadline && incomingHasActiveDeadline) {
            val stackedExpiryMs = if (incoming.expiryDurationAmount > 0) {
                computeExpiryEpochMs(existing.expiresAtEpochMs, incoming.expiryDurationAmount, incoming.expiryDurationUnit)
            } else {
                val addMs = (incoming.expiresAtEpochMs - nowMs).coerceAtLeast(0L)
                existing.expiresAtEpochMs + addMs
            }
            val addedTag = when {
                incoming.expiryDurationAmount > 0 -> formatDurationLabel(incoming.expiryDurationAmount, incoming.expiryDurationUnit)
                incoming.expiryLabel.isNotBlank() -> incoming.expiryLabel
                else -> "Diperpanjang"
            }
            val mergedBase = incoming.copy(
                id = existing.id,
                originId = preservedOrigin,
                logoImage = resolvedLogo,
                expiresAtEpochMs = stackedExpiryMs,
                expiryLabel = "+$addedTag",
                createdAt = existing.createdAt
            )
            val sealed = mergedBase.copy(securitySeal = computeSeal(mergedBase))
            return OverwriteResult(merged = sealed, deadlineExtended = true)
        }

        val mergedBase = incoming.copy(
            id = existing.id,
            originId = preservedOrigin,
            logoImage = resolvedLogo,
            createdAt = existing.createdAt
        )
        return OverwriteResult(merged = mergedBase, deadlineExtended = false)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
