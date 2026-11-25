package com.example.vision

import com.example.data.Region
import com.example.data.TextAction
import com.example.data.TextCompareMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 * - Case-insensitive & OCR-tolerant text contains / exact match across lines and merged horizontal rows
 * - Numeric comparison (<, <=, >, >=, =) with robust parsing of:
 *   - Plain integers, decimals, and negative numbers
 *   - Space-separated game HUD thousands (e.g. "450 000", "1 250 000")
 *   - Comma & Indonesian/European dot thousands (e.g. "1,250", "500.000", "1.250.000", "450, 000")
 *   - K / M / B suffixes (e.g. "12.5K", "2M", "1.5B")
 *   - Common game-font OCR digit confusions (O/o/Q -> 0, I/l/| -> 1, S/s -> 5, Z/z -> 2, inner B -> 8)
 */
object TextConditionEvaluator {

    // Matches either space-grouped thousands (e.g. "450 000", "1 250 000.5") or standard numeric tokens
    private val numberRegex = Regex("""-?(?:\d{1,3}(?: \d{3})+(?:[.,]\d+)?|\d[\d.,]*)\s*[KkMmBb]?""")
    private val whitespaceRegex = Regex("""\s+""")
    private val spacedSepRegex = Regex("""(?<=\d[.,])\s+(?=\d{3}(?!\d))""")
    private val candidateTokenRegex = Regex("""[+-]?[0-9OoQqIl|SsZzBb.,]+[KkMmBb]?""")
    private val trailingZeroGroupRegex = Regex("""(?<=\d)\s+([OoQq0]{3})(?![0-9A-Za-z])""")

    fun extractNumbers(raw: String): List<Double> {
        if (raw.isBlank()) return emptyList()
        val prepared = prepareNumericText(raw)
        val out = ArrayList<Double>()
        for (m in numberRegex.findAll(prepared)) {
            parseSmartNumber(m.value)?.let { out += it }
        }
        return out
    }

    /**
     * Normalizes dashes/spaces and repairs common game-font OCR digit confusions ONLY inside
     * tokens that already look predominantly numeric (preserving normal words like "BOSS" or "GOLD"
     * and preserving trailing K/M/B magnitude suffixes).
     */
    internal fun prepareNumericText(raw: String): String {
        var text = raw
            .replace('\u00A0', ' ')
            .replace('\u2009', ' ')
            .replace('\u202F', ' ')
            .replace('−', '-')
            .replace('–', '-')
            .replace('—', '-')

        // Repair individual tokens that contain at least one real digit plus OCR-confusable glyphs
        text = candidateTokenRegex.replace(text) { match ->
            repairSingleNumericToken(match.value)
        }

        // Repair trailing 3-char zero groups read as "OOO" immediately following a digit group (e.g. "450 OOO" -> "450 000")
        var prev: String
        do {
            prev = text
            text = trailingZeroGroupRegex.replace(text) { m ->
                " " + m.groupValues[1].map { if (it in "OoQq") '0' else it }.joinToString("")
            }
        } while (text != prev)

        // Collapse stray OCR space immediately after thousand separator comma/dot ("450, 000" -> "450,000")
        text = spacedSepRegex.replace(text, "")
        // Collapse multiple spaces between digit groups to a single space
        text = text.replace(Regex("""(?<=\d)[ \t]{2,}(?=\d{3}(?!\d))"""), " ")
        return text
    }

    private fun repairSingleNumericToken(token: String): String {
        var digitCount = 0
        for (c in token) if (c.isDigit()) digitCount++
        if (digitCount == 0) return token

        // Separate optional trailing K/M/B magnitude suffix (only if preceded by a digit or digit-like char)
        val lastChar = token.last()
        val hasMultiplierSuffix = token.length >= 2 && lastChar in "KkMmBb"
        val coreEnd = if (hasMultiplierSuffix) token.length - 1 else token.length

        val sb = StringBuilder(token.length)
        for (i in 0 until coreEnd) {
            val c = token[i]
            val mapped = when (c) {
                'O', 'o', 'Q', 'q' -> '0'
                'I', 'l', '|' -> '1'
                'S', 's' -> '5'
                'Z', 'z' -> '2'
                'B' -> '8'
                else -> c
            }
            sb.append(mapped)
        }
        if (hasMultiplierSuffix) {
            // If trailing 'b' is lowercase and preceded by >=2 digits with no dot, could be 'B' suffix (keep)
            sb.append(lastChar)
        }
        return sb.toString()
    }

    fun parseSmartNumber(token: String): Double? {
        val repaired = prepareNumericText(token).trim()
        var s = repaired.replace(" ", "")
        if (s.isEmpty()) return null

        var multiplier = 1.0
        var hasSuffix = false
        when (s.last().uppercaseChar()) {
            'K' -> { multiplier = 1_000.0; s = s.dropLast(1); hasSuffix = true }
            'M' -> { multiplier = 1_000_000.0; s = s.dropLast(1); hasSuffix = true }
            'B' -> { multiplier = 1_000_000_000.0; s = s.dropLast(1); hasSuffix = true }
        }
        if (s.isEmpty() || s == "-" || s == "+") return null

        val normalized = normalizeDecimalSeparators(s, hasSuffix)
        val base = normalized.toDoubleOrNull() ?: return null
        return base * multiplier
    }

    private fun normalizeDecimalSeparators(raw: String, hasMultiplierSuffix: Boolean): String {
        val sign = when {
            raw.startsWith("-") -> "-"
            raw.startsWith("+") -> ""
            else -> ""
        }
        val body = if (raw.startsWith("-") || raw.startsWith("+")) raw.drop(1) else raw
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
                if (parts.size > 2 && parts.drop(1).all { it.length == 3 }) {
                    // Multi-group thousand separator (e.g. 1,000,000)
                    body.replace(",", "")
                } else if (parts.size == 2 && !hasMultiplierSuffix && parts[1].length == 3 && parts[0].length in 1..3 && parts[0] != "0") {
                    // Thousand separator (e.g. 1,250 or 500,000)
                    body.replace(",", "")
                } else if (parts.size == 2) {
                    // Decimal comma (e.g. 12,5 or 0,75 or 1,250K)
                    body.replace(',', '.')
                } else {
                    body.replace(",", "")
                }
            }
            hasDot -> {
                val parts = body.split('.')
                if (parts.size > 2 && parts.drop(1).all { it.length == 3 }) {
                    // Indonesian/European multi-dot thousands (e.g. 1.250.000)
                    body.replace(".", "")
                } else if (parts.size == 2 && !hasMultiplierSuffix && parts[1].length == 3 && parts[0].length in 1..3 && parts[0] != "0") {
                    // Indonesian/game single-dot thousand separator (e.g. 1.500, 10.000, 500.000)
                    // Note: "0.125" stays decimal (parts[0] == "0"), "12.5" stays decimal (len != 3), "1.250K" stays decimal (has suffix)
                    body.replace(".", "")
                } else if (parts.size > 2) {
                    // Stray extra dots from OCR: keep only the last dot if it looks like 1-2 decimal places
                    if (parts.last().length in 1..2) {
                        parts.dropLast(1).joinToString("") + "." + parts.last()
                    } else {
                        body.replace(".", "")
                    }
                } else {
                    body
                }
            }
            else -> body
        }
        return sign + clean
    }

    /**
     * Merges horizontally adjacent OCR blocks that sit on the same visual text row.
     * Fixes ML Kit splitting spaced numbers like "450" + "000" into two separate blocks.
     */
    internal fun mergeSameLineBlocks(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) return blocks
        val sorted = blocks.sortedWith(compareBy<OcrBlock> { it.top }.thenBy { it.left })
        val merged = ArrayList<OcrBlock>(sorted.size)
        for (b in sorted) {
            var attached = false
            for (i in merged.indices.reversed()) {
                val cur = merged[i]
                val overlapY = min(cur.bottom, b.bottom) - max(cur.top, b.top)
                val minH = min(cur.height, b.height).coerceAtLeast(1)
                val sameRow = overlapY >= (minH * 0.45f)
                if (!sameRow) continue
                val maxH = max(cur.height, b.height).coerceAtLeast(1)
                val gapX = if (b.left >= cur.right) b.left - cur.right else cur.left - b.right
                // Allow modest word/thousand-group spacing (up to 2.2x character height) without merging distant columns
                if (gapX in (-minH / 2)..((maxH * 2.2f).toInt().coerceAtLeast(24))) {
                    val orderedText = if (b.left >= cur.left) {
                        "${cur.text.trim()} ${b.text.trim()}"
                    } else {
                        "${b.text.trim()} ${cur.text.trim()}"
                    }
                    merged[i] = OcrBlock(
                        text = orderedText.trim(),
                        left = min(cur.left, b.left),
                        top = min(cur.top, b.top),
                        right = max(cur.right, b.right),
                        bottom = max(cur.bottom, b.bottom)
                    )
                    attached = true
                    break
                }
            }
            if (!attached) merged += b
        }
        return merged
    }

    private fun normalizeWhitespace(s: String): String =
        s.trim().replace(whitespaceRegex, " ")

    private fun normalizeForOcrFuzzy(s: String): String {
        val folded = buildString(s.length) {
            for (ch in s.lowercase()) {
                when (ch) {
                    '0' -> append('o')
                    '1', 'l', '|' -> append('i')
                    in 'a'..'z', in '2'..'9' -> append(ch)
                    ' ', '\t', '\n', '\r' -> append(' ')
                }
            }
        }
        return folded.trim().replace(whitespaceRegex, " ")
    }

    fun evaluate(action: TextAction, blocks: List<OcrBlock>): TextMatchOutcome {
        val mergedLines = mergeSameLineBlocks(blocks)
        val joinedText = mergedLines.joinToString(" ") { it.text.trim() }.trim()
        val lineNums = mergedLines.flatMap { extractNumbers(it.text) }
        val blockNums = blocks.flatMap { extractNumbers(it.text) }
        val allNums = if (lineNums.isNotEmpty()) lineNums else blockNums

        if (!action.compareMode.isNumeric) {
            val needle = normalizeWhitespace(action.query)
            if (needle.isEmpty()) {
                return TextMatchOutcome(matched = false, allText = joinedText, allNumbers = allNums)
            }
            val candidates = (blocks + mergedLines).distinctBy { "${it.text}|${it.left}|${it.top}" }

            // Pass 1: clean case-insensitive, whitespace-normalized match
            for (b in candidates) {
                val t = normalizeWhitespace(b.text)
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

            // Pass 2: joined multi-block text match
            val normJoined = normalizeWhitespace(joinedText)
            val joinedHit = when (action.compareMode) {
                TextCompareMode.CONTAINS -> normJoined.contains(needle, ignoreCase = true)
                TextCompareMode.EQUALS -> normJoined.equals(needle, ignoreCase = true)
                else -> false
            }
            if (joinedHit) {
                return TextMatchOutcome(
                    matched = true,
                    matchedText = normJoined,
                    allText = joinedText,
                    allNumbers = allNums,
                    matchedBlock = mergedLines.firstOrNull() ?: blocks.firstOrNull()
                )
            }

            // Pass 3: OCR-tolerant fuzzy fallback (handles 0/O, 1/l/I, stray punctuation on game fonts)
            val fuzzyNeedle = normalizeForOcrFuzzy(needle)
            if (fuzzyNeedle.length >= 3) {
                for (b in candidates) {
                    val ft = normalizeForOcrFuzzy(b.text)
                    val hit = when (action.compareMode) {
                        TextCompareMode.CONTAINS -> ft.contains(fuzzyNeedle)
                        TextCompareMode.EQUALS -> ft == fuzzyNeedle
                        else -> false
                    }
                    if (hit) {
                        return TextMatchOutcome(
                            matched = true,
                            matchedText = b.text.trim(),
                            allText = joinedText,
                            allNumbers = allNums,
                            matchedBlock = b
                        )
                    }
                }
                val fuzzyJoined = normalizeForOcrFuzzy(joinedText)
                val fuzzyJoinedHit = when (action.compareMode) {
                    TextCompareMode.CONTAINS -> fuzzyJoined.contains(fuzzyNeedle)
                    TextCompareMode.EQUALS -> fuzzyJoined == fuzzyNeedle
                    else -> false
                }
                if (fuzzyJoinedHit) {
                    return TextMatchOutcome(
                        matched = true,
                        matchedText = normJoined,
                        allText = joinedText,
                        allNumbers = allNums,
                        matchedBlock = mergedLines.firstOrNull() ?: blocks.firstOrNull()
                    )
                }
            }

            return TextMatchOutcome(
                matched = false,
                allText = joinedText,
                allNumbers = allNums
            )
        } else {
            val target = action.targetNumber
            // Evaluate merged same-line blocks only so split thousands ("450" + "000" -> 450000)
            // are compared as one whole number without false-matching on individual fragments.
            for (b in mergedLines) {
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
