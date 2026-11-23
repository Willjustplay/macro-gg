package com.example.vision

import com.example.data.Region
import com.example.data.TextAction
import com.example.data.TextCompareMode
import kotlin.math.abs

/** A recognized OCR text element with its real-screen bounding rectangle. */
data class OcrBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    fun toRegion(): Region = Region(left, top, right, bottom)
}

/** Result of evaluating a [TextAction] against OCR blocks. */
data class TextMatchOutcome(
    val matched: Boolean,
    val matchedText: String = "",
    val parsedNumber: Double? = null,
    val allText: String = "",
    val allNumbers: List<Double> = emptyList(),
    val matchedBlock: OcrBlock? = null
)

/**
 * Pure-Kotlin evaluator for [TextAction] supporting:
 * - Case-insensitive text contains / exact match
 * - Numeric comparison (<, <=, >, >=, =) with smart parsing of integers, decimals,
 *   thousand separators (1,250 or 1.250), negative values, and K/M/B suffixes (e.g. 12.5K).
 */
object TextConditionEvaluator {

    private val numberRegex = Regex("""-?\d[\d.,]*\s*[KkMmBb]?""")

    fun extractNumbers(raw: String): List<Double> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Double>()
        for (m in numberRegex.findAll(raw)) {
            parseSmartNumber(m.value)?.let { out += it }
        }
        return out
    }

    fun parseSmartNumber(token: String): Double? {
        var s = token.trim().replace(" ", "")
        if (s.isEmpty()) return null

        var multiplier = 1.0
        when (s.last().uppercaseChar()) {
            'K' -> { multiplier = 1_000.0; s = s.dropLast(1) }
            'M' -> { multiplier = 1_000_000.0; s = s.dropLast(1) }
            'B' -> { multiplier = 1_000_000_000.0; s = s.dropLast(1) }
        }
        if (s.isEmpty() || s == "-") return null

        val normalized = normalizeDecimalSeparators(s)
        val base = normalized.toDoubleOrNull() ?: return null
        return base * multiplier
    }

    private fun normalizeDecimalSeparators(raw: String): String {
        val sign = if (raw.startsWith("-")) "-" else ""
        val body = if (sign.isNotEmpty()) raw.drop(1) else raw
        val hasComma = ',' in body
        val hasDot = '.' in body

        val clean = when {
            hasComma && hasDot -> {
                // Whichever separator appears last is the decimal separator (e.g. 1,234.56 or 1.234,56)
                if (body.lastIndexOf(',') > body.lastIndexOf('.')) {
                    body.replace(".", "").replace(',', '.')
                } else {
                    body.replace(",", "")
                }
            }
            hasComma -> {
                val parts = body.split(',')
                if (parts.size == 2 && parts[1].length != 3) {
                    // Decimal comma (e.g. 12,5 or 0,75)
                    body.replace(',', '.')
                } else if (parts.drop(1).all { it.length == 3 }) {
                    // Thousand separator (e.g. 1,250 or 1,000,000)
                    body.replace(",", "")
                } else {
                    body.replace(',', '.')
                }
            }
            hasDot -> {
                val parts = body.split('.')
                if (parts.size > 2 && parts.drop(1).all { it.length == 3 }) {
                    // Indonesian/European thousand dots (e.g. 1.250.000)
                    body.replace(".", "")
                } else if (parts.size == 2 && parts[1].length == 3 && parts[0].length <= 3 && parts[0] != "0") {
                    // Ambiguous like "1.250" vs "0.125": if leading part is 0 it's decimal; otherwise treat 3-digit group >=1 as thousand only when >1 group? Keep standard decimal for single dot unless multi-dot, so "1.500" -> wait: in games "1.250" can be thousand or decimal. Standard float notation uses '.' as decimal unless multiple dots.
                    body
                } else {
                    body
                }
            }
            else -> body
        }
        return sign + clean
    }

    fun evaluate(action: TextAction, blocks: List<OcrBlock>): TextMatchOutcome {
        val joinedText = blocks.joinToString(" ") { it.text.trim() }.trim()
        val allNums = blocks.flatMap { extractNumbers(it.text) }

        if (!action.compareMode.isNumeric) {
            val needle = action.query.trim()
            if (needle.isEmpty()) {
                return TextMatchOutcome(matched = false, allText = joinedText, allNumbers = allNums)
            }
            // Prefer matching a specific OCR block so clickOnFound hits the exact word/line
            for (b in blocks) {
                val t = b.text.trim()
                val hit = when (action.compareMode) {
                    TextCompareMode.CONTAINS -> t.contains(needle, ignoreCase = true)
                    TextCompareMode.EQUALS -> t.equals(needle, ignoreCase = true)
                    else -> false
                }
                if (hit) {
                    return TextMatchOutcome(
                        matched = true,
                        matchedText = t,
                        allText = joinedText,
                        allNumbers = allNums,
                        matchedBlock = b
                    )
                }
            }
            // Fallback against joined multi-block text
            val fallbackHit = when (action.compareMode) {
                TextCompareMode.CONTAINS -> joinedText.contains(needle, ignoreCase = true)
                TextCompareMode.EQUALS -> joinedText.equals(needle, ignoreCase = true)
                else -> false
            }
            return TextMatchOutcome(
                matched = fallbackHit,
                matchedText = if (fallbackHit) joinedText else "",
                allText = joinedText,
                allNumbers = allNums,
                matchedBlock = if (fallbackHit) blocks.firstOrNull() else null
            )
        } else {
            val target = action.targetNumber
            for (b in blocks) {
                val nums = extractNumbers(b.text)
                for (n in nums) {
                    if (compareNumber(n, target, action.compareMode)) {
                        return TextMatchOutcome(
                            matched = true,
                            matchedText = b.text.trim(),
                            parsedNumber = n,
                            allText = joinedText,
                            allNumbers = allNums,
                            matchedBlock = b
                        )
                    }
                }
            }
            return TextMatchOutcome(
                matched = false,
                parsedNumber = allNums.firstOrNull(),
                allText = joinedText,
                allNumbers = allNums
            )
        }
    }

    fun compareNumber(value: Double, target: Double, mode: TextCompareMode): Boolean = when (mode) {
        TextCompareMode.LESS_THAN -> value < target
        TextCompareMode.LESS_OR_EQUAL -> value <= target
        TextCompareMode.GREATER_THAN -> value > target
        TextCompareMode.GREATER_OR_EQUAL -> value >= target
        TextCompareMode.NUMBER_EQUALS -> abs(value - target) < 1e-6
        else -> false
    }
}
