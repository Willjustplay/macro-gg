package com.example.automation

import kotlinx.coroutines.runBlocking
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaBoolean
import org.luaj.vm2.LuaError
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
import org.luaj.vm2.lib.TwoArgFunction
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
    fun sleepCooperative(ms: Long)
    fun getColorArgb(x: Int, y: Int): Int?
    fun log(message: String)
}

object SandboxedLuaEngine {

    /**
     * Builds a strictly sandboxed Lua 5.2 Globals environment:
     * - Allows pure computation libraries: base, table, string, math, bit32, coroutine
     * - Explicitly omits LuajavaLib, OsLib, and IoLib (no reflection, shell, or file access)
     * - Strips dofile/loadfile/package.loadlib
     */
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
            registerTapLuaApi(globals, bridge)
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
            val chunk = globals.load(code, "taplua_script.lua")
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

    private fun registerTapLuaApi(globals: Globals, bridge: LuaBridge) {
        // click(x, y, [durationMs])
        globals.set("click", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val x = args.checkint(1)
                val y = args.checkint(2)
                val duration = args.optint(3, 60)
                val ok = bridge.click(x, y, duration)
                return LuaBoolean.valueOf(ok)
            }
        })

        // longClick(x, y, [durationMs])
        globals.set("longClick", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                checkState(bridge)
                val x = args.checkint(1)
                val y = args.checkint(2)
                val duration = args.optint(3, 600)
                val ok = bridge.click(x, y, duration)
                return LuaBoolean.valueOf(ok)
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
                val ok = bridge.swipe(x1, y1, x2, y2, duration)
                return LuaBoolean.valueOf(ok)
            }
        })

        // sleep(ms)
        globals.set("sleep", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                checkState(bridge)
                val ms = arg.checklong().coerceIn(0L, 600000L)
                bridge.sleepCooperative(ms)
                return LuaValue.NIL
            }
        })

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
                val hex = String.format("#%02X%02X%02X", r, g, b)
                return LuaValue.varargsOf(
                    arrayOf(
                        LuaValue.valueOf(hex),
                        LuaValue.valueOf(r),
                        LuaValue.valueOf(g),
                        LuaValue.valueOf(b)
                    )
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
                val matched = ScreenColorSamplerService.colorMatches(argb, hex, tolerance)
                return LuaBoolean.valueOf(matched)
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
                val timeoutMs = args.optlong(5, 5000L).coerceIn(100L, 600000L)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() <= deadline) {
                    checkState(bridge)
                    val argb = bridge.getColorArgb(x, y)
                    if (argb != null && ScreenColorSamplerService.colorMatches(argb, hex, tolerance)) {
                        return LuaValue.TRUE
                    }
                    bridge.sleepCooperative(100L)
                }
                return LuaValue.FALSE
            }
        })

        // getScreenSize() -> width, height
        globals.set("getScreenSize", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                return LuaValue.varargsOf(
                    LuaValue.valueOf(bridge.screenWidth),
                    LuaValue.valueOf(bridge.screenHeight)
                )
            }
        })

        // log(message...) & print(message...)
        val logFn = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val parts = (1..args.narg()).map { idx -> args.arg(idx).tojstring() }
                bridge.log(parts.joinToString(" "))
                return LuaValue.NIL
            }
        }
        globals.set("log", logFn)
        globals.set("print", logFn)

        // isRunning() -> boolean
        globals.set("isRunning", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaBoolean.valueOf(!bridge.isStopped())
        })

        // stop() -> graceful exit
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
