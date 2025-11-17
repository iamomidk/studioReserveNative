package com.studioreserve.bookings

import com.studioreserve.IntegrationTestBase
import com.studioreserve.TestDatabase
import com.studioreserve.module
import com.studioreserve.studios.RoomsTable
import com.studioreserve.studios.StudiosTable
import com.studioreserve.users.UserRole
import com.studioreserve.users.UsersTable
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class BookingRoutesIntegrationTest : IntegrationTestBase() {
    private val ownerId = UUID.randomUUID()
    private val photographerId = UUID.randomUUID()
    private val studioId = UUID.randomUUID()
    private val roomId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
        seedData()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `photographer can create booking and conflict is rejected`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(photographerId.toString(), UserRole.PHOTOGRAPHER)

        val start = Instant.now().plusSeconds(3600)
        val end = start.plusSeconds(3600)

        val createResponse = client.post("/api/bookings") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateBookingRequest(
                    roomId = roomId.toString(),
                    startTime = start.toString(),
                    endTime = end.toString(),
                    equipmentIds = emptyList()
                )
            )
        }

        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())
        val booking = createResponse.body<BookingDto>()
        assertEquals(roomId.toString(), booking.roomId)

        val conflictResponse = client.post("/api/bookings") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateBookingRequest(
                    roomId = roomId.toString(),
                    startTime = start.plusSeconds(600).toString(),
                    endTime = end.plusSeconds(600).toString(),
                    equipmentIds = emptyList()
                )
            )
        }

        assertEquals(HttpStatusCode.Conflict, conflictResponse.status)
    }

    private fun seedData() {
        transaction {
            UsersTable.insert { statement ->
                statement[id] = ownerId
                statement[name] = "Owner"
                statement[phoneNumber] = "+989100000001"
                statement[email] = "owner@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.STUDIO_OWNER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            UsersTable.insert { statement ->
                statement[id] = photographerId
                statement[name] = "Photographer"
                statement[phoneNumber] = "+989100000002"
                statement[email] = "photographer@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.PHOTOGRAPHER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

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
            }

            RoomsTable.insert { statement ->
                statement[id] = roomId
                statement[studioId] = studioId
                statement[name] = "Room"
                statement[description] = "desc"
                statement[hourlyPrice] = 100000
                statement[dailyPrice] = 300000
                statement[features] = emptyList()
                statement[images] = emptyList()
            }
        }
    }
}
