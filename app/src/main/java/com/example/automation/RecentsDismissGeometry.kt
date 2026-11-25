package com.example.automation

import com.example.data.RecentsSwipeDirection

/**
 * Pure-Kotlin geometry helper that computes the exact Accessibility swipe vector to dismiss the
 * foreground app card in Android's Recent Apps screen across both Portrait and Landscape apps.
 *
 * Why landscape used to swipe sideways:
 * On Android 11+ (API 30+, Quickstep / RecentsOrientedState — Pixel, Samsung, Xiaomi, Oppo, Vivo,
 * Redfinger, etc.), pressing Recents from a Landscape app (ROTATION_90 or ROTATION_270) when
 * Auto-Rotate is off snaps the logical display back to Portrait (ROTATION_0, width < height) while
 * keeping the Recent App cards visually oriented in Landscape! Swiping along the Portrait Y-axis
 * therefore moves visually SIDEWAYS across the carousel instead of UP to dismiss the card.
 *
 * Measuring rotation & dimensions BEFORE and AFTER opening Recents lets us rotate the dismiss
 * vector by `delta = (beforeRotation - afterRotation + 4) % 4` so the gesture always swipes
 * visually UP on the active card.
 */
object RecentsDismissGeometry {

    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    data class SwipeCoords(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val description: String
    )

    fun computeSwipe(
        beforeRotation: Int,
        beforeWidth: Int,
        beforeHeight: Int,
        afterRotation: Int,
        afterWidth: Int,
        afterHeight: Int,
        sdkInt: Int,
        direction: RecentsSwipeDirection = RecentsSwipeDirection.AUTO
    ): SwipeCoords {
        val sw = afterWidth.coerceAtLeast(100).toFloat()
        val sh = afterHeight.coerceAtLeast(100).toFloat()

        return when (direction) {
            RecentsSwipeDirection.SWIPE_UP -> SwipeCoords(
                x1 = sw * 0.50f,
                y1 = sh * 0.70f,
                x2 = sw * 0.50f,
                y2 = sh * 0.04f,
                description = "manual-atas"
            )
            RecentsSwipeDirection.SWIPE_RIGHT -> SwipeCoords(
                x1 = sw * 0.30f,
                y1 = sh * 0.50f,
                x2 = sw * 0.96f,
                y2 = sh * 0.50f,
                description = "manual-kanan"
            )
            RecentsSwipeDirection.SWIPE_LEFT -> SwipeCoords(
                x1 = sw * 0.70f,
                y1 = sh * 0.50f,
                x2 = sw * 0.04f,
                y2 = sh * 0.50f,
                description = "manual-kiri"
            )
            RecentsSwipeDirection.AUTO -> computeAutoSwipe(
                beforeRotation = beforeRotation,
                beforeWidth = beforeWidth,
                beforeHeight = beforeHeight,
                afterRotation = afterRotation,
                sw = sw,
                sh = sh,
                sdkInt = sdkInt
            )
        }
    }

    private fun computeAutoSwipe(
        beforeRotation: Int,
        beforeWidth: Int,
        beforeHeight: Int,
        afterRotation: Int,
        sw: Float,
        sh: Float,
        sdkInt: Int
    ): SwipeCoords {
        val wasLandscape = beforeWidth > beforeHeight
        val isNowPortrait = sw < sh
        val bRot = ((beforeRotation % 4) + 4) % 4
        val aRot = ((afterRotation % 4) + 4) % 4

        // Android 11+ (API 30+) Quickstep RecentsOrientedState keeps cards visually rotated in
        // landscape when entering Recents from a landscape app on a portrait-locked launcher.
        if (sdkInt >= 30 && wasLandscape && isNowPortrait && bRot != aRot) {
            val delta = (bRot - aRot + 4) % 4
            return when (delta) {
                // ROTATION_90 -> ROTATION_0: visual TOP of landscape card is Portrait right edge (+X).
                1 -> SwipeCoords(
                    x1 = sw * 0.30f,
                    y1 = sh * 0.50f,
                    x2 = sw * 0.96f,
                    y2 = sh * 0.50f,
                    description = "auto-lanskap90→potret (+X)"
                )
                // ROTATION_270 -> ROTATION_0: visual TOP of landscape card is Portrait left edge (-X).
                3 -> SwipeCoords(
                    x1 = sw * 0.70f,
                    y1 = sh * 0.50f,
                    x2 = sw * 0.04f,
                    y2 = sh * 0.50f,
                    description = "auto-lanskap270→potret (-X)"
                )
                2 -> SwipeCoords(
                    x1 = sw * 0.50f,
                    y1 = sh * 0.30f,
                    x2 = sw * 0.50f,
                    y2 = sh * 0.96f,
                    description = "auto-180 (+Y)"
                )
                else -> SwipeCoords(
                    x1 = sw * 0.50f,
                    y1 = sh * 0.70f,
                    x2 = sw * 0.50f,
                    y2 = sh * 0.04f,
                    description = "auto-atas (-Y)"
                )
            }
        }

        // Same orientation (Portrait->Portrait or native Landscape->Landscape) or pre-Android 11
        // where Recents rotates cards upright to match the display orientation: swipe UP (-Y).
        return SwipeCoords(
            x1 = sw * 0.50f,
            y1 = sh * 0.70f,
            x2 = sw * 0.50f,
            y2 = sh * 0.04f,
            description = if (sw > sh) "auto-lanskap-asli (-Y)" else "auto-potret (-Y)"
        )
    }

    fun rotationDegrees(rotation: Int): Int = (((rotation % 4) + 4) % 4) * 90
}
