package com.studioreserve.config

import com.studioreserve.common.ApiError
import com.studioreserve.common.ApiException
import com.studioreserve.common.apiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

fun Application.configureStatusPages() {
    val logger = LoggerFactory.getLogger("StatusPages")

    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ApiError(code = cause.errorCode, message = cause.message, details = cause.details))
        }

        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            val error = apiError(
                code = "internal_error",
                message = cause.localizedMessage ?: "Unexpected server error"
            )
            call.respond(HttpStatusCode.InternalServerError, error)
        }
    }
}
