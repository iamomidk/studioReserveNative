package com.kaj.studioreserve.auth

import com.kaj.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val name: String,
    val phoneNumber: String,
    val email: String? = null,
    val password: String,
    val role: UserRole
)

@Serializable
data class LoginRequest(
    val phoneOrEmail: String,
    val password: String
)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val role: UserRole
)

@Serializable
private data class AuthErrorResponse(val message: String)

fun Route.authRoutes(controller: AuthController = AuthController()) {
    route("/api/auth") {
        post("/register") {
            val request = runCatching { call.receive<RegisterRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Invalid request payload"))
                return@post
            }

            val validationError = validateRegisterRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, AuthErrorResponse(validationError))
                return@post
            }

            runCatching { controller.register(request) }.fold(
                onSuccess = { auth ->
                    call.respond(HttpStatusCode.Created, auth)
                },
                onFailure = { throwable ->
                    call.handleAuthException(throwable)
                }
            )
        }

        post("/login") {
            val request = runCatching { call.receive<LoginRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Invalid request payload"))
                return@post
            }

            if (request.phoneOrEmail.isBlank() || request.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Phone/email and password are required"))
                return@post
            }

            runCatching { controller.login(request) }.fold(
                onSuccess = { auth -> call.respond(HttpStatusCode.OK, auth) },
                onFailure = { throwable -> call.handleAuthException(throwable) }
            )
        }

        post("/refresh") {
            val request = runCatching { call.receive<RefreshTokenRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Invalid request payload"))
                return@post
            }

            if (request.refreshToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("refreshToken is required"))
                return@post
            }

            runCatching { controller.refresh(request.refreshToken) }.fold(
                onSuccess = { auth -> call.respond(HttpStatusCode.OK, auth) },
                onFailure = { throwable -> call.handleAuthException(throwable) }
            )
        }

        authenticate("auth-jwt") {
            get("/me") {
                val userId = runCatching { currentUserId() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Invalid user id claim"))
                    return@get
                }

                runCatching { controller.me(userId) }.fold(
                    onSuccess = { me -> call.respond(HttpStatusCode.OK, me) },
                    onFailure = { throwable -> call.handleAuthException(throwable) }
                )
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.handleAuthException(throwable: Throwable) {
    val exception = throwable as? AuthException
        ?: return respond(
            HttpStatusCode.InternalServerError,
            AuthErrorResponse(throwable.localizedMessage ?: "Unexpected server error")
        )

    respond(exception.status, AuthErrorResponse(exception.message))
}

private fun validateRegisterRequest(request: RegisterRequest): String? {
    if (request.name.isBlank()) return "Name is required"
    if (request.phoneNumber.isBlank()) return "Phone number is required"
    if (request.password.length < 8) return "Password must be at least 8 characters"
    if (request.email != null && request.email.isNotBlank() && !request.email.contains('@')) {
        return "Email must be valid"
    }
    return null
}
