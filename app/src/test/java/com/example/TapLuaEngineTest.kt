package com.example

import com.example.automation.LuaBridge
import com.example.automation.SandboxedLuaEngine
import com.example.data.Region
import com.example.vision.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class TapLuaEngineTest {

    @Test
    fun syntaxValidation_detectsValidAndInvalidLua() {
        val ok = SandboxedLuaEngine.validateSyntax("local ok, x, y = findImage('play.png', 0.85)\nif ok then click(x, y) end")
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
    fun luaImageAndColorApis_workEndToEnd() {
        val logs = mutableListOf<String>()
        val clicks = mutableListOf<Pair<Int, Int>>()
        val bridge = FakeLuaBridge(
            logs = logs,
            clicks = clicks,
            pixelArgb = 0xFF2DD4BF.toInt(),
            fakeMatch = MatchResult(100, 200, 40, 20, 0.96)
        )
        val script = """
            local ok, x, y, score = findImage("play.png", 0.85)
            log("found=" .. tostring(ok) .. ":" .. x .. "," .. y)
            if ok then click(x, y) end
            local clicked, cx, cy = clickImage("play.png", 0.85, 0, 5, -5)
            log("clicked=" .. tostring(clicked) .. ":" .. cx .. "," .. cy)
            local all = findAllImages("play.png", 0.85)
            log("count=" .. #all)
            local hex = getColor(10, 10)
            log("hex=" .. hex)
        """.trimIndent()

        val result = SandboxedLuaEngine.execute(script, bridge)
        assertTrue(result.isSuccess)
        assertEquals(listOf(120 to 210, 125 to 205), clicks)
        assertEquals(
            listOf("found=true:120,210", "clicked=true:125,205", "count=1", "hex=#2DD4BF"),
            logs
        )
    }

    @Test
    fun cooperativeStop_abortsInfiniteLoopQuickly() {
        val stopFlag = AtomicBoolean(false)
        val bridge = FakeLuaBridge(stopFlag = stopFlag)
        val thread = Thread {
            Thread.sleep(60L)
            stopFlag.set(true)
        }
        thread.start()
        val started = System.currentTimeMillis()
        val result = SandboxedLuaEngine.execute("while true do end", bridge)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result.isFailure)
        assertTrue("Expected fast stop, got ${elapsed}ms", elapsed < 2000L)
    }

    private class FakeLuaBridge(
        private val logs: MutableList<String> = mutableListOf(),
        private val clicks: MutableList<Pair<Int, Int>> = mutableListOf(),
        private val pixelArgb: Int? = null,
        private val fakeMatch: MatchResult? = null,
        private val stopFlag: AtomicBoolean = AtomicBoolean(false)
    ) : LuaBridge {
        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2400
        override fun isStopped(): Boolean = stopFlag.get()
        override fun isPaused(): Boolean = false
        override fun click(x: Int, y: Int, durationMs: Int): Boolean {
            clicks += x to y
            return true
        }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean = true
        override fun sleepCooperative(ms: Long) {}
        override fun getColorArgb(x: Int, y: Int): Int? = pixelArgb
        override fun findImage(name: String, threshold: Double, region: Region?): MatchResult? = fakeMatch
        override fun findAllImages(name: String, threshold: Double, maxResults: Int, region: Region?): List<MatchResult> =
            listOfNotNull(fakeMatch)
        override fun log(message: String) { logs += message }
    }
}
