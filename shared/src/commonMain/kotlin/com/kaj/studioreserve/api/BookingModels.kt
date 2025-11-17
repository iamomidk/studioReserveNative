package com.kaj.studioreserve.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateBookingRequest(
    val roomId: String,
    val startTime: String,
    val endTime: String,
    val equipmentIds: List<String> = emptyList(),
)

@Serializable
data class UpdateBookingStatusRequest(val status: BookingStatus)

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
    val createdAt: String,
)

@Serializable
enum class BookingStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELLED,
    COMPLETED,
}

@Serializable
enum class PaymentStatus {
    @SerialName("PENDING")
    PENDING,

    @SerialName("SUCCESS")
    SUCCESS,

    @SerialName("FAILED")
    FAILED,

    @SerialName("REFUNDED")
    REFUNDED,
}
