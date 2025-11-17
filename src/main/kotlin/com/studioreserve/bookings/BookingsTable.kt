package com.studioreserve.bookings

import com.studioreserve.payments.PaymentStatus
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object BookingsTable : Table("bookings") {
    val id = uuid("id")
    val roomId = uuid("room_id")
    val photographerId = uuid("photographer_id")
    val startTime = datetime("start_time")
    val endTime = datetime("end_time")
    val equipmentIds = text("equipment_ids")
    val totalPrice = decimal("total_price", 12, 2)
    val paymentStatus = enumerationByName("payment_status", 50, PaymentStatus::class)
    val bookingStatus = enumerationByName("booking_status", 50, BookingStatus::class)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}
