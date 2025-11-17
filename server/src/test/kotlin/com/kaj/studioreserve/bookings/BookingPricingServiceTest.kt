package com.kaj.studioreserve.bookings

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class BookingPricingServiceTest {
    @Test
    fun `calculateHourlyTotal multiplies billed hours`() {
        val start = LocalDateTime.of(2024, 1, 1, 10, 0)
        val end = start.plusHours(3)

        val total = BookingPricingService.calculateHourlyTotal(50000, start, end)
        assertEquals("150000.00", total.toPlainString())
    }

    @Test
    fun `calculateHourlyTotal rounds up partial hour`() {
        val start = LocalDateTime.of(2024, 1, 1, 10, 0)
        val end = start.plusMinutes(90)

        val total = BookingPricingService.calculateHourlyTotal(100000, start, end)
        assertEquals("200000.00", total.toPlainString())
    }
}
