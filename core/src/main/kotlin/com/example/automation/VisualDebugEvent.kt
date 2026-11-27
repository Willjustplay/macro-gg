package com.example.automation

import com.example.data.Region
import com.example.vision.OcrBlock

/**
 * Real-time visual debugging telemetry emitted by [MacroRunner] ONLY when the user explicitly turns on
 * Live Visual Debugger in Wjust macro (`:app`). Intentionally disabled in Wjust Player (`.wjm`).
 */
sealed class VisualDebugEvent {
    data class ImageCheck(
        val label: String,
        val found: Boolean,
        val score: Float,
        val threshold: Float,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val scanRegion: Region? = null
    ) : VisualDebugEvent()

    data class OcrCheck(
        val label: String,
        val matched: Boolean,
        val readPreview: String,
        val region: Region? = null,
        val blocks: List<OcrBlock> = emptyList()
    ) : VisualDebugEvent()

    data class TapPoint(
        val x: Int,
        val y: Int,
        val label: String = "",
        val humanized: Boolean = false
    ) : VisualDebugEvent()

    data class SwipePath(
        val x1: Int,
        val y1: Int,
        val cx: Int,
        val cy: Int,
        val x2: Int,
        val y2: Int,
        val curved: Boolean,
        val label: String = ""
    ) : VisualDebugEvent()

    data class BannedBlocked(
        val x: Int,
        val y: Int,
        val imageName: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) : VisualDebugEvent()

    data class ColorCheck(
        val x: Int,
        val y: Int,
        val matched: Boolean,
        val actualHex: String,
        val targetHex: String
    ) : VisualDebugEvent()
}
