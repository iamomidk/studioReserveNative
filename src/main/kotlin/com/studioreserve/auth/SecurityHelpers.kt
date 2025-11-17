package com.studioreserve.auth

import com.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import java.util.UUID

class AuthorizationException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

fun ApplicationCall.currentUserId(): UUID {
    val principal = principal<UserPrincipal>()
        ?: throw AuthorizationException(HttpStatusCode.Unauthorized, "Missing authentication token")
    return runCatching { UUID.fromString(principal.userId) }
        .getOrElse { throw AuthorizationException(HttpStatusCode.BadRequest, "Invalid user id claim") }
}

fun ApplicationCall.requireRole(vararg roles: UserRole): UserRole {
    val principal = principal<UserPrincipal>()
        ?: throw AuthorizationException(HttpStatusCode.Unauthorized, "Missing authentication token")
    val userRole = runCatching { UserRole.valueOf(principal.role) }
        .getOrElse { throw AuthorizationException(HttpStatusCode.Forbidden, "Unknown role") }

    if (roles.isNotEmpty() && userRole !in roles.toSet()) {
        throw AuthorizationException(HttpStatusCode.Forbidden, "Insufficient permissions")
    }

    return userRole
}
