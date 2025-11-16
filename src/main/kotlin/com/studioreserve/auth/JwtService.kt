package com.studioreserve.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.util.Date

@Serializable
data class JwtClaims(val userId: String, val role: String)

enum class TokenType { ACCESS, REFRESH }

class JwtService(
    private val clock: Clock = Clock.systemUTC()
) {
    private val secret = env("JWT_SECRET")
    private val issuer = env("JWT_ISSUER")
    private val audience = env("JWT_AUDIENCE")
    private val accessExpiresMinutes = env("JWT_ACCESS_EXPIRES_MIN").toLong()
    private val refreshExpiresMinutes = env("JWT_REFRESH_EXPIRES_MIN").toLong()
    private val algorithm = Algorithm.HMAC256(secret)

    fun generateAccessToken(claims: JwtClaims): String =
        buildToken(claims, accessExpiresMinutes, TokenType.ACCESS)

    fun generateRefreshToken(claims: JwtClaims): String =
        buildToken(claims, refreshExpiresMinutes, TokenType.REFRESH)

    fun accessTokenVerifier(): JWTVerifier = buildVerifier(TokenType.ACCESS)

    fun refreshTokenVerifier(): JWTVerifier = buildVerifier(TokenType.REFRESH)

    private fun buildToken(
        claims: JwtClaims,
        expiresInMinutes: Long,
        tokenType: TokenType,
    ): String {
        val now = Instant.now(clock)
        val expiresAt = Date.from(now.plusSeconds(expiresInMinutes * 60))
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(claims.userId)
            .withClaim("userId", claims.userId)
            .withClaim("role", claims.role)
            .withClaim("tokenType", tokenType.name)
            .withExpiresAt(expiresAt)
            .withIssuedAt(Date.from(now))
            .sign(algorithm)
    }

    private fun buildVerifier(tokenType: TokenType): JWTVerifier = JWT
        .require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("tokenType", tokenType.name)
        .build()

    companion object {
        private fun env(key: String): String = System.getenv(key)
            ?: error("Environment variable '$key' is not set")
    }
}
