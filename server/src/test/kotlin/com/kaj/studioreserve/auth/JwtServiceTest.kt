package com.kaj.studioreserve.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class JwtServiceTest {
    private val fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = JwtService(clock = fixedClock)

    @Test
    fun `generated access token carries claims`() {
        val token = service.generateAccessToken(JwtClaims("user-1", "PHOTOGRAPHER"))

        val decoded = service.verify(token.token)
        assertEquals("user-1", decoded.getClaim("userId").asString())
        assertEquals("PHOTOGRAPHER", decoded.getClaim("role").asString())
        assertEquals(TokenType.ACCESS.claimValue, decoded.getClaim(JwtService.CLAIM_TOKEN_TYPE).asString())
    }

    @Test
    fun `refresh token embeds token id`() {
        val token = service.generateRefreshToken(JwtClaims("user-2", "ADMIN"))

        assertNotNull(token.tokenId)
        val decoded = service.verify(token.token)
        assertEquals(token.tokenId, decoded.id)
        assertEquals(TokenType.REFRESH.claimValue, decoded.getClaim(JwtService.CLAIM_TOKEN_TYPE).asString())
    }

    @Test
    fun `tampered token fails verification`() {
        val token = service.generateAccessToken(JwtClaims("user-3", "STUDIO_OWNER"))
        val tampered = token.token + "a"

        assertFailsWith<Exception> { service.verify(tampered) }
    }
}
