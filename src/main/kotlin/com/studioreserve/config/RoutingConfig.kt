package com.studioreserve.config

import com.studioreserve.admin.adminRoutes
import com.studioreserve.admin.adminSettingsRoutes
import com.studioreserve.auth.authRoutes
import com.studioreserve.bookings.bookingRoutes
import com.studioreserve.equipment.equipmentRoutes
import com.studioreserve.payments.paymentRoutes
import com.studioreserve.rooms.roomRoutes
import com.studioreserve.studios.studioRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

fun Application.configureRouting() {
    routing {
        authRoutes()
        studioRoutes()
        roomRoutes()
        bookingRoutes()
        equipmentRoutes()
        paymentRoutes()
        adminRoutes()
        adminSettingsRoutes()
    }
}
