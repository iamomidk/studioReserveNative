package com.studioreserve.admin

import com.studioreserve.auth.UserPrincipal
import com.studioreserve.settings.SystemSettingsRepository
import com.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

fun Route.adminSettingsRoutes(
    systemSettingsRepository: SystemSettingsRepository = SystemSettingsRepository()
) {
    authenticate("auth-jwt") {
        route("/api/admin/settings") {
            get("/commission") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.ADMIN) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required"))
                }

                val percent = systemSettingsRepository.getCommissionPercent()
                call.respond(CommissionResponse(percent))
            }

            put("/commission") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.ADMIN) {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required"))
                }

                val request = runCatching { call.receive<UpdateCommissionRequest>() }.getOrElse {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                if (request.percent !in 0..100) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("percent must be between 0 and 100"))
                }

                val updatedPercent = systemSettingsRepository.setCommissionPercent(request.percent)
                call.respond(CommissionResponse(updatedPercent))
            }
        }
    }
}

@Serializable
private data class CommissionResponse(val percent: Int)

@Serializable
private data class UpdateCommissionRequest(val percent: Int)

@Serializable
private data class ErrorResponse(val message: String)
