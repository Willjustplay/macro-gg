package com.example.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Metadata extracted from an installed PRISM Cloner (`app.prism.cloner`) stub package
 * (`app.prism.c.p<hash>.u01..u99`) via `assets/prism_slot.json` or AndroidManifest `<meta-data>`.
 */
data class PrismSlotInfo(
    val stubPackage: String,
    val sourcePackage: String = "",
    val profileId: String = "",
    val displayLabel: String = "",
    val slotOrder: Int = 0,
    val hostPackage: String = PrismProtocol.PRISM_HOST_PACKAGE,
    val isRootDelegation: Boolean = false
)

/**
 * Request payload dispatched directly to PRISM Cloner's anti-BAL BroadcastReceiver
 * (`app.prism.cloner/.RootSwitchBroadcastReceiver`) or trampoline Activity.
 */
data class PrismSwitchRequest(
    val targetPackage: String = "",
    val slotOrder: Int = 0,
    val profileId: String = "",
    val displayLabel: String = "",
    val stubPackage: String = "",
    val switchNext: Boolean = false,
    val hostPackage: String = PrismProtocol.PRISM_HOST_PACKAGE
)

/**
 * Pure-Kotlin protocol specification & safe helpers matching `Willjustplay/clone-app` (PRISM Cloner).
 *
 * Kept free of Android framework imports so it is shared cleanly across `:core`, `:app`, `:player`,
 * `:desktop`, and unit tests without R8 serialization reflection issues.
 */
object PrismProtocol {

    const val PRISM_HOST_PACKAGE = "app.prism.cloner"
    const val PRISM_SWITCH_RECEIVER_CLASS = "app.prism.cloner.RootSwitchBroadcastReceiver"
    const val PRISM_QUICK_LAUNCH_ACTIVITY_CLASS = "app.prism.cloner.RootQuickLaunchActivity"

    const val STUB_PACKAGE_PREFIX = "app.prism.c."
    const val STUB_LAUNCH_ACTIVITY_CLASS = "app.prism.stub.StubLaunchActivity"
    const val STUB_SLOT_ASSET_PATH = "prism_slot.json"
    const val STUB_SLOT_ZIP_ENTRY = "assets/prism_slot.json"

    const val META_TARGET_PACKAGE = "prism.target_package"
    const val META_PROFILE_ID = "prism.profile_id"
    const val PLACEHOLDER_TARGET_PACKAGE = "PRISM_TARGET_PACKAGE_PLACEHOLDER"
    const val PLACEHOLDER_PROFILE_ID = "PRISM_PROFILE_ID_PLACEHOLDER"

    const val ACTION_SWITCH_ACCOUNT = "app.prism.action.SWITCH_ACCOUNT"
    const val ACTION_SWITCH_NEXT = "app.prism.action.SWITCH_NEXT"

    const val EXTRA_TARGET_PACKAGE = "app.prism.extra.ROOT_TARGET_PACKAGE"
    const val EXTRA_PROFILE_ID = "app.prism.extra.ROOT_PROFILE_ID"
    const val EXTRA_DISPLAY_LABEL = "app.prism.extra.ROOT_DISPLAY_LABEL"
    const val EXTRA_SLOT_ORDER = "app.prism.extra.ROOT_SLOT_ORDER"
    const val EXTRA_STUB_PACKAGE = "app.prism.extra.ROOT_STUB_PACKAGE"

    /** FLAG_RECEIVER_FOREGROUND (0x10000000) | FLAG_INCLUDE_STOPPED_PACKAGES (0x00000020). */
    const val FLAG_ANTI_BAL_BROADCAST = 0x10000020

    private val SLOT_SUFFIX_REGEX = Regex("\\.u(\\d{2})$")
    private val VALID_PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val SAFE_PROFILE_REGEX = Regex("^[a-zA-Z0-9._-]{1,80}$")

    fun isPrismStubPackageName(packageName: String?): Boolean {
        val clean = packageName?.trim().orEmpty()
        return clean.startsWith(STUB_PACKAGE_PREFIX) && isValidAndroidPackage(clean)
    }

    fun isValidAndroidPackage(packageName: String?): Boolean {
        val clean = packageName?.trim().orEmpty()
        return clean.length in 3..128 && VALID_PACKAGE_REGEX.matches(clean)
    }

    fun isSafeProfileId(profileId: String?): Boolean {
        val clean = profileId?.trim().orEmpty()
        return clean.isNotEmpty() && SAFE_PROFILE_REGEX.matches(clean)
    }

    /** Extracts 1..99 from PRISM stub package suffix `.u01`..`.u99`, or returns 0 if absent. */
    fun parseSlotNumberFromPackage(packageName: String?): Int {
        val clean = packageName?.trim().orEmpty()
        if (clean.isEmpty()) return 0
        val parsed = SLOT_SUFFIX_REGEX.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return if (parsed in 1..99) parsed else 0
    }

    /**
     * Deterministically reproduces PRISM's `PackageNameGenerator.generate(sourcePackage, slotIndex)`
     * so macro-gg can correlate target games + slot numbers with PRISM stub packages.
     */
    fun generateStubPackageName(sourcePackage: String, slotIndex: Int): String {
        val cleanSource = sourcePackage.trim()
        require(isValidAndroidPackage(cleanSource)) { "Invalid sourcePackage: $sourcePackage" }
        require(slotIndex in 1..99) { "slotIndex must be 1..99 (was $slotIndex)" }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cleanSource.toByteArray(Charsets.UTF_8))
        val hex = digest.take(3).joinToString("") { "%02x".format(it) }
        return "${STUB_PACKAGE_PREFIX}p$hex.u%02d".format(slotIndex)
    }

    /**
     * Parses `assets/prism_slot.json` embedded in PRISM Thin Stub APKs without relying on
     * reflection-based serializers (safe under R8 minification in `:player`).
     */
    fun parseSlotMetadataJson(rawJson: String, fallbackStubPackage: String = ""): PrismSlotInfo? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val obj = Json.parseToJsonElement(rawJson).jsonObject
            val stubPkg = obj["stubPackage"]?.jsonPrimitive?.content?.trim().orEmpty()
                .ifBlank { fallbackStubPackage.trim() }
            if (stubPkg.isBlank()) return null

            val sourcePkg = obj["sourcePackage"]?.jsonPrimitive?.content?.trim().orEmpty()
                .takeUnless { it == PLACEHOLDER_TARGET_PACKAGE }.orEmpty()
            val profileId = obj["activeProfileId"]?.jsonPrimitive?.content?.trim().orEmpty()
                .takeUnless { it == PLACEHOLDER_PROFILE_ID }.orEmpty()
            val displayLabel = obj["displayLabel"]?.jsonPrimitive?.content?.trim().orEmpty()
            val dynamicOrder = obj["dynamicSlotOrder"]?.jsonPrimitive?.intOrNull ?: 0
            val slotIndex = obj["slotIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val parsedFromPkg = parseSlotNumberFromPackage(stubPkg)
            val resolvedSlotOrder = when {
                dynamicOrder in 1..99 -> dynamicOrder
                slotIndex in 1..99 -> slotIndex
                else -> parsedFromPkg
            }
            val hostPkg = obj["hostPackage"]?.jsonPrimitive?.content?.trim().orEmpty()
                .takeIf { isValidAndroidPackage(it) } ?: PRISM_HOST_PACKAGE
            val isRootDelegation = obj["rootDelegationMode"]?.jsonPrimitive?.booleanOrNull ?: false

            PrismSlotInfo(
                stubPackage = stubPkg,
                sourcePackage = sourcePkg,
                profileId = profileId,
                displayLabel = displayLabel,
                slotOrder = resolvedSlotOrder,
                hostPackage = hostPkg,
                isRootDelegation = isRootDelegation
            )
        }.getOrNull()
    }

    /**
     * Builds a validated Root shell `am broadcast` command targeting PRISM's anti-BAL BroadcastReceiver.
     * Used only as a Root fallback if normal Android broadcast delivery is blocked (e.g. Android 15+
     * stopped-state restriction). Returns null if inputs fail strict validation.
     */
    fun buildRootBroadcastShellCommand(request: PrismSwitchRequest): String? {
        val hostPkg = request.hostPackage.trim().ifBlank { PRISM_HOST_PACKAGE }
        if (!isValidAndroidPackage(hostPkg)) return null

        val action = if (request.switchNext) ACTION_SWITCH_NEXT else ACTION_SWITCH_ACCOUNT
        val targetPkg = request.targetPackage.trim()
        val stubPkg = request.stubPackage.trim()
        val profileId = request.profileId.trim()
        val slotOrder = request.slotOrder.takeIf { it in 1..99 } ?: parseSlotNumberFromPackage(stubPkg)

        if (targetPkg.isNotEmpty() && !isValidAndroidPackage(targetPkg)) return null
        if (stubPkg.isNotEmpty() && !isValidAndroidPackage(stubPkg)) return null
        if (profileId.isNotEmpty() && !isSafeProfileId(profileId)) return null
        if (!request.switchNext && targetPkg.isEmpty() && stubPkg.isEmpty()) return null

        return buildString {
            append("am broadcast --user 0 -f 0x10000020 ")
            append("-n ").append(hostPkg).append("/.RootSwitchBroadcastReceiver ")
            append("-a ").append(action)
            if (targetPkg.isNotEmpty()) {
                append(" --es ").append(EXTRA_TARGET_PACKAGE).append(' ').append(targetPkg)
            }
            if (profileId.isNotEmpty()) {
                append(" --es ").append(EXTRA_PROFILE_ID).append(' ').append(profileId)
            }
            if (slotOrder in 1..99) {
                append(" --ei ").append(EXTRA_SLOT_ORDER).append(' ').append(slotOrder)
            }
            if (stubPkg.isNotEmpty()) {
                append(" --es ").append(EXTRA_STUB_PACKAGE).append(' ').append(stubPkg)
            }
        }
    }

    /**
     * Builds a validated Root shell launch script for custom packages (including `app.prism.c.*` stubs
     * or root-repackaged clones) when Android's non-root `getLaunchIntentForPackage` / BAL fails.
     */
    fun buildRootLaunchShellCommand(packageName: String): String? {
        val cleanPkg = packageName.trim()
        if (!isValidAndroidPackage(cleanPkg)) return null
        val prismExtras = if (isPrismStubPackageName(cleanPkg)) {
            """
            if am start --user 0 -n "$cleanPkg/$STUB_LAUNCH_ACTIVITY_CLASS" -f 0x10000000 >/dev/null 2>&1; then
              exit 0
            fi
            if am start --user 0 -n "$cleanPkg/.MainActivity" -f 0x10000000 >/dev/null 2>&1; then
              exit 0
            fi
            """.trimIndent()
        } else {
            ""
        }
        return buildString {
            appendLine("PKG='$cleanPkg'")
            appendLine("COMP=\$(cmd package resolve-activity --brief --user 0 \"\$PKG\" 2>/dev/null | tail -n 1 || true)")
            appendLine("case \"\$COMP\" in")
            appendLine("  */*)")
            appendLine("    if am start --user 0 -n \"\$COMP\" -f 0x10200000 >/dev/null 2>&1; then exit 0; fi")
            appendLine("    ;;")
            appendLine("esac")
            if (prismExtras.isNotEmpty()) {
                appendLine(prismExtras)
            }
            appendLine("if monkey -p \"\$PKG\" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; then exit 0; fi")
            appendLine("if am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p \"\$PKG\" >/dev/null 2>&1; then exit 0; fi")
            append("exit 1")
        }
    }
}
