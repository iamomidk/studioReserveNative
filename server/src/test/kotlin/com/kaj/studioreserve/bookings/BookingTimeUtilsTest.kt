package com.kaj.studioreserve.bookings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant
import java.time.LocalDateTime

class BookingTimeUtilsTest {
    @Test
    fun `parseInstantOrNull returns instant for valid iso`() {
        val now = Instant.now()
        val parsed = BookingTimeUtils.parseInstantOrNull(now.toString())

        assertEquals(now.epochSecond, parsed?.epochSecond)
    }

    @Test
    fun `parseInstantOrNull returns null for invalid`() {
        val parsed = BookingTimeUtils.parseInstantOrNull("not-a-date")
        assertNull(parsed)
    }

    @Test
    fun `isChronologicallyValid enforces start before end`() {
        val start = Instant.now()
        val end = start.plusSeconds(3600)

        assertTrue(BookingTimeUtils.isChronologicallyValid(start, end))
        assertFalse(BookingTimeUtils.isChronologicallyValid(end, start))
    }

    @Test
    fun `isStartTooFarInPast detects threshold`() {
        val now = Instant.now()
        val past = now.minusSeconds(11 * 60)
        val recent = now.minusSeconds(5 * 60)

        assertTrue(BookingTimeUtils.isStartTooFarInPast(past, now))
        assertFalse(BookingTimeUtils.isStartTooFarInPast(recent, now))
    }

    @Test
    fun `isOverlapping validates symmetric overlap`() {
        val startA = LocalDateTime.of(2024, 1, 1, 10, 0)
        val endA = startA.plusHours(2)
        val startB = LocalDateTime.of(2024, 1, 1, 11, 0)
        val endB = startB.plusHours(2)

        assertTrue(BookingTimeUtils.isOverlapping(startA, endA, startB, endB))
        assertTrue(BookingTimeUtils.isOverlapping(startB, endB, startA, endA))
    }

    @Test
    fun `isOverlapping returns false for adjacent ranges`() {
        val startA = LocalDateTime.of(2024, 1, 1, 10, 0)
        val endA = startA.plusHours(2)
        val startB = endA
        val endB = startB.plusHours(1)

        assertFalse(BookingTimeUtils.isOverlapping(startA, endA, startB, endB))
    }

    @Test
    fun `calculateBilledHours rounds up partial hours`() {
        val start = LocalDateTime.of(2024, 1, 1, 10, 0)

        assertEquals(1, BookingTimeUtils.calculateBilledHours(start, start.plusMinutes(10)))
        assertEquals(1, BookingTimeUtils.calculateBilledHours(start, start.plusMinutes(59)))
        assertEquals(2, BookingTimeUtils.calculateBilledHours(start, start.plusMinutes(61)))
        assertEquals(3, BookingTimeUtils.calculateBilledHours(start, start.plusHours(2).plusMinutes(1)))
    }

    @Test
    fun `toInstantString converts back to UTC string`() {
        val instant = Instant.parse("2024-01-01T12:00:00Z")
        val ldt = BookingTimeUtils.toUtcLocalDateTime(instant)

        val result = ldt.toInstantString()
        assertNotNull(BookingTimeUtils.parseInstantOrNull(result))
    }
}
