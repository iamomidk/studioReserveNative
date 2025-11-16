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

class JwtService(
    private val clock: Clock = Clock.systemUTC()
) {
    private val secret = env("JWT_SECRET")
    private val issuer = env("JWT_ISSUER")
    private val audience = env("JWT_AUDIENCE")
    private val accessExpiresMinutes = env("JWT_ACCESS_EXPIRES_MIN").toLong()
    private val refreshExpiresMinutes = env("JWT_REFRESH_EXPIRES_MIN").toLong()
    private val algorithm = Algorithm.HMAC256(secret)

    fun generateAccessToken(claims: JwtClaims): String = buildToken(claims, accessExpiresMinutes)

    fun generateRefreshToken(claims: JwtClaims): String = buildToken(claims, refreshExpiresMinutes)

    fun verifier(): JWTVerifier = JWT
        .require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    private fun buildToken(claims: JwtClaims, expiresInMinutes: Long): String {
        val now = Instant.now(clock)
        val expiresAt = Date.from(now.plusSeconds(expiresInMinutes * 60))
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(claims.userId)
            .withClaim("userId", claims.userId)
            .withClaim("role", claims.role)
            .withExpiresAt(expiresAt)
            .withIssuedAt(Date.from(now))
            .sign(algorithm)
    }

    companion object {
        private fun env(key: String): String = System.getenv(key)
            ?: error("Environment variable '$key' is not set")
    }
}
