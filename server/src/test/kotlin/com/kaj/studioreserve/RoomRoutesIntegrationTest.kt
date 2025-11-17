package com.kaj.studioreserve

import com.kaj.studioreserve.rooms.CreateRoomRequest
import com.kaj.studioreserve.rooms.UpdateRoomRequest
import com.kaj.studioreserve.studios.RoomsTable
import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.studios.VerificationStatus
import com.kaj.studioreserve.users.UserRole
import com.kaj.studioreserve.users.UsersTable
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class RoomRoutesIntegrationTest : IntegrationTestBase() {
    private val ownerId = UUID.randomUUID()
    private val otherOwnerId = UUID.randomUUID()
    private val studioId = UUID.randomUUID()
    private val roomId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
        seedUsers()
        seedStudios()
        seedRooms()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `owner can create and update room`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(ownerId.toString(), UserRole.STUDIO_OWNER)

        val createResponse = client.post("/api/rooms") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateRoomRequest(
                    studioId = studioId.toString(),
                    name = "New Room",
                    description = "desc",
                    hourlyPrice = 1000,
                    dailyPrice = 2000,
                    features = listOf("feature"),
                    images = listOf("img")
                )
            )
        }

        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())

        val updateResponse = client.put("/api/rooms/$roomId") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                UpdateRoomRequest(
                    name = "Updated Room",
                    description = "new desc",
                    hourlyPrice = 1500,
                    dailyPrice = 2500,
                    features = listOf("f1"),
                    images = listOf("i1")
                )
            )
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status, updateResponse.bodyAsText())
    }

    @Test
    fun `non owner cannot manage rooms`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(otherOwnerId.toString(), UserRole.STUDIO_OWNER)

        val response = client.put("/api/rooms/$roomId") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                UpdateRoomRequest(
                    name = "Hack",
                    description = "hack",
                    hourlyPrice = 100,
                    dailyPrice = 200,
                    features = emptyList(),
                    images = emptyList()
                )
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
    }

    @Test
    fun `invalid pricing is rejected`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(ownerId.toString(), UserRole.STUDIO_OWNER)

        val response = client.post("/api/rooms") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateRoomRequest(
                    studioId = studioId.toString(),
                    name = "Invalid Room",
                    description = "desc",
                    hourlyPrice = 0,
                    dailyPrice = -1,
                    features = emptyList(),
                    images = emptyList()
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
    }

    private fun seedUsers() {
        transaction {
            UsersTable.insert { statement ->
                statement[id] = ownerId
                statement[name] = "Owner"
                statement[phoneNumber] = "+989100000003"
                statement[email] = "owner2@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.STUDIO_OWNER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            UsersTable.insert { statement ->
                statement[id] = otherOwnerId
                statement[name] = "Other"
                statement[phoneNumber] = "+989100000004"
                statement[email] = "other2@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.STUDIO_OWNER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    private fun seedStudios() {
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
                statement[verificationStatus] = VerificationStatus.APPROVED
            }
        }
    }

    private fun seedRooms() {
        transaction {
            RoomsTable.insert { statement ->
                statement[id] = roomId
                statement[studioId] = studioId
                statement[name] = "Seed Room"
                statement[description] = "desc"
                statement[hourlyPrice] = 100
                statement[dailyPrice] = 200
                statement[features] = emptyList()
                statement[images] = emptyList()
            }
        }
    }
}
