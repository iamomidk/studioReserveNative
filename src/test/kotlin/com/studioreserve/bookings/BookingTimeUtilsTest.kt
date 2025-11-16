package com.studioreserve.bookings

import java.time.LocalDateTime
import java.time.Month
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookingTimeUtilsTest {
    private val startA = LocalDateTime.of(2024, Month.JANUARY, 1, 12, 0)
    private val endA = startA.plusHours(2)

    @Test
    fun `overlap when ranges intersect`() {
        val startB = startA.plusHours(1)
        val endB = startB.plusHours(2)

        assertTrue(BookingTimeUtils.isOverlapping(startA, endA, startB, endB))
    }

    @Test
    fun `no overlap when second starts at first end`() {
        val startB = endA
        val endB = startB.plusHours(1)

        assertFalse(BookingTimeUtils.isOverlapping(startA, endA, startB, endB))
    }

    @Test
    fun `no overlap when ranges are disjoint`() {
        val startB = endA.plusHours(1)
        val endB = startB.plusHours(1)

        assertFalse(BookingTimeUtils.isOverlapping(startA, endA, startB, endB))
    }

    @Test
    fun `overlap when second completely inside first`() {
        val startB = startA.plusMinutes(30)
        val endB = startB.plusMinutes(15)

        assertTrue(BookingTimeUtils.isOverlapping(startA, endA, startB, endB))
    }
}
