package com.kaj.studioreserve.api

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Lightweight preview/sample data to allow UI layers (Compose Multiplatform, Android, iOS)
 * to render booking lists even without a live backend.
 */
object BookingSamples {
    fun sampleBookings(): List<BookingDto> {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        return listOf(
            BookingDto(
                id = "preview-1",
                roomId = "room-1",
                photographerId = "photographer-1",
                startTime = now.toString(),
                endTime = now.plusHours(2).toString(),
                equipmentIds = listOf("camera-1"),
                totalPrice = 250.0,
                paymentStatus = PaymentStatus.PENDING,
                bookingStatus = BookingStatus.PENDING,
                createdAt = now.toString(),
            ),
            BookingDto(
                id = "preview-2",
                roomId = "room-2",
                photographerId = "photographer-2",
                startTime = now.plusDays(1).toString(),
                endTime = now.plusDays(1).plusHours(3).toString(),
                equipmentIds = emptyList(),
                totalPrice = 540.0,
                paymentStatus = PaymentStatus.PAID,
                bookingStatus = BookingStatus.ACCEPTED,
                createdAt = now.toString(),
            ),
        )
    }
}

private fun kotlinx.datetime.LocalDateTime.plusHours(hours: Int) = this.plus(kotlin.time.Duration.parse("${hours}h"))
private fun kotlinx.datetime.LocalDateTime.plusDays(days: Int) = this.plus(kotlin.time.Duration.parse("${days}d"))
