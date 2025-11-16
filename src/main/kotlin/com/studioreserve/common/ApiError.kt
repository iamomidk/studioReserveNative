package com.studioreserve.common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, String>? = null
)

class ApiException(
    val status: HttpStatusCode,
    val errorCode: String,
    override val message: String,
    val details: Map<String, String>? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

fun apiError(
    code: String,
    message: String,
    details: Map<String, String>? = null
): ApiError = ApiError(code = code, message = message, details = details)

fun badRequest(code: String, message: String, details: Map<String, String>? = null): Nothing =
    throw ApiException(HttpStatusCode.BadRequest, code, message, details)

fun unauthorized(code: String = "unauthorized", message: String = "Unauthorized"): Nothing =
    throw ApiException(HttpStatusCode.Unauthorized, code, message)

fun forbidden(code: String = "forbidden", message: String = "Forbidden"): Nothing =
    throw ApiException(HttpStatusCode.Forbidden, code, message)

fun notFound(code: String = "not_found", message: String = "Resource not found"): Nothing =
    throw ApiException(HttpStatusCode.NotFound, code, message)

fun conflict(code: String = "conflict", message: String = "Conflict"): Nothing =
    throw ApiException(HttpStatusCode.Conflict, code, message)
