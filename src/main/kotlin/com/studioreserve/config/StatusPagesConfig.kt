package com.studioreserve.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val error = ErrorResponse(
                status = HttpStatusCode.InternalServerError.value,
                message = cause.localizedMessage ?: "Unexpected server error"
            )
            call.respond(HttpStatusCode.InternalServerError, error)
        }
    }
}

@Serializable
private data class ErrorResponse(
    val status: Int,
    val message: String
)
