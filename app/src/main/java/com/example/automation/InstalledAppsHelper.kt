package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class InstalledAppInfo(
    val label: String,
    val packageName: String
)

/**
 * Loads launchable installed apps on the device (excluding Wjust macro itself)
 * for the "Masuk Aplikasi" and "Keluar Aplikasi" pickers in both Compose and Overlay UIs.
 */
object InstalledAppsHelper {
    @Volatile
    private var cachedApps: List<InstalledAppInfo>? = null
    @Volatile
    private var cachedAtMs: Long = 0L

    fun loadLaunchableApps(context: Context, forceRefresh: Boolean = false): List<InstalledAppInfo> {
        val now = System.currentTimeMillis()
        val existing = cachedApps
        if (!forceRefresh && existing != null && now - cachedAtMs < 30_000L) {
            return existing
        }
        val pm = context.packageManager
        val selfPkg = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val seen = HashSet<String>()
        val list = ArrayList<InstalledAppInfo>(resolved.size)
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName?.trim().orEmpty()
            if (pkg.isBlank() || pkg == selfPkg || !seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString().trim() }
                .getOrDefault("")
                .ifBlank { pkg }
            list += InstalledAppInfo(label = label, packageName = pkg)
        }
        list.sortBy { it.label.lowercase() }
        cachedApps = list
        cachedAtMs = now
        return list
    }
}
