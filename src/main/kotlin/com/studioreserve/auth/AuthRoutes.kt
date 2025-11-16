package com.studioreserve.auth

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.authRoutes() {
    route("/auth") {
        get("/status") {
            call.respond(mapOf("module" to "auth", "status" to "ok"))
        }
    }
}
