package com.example.automation

import com.example.data.Region
import com.example.vision.ColorUtil
import com.example.vision.MatchResult
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaBoolean
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib

data class LuaSyntaxResult(
    val isValid: Boolean,
    val message: String
)

class LuaStopSignal(val graceful: Boolean = true) : RuntimeException("Lua execution stopped")

interface LuaBridge {
    val screenWidth: Int
    val screenHeight: Int
    fun isStopped(): Boolean
    fun isPaused(): Boolean
    fun click(x: Int, y: Int, durationMs: Int): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean
    fun zoom(cx: Int, cy: Int, zoomIn: Boolean, distancePx: Int, durationMs: Int): Boolean = true
    fun sleepCooperative(ms: Long)
    fun getColorArgb(x: Int, y: Int): Int?
    fun findImage(name: String, threshold: Double, region: Region?): MatchResult? = null
    fun findAllImages(name: String, threshold: Double, maxResults: Int, region: Region?): List<MatchResult> = emptyList()
    fun log(message: String)
}

object SandboxedLuaEngine {

    private fun createSandboxedGlobals(bridge: LuaBridge?): Globals {
        val globals = Globals()
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(MathLib())
        globals.load(CoroutineLib())

        // Strip unsafe filesystem / dynamic loader hooks
        globals.set("dofile", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("collectgarbage", LuaValue.NIL)
        val pkg = globals.get("package")
        if (pkg.istable()) {
            pkg.set("loadlib", LuaValue.NIL)
            pkg.set("searchers", LuaValue.NIL)
        }

        if (bridge != null) {
            globals.load(CooperativeAbortDebugLib(bridge))
            registerApi(globals, bridge)
        }

        LoadState.install(globals)
        LuaC.install(globals)
        return globals
    }

    fun validateSyntax(code: String): LuaSyntaxResult {
        if (code.isBlank()) {
            return LuaSyntaxResult(false, "Kode script masih kosong.")
        }
        return try {
            val globals = createSandboxedGlobals(null)
            globals.load(code, "script.lua")
            LuaSyntaxResult(true, "Sintaks Lua valid — siap dijalankan.")
        } catch (e: LuaError) {
            LuaSyntaxResult(false, e.message ?: "Kesalahan sintaks Lua.")
        } catch (e: Exception) {
            LuaSyntaxResult(false, e.message ?: "Gagal memvalidasi script.")
        }
    }

    fun execute(code: String, bridge: LuaBridge): Result<Unit> {
        return try {
            val globals = createSandboxedGlobals(bridge)
            val chunk = globals.load(code, "macrogg_script.lua")
            chunk.call()
            Result.success(Unit)
        } catch (stop: LuaStopSignal) {
            if (stop.graceful) Result.success(Unit) else Result.failure(stop)
        } catch (e: LuaError) {
            val cause = e.cause
            if (cause is LuaStopSignal) {
                if (cause.graceful) Result.success(Unit) else Result.failure(cause)
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseOptionalRegion(args: Varargs, startIdx: Int): Region? {
        if (args.narg() < startIdx + 3) return null
        if (args.isnil(startIdx)) return null
        return Region(
            left = args.checkint(startIdx),
            top = args.checkint(startIdx + 1),
            right = args.checkint(startIdx + 2),
            bottom = args.checkint(startIdx + 3)
        )
    }

    private fun matchToVarargs(match: MatchResult?): Varargs {
        return if (match == null) {
            LuaValue.varargsOf(
                arrayOf(LuaValue.FALSE, LuaValue.valueOf(0), LuaValue.valueOf(0), LuaValue.valueOf(0.0))
            )
        } else {
            LuaValue.varargsOf(
                arrayOf(
                    LuaValue.TRUE,
                    LuaValue.valueOf(match.centerX),
                    LuaValue.valueOf(match.centerY),
                    LuaValue.valueOf(match.score)
                )
            )
        }
    }

    private fun registerApi(globals: Globals, bridge: LuaBridge) {
        // click(x, y, [durationMs])
        globals.set("click", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val x = args.checkint(1)
                val y = args.checkint(2)
                val duration = args.optint(3, 50)
                return LuaBoolean.valueOf(bridge.click(x, y, duration))
            }
        })

        // longClick(x, y, [durationMs])
        globals.set("longClick", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val x = args.checkint(1)
                val y = args.checkint(2)
                val duration = args.optint(3, 600)
                return LuaBoolean.valueOf(bridge.click(x, y, duration))
            }
        })

        // swipe(x1, y1, x2, y2, [durationMs])
        globals.set("swipe", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val x1 = args.checkint(1)
                val y1 = args.checkint(2)
                val x2 = args.checkint(3)
                val y2 = args.checkint(4)
                val duration = args.optint(5, 350)
                return LuaBoolean.valueOf(bridge.swipe(x1, y1, x2, y2, duration))
            }
        })

        // zoomIn(cx, cy, [distancePx=260], [durationMs=400])
        globals.set("zoomIn", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val cx = args.checkint(1)
                val cy = args.checkint(2)
                val dist = args.optint(3, 260)
                val dur = args.optint(4, 400)
                return LuaBoolean.valueOf(bridge.zoom(cx, cy, true, dist, dur))
            }
        })

        // zoomOut(cx, cy, [distancePx=260], [durationMs=400])
        globals.set("zoomOut", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val cx = args.checkint(1)
                val cy = args.checkint(2)
                val dist = args.optint(3, 260)
                val dur = args.optint(4, 400)
                return LuaBoolean.valueOf(bridge.zoom(cx, cy, false, dist, dur))
            }
        })

        // sleep(ms)
        globals.set("sleep", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                checkState(bridge)
                val ms = arg.checklong().coerceIn(0L, 600_000L)
                bridge.sleepCooperative(ms)
                return LuaValue.NIL
            }
        })

        // ---- Image Detection APIs (Macrorify-style) ----

        // findImage(name, [threshold=0.85], [x1, y1, x2, y2]) -> found, cx, cy, score
        globals.set("findImage", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val name = args.checkjstring(1)
                val threshold = args.optdouble(2, 0.85).coerceIn(0.3, 1.0)
                val region = parseOptionalRegion(args, 3)
                val match = bridge.findImage(name, threshold, region)
                return matchToVarargs(match)
            }
        })

        // findAllImages(name, [threshold=0.85], [maxCount=10], [x1, y1, x2, y2]) -> table of {x, y, score}
        globals.set("findAllImages", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val name = args.checkjstring(1)
                val threshold = args.optdouble(2, 0.85).coerceIn(0.3, 1.0)
                val maxCount = args.optint(3, 10).coerceIn(1, 50)
                val region = parseOptionalRegion(args, 4)
                val matches = bridge.findAllImages(name, threshold, maxCount, region)
                val tbl = LuaTable()
                matches.forEachIndexed { idx, m ->
                    val item = LuaTable()
                    item.set("x", LuaValue.valueOf(m.centerX))
                    item.set("y", LuaValue.valueOf(m.centerY))
                    item.set("left", LuaValue.valueOf(m.x))
                    item.set("top", LuaValue.valueOf(m.y))
                    item.set("width", LuaValue.valueOf(m.width))
                    item.set("height", LuaValue.valueOf(m.height))
                    item.set("score", LuaValue.valueOf(m.score))
                    tbl.set(idx + 1, item)
                }
                return tbl
            }
        })

        // waitImage(name, [threshold=0.85], [timeoutMs=5000], [x1, y1, x2, y2]) -> found, cx, cy, score
        globals.set("waitImage", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val name = args.checkjstring(1)
                val threshold = args.optdouble(2, 0.85).coerceIn(0.3, 1.0)
                val timeoutMs = args.optlong(3, 5000L).coerceIn(0L, 600_000L)
                val region = parseOptionalRegion(args, 4)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    checkState(bridge)
                    val match = bridge.findImage(name, threshold, region)
                    if (match != null) return matchToVarargs(match)
                    if (System.currentTimeMillis() >= deadline) return matchToVarargs(null)
                    bridge.sleepCooperative(120L)
                }
            }
        })

        // clickImage(name, [threshold=0.85], [timeoutMs=0], [offsetX=0], [offsetY=0]) -> clicked, cx, cy, score
        globals.set("clickImage", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val name = args.checkjstring(1)
                val threshold = args.optdouble(2, 0.85).coerceIn(0.3, 1.0)
                val timeoutMs = args.optlong(3, 0L).coerceIn(0L, 600_000L)
                val offsetX = args.optint(4, 0)
                val offsetY = args.optint(5, 0)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    checkState(bridge)
                    val match = bridge.findImage(name, threshold, null)
                    if (match != null) {
                        val tx = (match.centerX + offsetX).coerceAtLeast(0)
                        val ty = (match.centerY + offsetY).coerceAtLeast(0)
                        val ok = bridge.click(tx, ty, 50)
                        return LuaValue.varargsOf(
                            arrayOf(
                                LuaBoolean.valueOf(ok),
                                LuaValue.valueOf(tx),
                                LuaValue.valueOf(ty),
                                LuaValue.valueOf(match.score)
                            )
                        )
                    }
                    if (System.currentTimeMillis() >= deadline) return matchToVarargs(null)
                    bridge.sleepCooperative(120L)
                }
            }
        })

        // ---- Color Detection APIs ----

        // getColor(x, y) -> hex, r, g, b
        globals.set("getColor", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val x = args.checkint(1)
                val y = args.checkint(2)
                val argb = bridge.getColorArgb(x, y)
                    ?: return LuaValue.varargsOf(
                        arrayOf(LuaValue.NIL, LuaValue.valueOf(0), LuaValue.valueOf(0), LuaValue.valueOf(0))
                    )
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val hex = ColorUtil.toHex(argb)
                return LuaValue.varargsOf(
                    arrayOf(LuaValue.valueOf(hex), LuaValue.valueOf(r), LuaValue.valueOf(g), LuaValue.valueOf(b))
                )
            }
        })

        // matchColor(x, y, hex, [tolerance]) -> boolean
        globals.set("matchColor", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val x = args.checkint(1)
                val y = args.checkint(2)
                val hex = args.checkjstring(3)
                val tolerance = args.optint(4, 15)
                val argb = bridge.getColorArgb(x, y) ?: return LuaValue.FALSE
                return LuaBoolean.valueOf(ColorUtil.matches(argb, hex, tolerance))
            }
        })

        // waitForColor(x, y, hex, [tolerance], [timeoutMs]) -> boolean
        globals.set("waitForColor", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val x = args.checkint(1)
                val y = args.checkint(2)
                val hex = args.checkjstring(3)
                val tolerance = args.optint(4, 15)
                val timeoutMs = args.optlong(5, 5000L).coerceIn(0L, 600_000L)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    checkState(bridge)
                    val argb = bridge.getColorArgb(x, y)
                    if (argb != null && ColorUtil.matches(argb, hex, tolerance)) {
                        return LuaValue.TRUE
                    }
                    if (System.currentTimeMillis() >= deadline) return LuaValue.FALSE
                    bridge.sleepCooperative(100L)
                }
            }
        })

        // getScreenSize() -> width, height
        globals.set("getScreenSize", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs =
                LuaValue.varargsOf(LuaValue.valueOf(bridge.screenWidth), LuaValue.valueOf(bridge.screenHeight))
        })

        // log(...) & print(...)
        val logFn = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val parts = (1..args.narg()).map { idx -> args.arg(idx).tojstring() }
                bridge.log(parts.joinToString(" "))
                return LuaValue.NIL
            }
        }
        globals.set("log", logFn)
        globals.set("print", logFn)

        globals.set("isRunning", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaBoolean.valueOf(!bridge.isStopped())
        })

        globals.set("stop", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                throw LuaStopSignal(graceful = true)
            }
        })
    }

    private fun checkState(bridge: LuaBridge) {
        while (bridge.isPaused() && !bridge.isStopped()) {
            try {
                Thread.sleep(30L)
            } catch (_: InterruptedException) {
                throw LuaStopSignal(graceful = false)
            }
        }
        if (bridge.isStopped()) {
            throw LuaStopSignal(graceful = false)
        }
    }

    private class CooperativeAbortDebugLib(
        private val bridge: LuaBridge
    ) : DebugLib() {
        private var tickCounter = 0

        override fun onInstruction(pc: Int, v: Varargs?, top: Int) {
            tickCounter++
            if (tickCounter >= 100) {
                tickCounter = 0
                checkState(bridge)
            }
            super.onInstruction(pc, v, top)
        }
    }
}
