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
            if (macro.oneTimeImportToken.isNotBlank()) {
                append("|oneTime=").append(macro.oneTimeImportToken)
            }
        }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun computeOneTimeTokenSeal(token: String, originId: String): String {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return ""
        val payload = "$SEAL_PEPPER|oneTimeMacro|$cleanToken|origin=${originId.trim()}"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun computeOneTimeBundleSeal(token: String, exportedAtEpochMs: Long, originId: String): String {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return ""
        val payload = "$SEAL_PEPPER|oneTimeBundle|$cleanToken|at=$exportedAtEpochMs|origin=${originId.trim()}"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Stamps a fresh one-time import token onto an exported copy of [macro] without mutating the creator's saved macro. */
    fun attachOneTimeExportToken(macro: Macro, token: String = generateSalt()): Macro {
        val stableOrigin = macro.originId.ifBlank { macro.id }
        val cleanToken = token.trim()
        val withToken = macro.copy(
            originId = stableOrigin,
            oneTimeImportToken = cleanToken,
            oneTimeImportSeal = computeOneTimeTokenSeal(cleanToken, stableOrigin)
        )
        return if (hasAnyProtection(withToken) || hasPassword(withToken) || withToken.securitySeal.isNotBlank()) {
            withToken.copy(securitySeal = computeSeal(withToken))
        } else {
            withToken
        }
    }

    /** Verifies that a macro's one-time import token (if any) has not been manually forged or stripped. */
    fun verifyMacroOneTimeToken(macro: Macro): Boolean {
        val tok = macro.oneTimeImportToken.trim()
        val seal = macro.oneTimeImportSeal.trim()
        if (tok.isBlank() && seal.isBlank()) return true
        if (tok.isBlank() || seal.isBlank()) return false
        val origin = macro.originId.ifBlank { macro.id }
        return constantTimeEquals(seal, computeOneTimeTokenSeal(tok, origin))
    }

    fun verifyOneTimeBundleMeta(token: String, exportedAtEpochMs: Long, originId: String, metaSeal: String): Boolean {
        if (token.isBlank() || metaSeal.isBlank()) return false
        return constantTimeEquals(metaSeal, computeOneTimeBundleSeal(token, exportedAtEpochMs, originId))
    }

    fun isTampered(macro: Macro): Boolean {
        if (!verifyMacroOneTimeToken(macro)) return true
        if (macro.securitySeal.isBlank()) return false
        if (constantTimeEquals(macro.securitySeal, computeSeal(macro))) return false
        // Backward compatibility with v2.7.0 exports (where passiveEnabled defaulted to true)
        if (macro.oneTimeImportToken.isBlank() && macro.passiveEnabled && constantTimeEquals(macro.securitySeal, computeLegacySealV27(macro))) return false
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

    /** Checks whether [macro] (and its owning [parentSet], if any) may be started directly by the user. */
    fun checkDirectRun(
        macro: Macro,
        nowMs: Long = System.currentTimeMillis(),
        parentSet: MacroSet? = null
    ): RunDenialReason? = when {
        parentSet != null && isSetTampered(parentSet) -> RunDenialReason.TAMPERED
        parentSet != null && isSetExpired(parentSet, nowMs) -> RunDenialReason.EXPIRED
        isTampered(macro) -> RunDenialReason.TAMPERED
        isExpired(macro, nowMs) -> RunDenialReason.EXPIRED
        macro.isPassive -> RunDenialReason.PASSIVE_DIRECT
        else -> null
    }

    /**
     * Checks whether [macro] (and its owning [parentSet], if any) may be executed when pulled/called by
     * another active macro via [RunMacroAction].
     * Returns [RunDenialReason.PASSIVE_OFF] when passive mode is switched OFF so the caller skips cleanly.
     */
    fun checkLinkedRun(
        macro: Macro,
        nowMs: Long = System.currentTimeMillis(),
        parentSet: MacroSet? = null
    ): RunDenialReason? = when {
        parentSet != null && isSetTampered(parentSet) -> RunDenialReason.TAMPERED
        parentSet != null && isSetExpired(parentSet, nowMs) -> RunDenialReason.EXPIRED
        isTampered(macro) -> RunDenialReason.TAMPERED
        isExpired(macro, nowMs) -> RunDenialReason.EXPIRED
        macro.isPassive && !macro.passiveEnabled -> RunDenialReason.PASSIVE_OFF
        else -> null
    }

    fun denialMessage(macro: Macro, reason: RunDenialReason, parentSet: MacroSet? = null): String = when (reason) {
        RunDenialReason.TAMPERED ->
            if (parentSet != null && isSetTampered(parentSet)) {
                "Macro Set '${parentSet.name}' tidak valid (segel keamanan rusak) dan macro di dalamnya tidak dapat dijalankan."
            } else {
                "Macro '${macro.name}' tidak valid (segel keamanan rusak) dan tidak dapat dijalankan."
            }
        RunDenialReason.EXPIRED ->
            if (parentSet != null && parentSet.expiresAtEpochMs > 0L && isSetExpired(parentSet)) {
                "Macro Set '${parentSet.name}' sudah habis masa berlakunya (${formatDeadlineDate(parentSet.expiresAtEpochMs)}) dan tidak dapat digunakan."
            } else {
                "Macro '${macro.name}' sudah habis masa berlakunya (${formatDeadlineDate(macro.expiresAtEpochMs)}) dan tidak dapat digunakan."
            }
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

    /**
     * Updates a macro's Telegram/Discord remote control ID & enable flag without requiring a password
     * (useful in Wjust Player for locked macros), refusing tampered macros and preserving [Macro.securitySeal].
     */
    fun updateMacroRemoteControl(
        macro: Macro,
        remoteControlEnabled: Boolean,
        remoteId: String
    ): Macro {
        if (isTampered(macro)) return macro
        return macro.copy(
            remoteControlEnabled = remoteControlEnabled,
            remoteId = RemoteIdSanitizer.sanitize(remoteId)
        )
    }

    /**
     * Flips the live ON/OFF state of an action explicitly marked remote-controllable by the macro creator,
     * preserving [Macro.securitySeal] and refusing to modify locked non-remote actions or tampered macros.
     */
    fun setRemoteActionActive(
        macro: Macro,
        actionId: String,
        active: Boolean
    ): Macro {
        if (isTampered(macro)) return macro
        val existing = macro.actionRemoteControls[actionId] ?: return macro
        if (!existing.enabled) return macro
        val updatedMap = macro.actionRemoteControls.toMutableMap().apply {
            this[actionId] = existing.copy(active = active)
        }
        return macro.copy(actionRemoteControls = updatedMap)
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
        val resolvedSetId = incoming.setId.ifBlank { existing.setId }

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
                setId = resolvedSetId,
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
            setId = resolvedSetId,
            createdAt = existing.createdAt
        )
        return OverwriteResult(merged = mergedBase, deadlineExtended = false)
    }

    // ---- Macro Set Security & Protection ----

    fun hasAnySetProtection(set: MacroSet): Boolean =
        set.isLocked || set.expiresAtEpochMs > 0L

    fun hasSetPassword(set: MacroSet): Boolean =
        set.lockPasswordHash.isNotBlank() && set.lockSalt.isNotBlank()

    fun verifySetPassword(set: MacroSet, candidatePassword: String): Boolean {
        if (!hasSetPassword(set)) return true
        val actual = hashPassword(candidatePassword, set.lockSalt)
        return constantTimeEquals(set.lockPasswordHash, actual)
    }

    fun isEffectivelyLocked(macro: Macro, parentSet: MacroSet?): Boolean =
        macro.isLocked || (parentSet?.isLocked == true)

    /** Accepts either the macro's own password or its owning Macro Set's password. */
    fun verifyMacroOrSetPassword(macro: Macro, parentSet: MacroSet?, candidatePassword: String): Boolean {
        val macroHasPass = hasPassword(macro)
        val setHasPass = parentSet != null && hasSetPassword(parentSet)
        if (!macroHasPass && !setHasPass) return true
        if (macroHasPass && verifyPassword(macro, candidatePassword)) return true
        if (parentSet != null && hasSetPassword(parentSet) && verifySetPassword(parentSet, candidatePassword)) return true
        return false
    }

    /** Integrity seal for [MacroSet] independent of [MacroSet.id] so ZIP import ID remapping keeps the seal intact. */
    fun computeSetSeal(set: MacroSet): String {
        if (!hasAnySetProtection(set) && !hasSetPassword(set)) return ""
        val payload = buildString {
            append(SEAL_PEPPER)
            append("|set|locked=").append(set.isLocked)
            append("|exp=").append(set.expiresAtEpochMs)
            append("|hash=").append(set.lockPasswordHash)
            append("|salt=").append(set.lockSalt)
            if (set.oneTimeImportToken.isNotBlank()) {
                append("|oneTime=").append(set.oneTimeImportToken)
            }
        }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun computeSetOneTimeTokenSeal(token: String, originId: String): String {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return ""
        val payload = "$SEAL_PEPPER|oneTimeSet|$cleanToken|origin=${originId.trim()}"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun attachOneTimeExportToken(set: MacroSet, token: String = generateSalt()): MacroSet {
        val stableOrigin = set.originId.ifBlank { set.id }
        val cleanToken = token.trim()
        val withToken = set.copy(
            originId = stableOrigin,
            oneTimeImportToken = cleanToken,
            oneTimeImportSeal = computeSetOneTimeTokenSeal(cleanToken, stableOrigin)
        )
        return if (hasAnySetProtection(withToken) || hasSetPassword(withToken) || withToken.securitySeal.isNotBlank()) {
            withToken.copy(securitySeal = computeSetSeal(withToken))
        } else {
            withToken
        }
    }

    fun verifySetOneTimeToken(set: MacroSet): Boolean {
        val tok = set.oneTimeImportToken.trim()
        val seal = set.oneTimeImportSeal.trim()
        if (tok.isBlank() && seal.isBlank()) return true
        if (tok.isBlank() || seal.isBlank()) return false
        val origin = set.originId.ifBlank { set.id }
        return constantTimeEquals(seal, computeSetOneTimeTokenSeal(tok, origin))
    }

    fun isSetTampered(set: MacroSet): Boolean {
        if (!verifySetOneTimeToken(set)) return true
        if (set.securitySeal.isBlank()) return false
        return !constantTimeEquals(set.securitySeal, computeSetSeal(set))
    }

    fun isSetExpired(set: MacroSet, nowMs: Long = System.currentTimeMillis()): Boolean =
        set.expiresAtEpochMs > 0L && nowMs >= set.expiresAtEpochMs

    fun remainingSetTimeLabel(set: MacroSet, nowMs: Long = System.currentTimeMillis()): String? {
        if (set.expiresAtEpochMs <= 0L) return null
        val diff = set.expiresAtEpochMs - nowMs
        if (diff <= 0L) return "Kedaluwarsa"
        return when {
            diff >= 60L * DAY_MS -> "Sisa ${diff / (30L * DAY_MS)} bln"
            diff >= DAY_MS -> "Sisa ${(diff + DAY_MS - 1) / DAY_MS} hari"
            diff >= HOUR_MS -> "Sisa ${(diff + HOUR_MS - 1) / HOUR_MS} jam"
            else -> "Sisa ${((diff + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1)} mnt"
        }
    }

    fun applySetSecurity(
        set: MacroSet,
        isLocked: Boolean,
        expiresAtEpochMs: Long,
        expiryLabel: String,
        newPassword: String? = null,
        expiryDurationAmount: Int = set.expiryDurationAmount,
        expiryDurationUnit: ExpiryUnit = set.expiryDurationUnit
    ): MacroSet {
        val safeExp = expiresAtEpochMs.coerceAtLeast(0L)
        val anyProtected = isLocked || safeExp > 0L
        if (!anyProtected) {
            return set.copy(
                isLocked = false,
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
            set.lockSalt to set.lockPasswordHash
        }

        val updated = set.copy(
            isLocked = isLocked,
            expiresAtEpochMs = safeExp,
            expiryLabel = if (safeExp > 0L) expiryLabel else "",
            expiryDurationAmount = if (safeExp > 0L) expiryDurationAmount.coerceAtLeast(0) else 0,
            expiryDurationUnit = expiryDurationUnit,
            lockSalt = salt,
            lockPasswordHash = hash
        )
        return updated.copy(securitySeal = computeSetSeal(updated))
    }

    /**
     * Propagates [set]'s lock, deadline, and password hash onto a member [macro] while preserving
     * each macro's own Active/Passive (`isPassive` / `passiveEnabled`) state and integrity seal.
     */
    fun syncMacroWithSetSecurity(macro: Macro, set: MacroSet): Macro {
        if (isSetTampered(set)) return macro
        if (hasAnySetProtection(set) && hasSetPassword(set)) {
            val updated = macro.copy(
                isLocked = set.isLocked,
                expiresAtEpochMs = set.expiresAtEpochMs,
                expiryLabel = set.expiryLabel,
                expiryDurationAmount = set.expiryDurationAmount,
                expiryDurationUnit = set.expiryDurationUnit,
                lockSalt = set.lockSalt,
                lockPasswordHash = set.lockPasswordHash
            )
            return updated.copy(securitySeal = computeSeal(updated))
        }
        // Set protection turned OFF: unlock & clear deadline, while keeping Passive mode intact if enabled
        return if (macro.isPassive) {
            val updated = macro.copy(
                isLocked = false,
                expiresAtEpochMs = 0L,
                expiryLabel = "",
                expiryDurationAmount = 0,
                expiryDurationUnit = ExpiryUnit.DAYS
            )
            updated.copy(securitySeal = computeSeal(updated))
        } else {
            macro.copy(
                isLocked = false,
                expiresAtEpochMs = 0L,
                expiryLabel = "",
                expiryDurationAmount = 0,
                expiryDurationUnit = ExpiryUnit.DAYS,
                lockSalt = "",
                lockPasswordHash = "",
                securitySeal = ""
            )
        }
    }

    data class SetOverwriteResult(
        val merged: MacroSet,
        val deadlineExtended: Boolean
    )

    /**
     * Overwrites [existing] with an imported [incoming] copy of the same Macro Set,
     * stacking active deadlines just like [mergeOverwrittenMacro].
     */
    fun mergeOverwrittenMacroSet(existing: MacroSet, incoming: MacroSet, nowMs: Long): SetOverwriteResult {
        val incomingUntampered = !isSetTampered(incoming)
        val existingHasActiveDeadline = !isSetTampered(existing) && existing.expiresAtEpochMs > nowMs
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
            val sealed = mergedBase.copy(securitySeal = computeSetSeal(mergedBase))
            return SetOverwriteResult(merged = sealed, deadlineExtended = true)
        }

        val mergedBase = incoming.copy(
            id = existing.id,
            originId = preservedOrigin,
            logoImage = resolvedLogo,
            createdAt = existing.createdAt
        )
        return SetOverwriteResult(merged = mergedBase, deadlineExtended = false)
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
