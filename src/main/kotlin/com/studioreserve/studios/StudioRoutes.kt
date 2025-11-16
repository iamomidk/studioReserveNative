package com.studioreserve.studios

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.studioRoutes() {
    route("/studios") {
        get {
            call.respond(mapOf("studios" to emptyList<String>()))
        }
    }
}
