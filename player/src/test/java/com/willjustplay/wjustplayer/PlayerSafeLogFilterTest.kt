package com.willjustplay.wjustplayer

import com.example.automation.LogLevel
import com.willjustplay.wjustplayer.data.PlayerStorageHelper
import com.willjustplay.wjustplayer.runtime.PlayerSafeLogBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSafeLogFilterTest {

    @Test
    fun hidesActionImageLuaAndCoordinateLogs() {
        assertNull(PlayerSafeLogBus.filterRunnerMessage(LogLevel.ACTION, "Klik (540, 1200)"))
        assertNull(PlayerSafeLogBus.filterRunnerMessage(LogLevel.IMAGE, "Cocok tombol_play.png (96%)"))
        assertNull(PlayerSafeLogBus.filterRunnerMessage(LogLevel.LUA, "Lua click(100, 200)"))
        assertNull(PlayerSafeLogBus.filterRunnerMessage(LogLevel.INFO, "Klik titik (120, 340)"))
        assertNull(PlayerSafeLogBus.filterRunnerMessage(LogLevel.WARN, "Gambar target_boss.png tidak ditemukan"))
    }

    @Test
    fun preservesSafeLifecycleStatusAndSanitizesErrors() {
        val loop = PlayerSafeLogBus.filterRunnerMessage(LogLevel.INFO, "Loop #3 dimulai")
        assertNotNull(loop)
        assertEquals("Loop #3 dimulai", loop!!.second)

        val err = PlayerSafeLogBus.filterRunnerMessage(LogLevel.ERROR, "Gagal memuat secret_btn.png di (540, 960)")
        assertNotNull(err)
        assertFalse(err!!.second.contains("secret_btn.png"))
        assertFalse(err.second.contains("540"))
        assertTrue(err.second.contains("[aset]"))
    }

    @Test
    fun storageHelperOnlyAcceptsWjmExtension() {
        assertTrue(PlayerStorageHelper.isWjmFileName("farming.wjm"))
        assertTrue(PlayerStorageHelper.isWjmFileName("PAKET_SET_1X.WJM"))
        assertFalse(PlayerStorageHelper.isWjmFileName("macro.zip"))
        assertFalse(PlayerStorageHelper.isWjmFileName("macro.json"))
    }
}
