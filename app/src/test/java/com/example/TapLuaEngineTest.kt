package com.example

import com.example.automation.LuaBridge
import com.example.automation.SandboxedLuaEngine
import com.example.automation.ScreenColorSamplerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class TapLuaEngineTest {

    @Test
    fun syntaxValidation_detectsValidAndInvalidLua() {
        val ok = SandboxedLuaEngine.validateSyntax("local x = 10\nclick(x, 20)\nlog('ok')")
        assertTrue(ok.isValid)

        val bad = SandboxedLuaEngine.validateSyntax("if true then click(10, 20)")
        assertFalse(bad.isValid)
    }

    @Test
    fun sandboxBlocksOsIoAndLuajava() {
        val logs = mutableListOf<String>()
        val bridge = FakeLuaBridge(logs = logs)
        val result = SandboxedLuaEngine.execute(
            """
            log("os=" .. tostring(os))
            log("io=" .. tostring(io))
            log("luajava=" .. tostring(luajava))
            log("dofile=" .. tostring(dofile))
            """.trimIndent(),
            bridge
        )
        assertTrue(result.isSuccess)
        assertEquals(listOf("os=nil", "io=nil", "luajava=nil", "dofile=nil"), logs)
    }

    @Test
    fun tapLuaCustomApis_dispatchGesturesAndColorMatching() {
        val logs = mutableListOf<String>()
        val clicks = mutableListOf<Pair<Int, Int>>()
        val bridge = FakeLuaBridge(
            logs = logs,
            clicks = clicks,
            pixelArgb = 0xFF2DD4BF.toInt()
        )
        val script = """
            local w, h = getScreenSize()
            local hex, r, g, b = getColor(100, 200)
            log(w .. "x" .. h .. ":" .. hex)
            if matchColor(100, 200, "#2ED4BE", 5) then
                click(100, 200, 50)
            end
        """.trimIndent()

        val result = SandboxedLuaEngine.execute(script, bridge)
        assertTrue(result.isSuccess)
        assertEquals(listOf("1080x2400:#2DD4BF"), logs)
        assertEquals(listOf(100 to 200), clicks)
    }

    @Test
    fun cooperativeAbortHook_stopsInfiniteLoopImmediately() {
        val stopped = AtomicBoolean(false)
        val bridge = FakeLuaBridge(stoppedFlag = stopped)

        val worker = Thread {
            SandboxedLuaEngine.execute("while true do local a = 1 + 1 end", bridge)
        }
        worker.start()
        Thread.sleep(60L)
        stopped.set(true)
        worker.join(2000L)
        assertFalse("Infinite Lua loop must terminate promptly when stopped", worker.isAlive)
    }

    @Test
    fun colorMatcher_respectsToleranceBounds() {
        val teal = 0xFF2DD4BF.toInt()
        assertTrue(ScreenColorSamplerService.colorMatches(teal, "#2DD4BF", 0))
        assertTrue(ScreenColorSamplerService.colorMatches(teal, "#32D9C4", 10))
        assertFalse(ScreenColorSamplerService.colorMatches(teal, "#FF0000", 15))
    }

    private class FakeLuaBridge(
        private val logs: MutableList<String> = mutableListOf(),
        private val clicks: MutableList<Pair<Int, Int>> = mutableListOf(),
        private val pixelArgb: Int? = 0xFF000000.toInt(),
        private val stoppedFlag: AtomicBoolean = AtomicBoolean(false)
    ) : LuaBridge {
        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2400
        override fun isStopped(): Boolean = stoppedFlag.get()
        override fun isPaused(): Boolean = false
        override fun click(x: Int, y: Int, durationMs: Int): Boolean {
            clicks.add(x to y)
            return true
        }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean = true
        override fun sleepCooperative(ms: Long) {}
        override fun getColorArgb(x: Int, y: Int): Int? = pixelArgb
        override fun log(message: String) {
            logs.add(message)
        }
    }
}
