package com.studioreserve.admin

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.adminRoutes() {
    route("/admin") {
        get("/health") {
            call.respond(mapOf("module" to "admin", "status" to "healthy"))
        }
    }

    adminStudioRoutes()
    adminMonitoringRoutes()
    adminSettingsRoutes()
}
