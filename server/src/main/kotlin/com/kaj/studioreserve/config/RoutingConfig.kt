package com.kaj.studioreserve.config

import com.kaj.studioreserve.admin.adminRoutes
import com.kaj.studioreserve.admin.adminMonitoringRoutes
import com.kaj.studioreserve.auth.authRoutes
import com.kaj.studioreserve.bookings.bookingRoutes
import com.kaj.studioreserve.equipment.equipmentRoutes
import com.kaj.studioreserve.notifications.FakeNotificationService
import com.kaj.studioreserve.notifications.NotificationService
import com.kaj.studioreserve.payments.FakeZarinpalPaymentGatewayService
import com.kaj.studioreserve.payments.paymentRoutes
import com.kaj.studioreserve.rooms.roomRoutes
import com.kaj.studioreserve.studios.studioRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

fun Application.configureRouting() {
    val notificationService: NotificationService = FakeNotificationService()
    val paymentGatewayService = FakeZarinpalPaymentGatewayService()

    routing {
        authRoutes()
        studioRoutes()
        roomRoutes()
        bookingRoutes(notificationService)
        equipmentRoutes()
        paymentRoutes(paymentGatewayService, notificationService)
        adminRoutes()
        adminMonitoringRoutes()
    }
}
