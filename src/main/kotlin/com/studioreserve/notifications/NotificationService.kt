package com.studioreserve.notifications

import com.studioreserve.bookings.BookingStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory

/**
 * Abstraction around any outbound SMS provider so that higher level modules can stay provider agnostic.
 */
interface NotificationService {
    suspend fun sendBookingStatusSms(phoneNumber: String, bookingId: String, status: BookingStatus)

    suspend fun sendPaymentSuccessSms(phoneNumber: String, bookingId: String)
}

/**
 * Simple implementation that only logs the outgoing messages.
 */
class FakeNotificationService : NotificationService {
    private val logger = LoggerFactory.getLogger(FakeNotificationService::class.java)

    override suspend fun sendBookingStatusSms(phoneNumber: String, bookingId: String, status: BookingStatus) {
        logger.info("[FAKE SMS] Booking #{} for {} is now {}", bookingId, phoneNumber, status)
    }

    override suspend fun sendPaymentSuccessSms(phoneNumber: String, bookingId: String) {
        logger.info("[FAKE SMS] Payment received for booking #{} (recipient: {})", bookingId, phoneNumber)
    }
}

/**
 * Optional stub implementation that sketches how a real Kavenegar integration might look.
 */
class KavenegarNotificationService(
    private val client: HttpClient = HttpClient(CIO),
    private val apiKey: String? = System.getenv("KAVENEGAR_API_KEY")
) : NotificationService {

    private val logger = LoggerFactory.getLogger(KavenegarNotificationService::class.java)

    override suspend fun sendBookingStatusSms(phoneNumber: String, bookingId: String, status: BookingStatus) {
        val template = "booking-status"
        val token = status.name
        postSms(phoneNumber, template, mapOf("token" to token, "bookingId" to bookingId))
    }

    override suspend fun sendPaymentSuccessSms(phoneNumber: String, bookingId: String) {
        val template = "payment-success"
        postSms(phoneNumber, template, mapOf("bookingId" to bookingId))
    }

    private suspend fun postSms(phoneNumber: String, template: String, tokens: Map<String, String>) {
        val key = apiKey ?: run {
            logger.warn("KAVENEGAR_API_KEY is not configured; skipping SMS send")
            return
        }

        // Example Kavenegar API call. Adjust URL/parameters when wiring up real integration.
        client.post("https://api.kavenegar.com/v1/$key/sms/send.json") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "receptor" to phoneNumber,
                    "template" to template,
                    "tokens" to tokens
                )
            )
        }
    }
}

/**
 * Example usage that callers can reference for wiring into routes or workflows later.
 */
suspend fun exampleNotificationUsage() {
    val notificationService: NotificationService = FakeNotificationService()
    notificationService.sendBookingStatusSms(
        phoneNumber = "+989123456789",
        bookingId = "booking-1",
        status = BookingStatus.ACCEPTED
    )
    notificationService.sendPaymentSuccessSms(phoneNumber = "+989123456789", bookingId = "booking-1")
}
