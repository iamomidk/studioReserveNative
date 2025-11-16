package com.studioreserve.bookings

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

object BookingTimeUtils {
    private const val PAST_THRESHOLD_MINUTES = 10L

    fun parseInstantOrNull(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    fun toUtcLocalDateTime(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    fun LocalDateTime.toInstantString(): String = this.atOffset(ZoneOffset.UTC).toInstant().toString()

    fun isChronologicallyValid(start: Instant, end: Instant): Boolean = start.isBefore(end)

    fun isStartTooFarInPast(start: Instant, now: Instant = Instant.now()): Boolean {
        val threshold = now.minus(Duration.ofMinutes(PAST_THRESHOLD_MINUTES))
        return start.isBefore(threshold)
    }

    fun calculateBilledHours(start: LocalDateTime, end: LocalDateTime): Int {
        val minutes = Duration.between(start, end).toMinutes()
        return ((minutes + 59) / 60).coerceAtLeast(1)
    }
}
