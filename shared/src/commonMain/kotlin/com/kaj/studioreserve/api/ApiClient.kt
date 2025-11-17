package com.kaj.studioreserve.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class StudioReserveApi(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val httpClient: HttpClient = defaultClient,
) {
    suspend fun listBookings(): Result<List<BookingDto>> = safeGet("/api/bookings")

    suspend fun createBooking(request: CreateBookingRequest): Result<BookingDto> = safePost(
        path = "/api/bookings",
        payload = request,
        expectStatus = HttpStatusCode.Created,
    )

    suspend fun updateBookingStatus(id: String, status: UpdateBookingStatusRequest): Result<BookingDto> = safePatch(
        path = "/api/bookings/$id/status",
        payload = status,
    )

    private suspend inline fun <reified T> safeGet(path: String): Result<T> = runCatching {
        val response = httpClient.get {
            url(baseUrl + path)
            headerAuthorization()
            accept(ContentType.Application.Json)
        }
        response.bodyOrApiError()
    }

    private suspend inline fun <reified T> safePost(
        path: String,
        payload: Any,
        expectStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Result<T> = runCatching {
        val response = httpClient.post {
            url(baseUrl + path)
            headerAuthorization()
            accept(ContentType.Application.Json)
            setBody(payload)
        }
        response.ensureStatus(expectStatus).bodyOrApiError()
    }

    private suspend inline fun <reified T> safePatch(
        path: String,
        payload: Any,
    ): Result<T> = runCatching {
        val response = httpClient.patch {
            url(baseUrl + path)
            headerAuthorization()
            accept(ContentType.Application.Json)
            setBody(payload)
        }
        response.bodyOrApiError()
    }

    private suspend inline fun <reified T> HttpResponse.bodyOrApiError(): T {
        if (status.isSuccess()) return body()
        val apiError: ErrorResponse? = runCatching { body<ErrorResponse>() }.getOrNull()
        throw ApiException(status.value, apiError?.message)
    }

    private fun HttpResponse.ensureStatus(expected: HttpStatusCode): HttpResponse {
        if (status != expected) {
            throw ApiException(status.value, "Unexpected status ${'$'}status; expected ${'$'}expected")
        }
        return this
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    private fun io.ktor.client.request.HttpRequestBuilder.headerAuthorization() {
        tokenProvider()?.let { header("Authorization", "Bearer ${'$'}it") }
    }

    companion object {
        private val defaultJson = Json { ignoreUnknownKeys = true }

        val defaultClient: HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(defaultJson)
            }
            install(Logging) {
                level = LogLevel.INFO
            }
        }
    }
}

@Serializable
data class ErrorResponse(val message: String)

class ApiException(val statusCode: Int, override val message: String?) : Exception(message)
