package com.studioreserve.bookings

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

object BookingPricingService {
    fun calculateHourlyTotal(
        hourlyPrice: Int,
        start: LocalDateTime,
        end: LocalDateTime
    ): BigDecimal {
        val billedHours = BookingTimeUtils.calculateBilledHours(start, end)
        return BigDecimal.valueOf(hourlyPrice.toLong())
            .multiply(BigDecimal.valueOf(billedHours.toLong()))
            .setScale(2, RoundingMode.HALF_UP)
    }
}
