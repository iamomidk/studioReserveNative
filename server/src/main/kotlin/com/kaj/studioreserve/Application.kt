package com.kaj.studioreserve

import com.kaj.studioreserve.config.DatabaseConfig
import com.kaj.studioreserve.config.configureAuthentication
import com.kaj.studioreserve.config.configureMonitoring
import com.kaj.studioreserve.config.configureRouting
import com.kaj.studioreserve.config.configureSerialization
import com.kaj.studioreserve.config.configureStatusPages
import io.ktor.server.application.Application

fun Application.module() {
    DatabaseConfig.initDatabase()
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureAuthentication()
    configureRouting()
}
