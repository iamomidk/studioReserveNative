package com.studioreserve.config

import com.studioreserve.auth.JwtService
import com.studioreserve.auth.TokenType
import com.studioreserve.auth.UserPrincipal
import io.ktor.server.application.Application
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt

fun Application.configureAuthentication(jwtService: JwtService = JwtService()) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "StudioReserve"
            verifier(jwtService.verifier())
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asString()
                val role = credential.payload.getClaim("role").asString()
                val tokenType = credential.payload.getClaim(JwtService.CLAIM_TOKEN_TYPE).asString()

                val isAccessToken = tokenType == TokenType.ACCESS.claimValue
                if (!userId.isNullOrBlank() && !role.isNullOrBlank() && isAccessToken) {
                    UserPrincipal(userId, role)
                } else {
                    null
                }
            }
        }
    }
}
