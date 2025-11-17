package com.kaj.studioreserve.config

import org.slf4j.LoggerFactory

/**
 * Performs startup-time validation for required environment variables so that
 * the server fails fast with a clear error rather than throwing from deep
 * inside JWT initialization or database drivers.
 */
object StartupValidation {
    private val logger = LoggerFactory.getLogger(StartupValidation::class.java)

    private val requiredEnv = listOf(
        "JWT_SECRET",
        "JWT_ISSUER",
        "JWT_AUDIENCE",
        "JWT_ACCESS_EXPIRES_MIN",
        "JWT_REFRESH_EXPIRES_MIN",
    )

    fun validateEnvOrThrow() {
        val missing = requiredEnv.filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            val message = "Missing required environment variables: ${missing.joinToString()}"
            logger.error(message)
            error(message)
        }
    }
}
