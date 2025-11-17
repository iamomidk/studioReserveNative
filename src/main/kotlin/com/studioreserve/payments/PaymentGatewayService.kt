package com.studioreserve.payments

import io.ktor.http.Parameters

interface PaymentGatewayService {
    suspend fun createPayment(bookingId: String, amount: Int, gateway: PaymentGateway): PaymentGatewayResult
    suspend fun verifyPayment(gateway: PaymentGateway, params: Parameters): PaymentVerificationResult
}

data class PaymentGatewayResult(
    val paymentUrl: String,
    val externalRef: String
)

data class PaymentVerificationResult(
    val success: Boolean,
    val amount: Int?,
    val externalRef: String?,
    val errorMessage: String?
)

class FakeZarinpalPaymentGatewayService : PaymentGatewayService {
    override suspend fun createPayment(bookingId: String, amount: Int, gateway: PaymentGateway): PaymentGatewayResult {
        val paymentUrl = "https://gateway.test/pay/$bookingId"
        val externalRef = "FAKE-$bookingId"
        return PaymentGatewayResult(paymentUrl, externalRef)
    }

    override suspend fun verifyPayment(
        gateway: PaymentGateway,
        params: Parameters
    ): PaymentVerificationResult {
        val status = params["Status"]
        val amount = params["Amount"]?.toIntOrNull()
        val externalRef = params["Authority"] ?: params["externalRef"] ?: params["RefId"]
        val success = status == "OK"
        val message = if (success) null else params["Message"] ?: "Payment verification failed"

        return PaymentVerificationResult(
            success = success,
            amount = amount,
            externalRef = externalRef,
            errorMessage = message
        )
    }
}
