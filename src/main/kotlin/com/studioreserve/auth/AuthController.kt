package com.studioreserve.auth

import com.studioreserve.users.UserRole
import com.studioreserve.users.UsersTable
import io.ktor.http.HttpStatusCode
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.singleOrNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class AuthController(
    private val passwordHasher: PasswordHasher = BCryptPasswordHasher(),
    private val jwtService: JwtService = JwtService()
) {
    fun register(request: RegisterRequest): AuthResponse {
        val normalizedEmail = request.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val phone = request.phoneNumber.trim()

        transaction {
            val phoneExists = UsersTable.select { UsersTable.phoneNumber eq phone }.empty().not()
            if (phoneExists) {
                throw AuthException(HttpStatusCode.Conflict, "Phone number already registered")
            }

            if (normalizedEmail != null) {
                val emailExists = UsersTable.select { UsersTable.email eq normalizedEmail }.empty().not()
                if (emailExists) {
                    throw AuthException(HttpStatusCode.Conflict, "Email already registered")
                }
            }
        }

        val userId = UUID.randomUUID()
        val passwordHash = passwordHasher.hash(request.password)
        val now = LocalDateTime.now(ZoneOffset.UTC)

        transaction {
            UsersTable.insert { statement ->
                statement[UsersTable.id] = userId
                statement[UsersTable.name] = request.name.trim()
                statement[UsersTable.phoneNumber] = phone
                statement[UsersTable.email] = normalizedEmail
                statement[UsersTable.passwordHash] = passwordHash
                statement[UsersTable.role] = request.role
                statement[UsersTable.avatarUrl] = null
                statement[UsersTable.createdAt] = now
            }
        }

        return issueTokens(userId, request.role)
    }

    fun login(request: LoginRequest): AuthResponse {
        val identifier = request.phoneOrEmail.trim()
        val normalizedEmail = identifier.lowercase()

        val userRow = transaction {
            UsersTable
                .select {
                    (UsersTable.phoneNumber eq identifier) or (UsersTable.email eq normalizedEmail)
                }
                .singleOrNull()
        } ?: throw AuthException(HttpStatusCode.BadRequest, "Invalid credentials")

        if (!passwordHasher.verify(request.password, userRow[UsersTable.passwordHash])) {
            throw AuthException(HttpStatusCode.BadRequest, "Invalid credentials")
        }

        val userId = userRow[UsersTable.id]
        val role = userRow[UsersTable.role]
        return issueTokens(userId, role)
    }

    fun me(userId: UUID): MeResponse {
        val row = transaction {
            UsersTable.select { UsersTable.id eq userId }.singleOrNull()
        } ?: throw AuthException(HttpStatusCode.NotFound, "User not found")

        return MeResponse(
            id = row[UsersTable.id].toString(),
            name = row[UsersTable.name],
            phoneNumber = row[UsersTable.phoneNumber],
            email = row[UsersTable.email],
            role = row[UsersTable.role],
            avatarUrl = row[UsersTable.avatarUrl],
            createdAt = row[UsersTable.createdAt].atOffset(ZoneOffset.UTC).toString()
        )
    }

    fun refresh(refreshToken: String): AuthResponse {
        val decoded = runCatching { jwtService.verify(refreshToken) }
            .getOrElse { throw AuthException(HttpStatusCode.BadRequest, "Invalid refresh token") }

        val tokenType = decoded.getClaim(JwtService.CLAIM_TOKEN_TYPE).asString()
        if (tokenType != TokenType.REFRESH.claimValue) {
            throw AuthException(HttpStatusCode.BadRequest, "Invalid refresh token type")
        }

        val tokenId = decoded.getClaim(JwtService.CLAIM_TOKEN_ID).asString()
            ?: throw AuthException(HttpStatusCode.BadRequest, "Missing refresh token id")
        val userId = decoded.getClaim("userId").asString()
            ?: throw AuthException(HttpStatusCode.BadRequest, "Missing user id claim")
        val roleName = decoded.getClaim("role").asString()
            ?: throw AuthException(HttpStatusCode.BadRequest, "Missing role claim")

        val tokenUuid = runCatching { UUID.fromString(tokenId) }
            .getOrElse { throw AuthException(HttpStatusCode.BadRequest, "Invalid token id") }
        val userUuid = runCatching { UUID.fromString(userId) }
            .getOrElse { throw AuthException(HttpStatusCode.BadRequest, "Invalid user id") }

        val now = LocalDateTime.now(ZoneOffset.UTC)
        val record = transaction {
            RefreshTokensTable
                .select {
                    (RefreshTokensTable.tokenId eq tokenUuid) and (RefreshTokensTable.userId eq userUuid)
                }
                .singleOrNull()
        } ?: throw AuthException(HttpStatusCode.BadRequest, "Refresh token not recognized")

        if (record[RefreshTokensTable.revoked]) {
            throw AuthException(HttpStatusCode.BadRequest, "Refresh token has been revoked")
        }

        if (record[RefreshTokensTable.expiresAt].isBefore(now)) {
            throw AuthException(HttpStatusCode.BadRequest, "Refresh token expired")
        }

        transaction {
            RefreshTokensTable.update({ RefreshTokensTable.tokenId eq tokenUuid }) { statement ->
                statement[revoked] = true
            }
        }

        val role = runCatching { UserRole.valueOf(roleName) }
            .getOrElse { throw AuthException(HttpStatusCode.BadRequest, "Unknown role") }

        return issueTokens(userUuid, role)
    }

    private fun issueTokens(userId: UUID, role: UserRole): AuthResponse {
        val claims = JwtClaims(userId.toString(), role.name)
        val accessToken = jwtService.generateAccessToken(claims)
        val refreshToken = jwtService.generateRefreshToken(claims)

        val now = LocalDateTime.now(ZoneOffset.UTC)
        transaction {
            RefreshTokensTable.insert { statement ->
                statement[RefreshTokensTable.id] = UUID.randomUUID()
                statement[RefreshTokensTable.tokenId] = UUID.fromString(refreshToken.tokenId)
                statement[RefreshTokensTable.userId] = userId
                statement[RefreshTokensTable.expiresAt] = LocalDateTime.ofInstant(refreshToken.expiresAt, ZoneOffset.UTC)
                statement[RefreshTokensTable.createdAt] = now
                statement[RefreshTokensTable.revoked] = false
            }
        }

        return AuthResponse(
            accessToken = accessToken.token,
            refreshToken = refreshToken.token,
            userId = userId.toString(),
            role = role
        )
    }
}

class AuthException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

@Serializable
data class MeResponse(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val email: String?,
    val role: UserRole,
    val avatarUrl: String?,
    val createdAt: String
)
