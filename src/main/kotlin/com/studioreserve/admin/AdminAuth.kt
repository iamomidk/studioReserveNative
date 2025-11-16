package com.studioreserve.admin

import com.studioreserve.auth.UserPrincipal
import com.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

suspend fun ApplicationCall.ensureAdminPrincipal(): UserPrincipal? {
    val principal = principal<UserPrincipal>()
        ?: run {
            respond(HttpStatusCode.Unauthorized, AdminErrorResponse("Missing authentication token"))
            return null
        }

    val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
        ?: run {
            respond(HttpStatusCode.Forbidden, AdminErrorResponse("Unknown role"))
            return null
        }

    if (role != UserRole.ADMIN) {
        respond(HttpStatusCode.Forbidden, AdminErrorResponse("Admin privileges required"))
        return null
    }

    return principal
}

@Serializable
data class AdminErrorResponse(val message: String)
