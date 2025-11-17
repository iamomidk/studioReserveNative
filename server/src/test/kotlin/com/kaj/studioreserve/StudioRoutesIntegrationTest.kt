package com.kaj.studioreserve

import com.kaj.studioreserve.studios.CreateStudioRequest
import com.kaj.studioreserve.studios.UpdateStudioRequest
import com.kaj.studioreserve.studios.VerificationStatus
import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.users.UserRole
import com.kaj.studioreserve.users.UsersTable
import io.ktor.client.call.body
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

class StudioRoutesIntegrationTest : IntegrationTestBase() {
    private val ownerId = UUID.randomUUID()
    private val otherOwnerId = UUID.randomUUID()
    private val existingStudioId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
        seedUsers()
        seedStudios()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `studio owner can create and update own studio`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(ownerId.toString(), UserRole.STUDIO_OWNER)

        val createResponse = client.post("/api/studios") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateStudioRequest(
                    name = "My Studio",
                    description = "desc",
                    province = "province",
                    city = "city",
                    address = "address",
                    mapCoordinates = "0,0",
                    photos = listOf("photo1")
                )
            )
        }

        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())

        val createdStudio = createResponse.body<Map<String, Any?>>()
        val studioId = createdStudio["id"] as String

        val updateResponse = client.put("/api/studios/$studioId") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                UpdateStudioRequest(
                    name = "Updated Studio",
                    description = "new desc",
                    province = "province",
                    city = "city",
                    address = "new address",
                    mapCoordinates = "1,1",
                    photos = listOf("photo2")
                )
            )
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status, updateResponse.bodyAsText())
    }

    @Test
    fun `non owner cannot update studio`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(otherOwnerId.toString(), UserRole.STUDIO_OWNER)

        val response = client.put("/api/studios/$existingStudioId") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                UpdateStudioRequest(
                    name = "Hacked Studio",
                    description = "desc",
                    province = "province",
                    city = "city",
                    address = "address",
                    mapCoordinates = "0,0",
                    photos = listOf("photo")
                )
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
    }

    @Test
    fun `validation errors return bad request`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(ownerId.toString(), UserRole.STUDIO_OWNER)

        val response = client.post("/api/studios") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateStudioRequest(
                    name = "",
                    description = "",
                    province = "province",
                    city = "city",
                    address = "address",
                    mapCoordinates = "0,0",
                    photos = emptyList()
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
                statement[phoneNumber] = "+989100000001"
                statement[email] = "owner@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.STUDIO_OWNER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            UsersTable.insert { statement ->
                statement[id] = otherOwnerId
                statement[name] = "Other Owner"
                statement[phoneNumber] = "+989100000002"
                statement[email] = "other@example.com"
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
                statement[id] = existingStudioId
                statement[ownerId] = ownerId
                statement[name] = "Existing Studio"
                statement[description] = "desc"
                statement[province] = "province"
                statement[city] = "city"
                statement[address] = "address"
                statement[mapCoordinates] = "0,0"
                statement[photos] = listOf("photo")
                statement[verificationStatus] = VerificationStatus.APPROVED
            }
        }
    }
}
