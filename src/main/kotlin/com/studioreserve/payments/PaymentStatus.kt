package com.studioreserve.payments

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentStatus {
    PAID,
    PENDING,
    FAILED
}
