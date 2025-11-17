package com.kaj.studioreserve.payments

import com.kaj.studioreserve.auth.UserPrincipal
import com.kaj.studioreserve.bookings.BookingsTable
import com.kaj.studioreserve.notifications.NotificationService
import com.kaj.studioreserve.users.UserRole
import com.kaj.studioreserve.users.UsersTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

private val paymentLogger = LoggerFactory.getLogger("PaymentRoutes")

fun Route.paymentRoutes(
    paymentGatewayService: PaymentGatewayService,
    notificationService: NotificationService
) {
    route("/api/payments") {
        authenticate("auth-jwt") {
            post("/initiate") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, PaymentErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.Forbidden, PaymentErrorResponse("Unknown role"))

                if (role != UserRole.PHOTOGRAPHER) {
                    return@post call.respond(HttpStatusCode.Forbidden, PaymentErrorResponse("Only photographers can initiate payments"))
                }

                val photographerId = runCatching { UUID.fromString(principal.userId) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, PaymentErrorResponse("Invalid user id"))
                }

                val request = runCatching { call.receive<PaymentInitiateRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, PaymentErrorResponse("Invalid request payload"))
                }

                if (request.bookingId.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, PaymentErrorResponse("bookingId is required"))
                }

                val bookingId = runCatching { UUID.fromString(request.bookingId) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, PaymentErrorResponse("bookingId must be a valid UUID"))
                }

                val selectedGateway = request.gateway ?: PaymentGateway.ZARINPAL

                val initiationResult = transaction {
                    val bookingRow = BookingsTable.select { BookingsTable.id eq bookingId }.singleOrNull()
                        ?: return@transaction PaymentInitiationDbResult.NotFound

                    if (bookingRow[BookingsTable.photographerId] != photographerId) {
                        return@transaction PaymentInitiationDbResult.Forbidden
                    }

                    if (bookingRow[BookingsTable.paymentStatus] != PaymentStatus.PENDING) {
                        return@transaction PaymentInitiationDbResult.InvalidStatus
                    }

                    val paymentId = UUID.randomUUID()
                    val amount = bookingRow[BookingsTable.totalPrice]

                    PaymentsTable.insert { statement ->
                        statement[PaymentsTable.id] = paymentId
                        statement[PaymentsTable.bookingId] = bookingId
                        statement[PaymentsTable.amount] = amount
                        statement[PaymentsTable.gateway] = selectedGateway
                        statement[PaymentsTable.status] = PaymentStatus.PENDING
                        statement[PaymentsTable.timestamp] = null
                        statement[PaymentsTable.transactionRef] = null
                    }

                    PaymentInitiationDbResult.Success(paymentId, amount)
                }

                when (initiationResult) {
                    PaymentInitiationDbResult.NotFound -> return@post call.respond(
                        HttpStatusCode.NotFound,
                        PaymentErrorResponse("Booking not found")
                    )

                    PaymentInitiationDbResult.Forbidden -> return@post call.respond(
                        HttpStatusCode.Forbidden,
                        PaymentErrorResponse("Booking does not belong to the current user")
                    )

                    PaymentInitiationDbResult.InvalidStatus -> return@post call.respond(
                        HttpStatusCode.BadRequest,
                        PaymentErrorResponse("Booking is not pending payment")
                    )

                    is PaymentInitiationDbResult.Success -> {
                        val amountInt = initiationResult.amount.toWholeAmountInt()
                        val gatewayResult = paymentGatewayService.createPayment(
                            bookingId = bookingId.toString(),
                            amount = amountInt,
                            gateway = selectedGateway
                        )

                        transaction {
                            PaymentsTable.update({ PaymentsTable.id eq initiationResult.paymentId }) { statement ->
                                statement[transactionRef] = gatewayResult.externalRef
                            }
                        }

                        return@post call.respond(
                            HttpStatusCode.OK,
                            PaymentInitiateResponse(
                                paymentId = initiationResult.paymentId.toString(),
                                paymentUrl = gatewayResult.paymentUrl
                            )
                        )
                    }
                }
            }
        }

        get("/callback/zarinpal") {
            val verification = paymentGatewayService.verifyPayment(
                gateway = PaymentGateway.ZARINPAL,
                params = call.request.queryParameters
            )

            val externalRef = verification.externalRef
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    PaymentCallbackResponse(success = false, message = "Missing payment reference")
                )

            val callbackResult = transaction {
                // transactionRef stores the gateway external reference to identify the payment on callbacks
                val paymentRow = PaymentsTable.select { PaymentsTable.transactionRef eq externalRef }.singleOrNull()
                    ?: return@transaction PaymentCallbackDbResult.NotFound

                val paymentId = paymentRow[PaymentsTable.id]
                val bookingId = paymentRow[PaymentsTable.bookingId]
                val bookingRow = BookingsTable.select { BookingsTable.id eq bookingId }.single()
                val now = LocalDateTime.now()

                if (verification.success) {
                    PaymentsTable.update({ PaymentsTable.id eq paymentId }) { statement ->
                        statement[status] = PaymentStatus.PAID
                        statement[timestamp] = now
                    }

                    BookingsTable.update({ BookingsTable.id eq bookingId }) { statement ->
                        statement[BookingsTable.paymentStatus] = PaymentStatus.PAID
                    }

                    val phoneNumber = UsersTable
                        .select { UsersTable.id eq bookingRow[BookingsTable.photographerId] }
                        .singleOrNull()
                        ?.get(UsersTable.phoneNumber)

                    PaymentCallbackDbResult.Success(bookingId, phoneNumber)
                } else {
                    PaymentsTable.update({ PaymentsTable.id eq paymentId }) { statement ->
                        statement[status] = PaymentStatus.FAILED
                        statement[timestamp] = now
                    }

                    PaymentCallbackDbResult.Failure
                }
            }

            val failureMessage = verification.errorMessage ?: "Payment failed"

            when (callbackResult) {
                PaymentCallbackDbResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    PaymentCallbackResponse(success = false, message = "Payment record not found")
                )

                is PaymentCallbackDbResult.Success -> {
                    call.respond(
                        HttpStatusCode.OK,
                        PaymentCallbackResponse(success = true, message = "Payment verified successfully")
                    )

                    val phoneNumber = callbackResult.phoneNumber
                    if (phoneNumber == null) {
                        paymentLogger.warn(
                            "Skipping payment success SMS for booking {} because photographer phone number was not found",
                            callbackResult.bookingId
                        )
                    } else {
                        try {
                            notificationService.sendPaymentSuccessSms(
                                phoneNumber = phoneNumber,
                                bookingId = callbackResult.bookingId.toString()
                            )
                        } catch (t: Throwable) {
                            paymentLogger.error(
                                "Failed to send payment success SMS for booking {}",
                                callbackResult.bookingId,
                                t
                            )
                        }
                    }
                }

                PaymentCallbackDbResult.Failure -> call.respond(
                    HttpStatusCode.OK,
                    PaymentCallbackResponse(success = false, message = failureMessage)
                )
            }
        }
    }
}

private fun BigDecimal.toWholeAmountInt(): Int {
    return this.setScale(0, RoundingMode.HALF_UP).toInt()
}

private sealed interface PaymentInitiationDbResult {
    data class Success(val paymentId: UUID, val amount: BigDecimal) : PaymentInitiationDbResult
    data object NotFound : PaymentInitiationDbResult
    data object Forbidden : PaymentInitiationDbResult
    data object InvalidStatus : PaymentInitiationDbResult
}

private sealed interface PaymentCallbackDbResult {
    data class Success(val bookingId: UUID, val phoneNumber: String?) : PaymentCallbackDbResult
    data object Failure : PaymentCallbackDbResult
    data object NotFound : PaymentCallbackDbResult
}

private fun ResultRow.toPaymentDto(): PaymentDto = PaymentDto(
    id = this[PaymentsTable.id].toString(),
    bookingId = this[PaymentsTable.bookingId].toString(),
    amount = this[PaymentsTable.amount].toDouble(),
    gateway = this[PaymentsTable.gateway],
    status = this[PaymentsTable.status],
    timestamp = this[PaymentsTable.timestamp]?.toString(),
    transactionRef = this[PaymentsTable.transactionRef]
)

@Serializable
data class PaymentInitiateRequest(
    val bookingId: String,
    val gateway: PaymentGateway? = null
)

@Serializable
data class PaymentInitiateResponse(
    val paymentId: String,
    val paymentUrl: String
)

@Serializable
data class PaymentDto(
    val id: String,
    val bookingId: String,
    val amount: Double,
    val gateway: PaymentGateway,
    val status: PaymentStatus,
    val timestamp: String?,
    val transactionRef: String?
)

@Serializable
private data class PaymentErrorResponse(val message: String)

@Serializable
data class PaymentCallbackResponse(val success: Boolean, val message: String)
