package com.kaj.studioreserve.e2e

import com.kaj.studioreserve.IntegrationTestBase
import com.kaj.studioreserve.TestDatabase
import com.kaj.studioreserve.auth.AuthResponse
import com.kaj.studioreserve.auth.RegisterRequest
import com.kaj.studioreserve.bookings.BookingDto
import com.kaj.studioreserve.bookings.CreateBookingRequest
import com.kaj.studioreserve.module
import com.kaj.studioreserve.payments.PaymentInitiateResponse
import com.kaj.studioreserve.payments.PaymentStatus
import com.kaj.studioreserve.rooms.CreateRoomRequest
import com.kaj.studioreserve.rooms.RoomDto
import com.kaj.studioreserve.studios.CreateStudioRequest
import com.kaj.studioreserve.studios.StudioDto
import com.kaj.studioreserve.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookingPaymentE2ETest : IntegrationTestBase() {
    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `photographer books room with payment flow`() = testApplication {
        application { module() }

        val client = createClient { defaultClientConfig() }

        val owner = registerUser(client, role = UserRole.STUDIO_OWNER)
        val photographer = registerUser(client, role = UserRole.PHOTOGRAPHER)

        val studio = createStudio(client, owner.token)
        val room = createRoom(client, owner.token, studio.id)
        val equipment = addEquipment(client, owner.token, studio.id)

        val start = Instant.now().plusSeconds(7_200)
        val end = start.plusSeconds(3_600)

        val booking = createBooking(
            client = client,
            token = photographer.token,
            roomId = room.id,
            startTime = start,
            endTime = end,
            equipmentIds = listOf(equipment.id)
        )

        val conflictResponse = client.post("/api/bookings") {
            client.authenticated(this, photographer.token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateBookingRequest(
                    roomId = room.id,
                    startTime = start.plusSeconds(300).toString(),
                    endTime = end.plusSeconds(300).toString(),
                    equipmentIds = emptyList()
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, conflictResponse.status)

        val bookingsResponse = client.get("/api/bookings") {
            client.authenticated(this, photographer.token)
        }
        assertEquals(HttpStatusCode.OK, bookingsResponse.status)
        val bookings = bookingsResponse.body<List<BookingDto>>()
        assertTrue(bookings.any { it.id == booking.id })

        val initiateResponse = client.post("/api/payments/initiate") {
            client.authenticated(this, photographer.token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                mapOf(
                    "bookingId" to booking.id,
                    "gateway" to null
                )
            )
        }
        assertEquals(HttpStatusCode.OK, initiateResponse.status, initiateResponse.bodyAsText())
        val initiation = initiateResponse.body<PaymentInitiateResponse>()
        assertTrue(initiation.paymentUrl.contains(booking.id))

        val callbackResponse = client.get("/api/payments/callback/zarinpal") {
            url {
                parameters.append("Status", "OK")
                parameters.append("Authority", "FAKE-${booking.id}")
                parameters.append("Amount", booking.totalPrice.toInt().toString())
            }
        }
        assertEquals(HttpStatusCode.OK, callbackResponse.status, callbackResponse.bodyAsText())

        val refreshedBookingResponse = client.get("/api/bookings/${booking.id}") {
            client.authenticated(this, photographer.token)
        }
        assertEquals(HttpStatusCode.OK, refreshedBookingResponse.status)
        val refreshedBooking = refreshedBookingResponse.body<BookingDto>()
        assertEquals(PaymentStatus.PAID, refreshedBooking.paymentStatus)

        val equipmentResponse = client.get("/api/equipment") {
            client.authenticated(this, owner.token)
            url { parameters.append("studioId", studio.id) }
        }
        assertEquals(HttpStatusCode.OK, equipmentResponse.status)
        val equipmentList = equipmentResponse.body<List<com.kaj.studioreserve.equipment.EquipmentDto>>()
        assertTrue(equipmentList.any { it.id == equipment.id })
    }

    @Test
    fun `rejects unauthorized or invalid payloads`() = testApplication {
        application { module() }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val unauthorizedStudio = client.post("/api/studios") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(CreateStudioRequest("", "", "", "", "", ""))
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorizedStudio.status)

        val owner = registerUser(client, role = UserRole.STUDIO_OWNER)
        val studio = createStudio(client, owner.token)
        val room = createRoom(client, owner.token, studio.id)
        val photographer = registerUser(client, role = UserRole.PHOTOGRAPHER)

        val invalidBooking = client.post("/api/bookings") {
            client.authenticated(this, photographer.token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateBookingRequest(
                    roomId = room.id,
                    startTime = Instant.now().plusSeconds(3_600).toString(),
                    endTime = Instant.now().minusSeconds(3_600).toString(),
                    equipmentIds = emptyList()
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidBooking.status)
    }

    private suspend fun registerUser(
        client: HttpClient,
        role: UserRole,
        password: String = "password123!"
    ): TestUser {
        val phone = "+9891" + UUID.randomUUID().toString().take(8)
        val response = client.post("/api/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    name = "Test ${role.name}",
                    phoneNumber = phone,
                    email = "${phone}@example.com",
                    password = password,
                    role = role
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val auth = response.body<AuthResponse>()
        return TestUser(auth.userId, auth.accessToken)
    }

    private suspend fun createStudio(client: HttpClient, token: String): StudioDto {
        val response = client.post("/api/studios") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateStudioRequest(
                    name = "Studio", description = "Desc", province = "Tehran", city = "Tehran",
                    address = "123 Street", mapCoordinates = "0,0", photos = listOf("photo1")
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun createRoom(client: HttpClient, token: String, studioId: String): RoomDto {
        val response = client.post("/api/rooms") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateRoomRequest(
                    studioId = studioId,
                    name = "Room A",
                    description = "Nice room",
                    hourlyPrice = 100_000,
                    dailyPrice = 500_000,
                    features = listOf("feature"),
                    images = listOf("image")
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun addEquipment(client: HttpClient, token: String, studioId: String): com.kaj.studioreserve.equipment.EquipmentDto {
        val response = client.post("/api/equipment") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                com.kaj.studioreserve.equipment.CreateEquipmentRequest(
                    studioId = studioId,
                    name = "Camera",
                    brand = "BrandX",
                    type = "Camera",
                    rentalPrice = 10_000,
                    condition = "New",
                    serialNumber = UUID.randomUUID().toString()
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun createBooking(
        client: HttpClient,
        token: String,
        roomId: String,
        startTime: Instant,
        endTime: Instant,
        equipmentIds: List<String>
    ): BookingDto {
        val response = client.post("/api/bookings") {
            client.authenticated(this, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                CreateBookingRequest(
                    roomId = roomId,
                    startTime = startTime.toString(),
                    endTime = endTime.toString(),
                    equipmentIds = equipmentIds
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private data class TestUser(val id: String, val token: String)
}
