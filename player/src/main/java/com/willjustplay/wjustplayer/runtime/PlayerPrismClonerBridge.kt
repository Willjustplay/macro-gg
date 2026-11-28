package com.willjustplay.wjustplayer.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.automation.PrismProtocol
import com.example.automation.PrismSlotInfo
import com.example.automation.PrismSwitchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Android bridge connecting Wjust Player (`:player`) directly with PRISM Cloner (`app.prism.cloner`)
 * and fixing account switching against custom root-installed packages (`app.prism.c.*` & clones).
 */
object PlayerPrismClonerBridge {

    fun isPrismInstalled(context: Context, hostPackage: String = PrismProtocol.PRISM_HOST_PACKAGE): Boolean =
        getApplicationInfoCompat(context.packageManager, hostPackage, 0) != null

    fun inspectStubPackage(context: Context, packageName: String): PrismSlotInfo? {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return null

        val pm = context.packageManager
        val appInfo = getApplicationInfoCompat(pm, cleanPkg, PackageManager.GET_META_DATA)
        if (appInfo == null) {
            if (PrismProtocol.isPrismStubPackageName(cleanPkg) && isPrismInstalled(context)) {
                return PrismSlotInfo(
                    stubPackage = cleanPkg,
                    slotOrder = PrismProtocol.parseSlotNumberFromPackage(cleanPkg),
                    hostPackage = PrismProtocol.PRISM_HOST_PACKAGE,
                    isRootDelegation = true
                )
            }
            return null
        }

        val appLabel = runCatching { pm.getApplicationLabel(appInfo).toString().trim() }.getOrDefault("")

        val jsonFromAssets = runCatching {
            pm.getResourcesForApplication(appInfo).assets.open(PrismProtocol.STUB_SLOT_ASSET_PATH)
                .bufferedReader().use { it.readText() }
        }.getOrNull()

        val jsonFromZip = jsonFromAssets ?: runCatching {
            val apkPath = appInfo.sourceDir
            if (apkPath.isNullOrBlank()) null
            else ZipFile(File(apkPath)).use { zf ->
                val entry = zf.getEntry(PrismProtocol.STUB_SLOT_ZIP_ENTRY) ?: return@use null
                zf.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }.getOrNull()

        if (!jsonFromZip.isNullOrBlank()) {
            val parsed = PrismProtocol.parseSlotMetadataJson(jsonFromZip, fallbackStubPackage = cleanPkg)
            if (parsed != null) {
                return if (parsed.displayLabel.isBlank() && appLabel.isNotBlank()) {
                    parsed.copy(displayLabel = appLabel)
                } else {
                    parsed
                }
            }
        }

        val metaTarget = appInfo.metaData?.getString(PrismProtocol.META_TARGET_PACKAGE)?.trim().orEmpty()
            .takeUnless { it == PrismProtocol.PLACEHOLDER_TARGET_PACKAGE }.orEmpty()
        val metaProfile = appInfo.metaData?.getString(PrismProtocol.META_PROFILE_ID)?.trim().orEmpty()
            .takeUnless { it == PrismProtocol.PLACEHOLDER_PROFILE_ID }.orEmpty()

        if (metaTarget.isNotBlank() || metaProfile.isNotBlank()) {
            val hasPrismStubActivity = resolveExplicitActivity(pm, cleanPkg, PrismProtocol.STUB_LAUNCH_ACTIVITY_CLASS) != null
            return PrismSlotInfo(
                stubPackage = cleanPkg,
                sourcePackage = metaTarget,
                profileId = metaProfile,
                displayLabel = appLabel,
                slotOrder = PrismProtocol.parseSlotNumberFromPackage(cleanPkg),
                hostPackage = PrismProtocol.PRISM_HOST_PACKAGE,
                isRootDelegation = hasPrismStubActivity && isPrismInstalled(context)
            )
        }

        return null
    }

    fun isRootDelegationPackage(context: Context, packageName: String): Boolean =
        inspectStubPackage(context, packageName)?.isRootDelegation == true

    fun resolveUnderlyingTargetPackage(context: Context, packageName: String): String {
        val clean = packageName.trim()
        if (clean.isBlank()) return ""
        val slot = inspectStubPackage(context, clean)
        return if (slot != null && slot.isRootDelegation && slot.sourcePackage.isNotBlank()) {
            slot.sourcePackage
        } else {
            clean
        }
    }

    fun discoverInstalledPrismRootSlots(context: Context): List<PrismSlotInfo> {
        val pm = context.packageManager
        val installed = getInstalledApplicationsCompat(pm, PackageManager.GET_META_DATA)
        val result = ArrayList<PrismSlotInfo>()
        for (appInfo in installed) {
            val pkg = appInfo.packageName?.trim().orEmpty()
            if (!PrismProtocol.isPrismStubPackageName(pkg)) continue
            val slot = inspectStubPackage(context, pkg) ?: continue
            if (!slot.isRootDelegation) continue
            result += slot
        }
        return result.sortedWith(
            compareBy<PrismSlotInfo>(
                { it.sourcePackage.lowercase() },
                { if (it.slotOrder > 0) it.slotOrder else Int.MAX_VALUE },
                { it.displayLabel.lowercase() },
                { it.stubPackage }
            )
        )
    }

    suspend fun triggerPrismSwitch(
        context: Context,
        request: PrismSwitchRequest,
        launchActivity: suspend (Intent) -> Boolean,
        logInfo: (String) -> Unit = {},
        logWarn: (String) -> Unit = {},
        logError: (String) -> Unit = {}
    ): Boolean {
        val stubInfo = request.stubPackage.trim()
            .takeIf { it.isNotBlank() }
            ?.let { inspectStubPackage(context, it) }

        val hostPkg = request.hostPackage.trim()
            .takeIf { PrismProtocol.isValidAndroidPackage(it) }
            ?: stubInfo?.hostPackage?.takeIf { PrismProtocol.isValidAndroidPackage(it) }
            ?: PrismProtocol.PRISM_HOST_PACKAGE

        val targetPkg = request.targetPackage.trim().ifBlank { stubInfo?.sourcePackage.orEmpty() }
        val profileId = request.profileId.trim().ifBlank { stubInfo?.profileId.orEmpty() }
        val displayLabel = request.displayLabel.trim().ifBlank { stubInfo?.displayLabel.orEmpty() }
        val stubPkg = request.stubPackage.trim()
        val slotOrder = when {
            request.slotOrder in 1..99 -> request.slotOrder
            (stubInfo?.slotOrder ?: 0) in 1..99 -> stubInfo!!.slotOrder
            else -> PrismProtocol.parseSlotNumberFromPackage(stubPkg)
        }

        val effective = request.copy(
            targetPackage = targetPkg,
            slotOrder = if (request.switchNext) 0 else slotOrder,
            profileId = if (request.switchNext) "" else profileId,
            displayLabel = displayLabel,
            stubPackage = if (request.switchNext) "" else stubPkg,
            hostPackage = hostPkg
        )

        val hostInfo = getApplicationInfoCompat(context.packageManager, hostPkg, 0)
        if (hostInfo == null) {
            logError("PRISM Cloner ($hostPkg) belum terpasang di perangkat.")
            return false
        }

        val actionName = if (effective.switchNext) {
            PrismProtocol.ACTION_SWITCH_NEXT
        } else {
            PrismProtocol.ACTION_SWITCH_ACCOUNT
        }

        val broadcastIntent = Intent(actionName).apply {
            component = ComponentName(hostPkg, PrismProtocol.PRISM_SWITCH_RECEIVER_CLASS)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            if (effective.targetPackage.isNotBlank()) putExtra(PrismProtocol.EXTRA_TARGET_PACKAGE, effective.targetPackage)
            if (effective.profileId.isNotBlank()) putExtra(PrismProtocol.EXTRA_PROFILE_ID, effective.profileId)
            if (effective.displayLabel.isNotBlank()) putExtra(PrismProtocol.EXTRA_DISPLAY_LABEL, effective.displayLabel)
            if (effective.slotOrder in 1..99) putExtra(PrismProtocol.EXTRA_SLOT_ORDER, effective.slotOrder)
            if (effective.stubPackage.isNotBlank()) putExtra(PrismProtocol.EXTRA_STUB_PACKAGE, effective.stubPackage)
        }

        val desc = if (effective.switchNext) "SWITCH_NEXT" else effective.displayLabel.ifBlank { effective.stubPackage.ifBlank { "Akun" } }

        val isHostStopped = (hostInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0
        if (!isHostStopped) {
            val sent = runCatching {
                context.sendBroadcast(broadcastIntent)
                true
            }.getOrDefault(false)
            if (sent) {
                logInfo("🔀 PRISM Root Broadcast terkirim → $desc")
                return true
            }
        } else {
            logWarn("ℹ️ PRISM Cloner dalam status berhenti — membangunkan via trampoline / Root shell...")
        }

        val trampolineIntent = Intent(actionName).apply {
            component = ComponentName(hostPkg, PrismProtocol.PRISM_QUICK_LAUNCH_ACTIVITY_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (effective.targetPackage.isNotBlank()) putExtra(PrismProtocol.EXTRA_TARGET_PACKAGE, effective.targetPackage)
            if (effective.profileId.isNotBlank()) putExtra(PrismProtocol.EXTRA_PROFILE_ID, effective.profileId)
            if (effective.displayLabel.isNotBlank()) putExtra(PrismProtocol.EXTRA_DISPLAY_LABEL, effective.displayLabel)
            if (effective.slotOrder in 1..99) putExtra(PrismProtocol.EXTRA_SLOT_ORDER, effective.slotOrder)
            if (effective.stubPackage.isNotBlank()) putExtra(PrismProtocol.EXTRA_STUB_PACKAGE, effective.stubPackage)
        }
        val startedTrampoline = launchActivity(trampolineIntent) || runCatching {
            context.startActivity(trampolineIntent)
            true
        }.getOrDefault(false)
        if (startedTrampoline) {
            logInfo("🔀 PRISM Root Trampoline dipicu → $desc")
            return true
        }

        val rootCmd = PrismProtocol.buildRootBroadcastShellCommand(effective)
        if (rootCmd != null && runRootShell(rootCmd, timeoutMs = 6_000L)) {
            logInfo("🔀 PRISM Root Shell Broadcast terkirim → $desc")
            return true
        }

        val bestEffort = runCatching {
            context.sendBroadcast(broadcastIntent)
            true
        }.getOrDefault(false)
        if (bestEffort) {
            logInfo("🔀 PRISM Root Broadcast cadangan terkirim → $desc")
            return true
        }

        logError("Gagal memicu ganti akun PRISM Cloner untuk $desc.")
        return false
    }

    suspend fun launchPackageRobust(
        context: Context,
        packageName: String,
        freshStart: Boolean,
        launchActivity: suspend (Intent) -> Boolean,
        logInfo: (String) -> Unit = {},
        logWarn: (String) -> Unit = {},
        logError: (String) -> Unit = {}
    ): Boolean {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return false

        val slotInfo = inspectStubPackage(context, cleanPkg)
        if (slotInfo != null && slotInfo.isRootDelegation) {
            return triggerPrismSwitch(
                context = context,
                request = PrismSwitchRequest(
                    targetPackage = slotInfo.sourcePackage,
                    slotOrder = slotInfo.slotOrder,
                    profileId = slotInfo.profileId,
                    displayLabel = slotInfo.displayLabel,
                    stubPackage = cleanPkg,
                    switchNext = false,
                    hostPackage = slotInfo.hostPackage
                ),
                launchActivity = launchActivity,
                logInfo = logInfo,
                logWarn = logWarn,
                logError = logError
            )
        }

        val pm = context.packageManager
        val resolvedIntent = runCatching { pm.getLaunchIntentForPackage(cleanPkg) }.getOrNull()
            ?: resolveFallbackLaunchIntent(pm, cleanPkg)

        if (resolvedIntent != null) {
            resolvedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (freshStart) {
                resolvedIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (launchActivity(resolvedIntent)) return true
            val directOk = runCatching {
                context.startActivity(resolvedIntent)
                true
            }.getOrDefault(false)
            if (directOk) return true
            logWarn("Peluncuran standar '$cleanPkg' gagal — mencoba jalur Root shell...")
        }

        val rootLaunchCmd = PrismProtocol.buildRootLaunchShellCommand(cleanPkg)
        if (rootLaunchCmd != null && runRootShell(rootLaunchCmd, timeoutMs = 6_000L)) {
            logInfo("🚀 Membuka '$cleanPkg' via Root shell.")
            return true
        }

        logError("Masuk Aplikasi gagal: paket '$cleanPkg' tidak ditemukan atau diblokir sistem.")
        return false
    }

    private fun resolveFallbackLaunchIntent(pm: PackageManager, packageName: String): Intent? {
        val mainQuery = Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val candidates = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    mainQuery,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainQuery, PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())

        val picked = candidates.firstOrNull { it.activityInfo?.exported == true } ?: candidates.firstOrNull()
        val actName = picked?.activityInfo?.name?.trim().orEmpty()
        if (actName.isNotEmpty()) {
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(packageName, actName)
            }
        }

        if (PrismProtocol.isPrismStubPackageName(packageName)) {
            resolveExplicitActivity(pm, packageName, PrismProtocol.STUB_LAUNCH_ACTIVITY_CLASS)?.let { return it }
            resolveExplicitActivity(pm, packageName, "$packageName.MainActivity")?.let { return it }
        }
        return null
    }

    private fun resolveExplicitActivity(pm: PackageManager, pkg: String, className: String): Intent? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg, className)
        }
        val ri = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_ALL)
            }
        }.getOrNull()
        return if (ri != null) intent else null
    }

    private fun getApplicationInfoCompat(pm: PackageManager, pkg: String, flags: Int): ApplicationInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, flags)
            }
        }.getOrNull()

    private fun getInstalledApplicationsCompat(pm: PackageManager, flags: Int): List<ApplicationInfo> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(flags)
            }
        }.getOrDefault(emptyList())

    private suspend fun runRootShell(command: String, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            runCatching { process.outputStream.close() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                val out = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                process.exitValue() == 0 &&
                    !out.contains("SecurityException", ignoreCase = true) &&
                    !out.contains("ActivityNotFoundException", ignoreCase = true) &&
                    !out.contains("Error:", ignoreCase = false)
            }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { process?.destroy() }
        }
    }
}
