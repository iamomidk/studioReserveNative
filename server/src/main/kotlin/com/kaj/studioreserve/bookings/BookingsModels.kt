package com.kaj.studioreserve.bookings

import com.kaj.studioreserve.bookings.BookingTimeUtils.toInstantString
import com.kaj.studioreserve.payments.PaymentStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow

@Serializable
data class CreateBookingRequest(
    val roomId: String,
    val startTime: String,
    val endTime: String,
    val equipmentIds: List<String> = emptyList()
)

@Serializable
data class BookingDto(
    val id: String,
    val roomId: String,
    val photographerId: String,
    val startTime: String,
    val endTime: String,
    val equipmentIds: List<String>,
    val totalPrice: Double,
    val paymentStatus: PaymentStatus,
    val bookingStatus: BookingStatus,
    val createdAt: String
)

@Serializable
data class UpdateBookingStatusRequest(val status: BookingStatus)

@Serializable
data class ErrorResponse(val message: String)

internal fun ResultRow.toBookingDto(): BookingDto = BookingDto(
    id = this[BookingsTable.id].toString(),
    roomId = this[BookingsTable.roomId].toString(),
    photographerId = this[BookingsTable.photographerId].toString(),
    startTime = this[BookingsTable.startTime].toInstantString(),
    endTime = this[BookingsTable.endTime].toInstantString(),
    equipmentIds = deserializeEquipmentIds(this[BookingsTable.equipmentIds]),
    totalPrice = this[BookingsTable.totalPrice].toDouble(),
    paymentStatus = this[BookingsTable.paymentStatus],
    bookingStatus = this[BookingsTable.bookingStatus],
    createdAt = this[BookingsTable.createdAt].toInstantString()
)

internal fun serializeEquipmentIds(ids: List<String>): String = bookingJson.encodeToString(ids)

internal fun deserializeEquipmentIds(raw: String): List<String> = runCatching {
    bookingJson.decodeFromString<List<String>>(raw)
}.getOrDefault(emptyList())

private val bookingJson = Json
