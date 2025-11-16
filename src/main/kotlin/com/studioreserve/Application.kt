package com.studioreserve

import com.studioreserve.config.DatabaseConfig
import com.studioreserve.config.configureAuthentication
import com.studioreserve.config.configureMonitoring
import com.studioreserve.config.configureRouting
import com.studioreserve.config.configureSerialization
import com.studioreserve.config.configureStatusPages
import io.ktor.server.application.Application

fun Application.module() {
    DatabaseConfig.initDatabase()
    configureSerialization()
    configureAuthentication()
    configureMonitoring()
    configureStatusPages()
    configureRouting()
}
