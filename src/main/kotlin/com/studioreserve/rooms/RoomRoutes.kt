package com.studioreserve.rooms

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.roomRoutes() {
    route("/rooms") {
        get {
            call.respond(mapOf("rooms" to emptyList<String>()))
        }
    }
}
