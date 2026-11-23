package com.example.data

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("Ikuti Sistem"),
    LIGHT("Terang"),
    DARK("Gelap")
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val defaultTapDurationMs: Long = 50L,
    val defaultJitterPx: Int = 3,
    val showDebugMatchBox: Boolean = true
)

data class ImageAsset(
    val name: String,
    val path: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val updatedAt: Long,
    val folder: String = ""
) {
    val displayName: String get() = name.substringAfterLast('/')
}
