package com.example.desktop

import javax.swing.SwingUtilities

/**
 * Entry point for Wjust macro PC (Windows / Linux / macOS).
 */
fun main() {
    // Enable crisp HiDPI scaling and low-latency rendering on Windows/Linux/macOS
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

    SwingUtilities.invokeLater {
        DesktopTheme.install()
        val store = DesktopStore()
        val coordinator = DesktopCoordinator(store)
        val window = DesktopMainWindow(store, coordinator)
        window.isVisible = true
    }
}
