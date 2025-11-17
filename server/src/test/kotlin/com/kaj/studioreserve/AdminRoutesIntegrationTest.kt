package com.kaj.studioreserve

import com.kaj.studioreserve.admin.AdminBookingSummaryDto
import com.kaj.studioreserve.admin.AdminEquipmentLogDto
import com.kaj.studioreserve.bookings.BookingStatus
import com.kaj.studioreserve.bookings.BookingsTable
import com.kaj.studioreserve.equipment.EquipmentAction
import com.kaj.studioreserve.equipment.EquipmentLogsTable
import com.kaj.studioreserve.equipment.EquipmentStatus
import com.kaj.studioreserve.equipment.EquipmentTable
import com.kaj.studioreserve.studios.RoomsTable
import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.studios.VerificationStatus
import com.kaj.studioreserve.users.UserRole
import com.kaj.studioreserve.users.UsersTable
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class AdminRoutesIntegrationTest : IntegrationTestBase() {
    private val adminId = UUID.randomUUID()
    private val photographerId = UUID.randomUUID()
    private val studioId = UUID.randomUUID()
    private val roomId = UUID.randomUUID()
    private val bookingId = UUID.randomUUID()
    private val equipmentId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
        seedUsers()
        seedStudioRoomAndBooking()
        seedEquipmentLog()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `admin can view bookings and equipment logs`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(adminId.toString(), UserRole.ADMIN)

        val bookingsResponse = client.get("/api/admin/bookings") {
            client.authenticated(this, token)
        }
        assertEquals(HttpStatusCode.OK, bookingsResponse.status, bookingsResponse.bodyAsText())
        val bookingSummaries = bookingsResponse.body<List<AdminBookingSummaryDto>>()
        assertTrue(bookingSummaries.any { it.id == bookingId.toString() })

        val logsResponse = client.get("/api/admin/equipment-logs") {
            client.authenticated(this, token)
        }
        assertEquals(HttpStatusCode.OK, logsResponse.status, logsResponse.bodyAsText())
        val logs = logsResponse.body<List<AdminEquipmentLogDto>>()
        assertTrue(logs.any { it.equipmentId == equipmentId.toString() })
    }

    @Test
    fun `non admin is forbidden from monitoring endpoints`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(photographerId.toString(), UserRole.PHOTOGRAPHER)

        val response = client.get("/api/admin/bookings") {
            client.authenticated(this, token)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
    }

    private fun seedUsers() {
        transaction {
            UsersTable.insert { statement ->
                statement[id] = adminId
                statement[name] = "Admin"
                statement[phoneNumber] = "+989100000011"
                statement[email] = "admin2@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.ADMIN
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            UsersTable.insert { statement ->
                statement[id] = photographerId
                statement[name] = "Photographer"
                statement[phoneNumber] = "+989100000012"
                statement[email] = "photographer5@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.PHOTOGRAPHER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    private fun seedStudioRoomAndBooking() {
        transaction {
            StudiosTable.insert { statement ->
                statement[id] = studioId
                statement[ownerId] = adminId
                statement[name] = "Admin Studio"
                statement[description] = "desc"
                statement[province] = "province"
                statement[city] = "city"
                statement[address] = "address"
                statement[mapCoordinates] = "0,0"
                statement[photos] = emptyList()
                statement[verificationStatus] = VerificationStatus.APPROVED
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

            BookingsTable.insert { statement ->
                statement[id] = bookingId
                statement[roomId] = roomId
                statement[photographerId] = photographerId
                statement[startTime] = LocalDateTime.now(ZoneOffset.UTC)
                statement[endTime] = LocalDateTime.now(ZoneOffset.UTC).plusHours(1)
                statement[bookingStatus] = BookingStatus.CONFIRMED
                statement[paymentStatus] = com.kaj.studioreserve.payments.PaymentStatus.PAID
                statement[totalPrice] = BigDecimal(100)
                statement[equipmentIds] = emptyList()
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    private fun seedEquipmentLog() {
        transaction {
            EquipmentTable.insert { statement ->
                statement[id] = equipmentId
                statement[studioId] = studioId
                statement[name] = "Light"
                statement[brand] = "Brand"
                statement[type] = "Type"
                statement[rentalPrice] = BigDecimal(50)
                statement[condition] = "New"
                statement[serialNumber] = "SN"
                statement[status] = EquipmentStatus.AVAILABLE
                statement[barcodeCode] = UUID.randomUUID().toString()
                statement[barcodeImageUrl] = null
            }

            EquipmentLogsTable.insert { statement ->
                statement[id] = UUID.randomUUID()
                statement[equipmentId] = equipmentId
                statement[userId] = adminId
                statement[action] = EquipmentAction.SCAN_OUT
                statement[timestamp] = LocalDateTime.now(ZoneOffset.UTC)
                statement[notes] = "log"
            }
        }
    }
}
