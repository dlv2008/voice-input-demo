package com.trendbot.voiceinputdemo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDocumentTest {
    @Test
    fun sanitizerRejectsBlankPunctuationAndShortFiller() {
        assertNull(TranscriptSanitizer.sanitize("  ", 1.0))
        assertNull(TranscriptSanitizer.sanitize("……。", 1.0))
        assertNull(TranscriptSanitizer.sanitize("嗯。", 0.4))
    }

    @Test
    fun sanitizerKeepsMeaningfulSingleCharacterAndRemovesMarkers() {
        assertEquals("对。", TranscriptSanitizer.sanitize("<|zh|>对。", 0.4))
        assertEquals("A", TranscriptSanitizer.sanitize("A", 0.2))
    }

    @Test
    fun formatterJoinsChineseWithoutArtificialSpaces() {
        val segments = listOf(
            segment(1, "今天下午三点开会。"),
            segment(2, "请提前十分钟提醒我。"),
        )
        assertEquals(
            "今天下午三点开会。请提前十分钟提醒我。",
            TranscriptTextFormatter.joinCommitted(segments),
        )
    }

    @Test
    fun formatterKeepsEnglishWordBoundary() {
        val segments = listOf(
            segment(1, "API requests"),
            segment(2, "use SSE streaming."),
        )
        assertEquals(
            "API requests use SSE streaming.",
            TranscriptTextFormatter.joinCommitted(segments),
        )
    }

    @Test
    fun documentRetainsFinalSource() {
        val document = TranscriptDocument(
            committedSegments = listOf(
                segment(7, "在线回退", FinalSource.ONLINE_FALLBACK),
            ),
        )
        assertTrue(
            document.committedSegments.single().source ==
                FinalSource.ONLINE_FALLBACK,
        )
    }

    private fun segment(
        id: Long,
        text: String,
        source: FinalSource = FinalSource.OFFLINE_SECOND_PASS,
    ): TranscriptSegment {
        return TranscriptSegment(
            segmentId = id,
            text = text,
            source = source,
        )
    }
}
