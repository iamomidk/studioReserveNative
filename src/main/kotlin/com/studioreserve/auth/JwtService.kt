package com.studioreserve.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class JwtClaims(val userId: String, val role: String)

data class GeneratedToken(
    val token: String,
    val expiresAt: Instant,
    val tokenId: String? = null,
    val tokenType: TokenType
)

enum class TokenType(val claimValue: String) {
    ACCESS("access"),
    REFRESH("refresh")
}

class JwtService(
    private val clock: Clock = Clock.systemUTC()
) {
    private val secret = env("JWT_SECRET", "test-secret")
    private val issuer = env("JWT_ISSUER", "studioreserve")
    private val audience = env("JWT_AUDIENCE", "studioreserve-clients")
    private val accessExpiresMinutes = env("JWT_ACCESS_EXPIRES_MIN", "60").toLong()
    private val refreshExpiresMinutes = env("JWT_REFRESH_EXPIRES_MIN", "120").toLong()
    private val algorithm = Algorithm.HMAC256(secret)

    fun generateAccessToken(claims: JwtClaims): GeneratedToken =
        buildToken(claims, accessExpiresMinutes, TokenType.ACCESS, tokenId = null)

    fun generateRefreshToken(claims: JwtClaims): GeneratedToken {
        val tokenId = UUID.randomUUID().toString()
        return buildToken(claims, refreshExpiresMinutes, TokenType.REFRESH, tokenId)
    }

    fun verifier(): JWTVerifier = JWT
        .require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun verify(token: String): DecodedJWT = verifier().verify(token)

    private fun buildToken(
        claims: JwtClaims,
        expiresInMinutes: Long,
        tokenType: TokenType,
        tokenId: String?
    ): GeneratedToken {
        val now = Instant.now(clock)
        val expiresAt = now.plusSeconds(expiresInMinutes * 60)
        val builder = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(claims.userId)
            .withClaim("userId", claims.userId)
            .withClaim("role", claims.role)
            .withClaim(CLAIM_TOKEN_TYPE, tokenType.claimValue)
            .withExpiresAt(Date.from(expiresAt))
            .withIssuedAt(Date.from(now))

        tokenId?.let {
            builder.withJWTId(it)
            builder.withClaim(CLAIM_TOKEN_ID, it)
        }

        val signed = builder.sign(algorithm)
        return GeneratedToken(
            token = signed,
            expiresAt = expiresAt,
            tokenId = tokenId,
            tokenType = tokenType
        )
    }

    companion object {
        const val CLAIM_TOKEN_TYPE = "tokenType"
        const val CLAIM_TOKEN_ID = "tokenId"

        private fun env(key: String, default: String): String = System.getenv(key)
            ?: System.getProperty(key)
            ?: default
    }
}
