package com.studioreserve.payments

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object PaymentsTable : Table("payments") {
    val id = uuid("id")
    val bookingId = uuid("booking_id")
    val amount = decimal("amount", 12, 2)
    val gateway = enumerationByName("gateway", 50, PaymentGateway::class)
    val status = enumerationByName("status", 50, PaymentStatus::class)
    val timestamp = datetime("timestamp")
    val transactionRef = varchar("transaction_ref", 100)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_payments_booking_id", bookingId)
    }
}
