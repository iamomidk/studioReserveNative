package com.kaj.studioreserve

import com.kaj.studioreserve.equipment.CreateEquipmentRequest
import com.kaj.studioreserve.equipment.EquipmentAction
import com.kaj.studioreserve.equipment.EquipmentLogsTable
import com.kaj.studioreserve.equipment.EquipmentStatus
import com.kaj.studioreserve.equipment.EquipmentTable
import com.kaj.studioreserve.equipment.ScanRequest
import com.kaj.studioreserve.equipment.UpdateEquipmentRequest
import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.users.UserRole
import com.kaj.studioreserve.users.UsersTable
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

class EquipmentRoutesIntegrationTest : IntegrationTestBase() {
    private val ownerId = UUID.randomUUID()
    private val otherOwnerId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()
    private val studioId = UUID.randomUUID()
    private val equipmentId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
        seedUsers()
        seedStudios()
        seedEquipment()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `owner can create equipment and admin can list`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val ownerToken = generateToken(ownerId.toString(), UserRole.STUDIO_OWNER)
        val adminToken = generateToken(adminId.toString(), UserRole.ADMIN)

        val createResponse = client.post("/api/equipment") {
            client.authenticated(this, ownerToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateEquipmentRequest(
                    studioId = studioId.toString(),
                    name = "Camera",
                    brand = "Brand",
                    type = "Type",
                    rentalPrice = 100,
                    condition = "New",
                    serialNumber = "SN"
                )
            )
        }

        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())

        val listResponse = client.get("/api/equipment") {
            client.authenticated(this, adminToken)
        }

        assertEquals(HttpStatusCode.OK, listResponse.status, listResponse.bodyAsText())
    }

    @Test
    fun `photographer cannot list equipment`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(UUID.randomUUID().toString(), UserRole.PHOTOGRAPHER)

        val response = client.get("/api/equipment") {
            client.authenticated(this, token)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
    }

    @Test
    fun `invalid update payload is rejected`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val token = generateToken(ownerId.toString(), UserRole.STUDIO_OWNER)

        val response = client.patch("/api/equipment/$equipmentId") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(UpdateEquipmentRequest())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
    }

    @Test
    fun `scan transitions enforce status and ownership`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }
        val ownerToken = generateToken(ownerId.toString(), UserRole.STUDIO_OWNER)
        val otherToken = generateToken(otherOwnerId.toString(), UserRole.STUDIO_OWNER)

        val barcode = transaction {
            EquipmentTable.select { EquipmentTable.id eq equipmentId }.single()[EquipmentTable.barcodeCode]
        }

        val forbiddenResponse = client.post("/api/equipment/scan") {
            client.authenticated(this, otherToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(ScanRequest(barcodeCode = barcode, action = EquipmentAction.SCAN_OUT))
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenResponse.status, forbiddenResponse.bodyAsText())

        val scanOutResponse = client.post("/api/equipment/scan") {
            client.authenticated(this, ownerToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(ScanRequest(barcodeCode = barcode, action = EquipmentAction.SCAN_OUT))
        }
        assertEquals(HttpStatusCode.OK, scanOutResponse.status, scanOutResponse.bodyAsText())

        val invalidScanInResponse = client.post("/api/equipment/scan") {
            client.authenticated(this, ownerToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(ScanRequest(barcodeCode = barcode, action = EquipmentAction.SCAN_OUT))
        }
        assertEquals(HttpStatusCode.Conflict, invalidScanInResponse.status, invalidScanInResponse.bodyAsText())

        val scanInResponse = client.post("/api/equipment/scan") {
            client.authenticated(this, ownerToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(ScanRequest(barcodeCode = barcode, action = EquipmentAction.SCAN_IN))
        }
        assertEquals(HttpStatusCode.OK, scanInResponse.status, scanInResponse.bodyAsText())

        val logCount = transaction {
            EquipmentLogsTable.select { EquipmentLogsTable.equipmentId eq equipmentId }.count()
        }
        assertEquals(2, logCount)
    }

    private fun seedUsers() {
        transaction {
            UsersTable.insert { statement ->
                statement[id] = ownerId
                statement[name] = "Owner"
                statement[phoneNumber] = "+989100000005"
                statement[email] = "owner3@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.STUDIO_OWNER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            UsersTable.insert { statement ->
                statement[id] = otherOwnerId
                statement[name] = "Other Owner"
                statement[phoneNumber] = "+989100000006"
                statement[email] = "other3@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.STUDIO_OWNER
                statement[avatarUrl] = null
                statement[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            UsersTable.insert { statement ->
                statement[id] = adminId
                statement[name] = "Admin"
                statement[phoneNumber] = "+989100000007"
                statement[email] = "admin@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.ADMIN
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
                statement[verificationStatus] = com.kaj.studioreserve.studios.VerificationStatus.APPROVED
            }
        }
    }

    private fun seedEquipment() {
        transaction {
            EquipmentTable.insert { statement ->
                statement[id] = equipmentId
                statement[studioId] = studioId
                statement[name] = "Seed Equipment"
                statement[brand] = "Brand"
                statement[type] = "Type"
                statement[rentalPrice] = 100.toBigDecimal()
                statement[condition] = "New"
                statement[serialNumber] = "SN-123"
                statement[status] = EquipmentStatus.AVAILABLE
                statement[barcodeCode] = UUID.randomUUID().toString()
                statement[barcodeImageUrl] = null
            }
        }
    }
}
