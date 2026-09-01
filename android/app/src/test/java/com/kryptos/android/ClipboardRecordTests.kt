package com.kryptos.android

import com.kryptos.android.security.ClipboardRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardRecordTests {

    private val digest = "a".repeat(64)

    @Test
    fun roundTrip() {
        val parsed = ClipboardRecord.parse(ClipboardRecord.encode(7, 123_456L, digest), 7)
        assertEquals(digest to 123_456L, parsed)
    }

    @Test
    fun aRebootInvalidatesTheRecord() {
        assertNull(ClipboardRecord.parse(ClipboardRecord.encode(7, 123_456L, digest), 8))
    }

    @Test
    fun malformedRecordsAreRejected() {
        assertNull(ClipboardRecord.parse("", 7))
        assertNull(ClipboardRecord.parse("7\n123", 7))
        assertNull(ClipboardRecord.parse("7\n123\n$digest\nextra", 7))
        assertNull(ClipboardRecord.parse("x\n123\n$digest", 7))
        assertNull(ClipboardRecord.parse("7\nx\n$digest", 7))
    }

    @Test
    fun onlyALowercaseHexDigestOfTheRightLengthIsAccepted() {
        assertNull(ClipboardRecord.parse("7\n1\n" + "a".repeat(63), 7))
        assertNull(ClipboardRecord.parse("7\n1\n" + "a".repeat(65), 7))
        assertNull(ClipboardRecord.parse("7\n1\n" + "A".repeat(64), 7))
        assertNull(ClipboardRecord.parse("7\n1\n" + "z".repeat(64), 7))
        assertEquals("0123456789abcdef".repeat(4) to 1L, ClipboardRecord.parse("7\n1\n" + "0123456789abcdef".repeat(4), 7))
    }
}
