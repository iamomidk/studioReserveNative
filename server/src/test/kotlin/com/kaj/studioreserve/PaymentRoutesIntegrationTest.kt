package com.kaj.studioreserve

import com.kaj.studioreserve.bookings.BookingStatus
import com.kaj.studioreserve.bookings.BookingsTable
import com.kaj.studioreserve.payments.PaymentStatus
import com.kaj.studioreserve.payments.PaymentsTable
import com.kaj.studioreserve.payments.PaymentGateway
import com.kaj.studioreserve.studios.RoomsTable
import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.users.UserRole
import com.kaj.studioreserve.users.UsersTable
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

class PaymentRoutesIntegrationTest : IntegrationTestBase() {
    private val ownerId = UUID.randomUUID()
    private val photographerId = UUID.randomUUID()
    private val otherPhotographerId = UUID.randomUUID()
    private val studioId = UUID.randomUUID()
    private val roomId = UUID.randomUUID()
    private val bookingId = UUID.randomUUID()
    private val paymentId = UUID.randomUUID()
    private val transactionRef = "FAKE-$paymentId"

    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
        seedUsers()
        seedStudioAndRoom()
        seedBooking()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `photographer can initiate payment for own booking`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(photographerId.toString(), UserRole.PHOTOGRAPHER)

        val response = client.post("/api/payments/initiate") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(mapOf("bookingId" to bookingId.toString(), "gateway" to PaymentGateway.ZARINPAL.name))
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val storedPayment = transaction {
            PaymentsTable.select { PaymentsTable.bookingId eq bookingId }.single()
        }
        assertEquals(PaymentStatus.PENDING, storedPayment[PaymentsTable.status])
    }

    @Test
    fun `non owner photographer cannot initiate payment`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(otherPhotographerId.toString(), UserRole.PHOTOGRAPHER)

        val response = client.post("/api/payments/initiate") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(mapOf("bookingId" to bookingId.toString()))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
    }

    @Test
    fun `successful callback marks booking and payment paid`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }

        seedPendingPayment()

        val response = client.get("/api/payments/callback/zarinpal?Status=OK&Authority=$transactionRef")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val statusPair = transaction {
            val paymentRow = PaymentsTable.select { PaymentsTable.id eq paymentId }.single()
            val bookingRow = BookingsTable.select { BookingsTable.id eq bookingId }.single()
            paymentRow[PaymentsTable.status] to bookingRow[BookingsTable.paymentStatus]
        }

        assertEquals(PaymentStatus.PAID, statusPair.first)
        assertEquals(PaymentStatus.PAID, statusPair.second)
    }

    private fun seedUsers() {
        transaction {
            UsersTable.insert { statement ->
                statement[id] = ownerId
                statement[name] = "Owner"
                statement[phoneNumber] = "+989100000008"
                statement[email] = "owner4@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.STUDIO_OWNER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            UsersTable.insert { statement ->
                statement[id] = photographerId
                statement[name] = "Photographer"
                statement[phoneNumber] = "+989100000009"
                statement[email] = "photographer4@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.PHOTOGRAPHER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            UsersTable.insert { statement ->
                statement[id] = otherPhotographerId
                statement[name] = "Other Photographer"
                statement[phoneNumber] = "+989100000010"
                statement[email] = "otherp@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.PHOTOGRAPHER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    private fun seedStudioAndRoom() {
        transaction {
            StudiosTable.insert { statement ->
                statement[id] = studioId
                statement[ownerId] = ownerId
                statement[name] = "Studio"
                statement[description] = "desc"
                statement[province] = "province"
                statement[city] = "city"
                statement[address] = "address"
                statement[mapCoordinates] = "0,0"
                statement[photos] = emptyList()
                statement[verificationStatus] = com.kaj.studioreserve.studios.VerificationStatus.APPROVED
            }

            RoomsTable.insert { statement ->
                statement[id] = roomId
                statement[studioId] = studioId
                statement[name] = "Room"
                statement[description] = "desc"
                statement[hourlyPrice] = 100
                statement[dailyPrice] = 100
                statement[features] = emptyList()
                statement[images] = emptyList()
            }
        }
    }

    private fun seedBooking() {
        transaction {
            BookingsTable.insert { statement ->
                statement[id] = bookingId
                statement[roomId] = roomId
                statement[photographerId] = photographerId
                statement[startTime] = LocalDateTime.now(ZoneOffset.UTC)
                statement[endTime] = LocalDateTime.now(ZoneOffset.UTC).plusHours(1)
                statement[bookingStatus] = BookingStatus.PENDING
                statement[paymentStatus] = PaymentStatus.PENDING
                statement[totalPrice] = BigDecimal(100)
                statement[equipmentIds] = emptyList()
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    private fun seedPendingPayment() {
        transaction {
            PaymentsTable.insert { statement ->
                statement[id] = paymentId
                statement[bookingId] = bookingId
                statement[amount] = BigDecimal(100)
                statement[gateway] = PaymentGateway.ZARINPAL
                statement[status] = PaymentStatus.PENDING
                statement[timestamp] = null
                statement[transactionRef] = transactionRef
            }
        }
    }
}
