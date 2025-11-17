package com.studioreserve

import com.studioreserve.auth.JwtClaims
import com.studioreserve.auth.JwtService
import com.studioreserve.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

open class IntegrationTestBase {
    protected val json = Json { ignoreUnknownKeys = true }

    protected fun HttpClient.authenticated(request: HttpRequestBuilder, token: String) {
        request.header(HttpHeaders.Authorization, "Bearer $token")
        request.accept(ContentType.Application.Json)
    }

    protected fun generateToken(userId: String, role: UserRole, jwtService: JwtService = JwtService()): String {
        val token = jwtService.generateAccessToken(JwtClaims(userId, role.name))
        return token.token
    }

    protected fun defaultClientConfig(): HttpClient.() -> Unit = {
        install(ContentNegotiation) {
            json(json)
        }
    }
}
