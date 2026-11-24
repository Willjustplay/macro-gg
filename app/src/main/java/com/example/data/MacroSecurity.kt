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
    PASSIVE_DIRECT
}

/**
 * Pure-Kotlin offline security helper for Wjust macro:
 * - Salted SHA-256 password hashing & verification for Lock, Expiry, and Passive Mode
 * - Calendar-accurate expiration deadlines in Days or Months
 * - Passive mode enforcement (blocked directly, allowed when called from an active macro)
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

    /** Computes an integrity seal independent of [Macro.id] so ZIP import ID remapping keeps the seal intact. */
    fun computeSeal(macro: Macro): String {
        if (!hasAnyProtection(macro) && !hasPassword(macro)) return ""
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

    fun isTampered(macro: Macro): Boolean {
        if (macro.securitySeal.isBlank()) return false
        return !constantTimeEquals(macro.securitySeal, computeSeal(macro))
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

    /** Checks whether [macro] may be executed when called by another active macro via [RunMacroAction]. */
    fun checkLinkedRun(macro: Macro, nowMs: Long = System.currentTimeMillis()): RunDenialReason? = when {
        isTampered(macro) -> RunDenialReason.TAMPERED
        isExpired(macro, nowMs) -> RunDenialReason.EXPIRED
        else -> null
    }

    fun denialMessage(macro: Macro, reason: RunDenialReason): String = when (reason) {
        RunDenialReason.TAMPERED ->
            "Macro '${macro.name}' tidak valid (segel keamanan rusak) dan tidak dapat dijalankan."
        RunDenialReason.EXPIRED ->
            "Macro '${macro.name}' sudah habis masa berlakunya (${formatDeadlineDate(macro.expiresAtEpochMs)}) dan tidak dapat digunakan."
        RunDenialReason.PASSIVE_DIRECT ->
            "Macro '${macro.name}' dalam Mode Pasif. Hubungkan lewat aksi 'Hubungkan Macro' dari macro aktif untuk menggunakannya."
    }

    /**
     * Applies updated Lock, Passive, Expiry, and Password settings and recomputes the integrity seal.
     * Passing [newPassword] replaces/initializes the password; leaving it null/blank preserves the existing password.
     */
    fun applySecurity(
        macro: Macro,
        isLocked: Boolean,
        isPassive: Boolean,
        expiresAtEpochMs: Long,
        expiryLabel: String,
        newPassword: String? = null
    ): Macro {
        val safeExp = expiresAtEpochMs.coerceAtLeast(0L)
        val anyProtected = isLocked || isPassive || safeExp > 0L
        if (!anyProtected) {
            return macro.copy(
                isLocked = false,
                isPassive = false,
                expiresAtEpochMs = 0L,
                expiryLabel = "",
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
            expiresAtEpochMs = safeExp,
            expiryLabel = if (safeExp > 0L) expiryLabel else "",
            lockSalt = salt,
            lockPasswordHash = hash
        )
        return updated.copy(securitySeal = computeSeal(updated))
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
