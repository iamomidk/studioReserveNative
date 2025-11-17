package com.studioreserve.config

import com.studioreserve.admin.adminRoutes
import com.studioreserve.admin.adminMonitoringRoutes
import com.studioreserve.auth.authRoutes
import com.studioreserve.bookings.bookingRoutes
import com.studioreserve.equipment.equipmentRoutes
import com.studioreserve.notifications.FakeNotificationService
import com.studioreserve.notifications.NotificationService
import com.studioreserve.payments.FakeZarinpalPaymentGatewayService
import com.studioreserve.payments.paymentRoutes
import com.studioreserve.rooms.roomRoutes
import com.studioreserve.studios.studioRoutes
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
