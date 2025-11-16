package com.studioreserve.payments

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.paymentRoutes() {
    route("/payments") {
        get {
            call.respond(mapOf("payments" to emptyList<String>()))
        }
    }
}
