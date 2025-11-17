package com.studioreserve.auth

import com.studioreserve.TestDatabase
import com.studioreserve.users.UserRole
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthControllerTest {
    private val controller = AuthController()

    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `register inserts user and returns tokens`() {
        val response = controller.register(
            RegisterRequest(
                name = "Alice",
                phoneNumber = "+989121234567",
                email = "alice@example.com",
                password = "password123",
                role = UserRole.PHOTOGRAPHER
            )
        )

        assertEquals(UserRole.PHOTOGRAPHER, response.role)
        assertTrue(response.accessToken.isNotBlank())
        assertTrue(response.refreshToken.isNotBlank())
    }

    @Test
    fun `login succeeds after registration`() {
        val phone = "+989121234567"
        controller.register(
            RegisterRequest(
                name = "Bob",
                phoneNumber = phone,
                email = null,
                password = "password123",
                role = UserRole.PHOTOGRAPHER
            )
        )

        val response = controller.login(LoginRequest(phoneOrEmail = phone, password = "password123"))
        assertEquals(UserRole.PHOTOGRAPHER, response.role)
    }

    @Test
    fun `refresh rotates token and revokes previous`() {
        val phone = "+989121234567"
        val initial = controller.register(
            RegisterRequest(
                name = "Carol",
                phoneNumber = phone,
                email = null,
                password = "password123",
                role = UserRole.PHOTOGRAPHER
            )
        )

        val refreshed = controller.refresh(initial.refreshToken)

        assertNotNull(refreshed)
        assertTrue(refreshed.accessToken.isNotBlank())
        assertTrue(refreshed.refreshToken.isNotBlank())
    }
}
