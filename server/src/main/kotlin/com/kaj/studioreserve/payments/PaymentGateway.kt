package com.kaj.studioreserve.payments

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentGateway {
    ZARINPAL,
    IDPAY,
    NEXTPAY
}
