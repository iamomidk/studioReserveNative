package com.studioreserve.auth

import com.studioreserve.IntegrationTestBase
import com.studioreserve.TestDatabase
import com.studioreserve.module
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRoutesTest : IntegrationTestBase() {
    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `register login and me flow`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }

        val registerResponse = client.post("/api/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    name = "Dave",
                    phoneNumber = "+989111111111",
                    email = "dave@example.com",
                    password = "password123",
                    role = com.studioreserve.users.UserRole.PHOTOGRAPHER
                )
            )
        }

        assertEquals(HttpStatusCode.Created, registerResponse.status, registerResponse.bodyAsText())
        val authResponse = registerResponse.body<AuthResponse>()
        assertTrue(authResponse.accessToken.isNotBlank())

        val meResponse = client.get("/api/auth/me") {
            authenticated(this, authResponse.accessToken)
        }

        assertEquals(HttpStatusCode.OK, meResponse.status)
        val me = meResponse.body<MeResponse>()
        assertEquals(authResponse.userId, me.id)
    }

    @Test
    fun `login fails with wrong password`() = testApplication {
        application { module() }
        val client = createClient { defaultClientConfig() }

        client.post("/api/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    name = "Eve",
                    phoneNumber = "+989122222222",
                    email = null,
                    password = "password123",
                    role = com.studioreserve.users.UserRole.PHOTOGRAPHER
                )
            )
        }

        val response = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(LoginRequest(phoneOrEmail = "+989122222222", password = "wrong"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
