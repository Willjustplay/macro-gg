package com.example

import com.example.automation.LogLevel
import com.example.automation.MacroRunner
import com.example.automation.MacroRuntime
import com.example.automation.RecentsDismissGeometry
import com.example.data.ClickAction
import com.example.data.ExitAppAction
import com.example.data.ExitAppMode
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.RecentsSwipeDirection
import com.example.data.RecordedGesture
import com.example.data.ResolutionScaleMode
import com.example.data.TrackInteractionMode
import com.example.data.TrackObjectAction
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import com.example.vision.TemplateMatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class TrackObjectAndLandscapeExitTest {

    /**
     * Renders a 32x32 asymmetric sprite (distinct left/right shape + inner core) centered at ([cx], [cy])
     * with an optional [mirrorX] flip and configurable background luma.
     */
    private fun renderMovingSpriteFrame(
        width: Int,
        height: Int,
        cx: Int?,
        cy: Int?,
        bgLuma: Int = 25,
        mirrorX: Boolean = false,
        texturedBg: Boolean = false
    ): Frame {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val bg = if (texturedBg) ((bgLuma + ((x * 7 + y * 13) % 40)).coerceIn(0, 255)) else bgLuma
                pixels[y * width + x] = (0xFF shl 24) or (bg shl 16) or (bg shl 8) or bg
            }
        }
        if (cx != null && cy != null) {
            val size = 32
            val left = cx - size / 2
            val top = cy - size / 2
            for (dy in 0 until size) {
                for (dx in 0 until size) {
                    val sx = left + dx
                    val sy = top + dy
                    if (sx !in 0 until width || sy !in 0 until height) continue
                    val lx = if (mirrorX) (size - 1 - dx) else dx
                    // Inner 20x20 sprite body sits inside [6..25]; outer 6px margin stays background
                    val inBody = lx in 6..25 && dy in 6..25
                    if (!inBody) continue
                    val luma = when {
                        // Left bright arrow bar (strongly asymmetric left vs right)
                        lx in 8..12 && dy in 9..22 -> 245
                        // Center dark diagonal stripe
                        abs((lx - 15) - (dy - 15)) <= 1 -> 20
                        // Right-top bright dot
                        lx in 19..23 && dy in 9..13 -> 230
                        // Right-bottom mid block
                        lx in 18..23 && dy in 18..22 -> 145
                        else -> 80
                    }
                    pixels[sy * width + sx] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                }
            }
        }
        return Frame(width, height, pixels)
    }

    private fun captureSpriteTemplate(bgLuma: Int = 210): PreparedTemplate {
        val f = renderMovingSpriteFrame(80, 80, cx = 40, cy = 40, bgLuma = bgLuma, mirrorX = false)
        return PreparedTemplate("sprite.png", f.crop(40 - 16, 40 - 16, 32, 32))
    }

    @Test
    fun recentsDismissGeometryAdaptsToLandscapeAndPortraitWithoutSidewaysSwipe() {
        // 1. Portrait -> Portrait: standard vertical swipe UP (-Y)
        val p2p = RecentsDismissGeometry.computeSwipe(
            beforeRotation = RecentsDismissGeometry.ROTATION_0, beforeWidth = 1080, beforeHeight = 2400,
            afterRotation = RecentsDismissGeometry.ROTATION_0, afterWidth = 1080, afterHeight = 2400,
            sdkInt = 34, direction = RecentsSwipeDirection.AUTO
        )
        assertEquals(p2p.x1, p2p.x2, 1f)
        assertTrue("Portrait must swipe UP (-Y)", p2p.y2 < p2p.y1 - 800f)

        // 2. Landscape ROTATION_90 -> Portrait ROTATION_0 on Android 11+ (Quickstep RecentsOrientedState):
        // Visual UP on the rotated landscape card is +X (Portrait left -> Portrait right), NOT -Y!
        val l90ToP = RecentsDismissGeometry.computeSwipe(
            beforeRotation = RecentsDismissGeometry.ROTATION_90, beforeWidth = 2400, beforeHeight = 1080,
            afterRotation = RecentsDismissGeometry.ROTATION_0, afterWidth = 1080, afterHeight = 2400,
            sdkInt = 34, direction = RecentsSwipeDirection.AUTO
        )
        assertEquals("Landscape90->Portrait should swipe along X (fixed Y)", l90ToP.y1, l90ToP.y2, 1f)
        assertTrue("Landscape90->Portrait visual UP must be +X", l90ToP.x2 > l90ToP.x1 + 400f)

        // 3. Reverse Landscape ROTATION_270 -> Portrait ROTATION_0 on Android 11+:
        // Visual UP on the rotated landscape card is -X (Portrait right -> Portrait left).
        val l270ToP = RecentsDismissGeometry.computeSwipe(
            beforeRotation = RecentsDismissGeometry.ROTATION_270, beforeWidth = 2400, beforeHeight = 1080,
            afterRotation = RecentsDismissGeometry.ROTATION_0, afterWidth = 1080, afterHeight = 2400,
            sdkInt = 35, direction = RecentsSwipeDirection.AUTO
        )
        assertEquals("Landscape270->Portrait should swipe along X (fixed Y)", l270ToP.y1, l270ToP.y2, 1f)
        assertTrue("Landscape270->Portrait visual UP must be -X", l270ToP.x2 < l270ToP.x1 - 400f)

        // 4. Native Landscape -> Landscape (Auto-Rotate ON / tablet): display stays landscape -> swipe UP (-Y)
        val l2l = RecentsDismissGeometry.computeSwipe(
            beforeRotation = RecentsDismissGeometry.ROTATION_90, beforeWidth = 2400, beforeHeight = 1080,
            afterRotation = RecentsDismissGeometry.ROTATION_90, afterWidth = 2400, afterHeight = 1080,
            sdkInt = 34, direction = RecentsSwipeDirection.AUTO
        )
        assertEquals(l2l.x1, l2l.x2, 1f)
        assertTrue("Native Landscape Recents must swipe UP (-Y)", l2l.y2 < l2l.y1 - 400f)

        // 5. Android 10 (API 29) without Quickstep RecentsOrientedState: cards rotate upright -> swipe UP (-Y)
        val api29 = RecentsDismissGeometry.computeSwipe(
            beforeRotation = RecentsDismissGeometry.ROTATION_90, beforeWidth = 2400, beforeHeight = 1080,
            afterRotation = RecentsDismissGeometry.ROTATION_0, afterWidth = 1080, afterHeight = 2400,
            sdkInt = 29, direction = RecentsSwipeDirection.AUTO
        )
        assertEquals(api29.x1, api29.x2, 1f)
        assertTrue("API 29 fallback must swipe UP (-Y)", api29.y2 < api29.y1 - 800f)

        // 6. Manual overrides
        val manualRight = RecentsDismissGeometry.computeSwipe(0, 1080, 2400, 0, 1080, 2400, 34, RecentsSwipeDirection.SWIPE_RIGHT)
        assertTrue(manualRight.x2 > manualRight.x1 + 400f)
        val manualLeft = RecentsDismissGeometry.computeSwipe(0, 1080, 2400, 0, 1080, 2400, 34, RecentsSwipeDirection.SWIPE_LEFT)
        assertTrue(manualLeft.x2 < manualLeft.x1 - 400f)
    }

    @Test
    fun preparedTemplateMirrorXAndCenterFocusMatchFlippedObjectOnChangingBackground() {
        // Template captured on bright white-ish background (215)
        val tpl = captureSpriteTemplate(bgLuma = 215)
        // Live game frame has the object flipped horizontally on a dark textured background (20..60)
        val liveFrame = renderMovingSpriteFrame(320, 200, cx = 210, cy = 115, bgLuma = 20, mirrorX = true, texturedBg = true)

        val coreMirrored = tpl.centerFocused().mirroredX()
        val hit = TemplateMatcher.find(liveFrame, coreMirrored, threshold = 0.80)
        assertNotNull("Mirrored + center-focused template must locate flipped sprite on dark textured bg", hit)
        assertTrue("Expected score >= 0.90, got ${hit!!.score}", hit.score >= 0.90)
        assertTrue("Center X should match 210, got ${hit.centerX}", abs(hit.centerX - 210) <= 2)
        assertTrue("Center Y should match 115, got ${hit.centerY}", abs(hit.centerY - 115) <= 2)
    }

    @Test
    fun trackObjectTapOnceLeadsMovingTargetWithVelocityPrediction() = runTest {
        val tpl = captureSpriteTemplate(bgLuma = 200)
        var clockMs = 1000L
        var frameIdx = 0
        val clicks = mutableListOf<Pair<Int, Int>>()
        val boostEvents = mutableListOf<Boolean>()

        // Frame 0 (t=1000ms): sprite at (120, 140); Frame 1 (t=1045ms): sprite moved +36px to (156, 140) -> vx = +0.8 px/ms
        val frames = listOf(
            renderMovingSpriteFrame(420, 260, cx = 120, cy = 140, bgLuma = 30, mirrorX = false),
            renderMovingSpriteFrame(420, 260, cx = 156, cy = 140, bgLuma = 30, mirrorX = false)
        )

        val runtime = object : MacroRuntime {
            override val screenWidth = 420
            override val screenHeight = 260
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) { clockMs += ms }
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun setTrackingBoost(enabled: Boolean) { boostEvents += enabled }
            override fun captureFrame(): Frame {
                val f = frames[frameIdx.coerceAtMost(frames.lastIndex)]
                frameIdx++
                return f
            }
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String) = if (name == "sprite.png") tpl else null
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs() = clockMs
        }

        val macro = Macro(
            name = "Chase Lead Test",
            sourceWidth = 420,
            sourceHeight = 260,
            autoScaleResolution = false,
            actions = listOf(
                TrackObjectAction(
                    imageName = "sprite.png",
                    threshold = 0.78f,
                    timeoutMs = 1000L,
                    interactionMode = TrackInteractionMode.TAP_ONCE,
                    leadPredictionMs = 100L,
                    matchMirrorX = true,
                    ignoreBackgroundEdges = true,
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(runtime).run(macro)

        assertEquals("Tracking boost should turn on then off cleanly", listOf(true, false), boostEvents)
        assertEquals("Should tap once", 1, clicks.size)
        // vx = 36 / 45 = 0.8 px/ms; lead 100ms -> +80px ahead of 156 = 236
        val (tx, ty) = clicks.first()
        assertTrue("Expected predicted lead X around 236 (±6), got $tx", abs(tx - 236) <= 6)
        assertTrue("Expected Y around 140 (±3), got $ty", abs(ty - 140) <= 3)
    }

    @Test
    fun trackObjectContinuousChasesMovingTargetAcrossScreenUntilDisappeared() = runTest {
        val tpl = captureSpriteTemplate(bgLuma = 210)
        var clockMs = 0L
        var frameIdx = 0
        val clicks = mutableListOf<Pair<Int, Int>>()

        // Object moves across 4 frames (including mirrored!) then disappears on remaining frames
        val positions = listOf(
            Triple(80, 100, false),
            Triple(130, 110, false),
            Triple(185, 120, true),
            Triple(245, 130, true),
            Triple(295, 135, true)
        )

        val runtime = object : MacroRuntime {
            override val screenWidth = 420
            override val screenHeight = 260
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) { clockMs += ms }
            override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                return true
            }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame(): Frame {
                val p = positions.getOrNull(frameIdx++)
                return if (p != null) {
                    renderMovingSpriteFrame(420, 260, cx = p.first, cy = p.second, bgLuma = 35, mirrorX = p.third)
                } else {
                    renderMovingSpriteFrame(420, 260, cx = null, cy = null, bgLuma = 35)
                }
            }
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String) = if (name == "sprite.png") tpl else null
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs() = clockMs
        }

        val macro = Macro(
            name = "Continuous Chase",
            sourceWidth = 420,
            sourceHeight = 260,
            autoScaleResolution = false,
            actions = listOf(
                TrackObjectAction(
                    imageName = "sprite.png",
                    threshold = 0.78f,
                    timeoutMs = 1000L,
                    interactionMode = TrackInteractionMode.TAP_CONTINUOUS,
                    repeatIntervalMs = 45L,
                    maxTrackDurationMs = 5000L,
                    leadPredictionMs = 0L,
                    matchMirrorX = true,
                    ignoreBackgroundEdges = true,
                    thenActions = listOf(ClickAction(x = 12, y = 12, delayAfterMs = 0L)),
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(runtime).run(macro)

        // Expect multiple chase taps moving rightward + final thenActions click at (12, 12)
        assertTrue("Expected at least 3 chase clicks + 1 thenAction click, got $clicks", clicks.size >= 4)
        assertEquals("thenActions click should run after chase finishes", 12 to 12, clicks.last())
        val chaseClicks = clicks.dropLast(1)
        assertTrue("Chase taps should follow rightward movement: $chaseClicks", chaseClicks.last().first > chaseClicks.first().first + 80)
    }

    @Test
    fun trackObjectDragModesExecuteAccurateSwipes() = runTest {
        val tpl = captureSpriteTemplate(bgLuma = 205)
        val liveFrame = renderMovingSpriteFrame(400, 300, cx = 140, cy = 120, bgLuma = 30, mirrorX = false)
        val swipes = mutableListOf<List<Int>>()
        var clockMs = 0L

        val runtime = object : MacroRuntime {
            override val screenWidth = 400
            override val screenHeight = 300
            override fun isStopped() = false
            override suspend fun waitIfPaused() {}
            override suspend fun sleep(ms: Long) { clockMs += ms }
            override suspend fun click(x: Int, y: Int, durationMs: Long) = true
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
                swipes += listOf(x1, y1, x2, y2, durationMs.toInt())
                return true
            }
            override suspend fun playRecordedGesture(gesture: RecordedGesture, speed: Float) = true
            override fun captureFrame() = liveFrame
            override fun samplePixelArgb(x: Int, y: Int): Int? = null
            override fun loadTemplate(name: String) = if (name == "sprite.png") tpl else null
            override fun log(level: LogLevel, message: String) {}
            override fun nowMs() = clockMs
        }

        val macro = Macro(
            name = "Drag Object Test",
            sourceWidth = 400,
            sourceHeight = 300,
            autoScaleResolution = false,
            actions = listOf(
                TrackObjectAction(
                    imageName = "sprite.png",
                    interactionMode = TrackInteractionMode.DRAG_TO_POINT,
                    targetX = 350,
                    targetY = 260,
                    dragDurationMs = 300L,
                    leadPredictionMs = 0L,
                    delayAfterMs = 0L
                ),
                TrackObjectAction(
                    imageName = "sprite.png",
                    interactionMode = TrackInteractionMode.DRAG_BY_OFFSET,
                    dragOffsetX = 60,
                    dragOffsetY = -50,
                    dragDurationMs = 220L,
                    leadPredictionMs = 0L,
                    delayAfterMs = 0L
                ),
                TrackObjectAction(
                    imageName = "sprite.png",
                    interactionMode = TrackInteractionMode.DRAG_FROM_POINT_TO_OBJECT,
                    targetX = 40,
                    targetY = 250,
                    dragDurationMs = 280L,
                    leadPredictionMs = 0L,
                    delayAfterMs = 0L
                )
            )
        )

        MacroRunner(runtime).run(macro)

        assertEquals(3, swipes.size)
        // 1. DRAG_TO_POINT: from object (140, 120) -> (350, 260)
        assertTrue(abs(swipes[0][0] - 140) <= 2 && abs(swipes[0][1] - 120) <= 2)
        assertEquals(350, swipes[0][2])
        assertEquals(260, swipes[0][3])
        assertEquals(300, swipes[0][4])

        // 2. DRAG_BY_OFFSET: from object (140, 120) -> (200, 70)
        assertTrue(abs(swipes[1][0] - 140) <= 2 && abs(swipes[1][1] - 120) <= 2)
        assertTrue(abs(swipes[1][2] - 200) <= 2 && abs(swipes[1][3] - 70) <= 2)

        // 3. DRAG_FROM_POINT_TO_OBJECT: from anchor (40, 250) -> object (140, 120)
        assertEquals(40, swipes[2][0])
        assertEquals(250, swipes[2][1])
        assertTrue(abs(swipes[2][2] - 140) <= 2 && abs(swipes[2][3] - 120) <= 2)
    }

    @Test
    fun zipBundleRoundTripPreservesTrackObjectExtraFramesAndExitSwipeDirection() {
        val macro = Macro(
            name = "Track + Landscape Exit Bundle",
            sourceWidth = 2400,
            sourceHeight = 1080,
            scaleMode = ResolutionScaleMode.ADAPTIVE,
            actions = listOf(
                TrackObjectAction(
                    imageName = "mob_1.png",
                    extraImageNames = listOf("mob_2.png", "mob_3.png"),
                    interactionMode = TrackInteractionMode.DRAG_TO_POINT,
                    targetX = 1800,
                    targetY = 500,
                    leadPredictionMs = 120L,
                    matchMirrorX = true,
                    ignoreBackgroundEdges = true
                ),
                ExitAppAction(
                    mode = ExitAppMode.CLOSE_RECENT_AND_HOME,
                    packageName = "com.supercell.clashofclans",
                    appLabel = "Clash of Clans",
                    recentsSwipeDirection = RecentsSwipeDirection.SWIPE_RIGHT
                )
            )
        )

        val images = mapOf(
            "mob_1.png" to byteArrayOf(1, 2, 3, 4),
            "mob_2.png" to byteArrayOf(5, 6, 7, 8),
            "mob_3.png" to byteArrayOf(9, 10, 11, 12)
        )
        val out = ByteArrayOutputStream()
        val exported = MacroBundleCodec.writeMacroZip(
            root = macro,
            lookupMacro = { null },
            loadImage = { name -> images[name]?.let { name to it } },
            output = out
        )
        assertEquals(1, exported.macrosExported)
        assertEquals(3, exported.imagesExported)

        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, decoded.macros.size)
        assertEquals("All 3 animation frame PNGs must be bundled", setOf("mob_1.png", "mob_2.png", "mob_3.png"), decoded.images.keys)

        val restoredTrack = decoded.macros.first().actions[0] as TrackObjectAction
        assertEquals("mob_1.png", restoredTrack.imageName)
        assertEquals(listOf("mob_2.png", "mob_3.png"), restoredTrack.extraImageNames)
        assertEquals(TrackInteractionMode.DRAG_TO_POINT, restoredTrack.interactionMode)
        assertEquals(1800, restoredTrack.targetX)
        assertEquals(500, restoredTrack.targetY)
        assertEquals(120L, restoredTrack.leadPredictionMs)

        val restoredExit = decoded.macros.first().actions[1] as ExitAppAction
        assertEquals(RecentsSwipeDirection.SWIPE_RIGHT, restoredExit.recentsSwipeDirection)
        assertFalse(restoredExit.packageName.isBlank())
    }
}
