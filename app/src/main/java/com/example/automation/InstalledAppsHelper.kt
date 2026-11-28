package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val isPrismRoot: Boolean = false,
    val prismTargetPackage: String = "",
    val prismSlotOrder: Int = 0,
    val prismProfileId: String = ""
)

/**
 * Loads launchable installed apps on the device (excluding Wjust macro itself) plus any
 * PRISM Cloner Root Macro Launcher stubs (`app.prism.c.*`) for the "Masuk Aplikasi",
 * "Keluar Aplikasi", and Multi-Account pickers in both Compose and Overlay UIs.
 */
object InstalledAppsHelper {
    @Volatile
    private var cachedApps: List<InstalledAppInfo>? = null
    @Volatile
    private var cachedAtMs: Long = 0L

    fun loadLaunchableApps(context: Context, forceRefresh: Boolean = false): List<InstalledAppInfo> {
        val now = System.currentTimeMillis()
        val existing = cachedApps
        if (!forceRefresh && existing != null && now - cachedAtMs < 5_000L) {
            return existing
        }
        val pm = context.packageManager
        val selfPkg = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val prismSlotsByPkg = PrismClonerBridge.discoverInstalledPrismRootSlots(context)
            .associateBy { it.stubPackage }

        val seen = HashSet<String>()
        val list = ArrayList<InstalledAppInfo>(resolved.size + prismSlotsByPkg.size)
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName?.trim().orEmpty()
            if (pkg.isBlank() || pkg == selfPkg || !seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString().trim() }
                .getOrDefault("")
                .ifBlank { pkg }
            val prism = prismSlotsByPkg[pkg]
            list += InstalledAppInfo(
                label = label,
                packageName = pkg,
                isPrismRoot = prism?.isRootDelegation == true,
                prismTargetPackage = prism?.sourcePackage.orEmpty(),
                prismSlotOrder = prism?.slotOrder ?: 0,
                prismProfileId = prism?.profileId.orEmpty()
            )
        }
        // Ensure every installed PRISM Root stub is selectable even if OEM launcher query filtered it out
        for ((pkg, prism) in prismSlotsByPkg) {
            if (pkg == selfPkg || !seen.add(pkg)) continue
            val label = prism.displayLabel.ifBlank {
                if (prism.slotOrder > 0) "PRISM Slot #%02d".format(prism.slotOrder) else pkg
            }
            list += InstalledAppInfo(
                label = label,
                packageName = pkg,
                isPrismRoot = true,
                prismTargetPackage = prism.sourcePackage,
                prismSlotOrder = prism.slotOrder,
                prismProfileId = prism.profileId
            )
        }
        list.sortWith(
            compareByDescending<InstalledAppInfo> { it.isPrismRoot }
                .thenBy { if (it.isPrismRoot && it.prismSlotOrder > 0) it.prismSlotOrder else Int.MAX_VALUE }
                .thenBy { it.label.lowercase() }
        )
        cachedApps = list
        cachedAtMs = now
        return list
    }
}
