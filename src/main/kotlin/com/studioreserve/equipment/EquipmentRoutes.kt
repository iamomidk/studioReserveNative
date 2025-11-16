package com.studioreserve.equipment

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.equipmentRoutes() {
    route("/equipment") {
        get {
            call.respond(mapOf("equipment" to emptyList<String>()))
        }
    }
}
