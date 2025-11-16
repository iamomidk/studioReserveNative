package com.studioreserve.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.Application
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.jwt.JWTPrincipal

fun Application.configureAuthentication() {
    val secret = environment.config.propertyOrNull("jwt.secret")?.getString()
        ?: System.getenv("JWT_SECRET")
        ?: "studioreserve-dev-secret"
    val issuer = environment.config.propertyOrNull("jwt.issuer")?.getString()
        ?: System.getenv("JWT_ISSUER")
        ?: "studioreserve"
    val audience = environment.config.propertyOrNull("jwt.audience")?.getString()
        ?: System.getenv("JWT_AUDIENCE")
        ?: "studioreserve-clients"
    val realm = environment.config.propertyOrNull("jwt.realm")?.getString()
        ?: System.getenv("JWT_REALM")
        ?: "StudioReserve"

    val algorithm = Algorithm.HMAC256(secret)
    val verifier = JWT
        .require(algorithm)
        .withAudience(audience)
        .withIssuer(issuer)
        .build()

    install(Authentication) {
        jwt("auth-jwt") {
            this.realm = realm
            this.verifier(verifier)
            validate { credential ->
                if (credential.payload.audience.contains(audience)) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }
}
