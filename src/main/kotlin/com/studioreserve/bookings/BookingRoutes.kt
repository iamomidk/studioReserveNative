package com.studioreserve.bookings

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.bookingRoutes() {
    route("/bookings") {
        get {
            call.respond(mapOf("bookings" to emptyList<String>()))
        }
    }
}
