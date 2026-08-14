package com.trendbot.voiceinputdemo.speech

/** A stable segment. Its source is retained so the UI can show confidence honestly. */
data class TranscriptSegment(
    val segmentId: Long,
    val text: String,
    val source: FinalSource,
    val durationSeconds: Double = 0.0,
    val speakerId: String? = null,
)

/** Immutable transcript state published by the Service. */
data class TranscriptDocument(
    val committedSegments: List<TranscriptSegment> = emptyList(),
    val partialSegmentId: Long? = null,
    val partialText: String = "",
    val revision: Long = 0L,
) {
    val isEmpty: Boolean
        get() = committedSegments.isEmpty() && partialText.isBlank()

    fun stableText(): String = TranscriptTextFormatter.joinCommitted(committedSegments)
}

/** Conservative filtering: never reject every one-character phrase. */
object TranscriptSanitizer {
    private val modelMarker = Regex("<\\|[^>]+\\|>")
    private val repeatedWhitespace = Regex("\\s+")
    private val shortFillers = setOf("嗯", "啊", "呃", "哦", "额", "唔")
    private val terminalMarks = charArrayOf(
        '。', '！', '？', '.', '!', '?', ',', '，', '、', ';', '；', ':', '：',
    )

    fun sanitize(rawText: String, durationSeconds: Double): String? {
        val cleaned = modelMarker
            .replace(rawText, "")
            .replace(repeatedWhitespace, " ")
            .trim()

        if (cleaned.isBlank() || !containsLetterOrDigit(cleaned)) return null

        val lexicalText = cleaned.trim().trimEnd(*terminalMarks).trim()
        if (durationSeconds < 0.8 && lexicalText in shortFillers) return null

        return cleaned
    }

    private fun containsLetterOrDigit(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (Character.isLetterOrDigit(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }
}

/** Joins internal segments into a paragraph without inventing punctuation. */
object TranscriptTextFormatter {
    fun joinCommitted(segments: List<TranscriptSegment>): String {
        return buildString {
            segments.forEach { segment ->
                appendSegment(this, segment.text)
            }
        }
    }

    fun appendSegment(target: StringBuilder, rawText: String) {
        val next = rawText.trim()
        if (next.isEmpty()) return
        target.append(separator(target, next))
        target.append(next)
    }

    fun separator(existing: CharSequence, nextText: CharSequence): String {
        if (existing.isEmpty() || nextText.isEmpty()) return ""

        val previousCodePoint = Character.codePointBefore(existing, existing.length)
        val nextCodePoint = Character.codePointAt(nextText, 0)

        if (
            Character.isWhitespace(previousCodePoint) ||
            Character.isWhitespace(nextCodePoint)
        ) {
            return ""
        }

        if (isCjk(previousCodePoint) || isCjk(nextCodePoint)) return ""

        return if (
            Character.isLetterOrDigit(previousCodePoint) &&
            Character.isLetterOrDigit(nextCodePoint)
        ) {
            " "
        } else if (isSentencePunctuation(previousCodePoint)) {
            " "
        } else {
            ""
        }
    }

    private fun isCjk(codePoint: Int): Boolean {
        val block = Character.UnicodeBlock.of(codePoint)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private fun isSentencePunctuation(codePoint: Int): Boolean {
        return codePoint == '.'.code ||
            codePoint == '!'.code ||
            codePoint == '?'.code ||
            codePoint == ';'.code ||
            codePoint == ':'.code
    }
}
