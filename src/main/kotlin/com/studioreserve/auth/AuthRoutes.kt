package com.studioreserve.auth

import com.studioreserve.users.UserRole
import com.studioreserve.users.UsersTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.singleOrNull
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class RegisterRequest(
    val name: String,
    val phoneNumber: String,
    val email: String? = null,
    val password: String,
    val role: UserRole
)

@Serializable
data class LoginRequest(
    val phoneOrEmail: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val role: UserRole
)

fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            val request = runCatching { call.receive<RegisterRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request payload"))
                return@post
            }

            val validationError = validateRegisterRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                return@post
            }

            val normalizedEmail = request.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            val phone = request.phoneNumber.trim()

            val conflictMessage = transaction {
                when {
                    UsersTable.select { UsersTable.phoneNumber eq phone }.empty().not() ->
                        "Phone number already registered"
                    normalizedEmail != null &&
                        UsersTable.select { UsersTable.email eq normalizedEmail }.empty().not() ->
                        "Email already registered"
                    else -> null
                }
            }

            if (conflictMessage != null) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to conflictMessage))
                return@post
            }

            val userId = UUID.randomUUID()
            val passwordHash = hashPassword(request.password)
            val assignedRole = UserRole.PHOTOGRAPHER
            val createdAt = LocalDateTime.now(ZoneOffset.UTC)

            transaction {
                UsersTable.insert { statement ->
                    statement[UsersTable.id] = userId
                    statement[UsersTable.name] = request.name.trim()
                    statement[UsersTable.phoneNumber] = phone
                    statement[UsersTable.email] = normalizedEmail
                    statement[UsersTable.passwordHash] = passwordHash
                    statement[UsersTable.role] = assignedRole
                    statement[UsersTable.avatarUrl] = null
                    statement[UsersTable.createdAt] = createdAt
                }
            }

            val response = AuthResponse(
                accessToken = generateToken(),
                refreshToken = generateToken(),
                userId = userId.toString(),
                role = assignedRole
            )

            call.respond(HttpStatusCode.Created, response)
        }

        post("/login") {
            val request = runCatching { call.receive<LoginRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request payload"))
                return@post
            }

            if (request.phoneOrEmail.isBlank() || request.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Phone/email and password are required"))
                return@post
            }

            val identifier = request.phoneOrEmail.trim()
            val normalizedEmail = identifier.lowercase()

            val userRow = transaction {
                UsersTable
                    .select {
                        (UsersTable.phoneNumber eq identifier) or
                            (UsersTable.email eq normalizedEmail)
                    }
                    .singleOrNull()
            }

            if (userRow == null || !verifyPassword(request.password, userRow[UsersTable.passwordHash])) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid credentials"))
                return@post
            }

            val response = AuthResponse(
                accessToken = generateToken(),
                refreshToken = generateToken(),
                userId = userRow[UsersTable.id].toString(),
                role = userRow[UsersTable.role]
            )

            call.respond(HttpStatusCode.OK, response)
        }
    }
}

private fun validateRegisterRequest(request: RegisterRequest): String? {
    if (request.name.isBlank()) return "Name is required"
    if (request.phoneNumber.isBlank()) return "Phone number is required"
    if (request.password.length < 8) return "Password must be at least 8 characters"
    if (request.email != null && request.email.isNotBlank() && !request.email.contains('@')) {
        return "Email must be valid"
    }
    return null
}

private fun hashPassword(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt())

private fun verifyPassword(password: String, hash: String): Boolean = BCrypt.checkpw(password, hash)

private fun generateToken(): String = UUID.randomUUID().toString()
