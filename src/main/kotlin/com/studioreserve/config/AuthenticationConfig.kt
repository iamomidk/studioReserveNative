package com.studioreserve.config

import com.studioreserve.auth.JwtService
import com.studioreserve.auth.UserPrincipal
import io.ktor.server.application.Application
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt

fun Application.configureAuthentication(jwtService: JwtService = JwtService()) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "StudioReserve"
            verifier(jwtService.accessTokenVerifier())
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asString()
                val role = credential.payload.getClaim("role").asString()
                if (!userId.isNullOrBlank() && !role.isNullOrBlank()) {
                    UserPrincipal(userId, role)
                } else {
                    null
                }
            }
        }
    }
}
